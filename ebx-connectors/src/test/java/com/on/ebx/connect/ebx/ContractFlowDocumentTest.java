package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.ebx.contract.ContractError;
import com.on.ebx.connect.ebx.contract.ContractException;
import com.on.ebx.connect.ebx.contract.FlowDocument;

/**
 * The flow document of the in-JVM contract against the mapping upsert it feeds, offline: a flow as the data catalog
 * declares its scan (inputs, rules with policies, a sub-object mapping, a feed) is checked and turned into an upsert
 * document that the upsert's own steps (feed flattening, inheritance of a child) accept without a note and without a field
 * it would drop; what the configuration export gives back of it reads again as the same flow (get, change, put); the
 * key designates exactly one root mapping; and every malformed document is refused with the field named.
 */
public final class ContractFlowDocumentTest
{
	private static int checks;

	private ContractFlowDocumentTest()
	{
	}

	private static final String SCAN = "{\"connector\":\"SNOW\",\"operation\":\"catalog\",\"direction\":\"INBOUND\",\"dataspace\":\"DataCatalog\",\"dataset\":\"DataCatalog\","
		+ "\"tablePath\":\"/root/Asset\",\"description\":\"Inventory of source X\",\"active\":true,\"batchSize\":500,"
		+ "\"feed\":{\"itemsPath\":\"assets\",\"keyPredicate\":\"./assetKey='${assetKey}'\",\"createIfMissing\":true},"
		+ "\"inputs\":{\"source\":\"X\",\"schemas\":\"SALES\",\"tables\":\"${param.tables|default(*)}\"},"
		+ "\"rules\":[{\"sourcePath\":\"assetKey\",\"targetPath\":\"./assetKey\",\"transformation\":\"\",\"policy\":\"IF_EMPTY\"},"
		+ "{\"sourcePath\":\"name\",\"targetPath\":\"./name\",\"policy\":\"ALWAYS\",\"active\":true,\"comment\":\"kept in step\"},"
		+ "{\"sourcePath\":\"\",\"targetPath\":\"./status\",\"transformation\":\"constant(ACTIVE)\",\"policy\":\"ALWAYS\",\"position\":90}],"
		+ "\"children\":[{\"label\":\"Catalog scan columns: X\",\"tablePath\":\"/root/Column\",\"collectionPath\":\"columns\",\"parentLinkField\":\"./asset\","
		+ "\"childKeyFields\":\"./name\",\"rules\":[{\"sourcePath\":\"name\",\"targetPath\":\"./name\",\"policy\":\"ALWAYS\"}]}]}";

	public static void main(final String[] pArgs) throws Exception
	{
		final JsonNode flow = Json.parse(ContractFlowDocumentTest.SCAN);
		final String before = Json.write(flow);

		// a new flow: the key becomes the label, nothing designates an existing mapping
		final ObjectNode created = FlowDocument.toUpsert("  Catalog scan: X ", flow, null);
		eq("Catalog scan: X", created.path("label").asText(), "the trimmed key is the label");
		yes(!created.has("id") && !created.has("updateById"), "a new flow carries no id");
		eq(before, Json.write(flow), "the caller's document is not modified");

		// an existing flow: update by id, so that a changed connector or table still updates the same mapping
		final ObjectNode updated = FlowDocument.toUpsert("Catalog scan: X", flow, Integer.valueOf(231));
		yes("231".equals(updated.path("id").asText()) && updated.path("updateById").asBoolean(), "the resolved mapping is updated by id: " + updated.path("id"));

		// the upsert's own steps accept it as it is: the feed is flattened without a note, the child inherits the parent
		final ArrayNode notes = Json.object().putArray("notes");
		final JsonNode flattened = ConfigTransfer.withFeed(created, notes);
		yes(notes.size() == 0 && "./assetKey='${assetKey}'".equals(flattened.path("keyPredicate").asText()) && "assets".equals(flattened.path("itemsPath").asText())
			&& !flattened.has("feed"), "feed flattened by the upsert, no note: " + notes);
		final JsonNode child = ConfigTransfer.inherited(flattened, flattened.path("children").get(0));
		yes("SNOW".equals(child.path("connector").asText()) && "INBOUND".equals(child.path("direction").asText()) && "DataCatalog".equals(child.path("dataset").asText()),
			"the child takes connector, direction and dataset from the flow");
		ContractFlowDocumentTest.everyFieldWritten(flattened, "flow");

		// get, change, put: the export of that mapping reads back as the same flow (ids and the upsert flag removed)
		final ObjectNode exported = ContractFlowDocumentTest.exportOf(flattened, 231);
		final ObjectNode read = FlowDocument.fromExport(exported);
		yes(!read.has("id") && !read.path("children").get(0).has("id"), "flows.get carries no ids: " + read);
		eq("Catalog scan: X", read.path("label").asText(), "flows.get carries the key as label");
		final ObjectNode again = FlowDocument.toUpsert("Catalog scan: X", read, Integer.valueOf(231));
		ContractFlowDocumentTest.contains(flattened, again, "put(get(flow))");
		final ObjectNode readAgain = FlowDocument.fromExport(ContractFlowDocumentTest.exportOf(ConfigTransfer.withFeed(again, notes), 231));
		eq(read, readAgain, "get(put(get(flow))) = get(flow): a fixed point, nothing drifts from one round to the next");
		yes(notes.size() == 0, "no note from the upsert on the round trip");
		read.put("description", "changed");
		eq("changed", FlowDocument.toUpsert("Catalog scan: X", read, Integer.valueOf(231)).path("description").asText(), "a changed field goes through");

		// the key: exactly one root mapping, children ignored, never a guess
		final List<MappingDefinition> mappings = new ArrayList<MappingDefinition>();
		mappings.add(ContractFlowDocumentTest.mapping(10, "Catalog scan: X ", null));
		mappings.add(ContractFlowDocumentTest.mapping(11, "Catalog scan: X", Integer.valueOf(10)));
		mappings.add(ContractFlowDocumentTest.mapping(12, "catalog scan: x", null));
		mappings.add(ContractFlowDocumentTest.mapping(13, null, null));
		eq(Integer.valueOf(10), FlowDocument.resolve(mappings, "Catalog scan: X"), "the root mapping of that label, its trailing space ignored, the child skipped");
		eq(null, FlowDocument.resolve(mappings, "Catalog scan: Y"), "no mapping: null (flows.put creates, flows.delete answers deleted=false)");
		eq(Integer.valueOf(12), FlowDocument.resolve(mappings, "catalog scan: x"), "the key is exact, case included");
		mappings.add(ContractFlowDocumentTest.mapping(14, "Catalog scan: X", null));
		try
		{
			FlowDocument.resolve(mappings, "Catalog scan: X");
			yes(false, "two root mappings with that label must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.AMBIGUOUS_KEY && ex.getMessage().contains("10") && ex.getMessage().contains("14"), "ambiguous key named: " + ex.getMessage());
		}

		// refusals, each naming the field
		ContractFlowDocumentTest.refused(" ", ContractFlowDocumentTest.SCAN, "must not be blank");
		ContractFlowDocumentTest.refused("a\nb", ContractFlowDocumentTest.SCAN, "control character");
		ContractFlowDocumentTest.refused("k", "[]", "flow must be a JSON object");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("label", "\"Other\""), "flow.label must be the key");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("id", "\"231\""), "flow.id is not accepted");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("parent", "\"231\""), "flow.parent is not accepted");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("tableName", "\"Asset\""), "flow.tableName is not a field of a flow");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("active", "\"true\""), "flow.active must be true or false");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("batchSize", "\"500\""), "flow.batchSize must be an integer");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("keyPredicate", "42"), "flow.keyPredicate must be a string");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("direction", "\"BOTH\""), "flow.direction must be INBOUND or OUTBOUND");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("tablePath", "\"Asset\""), "absolute table path");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("connector", "\" \""), "flow.connector is required");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("inputs", "{\"limit\":100}"), "flow.inputs.limit must be a string");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("rules", "[{\"target\":\"./x\"}]"), "flow.rules[0].target is not a field of a rule");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("rules", "[{\"targetPath\":\"./x\",\"policy\":\"SOMETIMES\"}]"), "flow.rules[0].policy must be one of");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("rules", "[{\"targetPath\":\"./x\",\"position\":\"1\"}]"), "flow.rules[0].position must be an integer");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("rules", "{}"), "flow.rules must be an array");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("children", "[{\"tablePath\":\"/root/Column\"}]"), "flow.children[0].label is required");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("children", "[{\"label\":\"c\",\"tablePath\":\"/root/Column\",\"rules\":[{\"policy\":\"NEVER\"}]}]"),
			"flow.children[0].rules[0].policy");
		ContractFlowDocumentTest.refused("k", ContractFlowDocumentTest.with("feed", "\"yes\""), "flow.feed must be an object");
		yes(FlowDocument.toUpsert("k", Json.parse(ContractFlowDocumentTest.with("label", "\"k\"")), null) != null, "label equal to the key is accepted");

		System.out.println("PASS: contract flow documents round-trip through the mapping upsert (" + ContractFlowDocumentTest.checks + " checks)");
	}

	/** Every field of an upsert document is one the upsert writes (a Mapping field) or carries (inputs, rules, children). */
	private static void everyFieldWritten(final JsonNode pMapping, final String pWhere)
	{
		final List<String> carried = Arrays.asList("inputs", "rules", "children", "id", "updateById");
		for (final Iterator<String> it = pMapping.fieldNames(); it.hasNext();)
		{
			final String field = it.next();
			yes(ConfigTransfer.mappingFields().contains(field) || carried.contains(field), pWhere + "." + field + " would be dropped by the upsert");
		}
		for (final JsonNode rule : pMapping.path("rules"))
		{
			for (final Iterator<String> it = rule.fieldNames(); it.hasNext();)
			{
				final String field = it.next();
				yes(ConfigTransfer.ruleFields().contains(field), pWhere + ".rules." + field + " would be dropped by the upsert");
			}
		}
		for (final JsonNode child : pMapping.path("children"))
		{
			ContractFlowDocumentTest.everyFieldWritten(child, pWhere + ".children");
		}
	}

	/** Every field of {@code pExpected} (children included, by position) is in {@code pActual} with the same value. */
	private static void contains(final JsonNode pExpected, final JsonNode pActual, final String pWhere)
	{
		for (final Iterator<Map.Entry<String, JsonNode>> it = pExpected.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> field = it.next();
			if ("children".equals(field.getKey()))
			{
				for (int i = 0; i < field.getValue().size(); i++)
				{
					ContractFlowDocumentTest.contains(field.getValue().get(i), pActual.path("children").get(i), pWhere + ".children[" + i + "]");
				}
				continue;
			}
			eq(field.getValue(), pActual.get(field.getKey()), pWhere + "." + field.getKey());
		}
	}

	/** What ConfigTransfer.exportMapping writes for a mapping stored from that upsert document: its fields, an id on each mapping. */
	private static ObjectNode exportOf(final JsonNode pUpsert, final int pId)
	{
		final ObjectNode out = Json.object();
		out.put("id", String.valueOf(pId));
		for (final Iterator<Map.Entry<String, JsonNode>> it = pUpsert.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> field = it.next();
			if (ConfigTransfer.mappingFields().contains(field.getKey()) || "inputs".equals(field.getKey()) || "rules".equals(field.getKey()))
			{
				out.set(field.getKey(), field.getValue().deepCopy());
			}
		}
		// the export always writes the three associations, empty when the mapping has none
		if (!out.has("inputs"))
		{
			out.putObject("inputs");
		}
		if (!out.has("rules"))
		{
			out.putArray("rules");
		}
		final ArrayNode children = out.putArray("children");
		int childId = pId;
		for (final JsonNode child : pUpsert.path("children"))
		{
			children.add(ContractFlowDocumentTest.exportOf(child, ++childId));
		}
		return out;
	}

	private static String with(final String pField, final String pValue) throws Exception
	{
		final ObjectNode flow = (ObjectNode) Json.parse(ContractFlowDocumentTest.SCAN);
		flow.set(pField, Json.parse(pValue));
		return Json.write(flow);
	}

	private static MappingDefinition mapping(final int pId, final String pLabel, final Integer pParent)
	{
		final MappingDefinition mapping = new MappingDefinition(pId, "SNOW", "catalog", MappingDefinition.Direction.INBOUND, pLabel, "DataCatalog", "DataCatalog",
			"/root/Asset", null, true);
		mapping.setParentId(pParent);
		return mapping;
	}

	private static void refused(final String pKey, final String pFlow, final String pExpected) throws Exception
	{
		try
		{
			FlowDocument.toUpsert(pKey, Json.parse(pFlow), null);
			yes(false, pFlow + " must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.INVALID_ARGUMENT && ex.getMessage().contains(pExpected), "refused with '" + pExpected + "': " + ex.getMessage());
		}
	}

	private static void eq(final Object pExpected, final Object pActual, final String pWhat)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pWhat + ": expected " + pExpected + ", got " + pActual);
	}

	private static void yes(final boolean pCondition, final String pWhat)
	{
		ContractFlowDocumentTest.checks++;
		if (!pCondition)
		{
			throw new AssertionError("FAIL: " + pWhat);
		}
	}
}
