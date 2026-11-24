package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * German Handelsregister through OffeneRegister ({@code https://db.offeneregister.de}), the open
 * data mirror published by the Open Knowledge Foundation as a Datasette JSON API: the official
 * register has no API. {@code search} by name, {@code get} by register number
 * ({@code HRB 12345} + court). Data may lag the official register.
 */
public final class OffeneRegisterConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("search", "Companies whose name contains q", "q"),
		Operation.read("get", "Company by register number (input number, e.g. HRB 12345, optional court)", "number"));

	@Override
	public String getType()
	{
		return "handelsregister";
	}

	@Override
	public String getLabel()
	{
		return "Handelsregister (OffeneRegister mirror)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("German company-register search through an OffeneRegister-compatible service.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OffeneRegisterConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("_shape", "objects");
		if ("search".equals(pOperation))
		{
			query.put("name__contains", AbstractRestConnector.require(pInputs, "q"));
			query.put("_size", pInputs.get("max") == null ? "30" : pInputs.get("max"));
			return OffeneRegisterConnector.rows(this.bodyOrNull(this.get("/openregister/company.json", query), "search"));
		}
		if ("get".equals(pOperation))
		{
			query.put("native_company_number__contains", AbstractRestConnector.require(pInputs, "number").replaceAll("\\s+", " ").trim());
			final String court = pInputs.get("court");
			if (court != null && !court.trim().isEmpty())
			{
				query.put("registrar__contains", court.trim());
			}
			query.put("_size", "1");
			final JsonNode rows = OffeneRegisterConnector.rows(this.bodyOrNull(this.get("/openregister/company.json", query), "get"));
			return rows == null ? null : Json.at(rows, "results[0]");
		}
		throw new ConnectorException("Handelsregister: unknown operation '" + pOperation + "'");
	}

	private static JsonNode rows(final JsonNode pReply)
	{
		final JsonNode rows = Json.at(pReply, "rows");
		if (rows == null || !rows.isArray() || rows.size() == 0)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		final ArrayNode results = out.putArray("results");
		for (final JsonNode row : rows)
		{
			results.add(row);
		}
		out.put("count", results.size());
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final String p = "search".equals(pOperation) ? "results[0]." : "";
		return Arrays.asList(FieldInfo.of(p + "name", "Name", "string"), FieldInfo.of(p + "company_number", "Company number (register id)", "string"),
			FieldInfo.of(p + "native_company_number", "Register number (court + HRB)", "string"), FieldInfo.of(p + "registrar", "Register court", "string"),
			FieldInfo.of(p + "federal_state", "Federal state", "string"), FieldInfo.of(p + "registered_address", "Registered address", "string"),
			FieldInfo.of(p + "current_status", "Status", "string"), FieldInfo.of(p + "retrieved_at", "Retrieved at", "string"));
	}
}
