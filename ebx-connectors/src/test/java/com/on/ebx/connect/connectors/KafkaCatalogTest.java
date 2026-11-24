package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Offline contract of the data-catalog operations of the Kafka connector: a fake cluster holds a JSON
 * topic (nested objects, missing fields, mixed numbers, dates), a text topic, an empty topic, a topic
 * the principal cannot read and the internal topics; the documents must have the shape the catalog
 * flows consume, the fields inferred from the messages and the statistics marked as a sample.
 */
public final class KafkaCatalogTest
{
	static final class FakeCluster implements KafkaCatalog.Broker
	{
		final Map<String, List<KafkaCatalog.Message>> topics = new LinkedHashMap<String, List<KafkaCatalog.Message>>();
		final Map<String, Long> retained = new LinkedHashMap<String, Long>();
		final List<String> denied = new ArrayList<String>();

		@Override
		public List<String> topics()
		{
			final List<String> names = new ArrayList<String>(this.topics.keySet());
			names.addAll(this.denied);
			java.util.Collections.sort(names);
			return names;
		}

		@Override
		public long retained(final String pTopic) throws ConnectorException
		{
			if (this.denied.contains(pTopic)) throw new ConnectorException("Topic authorization failed");
			return this.retained.containsKey(pTopic) ? this.retained.get(pTopic).longValue() : this.topics.get(pTopic).size();
		}

		@Override
		public List<KafkaCatalog.Message> latest(final String pTopic, final int pMax) throws ConnectorException
		{
			if (this.denied.contains(pTopic)) throw new ConnectorException("Topic authorization failed");
			final List<KafkaCatalog.Message> all = this.topics.get(pTopic);
			return all.size() > pMax ? all.subList(0, pMax) : all;
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final FakeCluster cluster = new FakeCluster();
		cluster.topics.put("__consumer_offsets", Arrays.asList(new KafkaCatalog.Message(null, 1L, "binary")));
		cluster.topics.put("_schemas", Arrays.asList(new KafkaCatalog.Message(null, 1L, "{\"schema\":\"x\"}")));
		cluster.topics.put("crm.partner.upsert", Arrays.asList(
			new KafkaCatalog.Message("5493001KJTIIGC8Y1R12", 1757836800000L,
				"{\"lei\":\"5493001KJTIIGC8Y1R12\",\"name\":\"Acme SA\",\"address\":{\"city\":\"Paris\",\"country\":\"FR\"},\"revenue\":1200,\"active\":true,\"createdOn\":\"2024-01-15\",\"tags\":[\"a\",\"b\"]}"),
			new KafkaCatalog.Message("9695005MSX1OYEMGDF46", 1757836700000L,
				"{\"lei\":\"9695005MSX1OYEMGDF46\",\"name\":\"Beta SARL\",\"address\":{\"country\":\"DE\"},\"revenue\":88.5,\"active\":false,\"createdOn\":\"2023-12-01\",\"tags\":[]}"),
			new KafkaCatalog.Message(null, 1757836600000L,
				"{\"lei\":\"529900T8BM49AURSDO55\",\"name\":\"Gamma AG\",\"revenue\":null,\"active\":true,\"createdOn\":\"2022-06-30T10:15:00Z\",\"tags\":[\"c\"]}")));
		cluster.retained.put("crm.partner.upsert", Long.valueOf(1200L));
		cluster.topics.put("logs.raw", Arrays.asList(new KafkaCatalog.Message(null, 1757836800000L, "plain text line"),
			new KafkaCatalog.Message(null, 1757836700000L, "{not json")));
		cluster.topics.put("empty.topic", new ArrayList<KafkaCatalog.Message>());
		cluster.denied.add("broken.topic");
		final KafkaCatalog catalog = new KafkaCatalog(cluster, "localhost:9082,localhost:9084");
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", "KAFKA_LOCAL");

		// --- catalog: internal topics left out, an unreadable topic reported, the others described
		final JsonNode scan = catalog.catalog(inputs);
		check(scan.get("count").asInt() == 3, "crm, empty and logs described, internal topics left out: " + scan.get("tables"));
		check(scan.get("errorCount").asInt() == 1 && "broken.topic".equals(scan.get("errors").get(0).get("topic").asText()), "unreadable topic reported: " + scan.get("errors"));
		final JsonNode crm = scan.get("tables").get(0);
		check("crm.partner.upsert".equals(crm.get("qualifiedName").asText()) && "KAFKA_LOCAL:crm.partner.upsert".equals(crm.get("assetKey").asText())
			&& "TOPIC".equals(crm.get("kind").asText()), "topic naming and kind: " + crm);
		check(crm.get("rowCount").asLong() == 1200L && "kafka://localhost:9082/crm.partner.upsert".equals(crm.get("url").asText()), "retained count and url on the first bootstrap server");
		check(crm.get("remarks").asText().contains("3 latest message"), "remarks say the fields are inferred: " + crm.get("remarks"));
		final JsonNode columns = crm.get("columns");
		final List<String> names = new ArrayList<String>();
		for (final JsonNode c : columns) names.add(c.get("name").asText());
		check(names.equals(Arrays.asList("@key", "@timestamp", "lei", "name", "address/city", "address/country", "revenue", "active", "createdOn", "tags")),
			"fields in order of appearance, nested objects flattened: " + names);
		check("STRING".equals(type(columns, "@key")) && field(columns, "@key").get("nullable").asBoolean() && "message key".equals(field(columns, "@key").get("nativeType").asText()),
			"message key, nullable since one message has none");
		check("TIMESTAMP".equals(type(columns, "@timestamp")), "message timestamp");
		check("DECIMAL".equals(type(columns, "revenue")) && field(columns, "revenue").get("nullable").asBoolean(), "1200 and 88.5 widen to DECIMAL; a null makes it nullable");
		check("BOOLEAN".equals(type(columns, "active")) && !field(columns, "active").get("nullable").asBoolean(), "boolean present everywhere");
		check("TIMESTAMP".equals(type(columns, "createdOn")), "two dates and a date-time widen to TIMESTAMP");
		check("JSON".equals(type(columns, "tags")) && "array".equals(field(columns, "tags").get("nativeType").asText()), "array kept as JSON");
		check(field(columns, "tags").get("nullable").asBoolean(), "an empty array is no value: tags is in every message but nullable, as the profile counts it");
		check(field(columns, "address/city").get("nullable").asBoolean() && !field(columns, "lei").get("nullable").asBoolean(), "a field missing from a message is nullable");
		check("KAFKA_LOCAL:crm.partner.upsert.address/city".equals(field(columns, "address/city").get("columnKey").asText()), "column key = assetKey.path");
		final JsonNode empty = scan.get("tables").get(1);
		check("empty.topic".equals(empty.get("name").asText()) && empty.get("columns").size() == 0 && empty.get("remarks").asText().contains("without any retained message"), "empty topic: no field");
		final JsonNode logs = scan.get("tables").get(2);
		check(logs.get("columns").size() == 2 && "@value".equals(logs.get("columns").get(1).get("name").asText()) && "STRING".equals(logs.get("columns").get(1).get("dataType").asText()),
			"a payload that is not a JSON object lands in @value: " + logs.get("columns"));

		// --- schema filter: topic patterns
		final Map<String, String> crmOnly = new LinkedHashMap<String, String>(inputs);
		crmOnly.put("schemas", "crm.*");
		final JsonNode filtered = catalog.catalog(crmOnly);
		check(filtered.get("count").asInt() == 1 && !filtered.has("errors"), "schemas = crm.* keeps one topic and does not touch the unreadable one");
		check(KafkaCatalog.glob("*.raw").matcher("logs.raw").matches() && !KafkaCatalog.glob("logs").matcher("logs.raw").matches(), "glob patterns");

		// --- profile: statistics on the messages read, marked SAMPLE, counts scaled to the retained messages
		final Map<String, String> profileInputs = new LinkedHashMap<String, String>(crmOnly);
		profileInputs.put("tables", "*");
		final JsonNode profile = catalog.profile(profileInputs);
		check(profile.get("count").asInt() == 10, "one item per inferred field: " + profile.get("count"));
		final JsonNode city = field(profile.get("columns"), "address/city");
		check("SAMPLE".equals(city.get("method").asText()) && city.get("sampleSize").asInt() == 3 && city.get("rowCount").asLong() == 1200L, "sample marked: " + city);
		check(Math.abs(city.get("nullRate").asDouble() - 0.6667) < 1e-9 && city.get("nullCount").asLong() == 800L, "null rate of the sample, count scaled: " + city);
		final JsonNode revenue = field(profile.get("columns"), "revenue");
		check(revenue.get("meanValue").asDouble() == 644.25 && "88.5".equals(revenue.get("minValue").asText()) && "1200".equals(revenue.get("maxValue").asText()), "numeric statistics: " + revenue);
		check(field(profile.get("columns"), "@key").get("distinctCount").asInt() == 2, "distinct keys");
		final Map<String, String> oneTable = new LinkedHashMap<String, String>(inputs);
		oneTable.put("tables", "crm.partner.upsert");
		final JsonNode single = catalog.profile(oneTable);
		check(single.get("count").asInt() == 10 && !single.has("errors"), "tables = one topic (run-time input of the re-profile button) reads nothing else");

		// --- sample: text fields only
		final JsonNode sample = catalog.sample(oneTable);
		check(sample.get("count").asInt() == 5, "@key, lei, name, address/city, address/country: " + sample);
		final JsonNode lei = field(sample.get("columns"), "lei");
		check(lei.get("values").size() == 3 && lei.get("sampleSize").asInt() == 3 && lei.get("valuesText").asText().startsWith("5493001KJTIIGC8Y1R12\n"), "sampled values: " + lei);

		// --- nothing readable at all is an error, not an empty result; the source is required
		final FakeCluster locked = new FakeCluster();
		locked.denied.add("secret.topic");
		try
		{
			new KafkaCatalog(locked, "localhost:9082").catalog(inputs);
			check(false, "a cluster where no topic can be read must fail");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("secret.topic") && expected.getMessage().contains("authorization"), "message names the topic and the cause: " + expected.getMessage());
		}
		try
		{
			catalog.catalog(new LinkedHashMap<String, String>());
			check(false, "source is required");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("source"), "message names the input");
		}
		System.out.println("KafkaCatalogTest: OK");
	}

	private static JsonNode field(final JsonNode pColumns, final String pName)
	{
		for (final JsonNode c : pColumns)
		{
			if (pName.equals(c.get("name").asText())) return c;
		}
		throw new AssertionError("no field " + pName + " in " + pColumns);
	}

	private static String type(final JsonNode pColumns, final String pName)
	{
		return KafkaCatalogTest.field(pColumns, pName).get("dataType").asText();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
