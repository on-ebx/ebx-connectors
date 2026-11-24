package com.on.ebx.connect.connectors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Account-free SuccessFactors defaults, validation, descriptor and dispatch contract. */
public final class SuccessFactorsConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final SuccessFactorsConnector connector = new SuccessFactorsConnector(); final ConnectorConfig config = config(); connector.configure(config);
		check("2".equals(config.getProperty("odata.version", null)) && "PerPerson".equals(config.getProperty("entitySet", null)), "product defaults");
		check(connector.validateConfiguration(config).isValid(), "valid SuccessFactors config rejected");
		config.setBaseUrl("http://api.successfactors.example/odata/v4"); final ConfigurationValidationResult invalid = connector.validateConfiguration(config);
		check(has(invalid, "baseUrl") && has(invalid, "odata.version") == false, "endpoint validation");
		final Map<String, String> inputs = new LinkedHashMap<String, String>(); inputs.put("personIdExternal", "O'Reilly");
		final SuccessFactorsConnector.Dispatch get = SuccessFactorsConnector.dispatch("getPerson", inputs);
		check("get".equals(get.operation) && "PerPerson".equals(get.inputs.get("entitySet")) && "personIdExternal='O''Reilly'".equals(get.inputs.get("key")), "person dispatch/escaping");
		check("EmpEmployment".equals(SuccessFactorsConnector.dispatch("queryEmployment", Collections.<String, String>emptyMap()).inputs.get("entitySet")), "employment dispatch");
		check(connector.getDescriptor().getDirections().contains(ConnectorDirection.INBOUND) && connector.getDescriptor().getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(connector.getDescriptor().getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY) && connector.getDescriptor().getCapabilities().contains(ConnectorCapability.BULK_WRITE), "capabilities");
		System.out.println("PASS: dedicated SAP SuccessFactors profile defaults, validation, descriptor, and dispatch");
	}
	private static ConnectorConfig config() { final ConnectorConfig c = new ConnectorConfig(); c.setId("SuccessFactors"); c.setType("sap-successfactors"); c.setBaseUrl("https://api12.sapsf.eu/odata/v2"); c.setAuthType(ConnectorConfig.AuthType.BASIC); c.setUsername("api-user"); c.setPassword("secret"); c.setProperty("companyId", "ACME"); return c; }
	private static boolean has(final ConfigurationValidationResult result, final String field) { for (final ConfigurationValidationIssue issue : result.getIssues()) if (field.equals(issue.getPropertyName())) return true; return false; }
	private static void check(final boolean value, final String message) { if (!value) throw new AssertionError(message); }
}
