package com.on.ebx.connect.core;

import java.util.Arrays;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deterministic, account-free contract checks for template ownership and interchange. */
public final class ConnectorTemplateLifecycleTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ConnectorTemplateLibrary library = new ConnectorTemplateLibrary();
		final ConnectorTemplate v1 = packaged("salesforce-master-data", 1, "salesforce", "v1");
		check(library.installPackaged(v1) == ConnectorTemplateLibrary.InstallResult.INSTALLED, "initial install");
		final ObjectNode customized = v1.getConfiguration();
		customized.put("label", "Customer Salesforce");
		library.customize(v1.getId(), "acme-salesforce", customized);

		final ConnectorTemplate v2 = packaged("salesforce-master-data", 2, "salesforce", "v2");
		check(library.installPackaged(v2) == ConnectorTemplateLibrary.InstallResult.UPGRADED, "packaged upgrade");
		check("v2".equals(library.get(v1.getId()).getConfiguration().get("schemaRevision").asText()), "packaged content upgraded");
		check("Customer Salesforce".equals(library.get("acme-salesforce").getConfiguration().get("label").asText()), "customer content preserved");
		check(library.get("acme-salesforce").getBasedOnVersion() == 1, "origin version preserved");

		final String exported = ConnectorTemplateTransfer.exportTemplates(library.all());
		final ConnectorTemplateLibrary restored = new ConnectorTemplateLibrary();
		restored.importTemplates(ConnectorTemplateTransfer.importTemplates(exported));
		check(restored.all().size() == 2, "round trip count");
		check(exported.equals(ConnectorTemplateTransfer.exportTemplates(restored.all())), "deterministic export");
		check(ProductConnectorTemplates.packaged().size() == 7, "seven packaged product templates");
		check(ProductConnectorTemplates.ids().equals(Arrays.asList("salesforce", "sap", "oracle-fusion", "dynamics365", "workday", "servicenow", "hubspot")), "stable profile order");
		checkProductProfiles();
		System.out.println("PASS: versioned connector template ownership, upgrade and interchange");
	}

	private static void checkProductProfiles()
	{
		final String[] connectorIds = { "Salesforce", "SAP", "OracleFusion", "Dynamics365", "Workday", "ServiceNow", "HubSpot" };
		final String[] types = { "salesforce", "sap-odata", "oracle-fusion", "dynamics365", "workday", "servicenow", "hubspot" };
		final ConnectorTemplateLibrary library = new ConnectorTemplateLibrary();
		int index = 0;
		for (final ConnectorTemplate template : ProductConnectorTemplates.packaged())
		{
			final ObjectNode configuration = template.getConfiguration();
			check(template.getOwnership() == ConnectorTemplate.Ownership.PACKAGED, "product profile is packaged");
			check(connectorIds[index].equals(configuration.path("connectorId").asText()), "existing connector identity " + connectorIds[index]);
			check(types[index].equals(configuration.path("type").asText()), "connector type " + types[index]);
			check("BUSINESS_APPS".equals(configuration.path("category").asText()), "business application category");
			check(!configuration.path("active").asBoolean(true), "packaged profile is inactive");
			check(!configuration.has("apiKey") && !configuration.has("password") && !configuration.has("refreshToken"), "profile contains no credential field");
			check(library.installPackaged(template) == ConnectorTemplateLibrary.InstallResult.INSTALLED, "first profile install");
			check(library.installPackaged(template) == ConnectorTemplateLibrary.InstallResult.UNCHANGED, "idempotent profile install");
			index++;
		}

		final ConnectorTemplate salesforce = library.get("product.salesforce");
		final ObjectNode customerConfiguration = salesforce.getConfiguration();
		customerConfiguration.put("label", "Acme Salesforce");
		customerConfiguration.put("baseUrl", "https://acme.my.salesforce.com");
		library.customize(salesforce.getId(), "customer.acme-salesforce", customerConfiguration);
		for (final ConnectorTemplate template : ProductConnectorTemplates.packaged()) { library.installPackaged(template); }
		check(library.all().size() == 8, "reinstall creates neither duplicate packaged profiles nor connector copies");
		check("Acme Salesforce".equals(library.get("customer.acme-salesforce").getConfiguration().path("label").asText()), "customer label retained");
		check("https://acme.my.salesforce.com".equals(library.get("customer.acme-salesforce").getConfiguration().path("baseUrl").asText()), "customer endpoint retained");
	}

	private static ConnectorTemplate packaged(final String pId, final int pVersion, final String pType, final String pRevision)
	{
		final ObjectNode config = Json.object();
		config.put("type", pType);
		config.put("schemaRevision", pRevision);
		return new ConnectorTemplate(pId, pVersion, ConnectorTemplate.Ownership.PACKAGED, null, 0, config);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}
}
