package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * JD Edwards EnterpriseOne through the AIS server (Application Interface Services, REST).
 * <p>
 * Connector row: {@code baseUrl} = AIS root ({@code https://host:9080/jderest}); authentication
 * BASIC = the EnterpriseOne user and password, exchanged for a session token on
 * {@code /v2/tokenrequest} and renewed when AIS rejects it. Properties: {@code ais.version}
 * (default {@code v2}), {@code environment}, {@code role}, {@code device}, {@code max.rows},
 * {@code strip.prefix} (default true: {@code F0101_ALPH} becomes {@code ALPH}).
 * <ul>
 * <li>{@code query} (SEARCH): data service BROWSE on a table or view. Inputs {@code table}
 * ({@code F0101}, {@code V0101E}), {@code targetType} (table | view), {@code columns}
 * ({@code AN8|ALPH|AT1}, empty = all), {@code filter} ({@code AT1 EQUAL C;ALPH CONTAINS BANK}),
 * {@code max}. Returns {@code {results:[{AN8, ALPH, ...}], count}}.</li>
 * <li>{@code get} (READ): first row of {@code table} where {@code keyColumn} = {@code value}.</li>
 * <li>{@code orchestration} (WRITE): POST {@code /orchestrator/{name}} with the outbound document
 * as input, the JSON reply is the response document; {@code orchestrationQuery} (SEARCH): same
 * with the mapping inputs (other than name / max) as the orchestration input.</li>
 * <li>{@code form} (WRITE): raw form service request ({@code /formservice}) taken from the
 * outbound document.</li>
 * </ul>
 */
public final class JdeAisConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("query", "Data service BROWSE on a table or view (inputs table, columns, filter, max)", "table"),
		Operation.read("get", "First row of a table where keyColumn = value", "table", "keyColumn", "value"),
		Operation.write("orchestration", "Run an orchestration with the outbound document as input", "name"),
		Operation.search("orchestrationQuery", "Run an orchestration with the mapping inputs as input", "name"),
		Operation.write("form", "Raw form service request built by the outbound mapping", new String[0]));

	private static final String[] OPERATORS = { "EQUAL", "NOT_EQUAL", "GREATER", "GREATER_EQUAL", "LESS", "LESS_EQUAL",
		"CONTAINS", "STARTS_WITH", "ENDS_WITH", "BLANK", "NOT_BLANK", "BETWEEN", "LIST" };

	private String token;
	private long tokenTime;

	@Override
	public String getType()
	{
		return "jde-ais";
	}

	@Override
	public String getLabel()
	{
		return "JD Edwards EnterpriseOne (AIS)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("JD Edwards EnterpriseOne AIS REST connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.configurationProperty(prop("ais.version", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, "v2"))
			.configurationProperty(prop("environment", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("role", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("device", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, Integer.valueOf(500)))
			.configurationProperty(prop("strip.prefix", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, Boolean.TRUE));
		for (final Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition prop(final String n, final com.on.ebx.connect.spi.ConfigurationPropertyType t, final Object v) { return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n, t).defaultValue(v).build(); }

	@Override
	public List<Operation> getOperations()
	{
		return JdeAisConnector.OPERATIONS;
	}

	private String version()
	{
		return this.getConfig().getProperty("ais.version", "v2");
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		headers.put("Content-Type", "application/json");
		if (this.token != null)
		{
			headers.put("Authorization", "Bearer " + this.token);
		}
		return headers;
	}

	// ------------------------------------------------------------------ session

	private synchronized void login() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (cfg.getUsername() == null || cfg.isPlaceholder(cfg.getPassword()))
		{
			throw new ConnectorException("JD Edwards: set the EnterpriseOne user name and password (authentication BASIC)");
		}
		if (this.token != null && System.currentTimeMillis() - this.tokenTime < 20L * 60L * 1000L)
		{
			return;
		}
		final ObjectNode body = Json.object();
		body.put("username", cfg.getUsername());
		body.put("password", cfg.getPassword());
		body.put("deviceName", cfg.getProperty("device", "EBX"));
		if (cfg.getProperty("environment", null) != null)
		{
			body.put("environment", cfg.getProperty("environment", null));
		}
		if (cfg.getProperty("role", null) != null)
		{
			body.put("role", cfg.getProperty("role", null));
		}
		this.token = null;
		final HttpJsonClient.Response response = this.send("POST", "/" + this.version() + "/tokenrequest", null, body);
		final JsonNode token = Json.at(response.getBody(), "userInfo.token");
		if (!response.isSuccess() || token == null)
		{
			throw new ConnectorException("JD Edwards: token request failed (HTTP " + response.getStatus() + ") "
					+ String.valueOf(Json.at(response.getBody(), "message")), response.getStatus(), null);
		}
		this.token = token.asText();
		this.tokenTime = System.currentTimeMillis();
	}

	private HttpJsonClient.Response call(final String pPath, final ObjectNode pBody) throws ConnectorException
	{
		this.login();
		pBody.put("token", this.token);
		HttpJsonClient.Response response = this.send("POST", pPath, null, pBody);
		if (response.getStatus() == 401 || response.getStatus() == 403 || JdeAisConnector.notAuthorized(response.getBody()))
		{
			this.token = null;
			this.login();
			pBody.put("token", this.token);
			response = this.send("POST", pPath, null, pBody);
		}
		return response;
	}

	private static boolean notAuthorized(final JsonNode pBody)
	{
		final JsonNode message = Json.at(pBody, "message");
		return message != null && message.asText().toLowerCase().contains("not authorized");
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final int max = JdeAisConnector.toInt(pInputs == null ? null : pInputs.get("max"),
			JdeAisConnector.toInt(this.getConfig().getProperty("max.rows", "500"), 500));
		if ("query".equals(pOperation))
		{
			final String table = JdeAisConnector.name(AbstractRestConnector.require(pInputs, "table"));
			return this.browse(table, pInputs.get("targetType"), pInputs.get("columns"), pInputs.get("filter"), max);
		}
		if ("get".equals(pOperation))
		{
			final String table = JdeAisConnector.name(AbstractRestConnector.require(pInputs, "table"));
			final String filter = JdeAisConnector.name(AbstractRestConnector.require(pInputs, "keyColumn")) + " EQUAL "
					+ AbstractRestConnector.require(pInputs, "value");
			final JsonNode result = this.browse(table, pInputs.get("targetType"), pInputs.get("columns"), filter, 1);
			final JsonNode rows = Json.at(result, "results");
			return rows == null || rows.size() == 0 ? null : rows.get(0);
		}
		if ("orchestration".equals(pOperation) || "orchestrationQuery".equals(pOperation))
		{
			final String name = JdeAisConnector.name(AbstractRestConnector.require(pInputs, "name"));
			final ObjectNode body = Json.object();
			if ("orchestration".equals(pOperation) && pPayload != null && pPayload.isObject())
			{
				body.setAll((ObjectNode) pPayload);
			}
			else
			{
				for (final Map.Entry<String, String> input : pInputs.entrySet())
				{
					if (!"name".equals(input.getKey()) && !"max".equals(input.getKey()) && input.getValue() != null)
					{
						body.put(input.getKey(), input.getValue());
					}
				}
			}
			final HttpJsonClient.Response response = this.call("/" + this.version() + "/orchestrator/" + AbstractRestConnector.segment(name), body);
			return this.bodyOrNull(response, "orchestration " + name);
		}
		if ("form".equals(pOperation))
		{
			if (pPayload == null || !pPayload.isObject())
			{
				throw new ConnectorException("JD Edwards form: the outbound document must be the form service request");
			}
			final HttpJsonClient.Response response = this.call("/" + this.version() + "/formservice", ((ObjectNode) pPayload).deepCopy());
			return this.bodyOrNull(response, "form service");
		}
		throw new ConnectorException("JD Edwards: unknown operation '" + pOperation + "'");
	}

	private JsonNode browse(final String pTable, final String pTargetType, final String pColumns, final String pFilter, final int pMax)
		throws ConnectorException
	{
		final ObjectNode body = Json.object();
		body.put("targetName", pTable);
		body.put("targetType", "view".equalsIgnoreCase(pTargetType) ? "view" : "table");
		body.put("dataServiceType", "BROWSE");
		body.put("maxPageSize", String.valueOf(Math.max(1, pMax)));
		body.put("findOnEntry", "TRUE");
		body.put("returnControlIDs", JdeAisConnector.controlIds(pTable, pColumns));
		final ObjectNode query = JdeAisConnector.query(pTable, pFilter);
		if (query != null)
		{
			body.set("query", query);
		}
		final HttpJsonClient.Response response = this.call("/" + this.version() + "/dataservice", body);
		final JsonNode reply = this.bodyOrNull(response, "data service " + pTable);
		return this.results(reply, pTable);
	}

	/** {@code AN8|ALPH} to {@code F0101.AN8|F0101.ALPH}; empty means every column. */
	static String controlIds(final String pTable, final String pColumns)
	{
		if (pColumns == null || pColumns.trim().isEmpty())
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (final String column : pColumns.split("[|,]"))
		{
			final String c = column.trim();
			if (c.isEmpty())
			{
				continue;
			}
			sb.append(sb.length() > 0 ? "|" : "").append(c.contains(".") ? c : pTable + "." + c);
		}
		return sb.toString();
	}

	/** {@code AT1 EQUAL C;ALPH CONTAINS BANK} to an AIS query with MATCH_ALL conditions. */
	static ObjectNode query(final String pTable, final String pFilter) throws ConnectorException
	{
		if (pFilter == null || pFilter.trim().isEmpty())
		{
			return null;
		}
		final ObjectNode query = Json.object();
		query.put("autoFind", true);
		query.put("matchType", "MATCH_ALL");
		final ArrayNode conditions = query.putArray("condition");
		for (final String clause : pFilter.split(";"))
		{
			final String c = clause.trim();
			if (c.isEmpty())
			{
				continue;
			}
			final String[] parts = c.split("\\s+", 3);
			if (parts.length < 2)
			{
				throw new ConnectorException("JD Edwards filter: expected 'COLUMN OPERATOR value', got '" + c + "'");
			}
			final String operator = parts[1].toUpperCase();
			if (!Arrays.asList(JdeAisConnector.OPERATORS).contains(operator))
			{
				throw new ConnectorException("JD Edwards filter: unknown operator '" + parts[1] + "' (" + Arrays.toString(JdeAisConnector.OPERATORS) + ")");
			}
			final ObjectNode condition = conditions.addObject();
			condition.put("controlId", parts[0].contains(".") ? parts[0] : pTable + "." + parts[0]);
			condition.put("operator", JdeAisConnector.aisOperator(operator));
			final ArrayNode values = condition.putArray("value");
			if (parts.length == 3 && !"BLANK".equals(operator) && !"NOT_BLANK".equals(operator))
			{
				for (final String v : "BETWEEN".equals(operator) || "LIST".equals(operator) ? parts[2].split("\\|") : new String[] { parts[2] })
				{
					final ObjectNode value = values.addObject();
					value.put("content", v.trim());
					value.put("specialValueId", "LITERAL");
				}
			}
		}
		return query;
	}

	private static String aisOperator(final String pOperator)
	{
		if ("CONTAINS".equals(pOperator))
		{
			return "STR_CONTAIN";
		}
		if ("STARTS_WITH".equals(pOperator))
		{
			return "STR_START_WITH";
		}
		if ("ENDS_WITH".equals(pOperator))
		{
			return "STR_END_WITH";
		}
		if ("BLANK".equals(pOperator))
		{
			return "STR_BLANK";
		}
		if ("NOT_BLANK".equals(pOperator))
		{
			return "STR_NOT_BLANK";
		}
		return pOperator;
	}

	/** {@code fs_DATABROWSE_F0101.data.gridData.rowset[]} to {@code {results:[...], count}} with clean column names. */
	JsonNode results(final JsonNode pReply, final String pTable)
	{
		if (pReply == null)
		{
			return null;
		}
		JsonNode rowset = null;
		final Iterator<Map.Entry<String, JsonNode>> fields = pReply.fields();
		while (fields.hasNext() && rowset == null)
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			if (field.getKey().startsWith("fs_"))
			{
				rowset = Json.at(field.getValue(), "data.gridData.rowset");
			}
		}
		if (rowset == null)
		{
			rowset = Json.at(pReply, "rowset");
		}
		if (rowset == null || !rowset.isArray() || rowset.size() == 0)
		{
			return null;
		}
		final boolean strip = !"false".equalsIgnoreCase(this.getConfig().getProperty("strip.prefix", "true"));
		final ObjectNode out = Json.object();
		final ArrayNode results = out.putArray("results");
		for (final JsonNode row : rowset)
		{
			final ObjectNode clean = results.addObject();
			final Iterator<Map.Entry<String, JsonNode>> columns = row.fields();
			while (columns.hasNext())
			{
				final Map.Entry<String, JsonNode> column = columns.next();
				String name = column.getKey();
				if ("rowIndex".equals(name))
				{
					continue;
				}
				if (strip && name.startsWith(pTable + "_"))
				{
					name = name.substring(pTable.length() + 1);
				}
				JsonNode value = column.getValue();
				if (value != null && value.isTextual())
				{
					value = Json.fromJava(value.asText().trim());
				}
				clean.set(name, value);
			}
		}
		out.put("count", results.size());
		final JsonNode more = JdeAisConnector.firstGridSummary(pReply, "moreRecords");
		if (more != null)
		{
			out.put("moreRecords", more.asBoolean());
		}
		return out;
	}

	private static JsonNode firstGridSummary(final JsonNode pReply, final String pKey)
	{
		final Iterator<Map.Entry<String, JsonNode>> fields = pReply.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			if (field.getKey().startsWith("fs_"))
			{
				return Json.at(field.getValue(), "data.gridData.summary." + pKey);
			}
		}
		return null;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String table = pInputs == null ? null : pInputs.get("table");
		if (table == null || table.trim().isEmpty())
		{
			return fields;
		}
		final JsonNode probe = this.browse(JdeAisConnector.name(table), pInputs.get("targetType"), pInputs.get("columns"), null, 1);
		final JsonNode first = Json.at(probe, "results[0]");
		if (first == null)
		{
			return fields;
		}
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		final Iterator<Map.Entry<String, JsonNode>> columns = first.fields();
		while (columns.hasNext())
		{
			final Map.Entry<String, JsonNode> column = columns.next();
			fields.add(new FieldInfo(prefix + column.getKey(), column.getKey(), column.getValue().isNumber() ? "number" : "string",
				column.getValue().asText()));
		}
		return fields;
	}

	static String name(final String pValue) throws ConnectorException
	{
		final String v = pValue.trim();
		if (!v.matches("[A-Za-z0-9_.]+"))
		{
			throw new ConnectorException("JD Edwards: invalid name '" + pValue + "'");
		}
		return v;
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
}
