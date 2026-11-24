package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Packaged, credential-free starting points for the first product-template wave. */
public final class ProductConnectorTemplates
{
	private static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
		"salesforce", "sap", "oracle-fusion", "dynamics365", "workday", "servicenow", "hubspot"));

	private ProductConnectorTemplates() { }

	public static List<String> ids() { return IDS; }

	public static List<ConnectorTemplate> packaged()
	{
		final List<ConnectorTemplate> templates = new ArrayList<ConnectorTemplate>();
		templates.add(profile("salesforce", "Salesforce", "Salesforce CRM", "salesforce",
			"https://login.salesforce.com", "NONE", properties("clientId", "TO_BE_SET", "oauth.flow", "client_credentials", "apiVersion", "v60.0")));
		templates.add(profile("sap", "SAP", "SAP S/4HANA Business Partner (OData)", "sap-odata",
			"https://sandbox.api.sap.com/s4hanacloud/sap/opu/odata/sap/API_BUSINESS_PARTNER", "API_KEY_HEADER",
			properties("odata.version", "2", "entitySet", "A_BusinessPartner", "max.rows", "200")));
		templates.add(profile("oracle-fusion", "OracleFusion", "Oracle Fusion Cloud", "oracle-fusion",
			"https://HOST.oraclecloud.com", "BASIC", properties("apiVersion", "11.13.18.05", "family", "crmRestApi")));
		templates.add(profile("dynamics365", "Dynamics365", "Microsoft Dynamics 365", "dynamics365",
			"https://ORG.crm4.dynamics.com", "BEARER", properties("tenantId", "TO_BE_SET", "clientId", "TO_BE_SET", "apiVersion", "v9.2")));
		templates.add(profile("workday", "Workday", "Workday", "workday",
			"https://wd3-impl-services1.workday.com", "BASIC", properties("tenant", "TO_BE_SET", "clientId", "TO_BE_SET")));
		templates.add(profile("servicenow", "ServiceNow", "ServiceNow", "servicenow",
			"https://INSTANCE.service-now.com", "BASIC", properties("max.rows", "1000", "display.values", "false")));
		templates.add(profile("hubspot", "HubSpot", "HubSpot CRM", "hubspot",
			"https://api.hubapi.com", "BEARER", properties("max.rows", "1000")));
		return Collections.unmodifiableList(templates);
	}

	private static ConnectorTemplate profile(final String pId, final String pConnectorId, final String pLabel,
		final String pType, final String pBaseUrl, final String pAuthentication, final ObjectNode pProperties)
	{
		final ObjectNode configuration = Json.object();
		configuration.put("connectorId", pConnectorId);
		configuration.put("label", pLabel);
		configuration.put("type", pType);
		configuration.put("category", "BUSINESS_APPS");
		configuration.put("active", false);
		configuration.put("baseUrl", pBaseUrl);
		configuration.put("authentication", pAuthentication);
		configuration.set("properties", pProperties);
		configuration.put("description", "Packaged " + pLabel + " master-data profile; copy and configure before activation.");
		return new ConnectorTemplate("product." + pId, 1, ConnectorTemplate.Ownership.PACKAGED, null, 0, configuration);
	}

	private static ObjectNode properties(final String... pEntries)
	{
		final ObjectNode properties = Json.object();
		for (int i = 0; i < pEntries.length; i += 2) { properties.put(pEntries[i], pEntries[i + 1]); }
		return properties;
	}
}
