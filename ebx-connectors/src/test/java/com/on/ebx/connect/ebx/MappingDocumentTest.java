package com.on.ebx.connect.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;

/**
 * Offline contract of the mapping documents written by connectors_mapping_upsert: a sub-object
 * mapping takes what it omits from its parent, and a feed may be given flat or under {@code feed}.
 * No EBX repository.
 */
public final class MappingDocumentTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode parent = (ObjectNode) Json.parse("{\"connector\":\"Kafka\",\"operation\":\"publish\",\"direction\":\"OUTBOUND\",\"dataspace\":\"PointOfSale\","
			+ "\"dataset\":\"PointOfSale\",\"tablePath\":\"/root/PointOfSale\",\"label\":\"POS -> Kafka\"}");
		final JsonNode child = Json.parse("{\"label\":\"brands\",\"tablePath\":\"/root/BrandPresence\",\"collectionPath\":\"brands\",\"dataset\":\"\"}");
		final JsonNode completed = ConfigTransfer.inherited(parent, child);
		check("Kafka".equals(completed.get("connector").asText()) && "publish".equals(completed.get("operation").asText())
			&& "OUTBOUND".equals(completed.get("direction").asText()), "a child takes connector, operation and direction from its parent: " + completed);
		check("PointOfSale".equals(completed.get("dataset").asText()) && "PointOfSale".equals(completed.get("dataspace").asText()),
			"a blank dataset counts as omitted: " + completed);
		check("/root/BrandPresence".equals(completed.get("tablePath").asText()), "a child keeps its own table: " + completed);
		check(!child.has("connector"), "the caller's document is not modified");
		final JsonNode own = ConfigTransfer.inherited(parent, Json.parse("{\"label\":\"x\",\"direction\":\"INBOUND\"}"));
		check("INBOUND".equals(own.get("direction").asText()), "a value the child sets wins");

		final ArrayNode notes = Json.object().putArray("notes");
		final JsonNode feed = ConfigTransfer.withFeed(Json.parse("{\"label\":\"ERP\",\"feed\":{\"itemsPath\":\"messages[*].value\",\"keyPredicate\":\"./Code='${code}'\","
			+ "\"createIfMissing\":false}}"), notes);
		check(!feed.has("feed") && "messages[*].value".equals(feed.get("itemsPath").asText()) && "./Code='${code}'".equals(feed.get("keyPredicate").asText())
			&& !feed.get("createIfMissing").asBoolean() && notes.size() == 0, "feed {} flattened onto the mapping: " + feed);
		final JsonNode flat = Json.parse("{\"label\":\"ERP\",\"keyPredicate\":\"./Code='${code}'\"}");
		check(ConfigTransfer.withFeed(flat, notes) == flat, "a flat feed is kept as is");
		ConfigTransfer.withFeed(Json.parse("{\"label\":\"ERP\",\"feed\":true}"), notes);
		check(notes.size() == 1 && notes.get(0).asText().contains("keyPredicate"), "feed=true without keyPredicate is reported, not silently ignored: " + notes);

		System.out.println("MappingDocumentTest: OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
