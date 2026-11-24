package com.on.ebx.connect.ebx.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingRule;
import com.on.ebx.connect.ebx.ConfigTransfer;

/**
 * The flow document of the contract ({@code flows.put}, {@code flows.get}): the mapping document of
 * {@code connectors_mapping_upsert} and of the configuration export, typed and closed. Pure: this class checks the shape
 * (known fields only, each of its type, the enumerations), turns the caller's key into the mapping's label and, when the
 * key already designates a mapping, into an update of that mapping by id; the upsert then applies its own validation
 * (connector exists, required fields, feed, policies) in {@link ConfigTransfer#upsertMapping}. The field lists come from
 * {@link ConfigTransfer} itself, so a field added to the Mapping model is accepted here the day the upsert writes it.
 */
public final class FlowDocument
{
	/** Fields of a flow that are not Mapping fields: associations and the feed shorthand. */
	static final List<String> STRUCTURE = Collections.unmodifiableList(Arrays.asList("inputs", "rules", "children", "feed"));
	/** Mapping fields typed boolean in Connectors.xsd. */
	static final Set<String> BOOLEANS = new HashSet<String>(Arrays.asList("active", "deleteMissing", "createIfMissing", "provenance", "logEveryRecord"));
	/** Mapping fields typed integer in Connectors.xsd. */
	static final Set<String> INTEGERS = new HashSet<String>(Arrays.asList("batchSize", "maxAttempts", "retryDelayMinutes"));
	/** MappingRule fields not typed string. */
	static final Set<String> RULE_BOOLEANS = new HashSet<String>(Arrays.asList("active"));
	static final Set<String> RULE_INTEGERS = new HashSet<String>(Arrays.asList("position"));
	/** Required at the root (with the key, which becomes the label). */
	static final List<String> ROOT_REQUIRED = Collections.unmodifiableList(Arrays.asList("connector", "operation", "direction", "dataset", "tablePath"));

	private FlowDocument()
	{
	}

	/** The key, trimmed; refused when blank or carrying a control character (it becomes a label, and part of XPath filters). */
	public static String key(final String pKey) throws ContractException
	{
		final String key = pKey == null ? "" : pKey.trim();
		if (key.isEmpty())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, "the flow key must not be blank");
		}
		for (int i = 0; i < key.length(); i++)
		{
			if (Character.isISOControl(key.charAt(i)))
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, "the flow key must not contain a control character");
			}
		}
		return key;
	}

	/**
	 * The upsert document for the flow of that key: a copy of {@code pFlow} checked and closed, with {@code label} = the
	 * key and, when {@code pExistingId} is given, {@code id} + {@code updateById} so that the upsert updates that mapping
	 * even when its connector, operation, direction or table changed. The caller's document is not modified.
	 */
	public static ObjectNode toUpsert(final String pKey, final JsonNode pFlow, final Integer pExistingId) throws ContractException
	{
		final String key = FlowDocument.key(pKey);
		if (pFlow == null || !pFlow.isObject())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, "flow must be a JSON object");
		}
		final ObjectNode out = ((ObjectNode) pFlow).deepCopy();
		final JsonNode label = out.get("label");
		if (label != null && !label.isNull() && !(label.isTextual() && key.equals(label.asText().trim())))
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, "flow.label must be the key ('" + key + "') or be omitted: the key is the flow's label");
		}
		FlowDocument.check("flow", out, true);
		out.put("label", key);
		if (pExistingId != null)
		{
			out.put("id", String.valueOf(pExistingId));
			out.put("updateById", true);
		}
		return out;
	}

	/** The flow document of an exported mapping ({@link ConfigTransfer#exportMapping}): the same, without the ids. */
	public static ObjectNode fromExport(final ObjectNode pExported)
	{
		final ObjectNode out = pExported.deepCopy();
		FlowDocument.stripIds(out);
		return out;
	}

	private static void stripIds(final ObjectNode pMapping)
	{
		pMapping.remove("id");
		pMapping.remove("updateById");
		final JsonNode children = pMapping.get("children");
		if (children != null && children.isArray())
		{
			for (final JsonNode child : children)
			{
				if (child.isObject())
				{
					FlowDocument.stripIds((ObjectNode) child);
				}
			}
		}
	}

	/**
	 * The id of the ROOT mapping whose label is the key (exact, after trimming both); null when none. Several root mappings
	 * with that label is {@link ContractError#AMBIGUOUS_KEY}: the contract never picks one at random.
	 */
	public static Integer resolve(final List<MappingDefinition> pMappings, final String pKey) throws ContractException
	{
		Integer found = null;
		for (final MappingDefinition mapping : pMappings)
		{
			if (mapping.isChild() || mapping.getLabel() == null || !pKey.equals(mapping.getLabel().trim()))
			{
				continue;
			}
			if (found != null)
			{
				throw new ContractException(ContractError.AMBIGUOUS_KEY, "more than one root mapping is labelled '" + pKey + "' (ids " + found + " and "
					+ mapping.getId() + "): rename one in the Connectors screens");
			}
			found = Integer.valueOf(mapping.getId());
		}
		return found;
	}

	private static void check(final String pWhere, final ObjectNode pMapping, final boolean pRoot) throws ContractException
	{
		final List<String> fields = ConfigTransfer.mappingFields();
		for (final Iterator<Map.Entry<String, JsonNode>> it = pMapping.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> entry = it.next();
			final String name = entry.getKey();
			final JsonNode value = entry.getValue();
			if ("id".equals(name) || "updateById".equals(name) || "parent".equals(name))
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + "." + name + " is not accepted: the flow key designates the mapping");
			}
			if (!fields.contains(name) && !FlowDocument.STRUCTURE.contains(name))
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + "." + name + " is not a field of a flow");
			}
			if (value == null || value.isNull())
			{
				continue;
			}
			if ("inputs".equals(name))
			{
				FlowDocument.checkInputs(pWhere + ".inputs", value);
			}
			else if ("rules".equals(name))
			{
				FlowDocument.checkRules(pWhere + ".rules", value);
			}
			else if ("children".equals(name))
			{
				FlowDocument.checkChildren(pWhere + ".children", value);
			}
			else if ("feed".equals(name))
			{
				if (!value.isObject() && !value.isBoolean())
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + ".feed must be an object {itemsPath, keyPredicate, createIfMissing}");
				}
				if (value.isObject() && value.hasNonNull("keyPredicate"))
				{
					FlowDocument.checkKeyPredicate(pWhere + ".feed.keyPredicate", value.get("keyPredicate"));
				}
			}
			else if ("keyPredicate".equals(name))
			{
				FlowDocument.checkScalar(pWhere + "." + name, value, FlowDocument.BOOLEANS.contains(name), FlowDocument.INTEGERS.contains(name));
				FlowDocument.checkKeyPredicate(pWhere + ".keyPredicate", value);
			}
			else
			{
				FlowDocument.checkScalar(pWhere + "." + name, value, FlowDocument.BOOLEANS.contains(name), FlowDocument.INTEGERS.contains(name));
			}
		}
		if (pRoot)
		{
			for (final String required : FlowDocument.ROOT_REQUIRED)
			{
				final JsonNode value = pMapping.get(required);
				if (value == null || value.isNull() || value.asText().trim().isEmpty())
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + "." + required + " is required");
				}
			}
		}
		final JsonNode direction = pMapping.get("direction");
		if (direction != null && !direction.isNull() && !"INBOUND".equals(direction.asText()) && !"OUTBOUND".equals(direction.asText()))
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + ".direction must be INBOUND or OUTBOUND");
		}
		final JsonNode table = pMapping.get("tablePath");
		if (table != null && !table.isNull() && !table.asText().startsWith("/"))
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + ".tablePath must be an absolute table path, e.g. /root/Asset");
		}
	}

	private static void checkScalar(final String pWhere, final JsonNode pValue, final boolean pBoolean, final boolean pInteger) throws ContractException
	{
		if (pBoolean && !pValue.isBoolean())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be true or false");
		}
		if (pInteger && !(pValue.isIntegralNumber() && pValue.canConvertToInt()))
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be an integer");
		}
		if (!pBoolean && !pInteger && !pValue.isTextual())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be a string");
		}
	}

	/** Audit C2-04: a placeholder of a key predicate must sit between apostrophes (./id='${id}'); the refusal says so in EN and FR. */
	private static void checkKeyPredicate(final String pWhere, final JsonNode pValue) throws ContractException
	{
		final String refusal = com.on.ebx.connect.core.ValueTemplates.xpathRefusal(pValue.asText());
		if (refusal != null)
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + ": " + refusal);
		}
	}

	private static void checkInputs(final String pWhere, final JsonNode pInputs) throws ContractException
	{
		if (!pInputs.isObject())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be an object {name: expression}");
		}
		for (final Iterator<Map.Entry<String, JsonNode>> it = pInputs.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> entry = it.next();
			if (entry.getKey().trim().isEmpty())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " has an input without a name");
			}
			if (entry.getValue() != null && !entry.getValue().isNull() && !entry.getValue().isTextual())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + "." + entry.getKey() + " must be a string (an expression)");
			}
			if ("sql".equals(entry.getKey()) && entry.getValue() != null && entry.getValue().isTextual())
			{
				final String refusal = com.on.ebx.connect.core.ValueTemplates.sqlRefusal(entry.getValue().asText());
				if (refusal != null)
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + ".sql: " + refusal);
				}
			}
		}
	}

	private static void checkRules(final String pWhere, final JsonNode pRules) throws ContractException
	{
		if (!pRules.isArray())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be an array of rules");
		}
		final List<String> fields = ConfigTransfer.ruleFields();
		int index = 0;
		for (final JsonNode rule : pRules)
		{
			final String where = pWhere + "[" + index++ + "]";
			if (!rule.isObject())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be an object");
			}
			for (final Iterator<Map.Entry<String, JsonNode>> it = rule.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				if (!fields.contains(entry.getKey()))
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, where + "." + entry.getKey() + " is not a field of a rule " + fields);
				}
				if (entry.getValue() == null || entry.getValue().isNull())
				{
					continue;
				}
				FlowDocument.checkScalar(where + "." + entry.getKey(), entry.getValue(), FlowDocument.RULE_BOOLEANS.contains(entry.getKey()),
					FlowDocument.RULE_INTEGERS.contains(entry.getKey()));
				if ("policy".equals(entry.getKey()) && !FlowDocument.isPolicy(entry.getValue().asText()))
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, where + ".policy must be one of " + Arrays.toString(MappingRule.Policy.values()));
				}
			}
		}
	}

	private static boolean isPolicy(final String pValue)
	{
		for (final MappingRule.Policy policy : MappingRule.Policy.values())
		{
			if (policy.name().equals(pValue))
			{
				return true;
			}
		}
		return false;
	}

	private static void checkChildren(final String pWhere, final JsonNode pChildren) throws ContractException
	{
		if (!pChildren.isArray())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pWhere + " must be an array of sub-object mappings");
		}
		int index = 0;
		for (final JsonNode child : pChildren)
		{
			final String where = pWhere + "[" + index++ + "]";
			if (!child.isObject())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be an object");
			}
			final JsonNode label = child.get("label");
			if (label == null || !label.isTextual() || label.asText().trim().isEmpty())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + ".label is required: a sub-object mapping is matched by its label under its parent");
			}
			final JsonNode table = child.get("tablePath");
			if (table == null || !table.isTextual() || table.asText().trim().isEmpty())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + ".tablePath is required");
			}
			FlowDocument.check(where, (ObjectNode) child, false);
		}
	}

	/** Pure: the run-time inputs of {@code flows.run} as the text connectors_sync reads ({@code name=value;...}); null for none. */
	public static String runtimeInputs(final JsonNode pInputs) throws ContractException
	{
		if (pInputs == null || pInputs.isNull() || pInputs.size() == 0)
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (final Iterator<Map.Entry<String, JsonNode>> it = pInputs.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> entry = it.next();
			final String name = entry.getKey().trim();
			if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf(';') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, "inputs: an input name must not be blank nor contain '=', ';' or a line break");
			}
			final JsonNode value = entry.getValue();
			if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean()))
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, "inputs." + name + " must be a string, a number or a boolean");
			}
			final String text = value.asText();
			if (text.indexOf(';') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, "inputs." + name + " must not contain ';' or a line break (connectors_sync separates inputs with them)");
			}
			out.append(out.length() == 0 ? "" : ";").append(name).append('=').append(text);
		}
		return out.toString();
	}
}
