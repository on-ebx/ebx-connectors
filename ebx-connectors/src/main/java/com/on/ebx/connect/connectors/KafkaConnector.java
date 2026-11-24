package com.on.ebx.connect.connectors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import java.util.Date;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MessagingGuard;
import com.on.ebx.connect.core.CdcCheckpointCoordinator;
import com.on.ebx.connect.core.SynchronizationCheckpoint;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Apache Kafka, both ways, using the client packaged in {@code WEB-INF/lib/kafka-clients.jar}.
 * <ul>
 * <li>{@code publish} (WRITE): sends the outbound document as a JSON message. Inputs: {@code topic}
 * (else property {@code topic}), {@code key} (else property {@code key.field}, a JSON path read in
 * the document, else no key). With property {@code envelope=true} the message is
 * {@code {table, key, operation, timestamp, data}}. Optional input {@code headers}: a JSON object of
 * string to string, verified 2026-09-27 to be read as a JSON-encoded string (the inputs map this
 * connector receives, like every other connector, only carries strings — see {@link #publish}); each
 * entry becomes one Kafka record header, UTF-8 encoded; a non-string value is refused. Optional input
 * {@code tombstone=true}: sends a Kafka tombstone (a {@code null} value) for the resolved key instead
 * of the document, for a compacted topic to forget that key; refused when no key can be resolved
 * (neither input {@code key} nor property {@code key.field}); property {@code envelope} is never
 * applied to a tombstone (a wrapped {@code null} would not be a tombstone), whatever its value.</li>
 * <li>{@code consume} (SEARCH): polls up to {@code max} messages (input, default property
 * {@code max.messages} = 100) and returns {@code {messages:[{topic, partition, offset, key, timestamp, value}]}};
 * {@code value} is parsed as JSON; a message that is not JSON is reported, never delivered. An import feed
 * with {@code itemsPath = messages[*].value} consumes the topic incrementally under the consumer group
 * {@code group.id} (default {@code ebx-connectors-<id>}), and an offset is committed only once EBX has written
 * the messages below it ({@link KafkaConsumeLedger}): a message that failed, was not read, or lies beyond the
 * feed's maximum is delivered again by the next run. A message that is not JSON or that EBX refused is moved to
 * the topic named by property {@code dead.letter.topic} when it is set, and holds back its partition otherwise.
 * A direct {@code consume} call commits the messages it returns.</li>
 * <li>{@code catalog}, {@code profile}, {@code sample} (SEARCH): the topics described for the data
 * catalog, their fields inferred from the latest messages, statistics computed by ON EBX on those
 * messages ({@link KafkaCatalog}); read-only, no consumer group, nothing committed.</li>
 * </ul>
 * Connection: {@code baseUrl} = bootstrap servers ({@code host:9092,host2:9092}); authType BASIC
 * turns username/password into SASL/PLAIN over the {@code security.protocol} property (default
 * SASL_SSL when credentials are set); any property starting with {@code kafka.} is passed to the
 * clients verbatim ({@code kafka.ssl.truststore.location}...).
 */
public final class KafkaConnector implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.write("publish", "Publish the outbound document as a JSON message (optional inputs: headers = JSON object of "
			+ "string to string written as Kafka record headers; tombstone = 'true' to send a null value for the resolved key "
			+ "instead of the document)", new String[0]),
		Operation.search("consume", "Poll messages of a topic; a feed commits an offset only once EBX has written the message, a direct call commits the messages it returns", new String[0]),
		Operation.read("peek", "Read the latest messages of a topic without committing (design time)", new String[0]),
		Operation.search("cdc", "Debezium change data capture: a feed applies c/u/r as upserts and d as delete, flag or ignore (inputs deleteMode, "
			+ "deleteFlagField, deleteFlagValue, maxEvents, maxBytes), the checkpoint saved per mapping only after EBX applied the batch; a direct "
			+ "call previews the next batch from input checkpoint and saves nothing", new String[0]),
		Operation.search("catalog", "Data catalog inventory: the topics, their fields inferred from the latest messages, the retained message count "
			+ "(input source; optional schemas = topics or patterns, exclude, sampleSize, rowCounts)", "source"),
		Operation.search("profile", "Data catalog statistics per field, computed by ON EBX on the latest messages of each topic (input source; optional schemas, tables, sampleSize)", "source"),
		Operation.search("sample", "Sampled values of the text fields of the latest messages, for the data catalog's classification (input source; optional schemas, tables, limit)", "source"));

	private ConnectorConfig config;
	private KafkaProducer<String, String> producer;

	/**
	 * Runs the isolated Debezium CDC path. Unlike {@code consume}, this never commits Kafka offsets;
	 * the supplied EBX checkpoint coordinator advances only after {@code pApplier} returns normally.
	 */
	public SynchronizationCheckpoint executeCdc(final int pMappingId, final String pCheckpoint,
		final int pMaxEvents, final int pMaxBytes, final boolean pDryRun,
		final KafkaCdcExecution.Applier pApplier, final CdcCheckpointCoordinator pCoordinator,
		final Date pCompletedAt) throws Exception
	{
		// preview function, closed by default: nothing of the change data capture runs, whoever calls
		com.on.ebx.connect.core.PreviewFeatures.require(com.on.ebx.connect.core.PreviewFeatures.Feature.CDC);
		if (this.config == null) throw new IllegalStateException("Kafka connector is not configured");
		final Properties properties = this.clientProperties();
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, this.config.getProperty("group.id", "ebx-connectors-" + this.config.getId()) + "-cdc-no-commit");
		return KafkaCdcExecution.pollApplyConfirm(pMappingId, pCheckpoint, pMaxEvents, pMaxBytes, pDryRun,
			new KafkaCdcSource(properties, this.topic(null), pMaxEvents,
				KafkaConnector.toInt(this.config.getProperty("poll.ms", "3000"), 3000)),
			pApplier, pCoordinator, pCompletedAt);
	}

	@Override
	public String getType()
	{
		return "kafka";
	}

	@Override
	public String getLabel()
	{
		return "Apache Kafka";
	}

	@Override public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor(){final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b=descriptor("MESSAGING");b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM).capability(com.on.ebx.connect.spi.ConnectorCapability.STREAMING).capability(com.on.ebx.connect.spi.ConnectorCapability.INCREMENTAL_READ).apiVersion("Kafka client-library-defined").dependency(new com.on.ebx.connect.spi.ConnectorDependency("org.apache.kafka:kafka-clients",null,true,"Connector type is skipped when the client is absent")).pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.OFFSET,100,10000,"offset")).configurationProperty(property("acks",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(enumeration("auto.offset.reset","earliest","latest","none")).configurationProperty(property("envelope",com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN,false)).configurationProperty(property("dead.letter.topic",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("group.id",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("key.field",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("max.messages",com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER,false)).configurationProperty(property("poll.ms",com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER,false)).configurationProperty(property("sasl.mechanism",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("security.protocol",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("topic",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false)).configurationProperty(property("kafka.*",com.on.ebx.connect.spi.ConfigurationPropertyType.STRING,false));return b.build();}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c){final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b=com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(),getLabel(),c);for(final com.on.ebx.connect.spi.Operation op:getOperations()){final com.on.ebx.connect.spi.ConnectorDirection d=op.getKind()==com.on.ebx.connect.spi.Operation.Kind.WRITE?com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND:com.on.ebx.connect.spi.ConnectorDirection.INBOUND;b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(),op.getKind(),op.getDescription(),java.util.Collections.singleton(d),java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));}return b;}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String n,final com.on.ebx.connect.spi.ConfigurationPropertyType t,final boolean r){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,t).required(r).build();}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition enumeration(final String n,final String...v){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).enumerationValues(v).build();}

	@Override
	public List<Operation> getOperations()
	{
		return KafkaConnector.OPERATIONS;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
		this.closeProducer();
	}

	/**
	 * The producer is the one client kept between calls (consumers and admin clients live for one
	 * call): it owns a network thread and buffers, and nothing else would ever close it. A send made
	 * through this instance afterwards opens a new producer, as the first send did.
	 */
	@Override
	public void close()
	{
		this.closeProducer();
	}

	/**
	 * Verifies the bootstrap endpoint through Kafka's read-only metadata API. No topic is
	 * created, changed, or consumed; the broker simply returns the topic names visible to
	 * the configured principal.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.currentTimeMillis();
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(KafkaConnector.class.getClassLoader());
		try (AdminClient admin = AdminClient.create(this.clientProperties()))
		{
			final Set<String> topics = admin.listTopics().names().get(this.config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
			return ConnectionTestResult.success("Connected to Kafka; read-only metadata returned " + topics.size() + " topic(s)",
				System.currentTimeMillis() - started);
		}
		catch (final Exception ex)
		{
			return ConnectionTestResult.failure("Kafka metadata check failed: " + KafkaConnector.failureMessage(ex),
				System.currentTimeMillis() - started);
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		if ("consume".equals(pOperation) || "peek".equals(pOperation))
		{
			fields.add(FieldInfo.of("messages[0].topic", "Topic", "string"));
			fields.add(FieldInfo.of("messages[0].key", "Message key", "string"));
			fields.add(FieldInfo.of("messages[0].offset", "Offset", "number"));
			fields.add(FieldInfo.of("messages[0].timestamp", "Timestamp (epoch ms)", "number"));
			fields.add(FieldInfo.of("messages[0].value", "Message value (JSON)", "object"));
		}
		if ("catalog".equals(pOperation))
		{
			fields.add(FieldInfo.of("tables", "Topics (feed items): source, assetKey, qualifiedName, name, kind TOPIC, remarks, rowCount (messages retained), url, scannedAt, columns[]", "array"));
			fields.add(FieldInfo.of("tables[0].columns", "Inferred fields: columnKey, name (address/city), position, dataType, nativeType, nullable, primaryKey, scannedAt", "array"));
			fields.add(FieldInfo.of("errors", "Topics that could not be read: topic, message", "array"));
		}
		if ("profile".equals(pOperation))
		{
			fields.add(FieldInfo.of("columns", "One item per field: columnKey, assetKey, qualifiedName, name, dataType, profiledAt, profileDate, rowCount, "
				+ "nullCount, nullRate, distinctCount, uniqueRate, method (SAMPLE), sampleSize, minValue, maxValue, meanValue, minLength, maxLength, avgLength", "array"));
		}
		if ("sample".equals(pOperation))
		{
			fields.add(FieldInfo.of("columns", "One item per text field: columnKey, assetKey, qualifiedName, name, sampledAt, sampleSize, values[], valuesText", "array"));
		}
		return fields;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (KafkaCatalog.OPERATIONS.contains(pOperation))
		{
			return this.catalogOperation(pOperation, pInputs);
		}
		if ("publish".equals(pOperation))
		{
			return this.publish(pInputs, pPayload);
		}
		if ("consume".equals(pOperation) || "peek".equals(pOperation))
		{
			return this.consume(pInputs, "peek".equals(pOperation));
		}
		if ("cdc".equals(pOperation))
		{
			return this.previewCdc(pInputs);
		}
		throw new ConnectorException("Kafka: unknown operation '" + pOperation + "'");
	}

	/**
	 * Gartner lot B: what the next CDC batch would apply from {@code checkpoint} (empty = the beginning), without
	 * committing an offset or saving a checkpoint - the design-time view of a {@code cdc} feed.
	 */
	private JsonNode previewCdc(final Map<String, String> pInputs) throws ConnectorException
	{
		// preview function, closed by default (the direct call that previews the next batch is part of it)
		com.on.ebx.connect.core.PreviewFeatures.require(com.on.ebx.connect.core.PreviewFeatures.Feature.CDC);
		if (this.config == null) throw new ConnectorException("Kafka connector is not configured");
		final int maxEvents = KafkaConnector.cdcMaxEvents(pInputs);
		final String checkpoint = pInputs == null || pInputs.get("checkpoint") == null ? "" : pInputs.get("checkpoint");
		try
		{
			final Properties properties = this.clientProperties();
			properties.put(ConsumerConfig.GROUP_ID_CONFIG, this.config.getProperty("group.id", "ebx-connectors-" + this.config.getId()) + "-cdc-no-commit");
			final KafkaCdcSource source = new KafkaCdcSource(properties, this.topic(pInputs), maxEvents,
				KafkaConnector.toInt(this.config.getProperty("poll.ms", "3000"), 3000));
			final com.on.ebx.connect.core.CdcBatch batch = com.on.ebx.connect.core.DebeziumKafkaCdcAdapter.normalize(source.poll(checkpoint), checkpoint,
				maxEvents, KafkaConnector.cdcMaxBytes(pInputs));
			final com.on.ebx.connect.core.CdcApplyPlan.Options options = com.on.ebx.connect.core.CdcApplyPlan.Options.from(pInputs);
			final com.on.ebx.connect.core.CdcApplyPlan.Plan plan = com.on.ebx.connect.core.CdcApplyPlan.plan(batch, options);
			final ObjectNode out = Json.object();
			out.put("summary", plan.summary(options.getDeleteMode()));
			out.putArray("upserts").addAll(new ArrayList<JsonNode>(plan.getUpserts()));
			out.putArray("deletes").addAll(new ArrayList<JsonNode>(plan.getDeletes()));
			out.put("candidateCheckpoint", batch.getCandidateCheckpoint());
			return out;
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException("Kafka CDC: " + ex.getMessage());
		}
	}

	/** Events per CDC batch: input or property {@code maxEvents}, default 500, 1..10000. */
	public static int cdcMaxEvents(final Map<String, String> pInputs)
	{
		final String raw = pInputs == null ? null : pInputs.get("maxEvents");
		return Math.max(1, Math.min(10000, KafkaConnector.toInt(raw == null ? "500" : raw, 500)));
	}

	/** Serialized bytes per CDC batch: input {@code maxBytes}, default 8 MB, ceiling 16 MB. */
	public static int cdcMaxBytes(final Map<String, String> pInputs)
	{
		final String raw = pInputs == null ? null : pInputs.get("maxBytes");
		return Math.max(1024, Math.min(16 * 1024 * 1024, KafkaConnector.toInt(raw == null ? String.valueOf(8 * 1024 * 1024) : raw, 8 * 1024 * 1024)));
	}

	// ------------------------------------------------------------------ publish

	private JsonNode publish(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final ProducerRecord<String, String> record = this.buildProducerRecord(pInputs, pPayload);
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(KafkaConnector.class.getClassLoader());
		try
		{
			final RecordMetadata metadata = this.producer().send(record)
					.get(this.config.getReadTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
			final ObjectNode result = Json.object();
			result.put("topic", metadata.topic());
			result.put("partition", metadata.partition());
			result.put("offset", metadata.offset());
			result.put("key", record.key() == null ? "" : record.key());
			return result;
		}
		catch (final Exception ex)
		{
			this.closeProducer();
			throw new ConnectorException("Kafka publish to " + record.topic() + " failed: " + ex.getMessage(), ex);
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	/**
	 * Builds the record {@link #publish} sends, with no network call — the seam a unit test uses to
	 * assert on headers, the tombstone value, and the key-resolution refusal without opening a real
	 * {@link KafkaProducer}. Verified live 2026-09-27 against a fake broker (see
	 * {@code KafkaPublishRecordTest}); the assumption carried over from the rest of this connector,
	 * not independently re-verified here, is that {@code pInputs} only ever holds strings (the shape
	 * every {@code Connector.execute} caller passes) — a caller that needs the {@code headers} input
	 * to carry a JSON object rather than a JSON-encoded string would have to change this method.
	 */
	ProducerRecord<String, String> buildProducerRecord(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final String topic = this.topic(pInputs);
		JsonNode message = pPayload == null ? Json.object() : pPayload;
		String key = pInputs == null ? null : pInputs.get("key");
		if (key == null || key.trim().isEmpty())
		{
			final String keyField = this.config.getProperty("key.field", null);
			final JsonNode fromDoc = keyField == null ? null : Json.at(message, keyField);
			key = fromDoc == null ? null : fromDoc.asText();
		}
		final boolean tombstone = pInputs != null && "true".equalsIgnoreCase(pInputs.get("tombstone"));
		if (tombstone && (key == null || key.trim().isEmpty()))
		{
			throw new ConnectorException("Kafka publish: 'tombstone' requires a resolvable key (input 'key' or property 'key.field')");
		}
		// A tombstone is a null value on the wire; wrapping it in the envelope would turn it into a
		// record with a non-null value that merely carries a null 'data', which a compacted topic
		// would never compact away. So the envelope property is never applied here, whatever its value.
		if (!tombstone && "true".equalsIgnoreCase(this.config.getProperty("envelope", "false")))
		{
			final ObjectNode envelope = Json.object();
			envelope.put("table", pInputs == null ? "" : String.valueOf(pInputs.get("table")));
			envelope.put("key", key == null ? "" : key);
			envelope.put("operation", pInputs == null || pInputs.get("operation") == null ? "upsert" : pInputs.get("operation"));
			envelope.put("timestamp", System.currentTimeMillis());
			envelope.set("data", message);
			message = envelope;
		}
		final List<org.apache.kafka.common.header.Header> headers = this.buildHeaders(pInputs);
		final String value = tombstone ? null : Json.write(message);
		return new ProducerRecord<String, String>(topic, null, key, value, headers);
	}

	/**
	 * Parses the optional {@code headers} input (a JSON object of string to string) into Kafka
	 * headers, UTF-8 encoded. Per this connector's convention every input reaches it as a plain
	 * string (see {@link #buildProducerRecord}), so a JSON object is only ever read here as a
	 * JSON-encoded string, never as a native map entry of {@code pInputs}.
	 */
	private List<org.apache.kafka.common.header.Header> buildHeaders(final Map<String, String> pInputs) throws ConnectorException
	{
		final String raw = pInputs == null ? null : pInputs.get("headers");
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		final JsonNode parsed;
		try
		{
			parsed = Json.parse(raw);
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Kafka publish: 'headers' is not valid JSON: " + ex.getMessage(), ex);
		}
		if (!parsed.isObject())
		{
			throw new ConnectorException("Kafka publish: 'headers' must be a JSON object of string to string");
		}
		final List<org.apache.kafka.common.header.Header> headers = new ArrayList<org.apache.kafka.common.header.Header>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> fields = parsed.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			if (!field.getValue().isTextual())
			{
				throw new ConnectorException("Kafka publish: header '" + field.getKey() + "' must be a JSON string value");
			}
			headers.add(new org.apache.kafka.common.header.internals.RecordHeader(field.getKey(),
				field.getValue().asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		return headers;
	}

	private synchronized KafkaProducer<String, String> producer()
	{
		if (this.producer == null)
		{
			final Properties props = this.clientProperties();
			props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
			props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
			props.put(ProducerConfig.ACKS_CONFIG, this.config.getProperty("acks", "all"));
			props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(this.config.getConnectTimeoutMs()));
			props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(Math.max(this.config.getReadTimeoutMs(), 30000)));
			props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(Math.min(this.config.getReadTimeoutMs(), 30000)));
			this.producer = new KafkaProducer<String, String>(props);
		}
		return this.producer;
	}

	private synchronized void closeProducer()
	{
		if (this.producer != null)
		{
			try
			{
				this.producer.close(Duration.ofSeconds(5));
			}
			catch (final RuntimeException ex)
			{
				// nothing to do
			}
			this.producer = null;
		}
	}

	// ------------------------------------------------------------------ consume

	/**
	 * An interactive {@code consume} (a {@code connectors_call}, a designer sample): the caller receives the
	 * messages in the answer, so they are committed - the offsets of the messages returned, never the
	 * consumer's position - and a message that is not JSON is moved aside or held ({@link KafkaConsumeLedger}).
	 * A feed does not come through here: {@link #readRows} commits only what EBX wrote.
	 */
	private JsonNode consume(final Map<String, String> pInputs, final boolean pPeek) throws ConnectorException
	{
		final String topic = this.topic(pInputs);
		final int max = this.maxMessages(pInputs, 0);
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(KafkaConnector.class.getClassLoader());
		KafkaConsumer<String, String> consumer = null;
		try
		{
			consumer = this.newConsumer(topic, max, pPeek);
			final KafkaConsumption read = KafkaConsumption.poll(consumer, topic, max, this.pollMs(),
				this.pollDeadline(), pPeek ? null : this.deadLetters(topic), this.deadLetterTopic());
			if (!read.consumed())
			{
				return null;
			}
			final ObjectNode result = read.document();
			if (!pPeek)
			{
				read.settleForCaller(result);
			}
			return result;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Kafka consume from " + topic + " failed: " + ex.getMessage(), ex);
		}
		finally
		{
			KafkaConnector.closeQuietly(consumer);
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	/**
	 * A feed's read of a topic: the messages are polled and delivered to the engine's sink, and an offset is
	 * committed only once EBX reports the messages below it written - never at the poll, never for a message
	 * beyond the feed's maximum, never for a message that failed or was not read (C-06). Any other operation
	 * reads like every connector.
	 */
	@Override
	public RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final RowSink pSink) throws ConnectorException
	{
		if (!"consume".equals(pOperation))
		{
			return Connector.super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		final String topic = this.topic(pInputs);
		final int max = this.maxMessages(pInputs, pMax);
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(KafkaConnector.class.getClassLoader());
		KafkaConsumer<String, String> consumer = null;
		try
		{
			consumer = this.newConsumer(topic, max, false);
			final KafkaConsumption read = KafkaConsumption.poll(consumer, topic, max, this.pollMs(),
				this.pollDeadline(), this.deadLetters(topic), this.deadLetterTopic());
			if (!read.consumed())
			{
				return new RowStreamResult(0, false, -1, null);
			}
			return read.deliver(pItemsPath, pSink);
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Kafka consume from " + topic + " failed: " + ex.getMessage(), ex);
		}
		finally
		{
			KafkaConnector.closeQuietly(consumer);
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	/** Messages to take: input {@code max}, else property {@code max.messages}; never more than {@code pCap} when it is above 0. */
	private int maxMessages(final Map<String, String> pInputs, final int pCap)
	{
		final int max = KafkaConnector.toInt(pInputs == null ? null : pInputs.get("max"),
			KafkaConnector.toInt(this.config.getProperty("max.messages", "100"), 100));
		return pCap > 0 ? Math.min(max, pCap) : max;
	}

	private long pollMs()
	{
		return KafkaConnector.toInt(this.config.getProperty("poll.ms", "3000"), 3000);
	}

	private long pollDeadline()
	{
		return System.currentTimeMillis() + Math.max(this.pollMs() * 3, this.config.getReadTimeoutMs());
	}

	private KafkaConsumer<String, String> newConsumer(final String pTopic, final int pMax, final boolean pPeek)
	{
		final Properties props = this.clientProperties();
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, this.config.getProperty("group.id", "ebx-connectors-" + this.config.getId()) + (pPeek ? "-peek" : ""));
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, pPeek ? "earliest" : this.config.getProperty("auto.offset.reset", "earliest"));
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(Math.max(1, Math.min(pMax, 500))));
		final KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(props);
		consumer.subscribe(Collections.singletonList(pTopic));
		return consumer;
	}

	private static void closeQuietly(final KafkaConsumer<String, String> pConsumer)
	{
		if (pConsumer != null)
		{
			try
			{
				pConsumer.close(Duration.ofSeconds(5));
			}
			catch (final RuntimeException ex)
			{
				// nothing to do
			}
		}
	}

	/** The property {@code dead.letter.topic}, null when blank. */
	private String deadLetterTopic()
	{
		final String topic = this.config.getProperty("dead.letter.topic", null);
		return topic == null || topic.trim().isEmpty() ? null : topic.trim();
	}

	/**
	 * Where a message that cannot be written goes: the topic named by property {@code dead.letter.topic}, with
	 * the original key and value and headers saying where it came from and why. Null when the property is not set
	 * (such messages are then held back, not moved).
	 */
	private KafkaConsumeLedger.DeadLetters deadLetters(final String pSourceTopic) throws ConnectorException
	{
		final String topic = this.deadLetterTopic();
		if (topic == null)
		{
			return null;
		}
		if (topic.equals(pSourceTopic))
		{
			throw new ConnectorException("Kafka: property dead.letter.topic must differ from the topic that is read ('" + topic + "')");
		}
		return new KafkaConsumeLedger.DeadLetters()
		{
			@Override
			public void send(final KafkaConsumeLedger.Entry pEntry, final String pReason) throws Exception
			{
				final List<org.apache.kafka.common.header.Header> headers = new ArrayList<org.apache.kafka.common.header.Header>();
				KafkaConnector.addHeader(headers, "ebx.source.topic", pEntry.partition.topic());
				KafkaConnector.addHeader(headers, "ebx.source.partition", String.valueOf(pEntry.partition.partition()));
				KafkaConnector.addHeader(headers, "ebx.source.offset", String.valueOf(pEntry.offset));
				KafkaConnector.addHeader(headers, "ebx.reason", pReason);
				KafkaConnector.this.producer().send(new ProducerRecord<String, String>(topic, null, pEntry.key, pEntry.rawValue, headers))
					.get(KafkaConnector.this.config.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
			}
		};
	}

	private static void addHeader(final List<org.apache.kafka.common.header.Header> pHeaders, final String pName, final String pValue)
	{
		pHeaders.add(new org.apache.kafka.common.header.internals.RecordHeader(pName, pValue.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	// ------------------------------------------------------------------ data catalog

	/**
	 * The data-catalog operations ({@link KafkaCatalog}) over one read-only client: the admin API lists
	 * the topics, a consumer without group assigns their partitions, reads the offsets and seeks back to
	 * the latest messages. Nothing is committed and no consumer group is created.
	 */
	private JsonNode catalogOperation(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(KafkaConnector.class.getClassLoader());
		final CatalogBroker broker = new CatalogBroker(this.clientProperties(), Math.max(5000L, (long) this.config.getReadTimeoutMs()));
		try
		{
			return new KafkaCatalog(broker, this.config.getBaseUrl().replace("kafka://", "").trim()).execute(pOperation, pInputs);
		}
		finally
		{
			broker.close();
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	/** Read-only view of the cluster for the catalog: topic names, retained message counts, latest messages. */
	static final class CatalogBroker implements KafkaCatalog.Broker
	{
		private final Properties properties;
		private final long timeoutMs;
		private AdminClient admin;
		private KafkaConsumer<String, String> consumer;

		CatalogBroker(final Properties pProperties, final long pTimeoutMs)
		{
			this.properties = pProperties;
			this.timeoutMs = pTimeoutMs;
		}

		@Override
		public List<String> topics() throws ConnectorException
		{
			try
			{
				if (this.admin == null)
				{
					this.admin = AdminClient.create(this.properties);
				}
				final List<String> names = new ArrayList<String>(this.admin.listTopics().names().get(this.timeoutMs, TimeUnit.MILLISECONDS));
				Collections.sort(names);
				return names;
			}
			catch (final Exception ex)
			{
				throw new ConnectorException("Kafka: listing the topics failed: " + CatalogBroker.message(ex), ex);
			}
		}

		private KafkaConsumer<String, String> consumer()
		{
			if (this.consumer == null)
			{
				final Properties props = new Properties();
				props.putAll(this.properties);
				props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
				props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
				props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
				props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
				this.consumer = new KafkaConsumer<String, String>(props);
			}
			return this.consumer;
		}

		private List<TopicPartition> partitions(final String pTopic)
		{
			final List<TopicPartition> out = new ArrayList<TopicPartition>();
			final List<PartitionInfo> infos = this.consumer().partitionsFor(pTopic, Duration.ofMillis(this.timeoutMs));
			if (infos != null)
			{
				for (final PartitionInfo info : infos)
				{
					out.add(new TopicPartition(info.topic(), info.partition()));
				}
			}
			return out;
		}

		@Override
		public long retained(final String pTopic) throws ConnectorException
		{
			try
			{
				final List<TopicPartition> partitions = this.partitions(pTopic);
				final Map<TopicPartition, Long> begin = this.consumer().beginningOffsets(partitions, Duration.ofMillis(this.timeoutMs));
				final Map<TopicPartition, Long> end = this.consumer().endOffsets(partitions, Duration.ofMillis(this.timeoutMs));
				long total = 0L;
				for (final TopicPartition p : partitions)
				{
					total += Math.max(0L, end.get(p).longValue() - begin.get(p).longValue());
				}
				return total;
			}
			catch (final RuntimeException ex)
			{
				throw new ConnectorException("Kafka: reading the offsets of " + pTopic + " failed: " + CatalogBroker.message(ex), ex);
			}
		}

		/** The {@code pMax} most recent messages, newest first, taken evenly from the end of every partition. */
		@Override
		public List<KafkaCatalog.Message> latest(final String pTopic, final int pMax) throws ConnectorException
		{
			final List<KafkaCatalog.Message> out = new ArrayList<KafkaCatalog.Message>();
			try
			{
				final KafkaConsumer<String, String> c = this.consumer();
				final List<TopicPartition> partitions = this.partitions(pTopic);
				if (partitions.isEmpty() || pMax <= 0)
				{
					return out;
				}
				final Map<TopicPartition, Long> begin = c.beginningOffsets(partitions, Duration.ofMillis(this.timeoutMs));
				final Map<TopicPartition, Long> end = c.endOffsets(partitions, Duration.ofMillis(this.timeoutMs));
				final long perPartition = Math.max(1L, (pMax + partitions.size() - 1) / partitions.size());
				final List<TopicPartition> pending = new ArrayList<TopicPartition>();
				c.assign(partitions);
				for (final TopicPartition p : partitions)
				{
					final long from = Math.max(begin.get(p).longValue(), end.get(p).longValue() - perPartition);
					c.seek(p, from);
					if (from < end.get(p).longValue()) pending.add(p);
				}
				final long deadline = System.currentTimeMillis() + this.timeoutMs;
				int idle = 0;
				while (!pending.isEmpty() && System.currentTimeMillis() < deadline && idle < 4)
				{
					final ConsumerRecords<String, String> polled = c.poll(Duration.ofMillis(500));
					idle = polled.isEmpty() ? idle + 1 : 0;
					for (final ConsumerRecord<String, String> r : polled)
					{
						final Long stop = end.get(new TopicPartition(r.topic(), r.partition()));
						// a message published while reading is left out: the counts stay those of the offsets read
						if (stop != null && r.offset() < stop.longValue()) out.add(new KafkaCatalog.Message(r.key(), r.timestamp(), r.value()));
					}
					for (final java.util.Iterator<TopicPartition> it = pending.iterator(); it.hasNext();)
					{
						final TopicPartition p = it.next();
						// compacted or transactional partitions have offset gaps: the position, not a count, says a partition is done
						if (c.position(p, Duration.ofMillis(this.timeoutMs)) >= end.get(p).longValue()) it.remove();
					}
				}
				c.unsubscribe();
			}
			catch (final RuntimeException ex)
			{
				throw new ConnectorException("Kafka: reading the latest messages of " + pTopic + " failed: " + CatalogBroker.message(ex), ex);
			}
			Collections.sort(out, new java.util.Comparator<KafkaCatalog.Message>()
			{
				@Override
				public int compare(final KafkaCatalog.Message a, final KafkaCatalog.Message b)
				{
					return Long.compare(b.timestamp, a.timestamp);
				}
			});
			return out.size() > pMax ? new ArrayList<KafkaCatalog.Message>(out.subList(0, pMax)) : out;
		}

		void close()
		{
			try
			{
				if (this.consumer != null) this.consumer.close(Duration.ofSeconds(5));
			}
			catch (final RuntimeException ex)
			{
				// nothing to do
			}
			try
			{
				if (this.admin != null) this.admin.close(Duration.ofSeconds(5));
			}
			catch (final RuntimeException ex)
			{
				// nothing to do
			}
		}

		static String message(final Exception pException)
		{
			final Throwable cause = pException.getCause() != null && pException.getCause().getMessage() != null ? pException.getCause() : pException;
			return cause.getMessage() == null || cause.getMessage().trim().isEmpty() ? cause.getClass().getSimpleName() : cause.getMessage();
		}
	}

	// ------------------------------------------------------------------ helpers

	private String topic(final Map<String, String> pInputs) throws ConnectorException
	{
		final String topic = pInputs != null && pInputs.get("topic") != null && !pInputs.get("topic").trim().isEmpty()
				? pInputs.get("topic").trim() : this.config.getProperty("topic", null);
		if (topic == null)
		{
			throw new ConnectorException("Kafka: no topic (input 'topic' or connector property 'topic')");
		}
		return topic;
	}

	private Properties clientProperties()
	{
		final Properties props = new Properties();
		props.put("bootstrap.servers", this.config.getBaseUrl().replace("kafka://", "").trim());
		props.put("client.id", "ebx-connectors-" + this.config.getId());
		if (this.config.getAuthType() == ConnectorConfig.AuthType.BASIC && this.config.getUsername() != null
			&& !this.config.isPlaceholder(this.config.getPassword()))
		{
			props.put("security.protocol", this.config.getProperty("security.protocol", "SASL_SSL"));
			props.put("sasl.mechanism", this.config.getProperty("sasl.mechanism", "PLAIN"));
			final String module = "SCRAM-SHA-256".equals(this.config.getProperty("sasl.mechanism", "PLAIN"))
					|| "SCRAM-SHA-512".equals(this.config.getProperty("sasl.mechanism", "PLAIN"))
					? "org.apache.kafka.common.security.scram.ScramLoginModule" : "org.apache.kafka.common.security.plain.PlainLoginModule";
			props.put("sasl.jaas.config", module + " required username=\"" + this.config.getUsername().replace("\"", "\\\"")
					+ "\" password=\"" + this.config.getPassword().replace("\"", "\\\"") + "\";");
		}
		else if (this.config.getProperty("security.protocol", null) != null)
		{
			props.put("security.protocol", this.config.getProperty("security.protocol", "PLAINTEXT"));
		}
		// audit C2-01: only allow-listed client settings are copied; the refusal names the key and the JVM property that allows it
		try
		{
			MessagingGuard.checkKafkaProperties(this.config.getProperties());
		}
		catch (final ConnectorException refused)
		{
			throw new IllegalStateException(refused.getMessage(), refused);
		}
		for (final Map.Entry<String, String> property : this.config.getProperties().entrySet())
		{
			if (property.getKey().startsWith("kafka.") && property.getValue() != null)
			{
				props.put(property.getKey().substring(6), property.getValue());
			}
		}
		return props;
	}

	private static int toInt(final String pValue, final int pDefault)
	{
		try
		{
			return Integer.parseInt(pValue.trim());
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}

	private static String failureMessage(final Exception pException)
	{
		final String message = pException.getMessage();
		return message == null || message.trim().isEmpty() ? pException.getClass().getSimpleName() : message;
	}
}
