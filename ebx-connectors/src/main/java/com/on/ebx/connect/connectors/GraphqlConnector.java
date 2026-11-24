package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.OpenApiImporter;
import com.on.ebx.connect.core.Templates;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Generic GraphQL client driven by properties (base URL = the endpoint):
 * <pre>
 * op.country.query       = query($code: ID!) { country(code: $code) { name capital currency } }
 * op.country.variables   = optional JSON template for the variables, {input} placeholders
 *                          (default: every input is sent as a string variable of the same name)
 * op.country.result      = path under "data" returned as the document (default: data itself)
 * op.country.required    = comma list of mandatory inputs ("-" = none; default: the $variables of the query)
 * op.country.fields      = catalogue for the designer: path=type;path=type
 * op.country.write       = true for a mutation (shown as a write operation)
 * </pre>
 * GraphQL errors become an error carrying the first message.
 */
public final class GraphqlConnector extends AbstractRestConnector
{
	@Override
	public String getType()
	{
		return "graphql";
	}

	@Override
	public String getLabel()
	{
		return "Generic GraphQL API";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "GENERIC_INTEGRATION")
			.description("Configuration-driven GraphQL connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS)
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.query", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).required(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.variables", com.on.ebx.connect.spi.ConfigurationPropertyType.JSON).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.write", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN).defaultValue(Boolean.FALSE).build());
		try
		{
			for (final Operation o : getOperations())
			{
				final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
				b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
			}
		}
		catch (final IllegalStateException ignored)
		{
			/* Dynamic operations require an instance configuration. */
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		final ConnectorConfig config = this.getConfig();
		final List<Operation> operations = new ArrayList<Operation>();
		if (config == null)
		{
			return operations;
		}
		for (final Map.Entry<String, String> property : config.getProperties().entrySet())
		{
			if (property.getKey().startsWith("op.") && property.getKey().endsWith(".query"))
			{
				final String name = property.getKey().substring(3, property.getKey().length() - 6);
				final String declared = config.getProperty("op." + name + ".required", null);
				final String[] required = declared != null ? Templates.required(declared) : GraphqlConnector.variablesOf(property.getValue());
				final boolean write = "true".equalsIgnoreCase(config.getProperty("op." + name + ".write", "false"));
				operations.add(new Operation(name, write ? Operation.Kind.WRITE : Operation.Kind.READ,
					config.getProperty("op." + name + ".description", "GraphQL operation " + name), required, null));
			}
		}
		return operations;
	}

	/** {@code $name} variables declared by the query. */
	static String[] variablesOf(final String pQuery)
	{
		final List<String> names = new ArrayList<String>();
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\s*:").matcher(pQuery == null ? "" : pQuery);
		while (m.find())
		{
			if (!names.contains(m.group(1)))
			{
				names.add(m.group(1));
			}
		}
		return names.toArray(new String[names.size()]);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		final String query = config.getProperty("op." + pOperation + ".query", null);
		if (query == null)
		{
			throw new ConnectorException(config.getId() + ": no property op." + pOperation + ".query");
		}
		final ObjectNode body = Json.object();
		body.put("query", query);
		final String variablesTemplate = config.getProperty("op." + pOperation + ".variables", null);
		if (variablesTemplate != null)
		{
			try
			{
				body.set("variables", Json.parse(Templates.fill(variablesTemplate, pInputs, Templates.JSON)));
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException(config.getId() + ": op." + pOperation + ".variables is not JSON once filled: " + ex.getMessage());
			}
		}
		else
		{
			final ObjectNode variables = body.putObject("variables");
			for (final String name : GraphqlConnector.variablesOf(query))
			{
				final String value = pInputs == null ? null : pInputs.get(name);
				if (value != null && !value.trim().isEmpty())
				{
					variables.put(name, value);
				}
			}
			if (pPayload != null && pPayload.isObject())
			{
				variables.setAll((ObjectNode) pPayload); // outbound document: variables built by the rules
			}
		}
		final JsonNode reply = this.bodyOrNull(this.send("POST", config.getProperty("op." + pOperation + ".path", ""), null, body), pOperation);
		if (reply == null)
		{
			return null;
		}
		final JsonNode errors = reply.get("errors");
		if (errors != null && errors.isArray() && errors.size() > 0)
		{
			throw new ConnectorException(config.getId() + ": " + pOperation + ": " + errors.get(0).path("message").asText(errors.get(0).toString()));
		}
		final JsonNode data = reply.get("data");
		final String result = config.getProperty("op." + pOperation + ".result", null);
		final JsonNode out = result == null || result.trim().isEmpty() ? data : Json.at(data, result.trim());
		return out == null || out.isNull() ? null : out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		for (final String[] field : OpenApiImporter.fields(this.getConfig(), "op." + pOperation + ".fields"))
		{
			fields.add(FieldInfo.of(field[0], field[0], field[1]));
		}
		return fields;
	}
}
