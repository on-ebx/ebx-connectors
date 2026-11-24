package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.*;

/** Account-free contract coverage for the Business Central product profile. */
public final class DynamicsBusinessCentralConnectorTest
{
	public static void main(final String[] args)
	{
		final DynamicsBusinessCentralConnector connector=new DynamicsBusinessCentralConnector(); final ConnectorConfig config=configuration(); connector.configure(config);
		check(connector.validateConfiguration(config).isValid(),"valid configuration");
		check("https://api.businesscentral.dynamics.com/v2.0/tenant-id/Production/api/v2.0/companies(company-id)".equals(DynamicsBusinessCentralConnector.apiRoot(config)),"standard API root");
		check("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token".equals(config.getProperty("oauth.tokenUrl",null)),"token default");
		check("https://api.businesscentral.dynamics.com/.default".equals(config.getProperty("oauth.scope",null)),"scope default");
		final ConnectorConfig invalid=configuration();invalid.setBaseUrl("http://insecure.example");invalid.setProperty("companyId","");check(!connector.validateConfiguration(invalid).isValid(),"invalid HTTPS/company accepted");
		final ConnectorDescriptor descriptor=connector.getDescriptor();check("BUSINESS_APPS".equals(descriptor.getCategory()),"category");check(descriptor.getCapabilities().contains(ConnectorCapability.BULK_READ),"bulk read");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)&&descriptor.getDirections().contains(ConnectorDirection.OUTBOUND),"directions");
		check(connector.getOperations().size()==4,"product-appropriate operation count");
		System.out.println("PASS: Dynamics 365 Business Central defaults, OData API root, validation and descriptor");
	}
	private static ConnectorConfig configuration(){final ConnectorConfig c=new ConnectorConfig();c.setId("BusinessCentral");c.setType("dynamics-business-central");c.setBaseUrl("https://api.businesscentral.dynamics.com");c.setAuthType(ConnectorConfig.AuthType.OAUTH2);c.setApiKey("secret");c.setProperty("tenantId","tenant-id");c.setProperty("environment","Production");c.setProperty("companyId","company-id");c.setProperty("clientId","client-id");return c;}
	private static void check(final boolean c,final String m){if(!c)throw new AssertionError(m);}
}
