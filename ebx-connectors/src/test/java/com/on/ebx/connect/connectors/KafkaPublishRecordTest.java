package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Offline contract of the Kafka {@code publish} operation's optional {@code headers} and
 * {@code tombstone} inputs, added 2026-09-27 for the MDM Factory's retire notices. No broker is
 * opened: {@link KafkaConnector#buildProducerRecord} builds the {@link ProducerRecord} without ever
 * calling {@code producer()}, which is the seam this test exercises. Behaviour without the new
 * inputs (the pre-existing topic/key/envelope contract) is re-checked here too, to catch a
 * regression the same way {@link KafkaCatalogTest} guards the catalog operations.
 */
public final class KafkaPublishRecordTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// --- unchanged behaviour without the new inputs: plain message, key from input, no headers
		final KafkaConnector plain = newConnector(null);
		final Map<String, String> plainInputs = inputs("topic", "crm.partner.upsert", "key", "K1");
		final ProducerRecord<String, String> plainRecord = plain.buildProducerRecord(plainInputs, Json.parse("{\"name\":\"Acme\"}"));
		check("topic from input: " + plainRecord.topic(), "crm.partner.upsert".equals(plainRecord.topic()));
		check("key from input: " + plainRecord.key(), "K1".equals(plainRecord.key()));
		check("value is the plain JSON message, not an envelope: " + plainRecord.value(), "{\"name\":\"Acme\"}".equals(plainRecord.value()));
		check("no headers input -> no headers: " + plainRecord.headers(), isEmpty(plainRecord.headers()));

		// --- key from property key.field when no 'key' input is given
		final KafkaConnector byField = newConnector("id");
		final ProducerRecord<String, String> byFieldRecord = byField.buildProducerRecord(inputs("topic", "t"), Json.parse("{\"id\":\"K2\"}"));
		check("key resolved from key.field: " + byFieldRecord.key(), "K2".equals(byFieldRecord.key()));

		// --- headers: each JSON string entry becomes one UTF-8 Kafka header
		final KafkaConnector withHeaders = newConnector(null);
		final Map<String, String> headerInputs = inputs("topic", "t", "key", "K3", "headers",
			"{\"mdm-change\":\"RETIRE\",\"mdm-retired-to\":\"692\"}");
		final ProducerRecord<String, String> headerRecord = withHeaders.buildProducerRecord(headerInputs, Json.parse("{}"));
		final Header change = header(headerRecord.headers(), "mdm-change");
		final Header retiredTo = header(headerRecord.headers(), "mdm-retired-to");
		check("mdm-change header value: " + new String(change.value(), "UTF-8"), "RETIRE".equals(new String(change.value(), "UTF-8")));
		check("mdm-retired-to header value: " + new String(retiredTo.value(), "UTF-8"), "692".equals(new String(retiredTo.value(), "UTF-8")));

		// --- headers: a non-string value is refused with a clear message naming the key
		try
		{
			withHeaders.buildProducerRecord(inputs("topic", "t", "key", "K4", "headers", "{\"mdm-change\":1}"), Json.parse("{}"));
			check("a non-string header value must be refused", false);
		}
		catch (final ConnectorException expected)
		{
			check("message names the offending header: " + expected.getMessage(),
				expected.getMessage().contains("mdm-change") && expected.getMessage().contains("string"));
		}

		// --- tombstone: null value for the resolved key, envelope=true is ignored
		final KafkaConnector enveloped = newConnector(null);
		enveloped.configure(configWith(null, "true"));
		final Map<String, String> tombstoneInputs = inputs("topic", "t", "key", "K5", "tombstone", "true");
		final ProducerRecord<String, String> tombstoneRecord = enveloped.buildProducerRecord(tombstoneInputs, Json.parse("{\"name\":\"Acme\"}"));
		check("tombstone key kept: " + tombstoneRecord.key(), "K5".equals(tombstoneRecord.key()));
		check("tombstone value is null even though envelope=true", tombstoneRecord.value() == null);

		// --- tombstone: headers still travel with the null value
		final Map<String, String> tombstoneWithHeaders = inputs("topic", "t", "key", "K6", "tombstone", "true",
			"headers", "{\"mdm-change\":\"RETIRE\"}");
		final ProducerRecord<String, String> tombstoneHeaderRecord = enveloped.buildProducerRecord(tombstoneWithHeaders, Json.parse("{}"));
		check("tombstone value still null with headers", tombstoneHeaderRecord.value() == null);
		check("tombstone headers preserved: " + tombstoneHeaderRecord.headers(),
			"RETIRE".equals(new String(header(tombstoneHeaderRecord.headers(), "mdm-change").value(), "UTF-8")));

		// --- tombstone: refused when no key can be resolved (no input key, no key.field property)
		final KafkaConnector noKey = newConnector(null);
		try
		{
			noKey.buildProducerRecord(inputs("topic", "t", "tombstone", "true"), Json.parse("{}"));
			check("tombstone without a resolvable key must be refused", false);
		}
		catch (final ConnectorException expected)
		{
			check("message explains the refusal: " + expected.getMessage(),
				expected.getMessage().contains("tombstone") && expected.getMessage().contains("key"));
		}

		System.out.println("KafkaPublishRecordTest: OK");
	}

	private static KafkaConnector newConnector(final String pKeyField) throws Exception
	{
		final KafkaConnector connector = new KafkaConnector();
		connector.configure(configWith(pKeyField, "false"));
		return connector;
	}

	private static ConnectorConfig configWith(final String pKeyField, final String pEnvelope)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("kafka-test");
		config.setType("kafka");
		config.setBaseUrl("localhost:9092");
		config.setReadTimeoutMs(5000);
		config.setConnectTimeoutMs(5000);
		if (pKeyField != null)
		{
			config.setProperty("key.field", pKeyField);
		}
		config.setProperty("envelope", pEnvelope);
		return config;
	}

	private static Map<String, String> inputs(final String... pKeyValuePairs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		for (int i = 0; i < pKeyValuePairs.length; i += 2)
		{
			inputs.put(pKeyValuePairs[i], pKeyValuePairs[i + 1]);
		}
		return inputs;
	}

	private static boolean isEmpty(final Iterable<Header> pHeaders)
	{
		if (pHeaders == null) return true;
		return !pHeaders.iterator().hasNext();
	}

	private static Header header(final Iterable<Header> pHeaders, final String pKey)
	{
		for (final Header h : pHeaders)
		{
			if (pKey.equals(h.key())) return h;
		}
		throw new AssertionError("no header " + pKey + " in " + pHeaders);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
