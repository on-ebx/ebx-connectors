package com.on.ebx.connect.connectors;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Deterministic Java 8 contract tests for the packaged SAP Ariba profile. */
public final class SapAribaConnectorTest
{
	private SapAribaConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		testDescriptorAndDefaults();
		testSecureValidation();
		testBoundsAndResponseNormalization();
		System.out.println("SapAribaConnectorTest: OK");
	}

	private static void testDescriptorAndDefaults()
	{
		final SapAribaConnector connector = new SapAribaConnector();
		final ConnectorConfig config = valid();
		connector.configure(config);
		check("apiKey".equals(config.getApiKeyHeader()), "Ariba API-key header default");
		check("100".equals(config.getProperty("max.rows", null)), "bounded page default");
		check(config.getProperty("supplier.path", "").startsWith("/supplier-data-api/"), "supplier API default");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("BUSINESS_APPS".equals(descriptor.getCategory()), "business application category");
		check(descriptor.getAuthenticationMethods().contains(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS), "OAuth descriptor");
		final Set<String> operations = new HashSet<String>();
		for (final Operation operation : connector.getOperations()) operations.add(operation.getName());
		check(operations.contains("searchSuppliers") && operations.contains("upsertSupplier")
			&& operations.contains("acknowledgePurchaseOrder"), "product operations");
	}

	private static void testSecureValidation()
	{
		final SapAribaConnector connector = new SapAribaConnector();
		final ConnectorConfig valid = valid(); connector.configure(valid);
		check(connector.validateConfiguration(valid).isValid(), "valid Ariba configuration");

		final ConnectorConfig invalid = valid();
		invalid.setBaseUrl("http://openapi.ariba.example");
		invalid.setAuthType(ConnectorConfig.AuthType.NONE);
		invalid.setProperty("realm", "");
		invalid.setProperty("supplier.path", "../suppliers?secret=yes");
		invalid.setProperty("max.rows", "501");
		connector.configure(invalid);
		final Set<String> names = new HashSet<String>();
		for (final ConfigurationValidationIssue issue : connector.validateConfiguration(invalid).getIssues()) names.add(issue.getPropertyName());
		check(names.contains("baseUrl") && names.contains("authentication") && names.contains("realm")
			&& names.contains("supplier.path") && names.contains("max.rows"), "unsafe configuration rejected");
	}

	private static void testBoundsAndResponseNormalization() throws Exception
	{
		check(SapAribaConnector.bounded("999", "100") == 500, "maximum enforced");
		check(SapAribaConnector.bounded("0", "100") == 1, "minimum enforced");
		check(SapAribaConnector.bounded("bad", "100") == 1, "malformed limit is safe");
		final JsonNode normalized = SapAribaConnector.normalize(Json.parse("{\"items\":[{\"id\":\"S1\"}],\"totalCount\":7}"));
		check(normalized.get("count").asInt() == 1, "bounded response count");
		check(normalized.get("totalCount").asInt() == 7, "remote total retained");
	}

	private static ConnectorConfig valid()
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("ariba-test"); config.setType("sap-ariba"); config.setLabel("SAP Ariba");
		config.setBaseUrl("https://openapi.ariba.example");
		config.setAuthType(ConnectorConfig.AuthType.API_KEY_HEADER); config.setApiKey("test-secret");
		config.setProperty("realm", "AN02000000000");
		return config;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{ if (!pCondition) throw new AssertionError(pMessage); }
}
