package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RecordProfiler;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The data-catalog operations of the Kafka connector — {@code catalog}, {@code profile},
 * {@code sample} — in the documents the catalog flows consume (the shape of {@code JdbcConnector}
 * and {@link EbxCatalog}). A topic has neither a declared schema nor a query engine, so everything is
 * read from its latest messages:
 * <ul>
 * <li>{@code catalog}: one asset of kind {@code TOPIC} per topic ({@code qualifiedName} = the topic,
 * {@code rowCount} = messages retained, end offset − beginning offset over the partitions) with the
 * fields inferred from the {@code sampleSize} latest messages (default 1000): nested objects flattened
 * as {@code address/city}, arrays and empty objects {@code JSON}, numbers {@code INTEGER} or
 * {@code DECIMAL}, ISO texts {@code DATE} or {@code TIMESTAMP}, mixed types {@code STRING};
 * {@code @key} and {@code @timestamp} describe the message itself, {@code @value} a payload that is
 * not a JSON object. A field absent from every message read is not listed.</li>
 * <li>{@code profile}: {@link RecordProfiler} statistics on the same latest messages
 * ({@code method = SAMPLE}).</li>
 * <li>{@code sample}: distinct values of the text fields of the {@code limit} latest messages.</li>
 * </ul>
 * Inputs: {@code source} (required), {@code schemas} (comma-separated topic names or patterns with
 * {@code *}; empty or {@code *} = every topic), {@code exclude} (regular expression on the topic name;
 * the default leaves out the internal topics: {@code __consumer_offsets}, {@code _schemas}, the Kafka
 * Connect storage topics), {@code tables} (topic names, or {@code *}), {@code rowCounts}. A topic that
 * cannot be read is reported under {@code errors} and the others go on.
 */
final class KafkaCatalog
{
	static final class Message
	{
		final String key;
		final long timestamp;
		final String value;

		Message(final String pKey, final long pTimestamp, final String pValue)
		{
			this.key = pKey;
			this.timestamp = pTimestamp;
			this.value = pValue;
		}
	}

	/** What the catalog reads from the cluster. */
	interface Broker
	{
		/** Topic names visible to the principal. */
		List<String> topics() throws ConnectorException;

		/** Messages retained by the topic: end offset − beginning offset over its partitions. */
		long retained(String pTopic) throws ConnectorException;

		/** Up to {@code pMax} of the most recent messages of the topic. */
		List<Message> latest(String pTopic, int pMax) throws ConnectorException;
	}

	/** One inferred field. */
	static final class Field
	{
		/** Normalised type; null while only null values were seen. */
		String type;
		final Set<String> natives = new LinkedHashSet<String>();
		/** Messages carrying a non-null value. */
		int present;
	}

	static final List<String> OPERATIONS = Arrays.asList("catalog", "profile", "sample");
	static final String DEFAULT_EXCLUDE = "__.*|_schemas|_confluent.*|connect-(configs|offsets|status)";
	static final String KEY = "@key";
	static final String TIMESTAMP = "@timestamp";
	static final String VALUE = "@value";
	/** Fields kept per topic; a payload with more distinct paths is cut. */
	static final int MAX_FIELDS = 500;

	private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}(:?\\d{2})?)?");

	private final Broker broker;
	private final String bootstrap;

	KafkaCatalog(final Broker pBroker, final String pBootstrap)
	{
		this.broker = pBroker;
		final String servers = pBootstrap == null ? "" : pBootstrap.trim();
		this.bootstrap = servers.indexOf(',') < 0 ? servers : servers.substring(0, servers.indexOf(',')).trim();
	}

	JsonNode execute(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		if ("catalog".equals(pOperation)) return this.catalog(pInputs);
		if ("profile".equals(pOperation)) return this.profile(pInputs);
		if ("sample".equals(pOperation)) return this.sample(pInputs);
		throw new ConnectorException("unknown catalog operation '" + pOperation + "'");
	}

	// ------------------------------------------------------------------ selection

	/** Topics selected by {@code schemas} and {@code exclude}, then by {@code tables} when {@code pWanted}. */
	List<String> selectTopics(final Map<String, String> pInputs, final boolean pWanted) throws ConnectorException
	{
		final Pattern exclude = Pattern.compile(EbxCatalog.text(pInputs, "exclude", KafkaCatalog.DEFAULT_EXCLUDE));
		final List<Pattern> include = new ArrayList<Pattern>();
		final String requested = EbxCatalog.text(pInputs, "schemas", "*");
		if (!"*".equals(requested))
		{
			for (final String entry : requested.split(","))
			{
				if (!entry.trim().isEmpty()) include.add(KafkaCatalog.glob(entry.trim()));
			}
		}
		final Set<String> wanted = pWanted ? EbxCatalog.wanted(pInputs) : Collections.<String> emptySet();
		final List<String> out = new ArrayList<String>();
		for (final String topic : this.broker.topics())
		{
			if (exclude.matcher(topic).matches()) continue;
			if (!include.isEmpty() && !KafkaCatalog.matchesAny(include, topic)) continue;
			if (!wanted.isEmpty() && !wanted.contains(topic.toUpperCase(Locale.ENGLISH))) continue;
			out.add(topic);
		}
		return out;
	}

	static Pattern glob(final String pPattern)
	{
		final StringBuilder regex = new StringBuilder();
		for (final String part : pPattern.split("\\*", -1))
		{
			if (regex.length() > 0 || pPattern.startsWith("*")) regex.append(".*");
			if (!part.isEmpty()) regex.append(Pattern.quote(part));
		}
		return Pattern.compile(pPattern.startsWith("*") ? regex.substring(2) : regex.toString());
	}

	private static boolean matchesAny(final List<Pattern> pPatterns, final String pTopic)
	{
		for (final Pattern p : pPatterns)
		{
			if (p.matcher(pTopic).matches()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ inference

	/** Messages as records: the JSON object payload, {@code @key}, {@code @timestamp}; any other payload under {@code @value}. */
	static List<JsonNode> records(final List<Message> pMessages)
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final Message m : pMessages)
		{
			final ObjectNode record = Json.object();
			if (m.key != null) record.put(KafkaCatalog.KEY, m.key);
			if (m.timestamp >= 0) record.put(KafkaCatalog.TIMESTAMP, java.time.Instant.ofEpochMilli(m.timestamp).toString());
			JsonNode value = null;
			if (m.value != null && !m.value.trim().isEmpty())
			{
				try
				{
					value = Json.parse(m.value);
				}
				catch (final Exception notJson)
				{
					value = null;
				}
				if (value == null) value = TextNode.valueOf(m.value);
			}
			if (value != null && value.isObject()) record.setAll((ObjectNode) value);
			else if (value != null && !value.isNull()) record.set(KafkaCatalog.VALUE, value);
			out.add(record);
		}
		return out;
	}

	/** Fields of the records, in the order they first appear. */
	static Map<String, Field> infer(final List<JsonNode> pRecords)
	{
		final Map<String, Field> fields = new LinkedHashMap<String, Field>();
		for (final JsonNode record : pRecords)
		{
			KafkaCatalog.walk(record, "", fields);
		}
		return fields;
	}

	private static void walk(final JsonNode pObject, final String pPrefix, final Map<String, Field> pFields)
	{
		final Iterator<Map.Entry<String, JsonNode>> it = pObject.fields();
		while (it.hasNext())
		{
			final Map.Entry<String, JsonNode> e = it.next();
			final String path = pPrefix + e.getKey();
			final JsonNode v = e.getValue();
			if (v != null && v.isObject() && v.size() > 0)
			{
				KafkaCatalog.walk(v, path + "/", pFields);
				continue;
			}
			Field f = pFields.get(path);
			if (f == null)
			{
				if (pFields.size() >= KafkaCatalog.MAX_FIELDS) continue;
				f = new Field();
				pFields.put(path, f);
			}
			if (v == null || v.isNull()) continue;
			// an empty array, object or text is no value, as RecordProfiler counts it: the field stays nullable
			if (!((v.isContainerNode() && v.size() == 0) || (v.isTextual() && v.asText().isEmpty()))) f.present++;
			final String type;
			if (v.isObject() || v.isArray())
			{
				type = "JSON";
				f.natives.add(v.isArray() ? "array" : "object");
			}
			else if (v.isBoolean())
			{
				type = "BOOLEAN";
				f.natives.add("boolean");
			}
			else if (v.isNumber())
			{
				type = v.isIntegralNumber() ? "INTEGER" : "DECIMAL";
				f.natives.add("number");
			}
			else
			{
				final String text = v.asText();
				type = KafkaCatalog.DATE.matcher(text).matches() ? "DATE" : KafkaCatalog.DATE_TIME.matcher(text).matches() ? "TIMESTAMP" : "STRING";
				f.natives.add("string");
			}
			f.type = KafkaCatalog.merge(f.type, type);
		}
	}

	/** Widest of two inferred types: INTEGER + DECIMAL = DECIMAL, DATE + TIMESTAMP = TIMESTAMP, anything else mixed = STRING. */
	static String merge(final String pSeen, final String pNew)
	{
		if (pSeen == null || pSeen.equals(pNew)) return pNew;
		if (("INTEGER".equals(pSeen) || "DECIMAL".equals(pSeen)) && ("INTEGER".equals(pNew) || "DECIMAL".equals(pNew))) return "DECIMAL";
		if (("DATE".equals(pSeen) || "TIMESTAMP".equals(pSeen)) && ("DATE".equals(pNew) || "TIMESTAMP".equals(pNew))) return "TIMESTAMP";
		return "STRING";
	}

	static String type(final Field pField)
	{
		return pField.type == null ? "STRING" : pField.type;
	}

	// ------------------------------------------------------------------ catalog

	JsonNode catalog(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int size = EbxCatalog.bounded(pInputs, "sampleSize", 1000, 1, 10000);
		final boolean counts = !"false".equalsIgnoreCase(EbxCatalog.text(pInputs, "rowCounts", "true"));
		final String scannedAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode tables = out.putArray("tables");
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String topic : this.selectTopics(pInputs, false))
		{
			try
			{
				final List<JsonNode> records = KafkaCatalog.records(this.broker.latest(topic, size));
				final String assetKey = source + ":" + topic;
				final ObjectNode table = Json.object();
				table.put("source", source);
				table.put("assetKey", assetKey);
				table.put("qualifiedName", topic);
				table.put("name", topic);
				table.put("kind", "TOPIC");
				table.put("remarks", records.isEmpty() ? "Kafka topic without any retained message: no field could be inferred."
					: "Kafka topic: fields inferred from its " + records.size() + " latest message(s), no schema registry read.");
				if (counts) table.put("rowCount", this.broker.retained(topic));
				table.put("url", "kafka://" + this.bootstrap + "/" + topic);
				table.put("scannedAt", scannedAt);
				final ArrayNode columns = table.putArray("columns");
				int position = 0;
				for (final Map.Entry<String, Field> e : KafkaCatalog.infer(records).entrySet())
				{
					final Field f = e.getValue();
					final ObjectNode c = columns.addObject();
					c.put("columnKey", assetKey + "." + e.getKey());
					c.put("name", e.getKey());
					c.put("position", ++position);
					c.put("dataType", KafkaCatalog.type(f));
					c.put("nativeType", KafkaCatalog.KEY.equals(e.getKey()) ? "message key" : KafkaCatalog.TIMESTAMP.equals(e.getKey()) ? "message timestamp"
						: f.natives.isEmpty() ? "null" : KafkaCatalog.join(f.natives));
					c.put("nullable", f.present < records.size());
					c.put("primaryKey", false);
					c.put("scannedAt", scannedAt);
				}
				tables.add(table);
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("topic", topic).put("message", ex.getMessage());
			}
		}
		return KafkaCatalog.finish(out, tables, errors);
	}

	// ------------------------------------------------------------------ profile

	JsonNode profile(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int size = EbxCatalog.bounded(pInputs, "sampleSize", 1000, 3, 10000);
		final boolean counts = !"false".equalsIgnoreCase(EbxCatalog.text(pInputs, "rowCounts", "true"));
		final java.time.LocalDateTime nowTs = java.time.LocalDateTime.now().withNano(0);
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
		out.put("profileDate", nowTs.toLocalDate().toString());
		final ArrayNode columns = out.putArray("columns");
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String topic : this.selectTopics(pInputs, true))
		{
			try
			{
				final List<JsonNode> records = KafkaCatalog.records(this.broker.latest(topic, size));
				if (records.isEmpty()) continue;
				final long retained = counts ? this.broker.retained(topic) : -1L;
				final List<ObjectNode> items = new ArrayList<ObjectNode>();
				for (final Map.Entry<String, Field> e : KafkaCatalog.infer(records).entrySet())
				{
					final ObjectNode c = Json.object();
					c.put("source", source);
					c.put("assetKey", source + ":" + topic);
					c.put("qualifiedName", topic);
					c.put("columnKey", source + ":" + topic + "." + e.getKey());
					c.put("name", e.getKey());
					c.put("dataType", KafkaCatalog.type(e.getValue()));
					c.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
					c.put("profileDate", nowTs.toLocalDate().toString());
					RecordProfiler.profile(c, records, e.getKey(), KafkaCatalog.type(e.getValue()), retained);
					items.add(c);
				}
				for (final ObjectNode c : items)
				{
					columns.add(c);
				}
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("topic", topic).put("message", ex.getMessage());
			}
		}
		return KafkaCatalog.finish(out, columns, errors);
	}

	// ------------------------------------------------------------------ sample

	JsonNode sample(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int limit = EbxCatalog.bounded(pInputs, "limit", 50, 3, 500);
		final String sampledAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String topic : this.selectTopics(pInputs, true))
		{
			try
			{
				final List<JsonNode> records = KafkaCatalog.records(this.broker.latest(topic, limit));
				int kept = 0;
				for (final Map.Entry<String, Field> e : KafkaCatalog.infer(records).entrySet())
				{
					if (!"STRING".equals(KafkaCatalog.type(e.getValue())) || kept++ >= 200) continue;
					final ObjectNode c = columns.addObject();
					c.put("source", source);
					c.put("assetKey", source + ":" + topic);
					c.put("qualifiedName", topic);
					c.put("columnKey", source + ":" + topic + "." + e.getKey());
					c.put("name", e.getKey());
					c.put("dataType", "STRING");
					c.put("sampledAt", sampledAt);
					c.put("sampleSize", records.size());
					final ArrayNode array = c.putArray("values");
					final StringBuilder joined = new StringBuilder();
					for (final String v : RecordProfiler.distinctValues(records, e.getKey(), 200))
					{
						array.add(v);
						joined.append(joined.length() > 0 ? "\n" : "").append(v.replace('\n', ' ').replace('\r', ' '));
					}
					c.put("valuesText", joined.toString());
				}
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("topic", topic).put("message", ex.getMessage());
			}
		}
		return KafkaCatalog.finish(out, columns, errors);
	}

	// ------------------------------------------------------------------ helpers

	/** Count and errors; null when nothing was produced, an exception when nothing could be read at all. */
	private static JsonNode finish(final ObjectNode pOut, final ArrayNode pItems, final ArrayNode pErrors) throws ConnectorException
	{
		pOut.put("count", pItems.size());
		if (pErrors.size() > 0)
		{
			pOut.put("errorCount", pErrors.size());
			pOut.set("errors", pErrors);
		}
		if (pItems.size() == 0 && pErrors.size() > 0)
		{
			throw new ConnectorException("Kafka: no topic could be read (" + pErrors.size() + "); first: " + pErrors.get(0).path("topic").asText() + ": "
				+ pErrors.get(0).path("message").asText());
		}
		return pItems.size() == 0 ? null : pOut;
	}

	private static String join(final Set<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String v : pValues)
		{
			out.append(out.length() > 0 ? " | " : "").append(v);
		}
		return out.toString();
	}
}
