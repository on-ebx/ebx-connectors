package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/**
 * SAP Ariba Open API profile for bounded supplier and procurement document exchange.
 *
 * <p>Ariba API paths differ by enabled product and tenant. The two collection paths are therefore
 * explicit configuration, while realm scoping, safe identifiers, authentication, response-size
 * limits and the public operations remain consistent. The default paths match the commonly used
 * supplier-data and procurement API families and can be replaced without a code change.</p>
 */
public final class SapAribaConnector extends AbstractRestConnector
{
	private static final int MAX_ROWS = 500;
	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		Operation.read("getSupplier", "Read one supplier from SAP Ariba", "supplierId"),
		Operation.search("searchSuppliers", "Search a bounded set of SAP Ariba suppliers"),
		Operation.write("upsertSupplier", "Create or update a supplier in SAP Ariba", "supplierId"),
		Operation.read("getPurchaseOrder", "Read one purchase order from SAP Ariba", "documentId"),
		Operation.search("searchPurchaseOrders", "Search a bounded set of SAP Ariba purchase orders"),
		Operation.write("acknowledgePurchaseOrder", "Submit an acknowledgement for an SAP Ariba purchase order", "documentId")));

	@Override public String getType() { return "sap-ariba"; }
	@Override public String getLabel() { return "SAP Ariba"; }
	@Override public List<Operation> getOperations() { return new ArrayList<Operation>(OPERATIONS); }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			defaultProperty(pConfig, "api.version", "v1");
			defaultProperty(pConfig, "supplier.path", "/supplier-data-api/v1/prod/suppliers");
			defaultProperty(pConfig, "purchase-order.path", "/procurement-api/v1/prod/purchaseOrders");
			defaultProperty(pConfig, "max.rows", "100");
			if (pConfig.getAuthType() == ConnectorConfig.AuthType.API_KEY_HEADER
				&& blank(pConfig.getApiKeyHeader())) pConfig.setApiKeyHeader("apiKey");
		}
		super.configure(pConfig);
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> result = super.headers();
		result.put("Accept", "application/json");
		result.put("Content-Type", "application/json");
		result.put("X-Ariba-Realm", getConfig().getProperty("realm", ""));
		return result;
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		if (pConfig == null) return common;
		final String baseUrl = pConfig.getBaseUrl();
		if (baseUrl != null && !baseUrl.trim().toLowerCase(java.util.Locale.ENGLISH).startsWith("https://"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "SAP Ariba endpoints must use HTTPS"));
		if (blank(pConfig.getProperty("realm", null)))
			issues.add(new ConfigurationValidationIssue("realm", "The SAP Ariba tenant realm is required"));
		if (pConfig.getAuthType() != ConnectorConfig.AuthType.API_KEY_HEADER
			&& pConfig.getAuthType() != ConnectorConfig.AuthType.BEARER
			&& pConfig.getAuthType() != ConnectorConfig.AuthType.OAUTH2)
			issues.add(new ConfigurationValidationIssue("authentication", "Use API key, bearer token, or OAuth 2.0 authentication"));
		pathIssue(issues, pConfig, "supplier.path");
		pathIssue(issues, pConfig, "purchase-order.path");
		integerIssue(issues, pConfig.getProperty("max.rows", "100"), "max.rows", 1, MAX_ROWS);
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("SAP Ariba supplier and procurement APIs with tenant-realm scoping and bounded reads")
			.authentication(AuthenticationMethod.API_KEY_HEADER)
			.authentication(AuthenticationMethod.BEARER_TOKEN)
			.authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS)
			.capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE)
			.apiVersion("SAP Ariba Open APIs v1")
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 100, MAX_ROWS, null))
			.configurationProperty(property("realm", ConfigurationPropertyType.STRING, true, null))
			.configurationProperty(property("api.version", ConfigurationPropertyType.STRING, false, "v1"))
			.configurationProperty(property("supplier.path", ConfigurationPropertyType.STRING, false, "/supplier-data-api/v1/prod/suppliers"))
			.configurationProperty(property("purchase-order.path", ConfigurationPropertyType.STRING, false, "/procurement-api/v1/prod/purchaseOrders"))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER, false, Integer.valueOf(100)));
		for (final Operation operation : OPERATIONS)
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			builder.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return builder.build();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		requireSecret();
		final Map<String, String> query = query(pInputs);
		if ("getSupplier".equals(pOperation))
			return bodyOrNull(get(path("supplier.path") + "/" + segment(require(pInputs, "supplierId")), query), pOperation);
		if ("searchSuppliers".equals(pOperation))
			return normalize(bodyOrNull(get(path("supplier.path"), query), pOperation));
		if ("upsertSupplier".equals(pOperation))
			return bodyOrNull(send("PUT", path("supplier.path") + "/" + segment(require(pInputs, "supplierId")), query,
				pPayload == null ? Json.object() : pPayload), pOperation);
		if ("getPurchaseOrder".equals(pOperation))
			return bodyOrNull(get(path("purchase-order.path") + "/" + segment(require(pInputs, "documentId")), query), pOperation);
		if ("searchPurchaseOrders".equals(pOperation))
			return normalize(bodyOrNull(get(path("purchase-order.path"), query), pOperation));
		if ("acknowledgePurchaseOrder".equals(pOperation))
			return bodyOrNull(send("POST", path("purchase-order.path") + "/" + segment(require(pInputs, "documentId"))
				+ "/acknowledgements", query, pPayload == null ? Json.object() : pPayload), pOperation);
		throw new ConnectorException("SAP Ariba: unknown operation '" + pOperation + "'");
	}

	private Map<String, String> query(final Map<String, String> pInputs)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("realm", getConfig().getProperty("realm", ""));
		query.put("limit", String.valueOf(bounded(pInputs == null ? null : pInputs.get("max"),
			getConfig().getProperty("max.rows", "100"))));
		if (pInputs != null)
		{
			copy(pInputs, query, "offset");
			copy(pInputs, query, "filter");
			copy(pInputs, query, "updatedSince");
		}
		return query;
	}

	private String path(final String pName) { return getConfig().getProperty(pName, ""); }

	static int bounded(final String pRequested, final String pDefault)
	{
		try
		{
			final int value = Integer.parseInt(blank(pRequested) ? pDefault : pRequested);
			return Math.max(1, Math.min(MAX_ROWS, value));
		}
		catch (final RuntimeException ex) { return 1; }
	}

	static JsonNode normalize(final JsonNode pResponse)
	{
		if (pResponse == null) return null;
		if (pResponse.isArray()) return AbstractCrudRestConnector.list(pResponse, null);
		final JsonNode records = first(pResponse, "records", "items", "results", "content");
		if (records == null || !records.isArray()) return pResponse;
		return AbstractCrudRestConnector.list(records, first(pResponse, "totalCount", "count", "total"));
	}

	private static JsonNode first(final JsonNode pNode, final String... pNames)
	{
		for (final String name : pNames) if (pNode.get(name) != null) return pNode.get(name);
		return null;
	}

	private static void copy(final Map<String, String> pFrom, final Map<String, String> pTo, final String pName)
	{
		if (!blank(pFrom.get(pName))) pTo.put(pName, pFrom.get(pName).trim());
	}

	private static void pathIssue(final List<ConfigurationValidationIssue> pIssues,
		final ConnectorConfig pConfig, final String pName)
	{
		final String value = pConfig.getProperty(pName, null);
		if (blank(value) || !value.startsWith("/") || value.contains("..") || value.contains("?") || value.contains("#"))
			pIssues.add(new ConfigurationValidationIssue(pName, "Use an absolute API path without traversal or query text"));
	}

	private static void integerIssue(final List<ConfigurationValidationIssue> pIssues, final String pValue,
		final String pName, final int pMin, final int pMax)
	{
		try
		{
			final int value = Integer.parseInt(pValue);
			if (value < pMin || value > pMax) throw new NumberFormatException();
		}
		catch (final NumberFormatException ex)
		{
			pIssues.add(new ConfigurationValidationIssue(pName, "Must be between " + pMin + " and " + pMax));
		}
	}

	private static boolean blank(final String pValue) { return pValue == null || pValue.trim().isEmpty(); }
	private static void defaultProperty(final ConnectorConfig pConfig, final String pName, final String pValue)
	{ if (pConfig.getProperty(pName, null) == null) pConfig.setProperty(pName, pValue); }
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType,
		final boolean pRequired, final Object pDefault)
	{ return ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).defaultValue(pDefault).build(); }
}
