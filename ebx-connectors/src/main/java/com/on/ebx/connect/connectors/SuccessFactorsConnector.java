package com.on.ebx.connect.connectors;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Dedicated SAP SuccessFactors HXM OData v2 profile on the bounded SAP/OData runtime. */
public final class SuccessFactorsConnector implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("getPerson", "Read one person by personIdExternal", "personIdExternal"),
		Operation.search("queryPeople", "Query people from PerPerson with bounded OData filtering and paging"),
		Operation.search("queryEmployment", "Query employment records from EmpEmployment with bounded OData filtering and paging"),
		Operation.search("queryUsers", "Query users from User with bounded OData filtering and paging"),
		Operation.write("createPerson", "Create a PerPerson record"),
		Operation.write("updatePerson", "Update a PerPerson record by personIdExternal", "personIdExternal"),
		Operation.write("upsertPerson", "Update a PerPerson record when present, otherwise create it", "personIdExternal"),
		Operation.read("describePeople", "Discover PerPerson fields from SuccessFactors metadata"));
	private final SapODataConnector delegate = new SapODataConnector();

	@Override public String getType() { return "sap-successfactors"; }
	@Override public String getLabel() { return "SAP SuccessFactors"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null) {
			defaultProperty(pConfig, "odata.version", "2");
			defaultProperty(pConfig, "entitySet", "PerPerson");
			defaultProperty(pConfig, "max.rows", "200");
			defaultProperty(pConfig, "max.pages", "20");
			defaultProperty(pConfig, "default.expand", "personalInfoNav,employmentNav");
		}
		delegate.configure(pConfig);
	}
	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = Connector.super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String url = pConfig.getBaseUrl();
		try {
			final URI parsed = new URI(url == null ? "" : url);
			if (!"https".equalsIgnoreCase(parsed.getScheme())) issues.add(new ConfigurationValidationIssue("baseUrl", "SuccessFactors API URL must use HTTPS"));
			if (parsed.getPath() == null || !parsed.getPath().replaceAll("/+$", "").endsWith("/odata/v2")) issues.add(new ConfigurationValidationIssue("baseUrl", "SuccessFactors API URL must end with /odata/v2"));
		} catch (final Exception ex) { issues.add(new ConfigurationValidationIssue("baseUrl", "Invalid SuccessFactors API URL")); }
		if (pConfig.getAuthType() == ConnectorConfig.AuthType.BASIC && blank(pConfig.getProperty("companyId", null)))
			issues.add(new ConfigurationValidationIssue("companyId", "Company identifier is required for Basic authentication"));
		if (!"2".equals(pConfig.getProperty("odata.version", "2"))) issues.add(new ConfigurationValidationIssue("odata.version", "SuccessFactors uses OData v2"));
		return new ConfigurationValidationResult(issues);
	}
	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("SAP SuccessFactors HXM employee, person, employment and user data through the bounded OData v2 foundation.")
			.authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.BEARER_TOKEN)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE)
			.apiVersion("SuccessFactors OData v2")
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.LINK, 200, 10000, "__next"))
			.configurationProperty(property("companyId", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("odata.version", ConfigurationPropertyType.ENUMERATION, false, "2", "2"))
			.configurationProperty(property("entitySet", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("default.expand", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("max.pages", ConfigurationPropertyType.INTEGER, false));
		for (final Operation operation : OPERATIONS) { final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction), java.util.Collections.<ConnectorCapability>emptySet())); }
		return b.build();
	}
	@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final Dispatch dispatch = dispatch(pOperation, pInputs);
		return delegate.execute(pContext, dispatch.operation, dispatch.inputs, pPayload);
	}
	@Override public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		final Dispatch dispatch = dispatch(pOperation, pInputs);
		return delegate.describeFields(pContext, dispatch.operation, dispatch.inputs);
	}
	@Override public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException { return delegate.testConnection(pContext); }

	static Dispatch dispatch(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		if (pInputs != null) inputs.putAll(pInputs);
		if ("getPerson".equals(pOperation))
		{
			inputs.put("entitySet", "PerPerson");
			inputs.put("key", personKey(inputs.remove("personIdExternal")));
			return new Dispatch("get", inputs);
		}
		if ("queryPeople".equals(pOperation))
		{
			inputs.put("entitySet", "PerPerson");
			return new Dispatch("query", inputs);
		}
		if ("queryEmployment".equals(pOperation))
		{
			inputs.put("entitySet", "EmpEmployment");
			return new Dispatch("query", inputs);
		}
		if ("queryUsers".equals(pOperation))
		{
			inputs.put("entitySet", "User");
			return new Dispatch("query", inputs);
		}
		if ("createPerson".equals(pOperation))
		{
			inputs.put("entitySet", "PerPerson");
			return new Dispatch("create", inputs);
		}
		if ("updatePerson".equals(pOperation) || "upsertPerson".equals(pOperation))
		{
			inputs.put("entitySet", "PerPerson");
			inputs.put("key", personKey(inputs.remove("personIdExternal")));
			return new Dispatch("updatePerson".equals(pOperation) ? "update" : "upsert", inputs);
		}
		if ("describePeople".equals(pOperation))
		{
			inputs.put("entitySet", "PerPerson");
			return new Dispatch("describe", inputs);
		}
		throw new ConnectorException("SuccessFactors: unknown operation '" + pOperation + "'");
	}
	private static String personKey(final String pId) throws ConnectorException
	{
		if (blank(pId)) throw new ConnectorException("SuccessFactors: personIdExternal is required");
		return "personIdExternal='" + pId.replace("'", "''") + "'";
	}
	static final class Dispatch
	{
		final String operation;
		final Map<String, String> inputs;
		Dispatch(final String o, final Map<String, String> i)
		{
			operation = o;
			inputs = i;
		}
	}
	private static void defaultProperty(final ConnectorConfig c, final String k, final String v) { if (c.getProperty(k, null) == null) c.setProperty(k, v); }
	private static boolean blank(final String v) { return v == null || v.trim().isEmpty(); }
	private static ConfigurationPropertyDefinition property(final String n, final ConfigurationPropertyType t, final boolean r, final String... values) {
		final ConfigurationPropertyDefinition.Builder b = ConfigurationPropertyDefinition.builder(n, t).required(r);
		if (values.length > 0)
		{
			b.defaultValue(values[0]);
			b.enumerationValues(values);
		}
		return b.build();
	}
}
