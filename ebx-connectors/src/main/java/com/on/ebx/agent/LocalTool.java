package com.on.ebx.agent;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An in-process tool the model may call, as an alternative to a tool served by an {@link McpClient}. This is
 * how a caller (the MDM Factory) lets the model produce an outcome without ever letting it write anything
 * through an MCP server itself: a local tool's handler is Java code the factory owns — typically "record
 * this proposed change" — which writes a row with some PROPOSED status for a person to accept, rather than
 * applying anything. The model only ever gets to call this method; it never touches EBX directly.
 * <p>
 * A handler may throw: {@link AgentLoop} turns any exception into an error tool-result (the exception's
 * message only, never a stack trace) fed back to the model, exactly as it does for an {@link McpException}
 * from an MCP tool — the model may recover, the run does not fail.
 */
public final class LocalTool
{
	/** What a handler answered. */
	public static final class Result
	{
		public final String text;
		public final JsonNode json;
		public final boolean isError;

		public Result(final String pText, final JsonNode pJson, final boolean pIsError)
		{
			this.text = pText == null ? "" : pText;
			this.json = pJson;
			this.isError = pIsError;
		}

		public static Result ok(final String pText)
		{
			return new Result(pText, null, false);
		}

		public static Result error(final String pText)
		{
			return new Result(pText, null, true);
		}
	}

	/** What runs when the model calls this tool. {@code pArguments} has already passed {@link #firstViolation}. */
	public interface Handler
	{
		Result handle(JsonNode pArguments) throws Exception;
	}

	public final String name;
	public final String description;
	public final JsonNode inputSchema;
	public final Handler handler;

	public LocalTool(final String pName, final String pDescription, final JsonNode pInputSchema, final Handler pHandler)
	{
		this.name = pName;
		this.description = pDescription == null ? "" : pDescription;
		this.inputSchema = pInputSchema;
		this.handler = pHandler;
	}

	/**
	 * A minimal, dependency-free structural check of {@code pArguments} against {@code pSchema}, run by
	 * {@link AgentLoop} before a local tool's handler — never before an MCP tool's, whose own server already
	 * validates: only a local tool's Java handler has no such protection of its own. Checks (a) every name
	 * listed in the schema's top-level {@code required} array is present, and (b) for a property under
	 * {@code properties} that declares a top-level {@code type} among {@code string}/{@code integer}/
	 * {@code number}/{@code boolean}/{@code array}/{@code object}, a present value's JSON type matches it.
	 * <p>
	 * This is deliberately NOT a JSON Schema validator: nested/{@code $ref} schemas, enums, formats, numeric
	 * bounds, string length/pattern, array item schemas, {@code additionalProperties}, {@code oneOf}/
	 * {@code anyOf}/{@code allOf}, and everything else in the spec are not checked. It exists to catch a
	 * model dropping a required field or sending the wrong basic shape — cheaply, so the model can correct
	 * itself in the next turn — not to enforce a rich contract; a handler that needs more should still
	 * validate its own arguments and return {@link Result#error}.
	 *
	 * @return the first violation found, as a ready-to-show message naming the property, or null if nothing
	 * is wrong — including when {@code pSchema} does not declare an object shape, or {@code pArguments} is
	 * missing or not a JSON object (nothing to check against then; a handler that truly requires an object
	 * should check that itself too).
	 */
	public static String firstViolation(final JsonNode pSchema, final JsonNode pArguments)
	{
		if (pSchema == null || !pSchema.isObject())
		{
			return null;
		}
		final JsonNode arguments = pArguments == null || !pArguments.isObject() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : pArguments;
		for (final JsonNode requiredName : pSchema.path("required"))
		{
			final String name = requiredName.asText("");
			if (!name.isEmpty() && !arguments.has(name))
			{
				return "missing required property '" + name + "'";
			}
		}
		final JsonNode properties = pSchema.path("properties");
		final Iterator<String> names = properties.fieldNames();
		while (names.hasNext())
		{
			final String name = names.next();
			if (!arguments.has(name))
			{
				continue;
			}
			final String declaredType = properties.path(name).path("type").asText(null);
			if (declaredType == null || LocalTool.matchesType(declaredType, arguments.get(name)))
			{
				continue;
			}
			return "property '" + name + "' must be of type " + declaredType + ", got " + LocalTool.actualType(arguments.get(name));
		}
		return null;
	}

	private static boolean matchesType(final String pDeclaredType, final JsonNode pValue)
	{
		if ("string".equals(pDeclaredType))
		{
			return pValue.isTextual();
		}
		if ("integer".equals(pDeclaredType))
		{
			return pValue.isIntegralNumber();
		}
		if ("number".equals(pDeclaredType))
		{
			return pValue.isNumber();
		}
		if ("boolean".equals(pDeclaredType))
		{
			return pValue.isBoolean();
		}
		if ("array".equals(pDeclaredType))
		{
			return pValue.isArray();
		}
		if ("object".equals(pDeclaredType))
		{
			return pValue.isObject();
		}
		// an unrecognised declared type (not one of the six JSON Schema primitives this checks): don't block on it
		return true;
	}

	private static String actualType(final JsonNode pValue)
	{
		if (pValue.isTextual())
		{
			return "string";
		}
		if (pValue.isIntegralNumber())
		{
			return "integer";
		}
		if (pValue.isNumber())
		{
			return "number";
		}
		if (pValue.isBoolean())
		{
			return "boolean";
		}
		if (pValue.isArray())
		{
			return "array";
		}
		if (pValue.isObject())
		{
			return "object";
		}
		return pValue.isNull() ? "null" : "unknown";
	}
}
