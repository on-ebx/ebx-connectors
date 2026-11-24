package com.on.ebx.connect.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Path access over Jackson trees. Paths are dotted: {@code data.attributes.entity.legalName.name},
 * {@code bic[0]}, {@code results[0].geometry.location.lat}; {@code addressLines[*]} returns the
 * array itself (a list once converted with {@link #toJava(JsonNode)}). A missing step yields null.
 */
public final class Json
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private Json()
	{
	}

	public static ObjectMapper mapper()
	{
		return Json.MAPPER;
	}

	public static ObjectNode object()
	{
		return JsonNodeFactory.instance.objectNode();
	}

	public static JsonNode parse(final String pText) throws java.io.IOException
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		return Json.MAPPER.readTree(pText);
	}

	public static String write(final JsonNode pNode)
	{
		try
		{
			return pNode == null ? "null" : Json.MAPPER.writeValueAsString(pNode);
		}
		catch (final Exception ex)
		{
			return String.valueOf(pNode);
		}
	}

	/**
	 * {@link #write} for JSON that becomes script source in a page (an inline script, {@code addJS}): the HTML
	 * parser ends a script element at the first {@code </script} whatever the JavaScript around it, so a remote
	 * sample value holding one would close the script and run what follows it as markup. The result is the same
	 * JSON value, with nothing the HTML parser reacts to.
	 */
	public static String writeForScript(final JsonNode pNode)
	{
		return Json.forScript(Json.write(pNode));
	}

	/**
	 * A JSON text made safe as script source: {@code <}, {@code >}, {@code &} and the two line separators
	 * JavaScript used to refuse inside a string (U+2028, U+2029) become their six-character JSON escape. In valid JSON these
	 * characters only occur inside strings, where the escape denotes the same character, so the value is unchanged.
	 */
	public static String forScript(final String pJson)
	{
		if (pJson == null)
		{
			return "null";
		}
		StringBuilder out = null;
		for (int i = 0; i < pJson.length(); i++)
		{
			final char c = pJson.charAt(i);
			if (c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029')
			{
				if (out == null)
				{
					out = new StringBuilder(pJson.length() + 32).append(pJson, 0, i);
				}
				out.append("\\u").append(c == '<' ? "003c" : c == '>' ? "003e" : c == '&' ? "0026" : c == '\u2028' ? "2028" : "2029");
			}
			else if (out != null)
			{
				out.append(c);
			}
		}
		return out == null ? pJson : out.toString();
	}

	/** Reads a dotted path; null when any step is absent or null. */
	public static JsonNode at(final JsonNode pRoot, final String pPath)
	{
		if (pRoot == null || pRoot.isNull() || pRoot.isMissingNode())
		{
			return null;
		}
		if (pPath == null || pPath.trim().isEmpty())
		{
			return pRoot;
		}
		JsonNode current = pRoot;
		final List<String> steps = Json.split(pPath);
		for (int s = 0; s < steps.size(); s++)
		{
			final String step = steps.get(s);
			// [*] projection: the rest of the path is applied to every element
			if (step.endsWith("[*]"))
			{
				final String name = step.substring(0, step.length() - 3);
				final JsonNode array = name.isEmpty() ? current : (current == null ? null : current.get(name));
				if (array == null || !array.isArray())
				{
					return null;
				}
				final StringBuilder rest = new StringBuilder();
				for (int r = s + 1; r < steps.size(); r++)
				{
					rest.append(r > s + 1 ? "." : "").append(steps.get(r));
				}
				if (rest.length() == 0)
				{
					return array;
				}
				final ArrayNode projected = JsonNodeFactory.instance.arrayNode();
				for (final JsonNode element : array)
				{
					final JsonNode hit = Json.at(element, rest.toString());
					projected.add(hit == null ? JsonNodeFactory.instance.nullNode() : hit);
				}
				return projected;
			}
			if (current == null || current.isNull() || current.isMissingNode())
			{
				return null;
			}
			final int bracket = step.indexOf('[');
			final String name = bracket < 0 ? step : step.substring(0, bracket);
			if (!name.isEmpty())
			{
				current = current.get(name);
			}
			if (bracket >= 0)
			{
				final String index = step.substring(bracket + 1, step.length() - 1).trim();
				if (current == null || !current.isArray())
				{
					return null;
				}
				try
				{
					current = current.get(Integer.parseInt(index));
				}
				catch (final NumberFormatException ex)
				{
					return null;
				}
			}
		}
		return current == null || current.isNull() || current.isMissingNode() ? null : current;
	}

	/** Writes a value at a dotted path, creating intermediate objects. {@code list[]} appends. */
	public static void set(final ObjectNode pRoot, final String pPath, final JsonNode pValue)
	{
		final List<String> steps = Json.split(pPath);
		ObjectNode current = pRoot;
		for (int i = 0; i < steps.size(); i++)
		{
			final String step = steps.get(i);
			final boolean last = i == steps.size() - 1;
			final boolean append = step.endsWith("[]");
			final String name = append ? step.substring(0, step.length() - 2) : step;
			if (last)
			{
				if (append)
				{
					JsonNode array = current.get(name);
					if (array == null || !array.isArray())
					{
						array = current.putArray(name);
					}
					((ArrayNode) array).add(pValue);
				}
				else
				{
					current.set(name, pValue);
				}
				return;
			}
			JsonNode next = current.get(name);
			if (next == null || !next.isObject())
			{
				next = current.putObject(name);
			}
			current = (ObjectNode) next;
		}
	}

	/** JSON to plain Java: String, BigDecimal, Boolean, List of those, or the JSON text of an object. */
	public static Object toJava(final JsonNode pNode)
	{
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			return null;
		}
		if (pNode.isTextual())
		{
			return pNode.asText();
		}
		if (pNode.isNumber())
		{
			return pNode.decimalValue();
		}
		if (pNode.isBoolean())
		{
			return Boolean.valueOf(pNode.asBoolean());
		}
		if (pNode.isArray())
		{
			final List<Object> values = new ArrayList<Object>();
			final Iterator<JsonNode> it = pNode.elements();
			while (it.hasNext())
			{
				values.add(Json.toJava(it.next()));
			}
			return values;
		}
		return Json.write(pNode);
	}

	/** Plain Java to JSON node (inverse of {@link #toJava(JsonNode)}). */
	public static JsonNode fromJava(final Object pValue)
	{
		if (pValue == null)
		{
			return JsonNodeFactory.instance.nullNode();
		}
		if (pValue instanceof JsonNode)
		{
			return (JsonNode) pValue;
		}
		if (pValue instanceof Boolean)
		{
			return JsonNodeFactory.instance.booleanNode(((Boolean) pValue).booleanValue());
		}
		if (pValue instanceof BigDecimal)
		{
			return JsonNodeFactory.instance.numberNode((BigDecimal) pValue);
		}
		if (pValue instanceof Integer)
		{
			return JsonNodeFactory.instance.numberNode(((Integer) pValue).intValue());
		}
		if (pValue instanceof Number)
		{
			return JsonNodeFactory.instance.numberNode(new BigDecimal(pValue.toString()));
		}
		if (pValue instanceof List)
		{
			final ArrayNode array = JsonNodeFactory.instance.arrayNode();
			for (final Object item : (List<?>) pValue)
			{
				array.add(Json.fromJava(item));
			}
			return array;
		}
		return JsonNodeFactory.instance.textNode(String.valueOf(pValue));
	}

	/**
	 * Flattens a document into (path, sample) pairs for the designer: objects recurse, arrays keep
	 * their first element as {@code name[0]} plus the array itself as {@code name} when scalar.
	 */
	public static List<String[]> flatten(final JsonNode pRoot, final int pMaxFields)
	{
		final List<String[]> out = new ArrayList<String[]>();
		Json.flatten(pRoot, "", out, pMaxFields, 0);
		return out;
	}

	private static void flatten(final JsonNode pNode, final String pPrefix, final List<String[]> pOut,
		final int pMax, final int pDepth)
	{
		if (pNode == null || pOut.size() >= pMax || pDepth > 12)
		{
			return;
		}
		if (pNode.isObject())
		{
			final Iterator<java.util.Map.Entry<String, JsonNode>> fields = pNode.fields();
			while (fields.hasNext())
			{
				final java.util.Map.Entry<String, JsonNode> field = fields.next();
				Json.flatten(field.getValue(), pPrefix.isEmpty() ? field.getKey() : pPrefix + "." + field.getKey(), pOut, pMax, pDepth + 1);
			}
			return;
		}
		if (pNode.isArray())
		{
			if (pNode.size() == 0)
			{
				pOut.add(new String[] { pPrefix, "[]", "array" });
				return;
			}
			final JsonNode first = pNode.get(0);
			if (first.isValueNode())
			{
				final StringBuilder sample = new StringBuilder();
				for (int i = 0; i < pNode.size() && i < 3; i++)
				{
					sample.append(i > 0 ? ", " : "").append(pNode.get(i).asText());
				}
				pOut.add(new String[] { pPrefix, sample.toString() + (pNode.size() > 3 ? ", ..." : ""), "array(" + pNode.size() + ")" });
				pOut.add(new String[] { pPrefix + "[0]", first.asText(), Json.typeOf(first) });
				return;
			}
			Json.flatten(first, pPrefix + "[0]", pOut, pMax, pDepth + 1);
			return;
		}
		pOut.add(new String[] { pPrefix, pNode.isNull() ? "" : pNode.asText(), Json.typeOf(pNode) });
	}

	private static String typeOf(final JsonNode pNode)
	{
		if (pNode.isNull())
		{
			return "null";
		}
		if (pNode.isNumber())
		{
			return "number";
		}
		if (pNode.isBoolean())
		{
			return "boolean";
		}
		return "string";
	}

	private static List<String> split(final String pPath)
	{
		final List<String> steps = new ArrayList<String>();
		for (final String raw : pPath.trim().split("\\."))
		{
			if (!raw.isEmpty())
			{
				steps.add(raw);
			}
		}
		return steps;
	}
}
