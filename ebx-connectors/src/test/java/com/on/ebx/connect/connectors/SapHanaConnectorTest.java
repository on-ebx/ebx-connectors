package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.*;

/** Offline contract checks for the SAP HANA JDBC profile. */
public final class SapHanaConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final SapHanaConnector c=new SapHanaConnector();SapAseConnectorTest.check("sap-hana".equals(c.getType())&&"SAP HANA".equals(c.getLabel()),"identity");
		final ConnectorConfig valid=config("jdbc:sap://hana.example:30015/?databaseName=GOVERNED");c.configure(valid);SapAseConnectorTest.check(SapHanaConnector.DRIVER.equals(valid.getProperty("driver",null)),"driver default");SapAseConnectorTest.check(c.validateConfiguration(valid).isValid(),"valid config");
		final ConnectorConfig badUrl=config("jdbc:sybase:Tds:ase.example:5000/governed");c.configure(badUrl);SapAseConnectorTest.check(SapAseConnectorTest.has(c.validateConfiguration(badUrl),"baseUrl"),"wrong URL");
		final ConnectorConfig badDriver=config("jdbc:sap://hana.example:30015");badDriver.setProperty("driver","bad.Driver");SapAseConnectorTest.check(SapAseConnectorTest.has(c.validateConfiguration(badDriver),"driver"),"wrong driver");
		final ConnectorConfig unsafe=config("jdbc:sap://hana.example:30015");unsafe.setProperty("max.rows","10001");unsafe.setProperty("batch.size","0");final ConfigurationValidationResult r=c.validateConfiguration(unsafe);SapAseConnectorTest.check(SapAseConnectorTest.has(r,"max.rows")&&SapAseConnectorTest.has(r,"batch.size"),"bounds");
		final ConnectorConfig jndi=config(null);jndi.setProperty("jndi","java:comp/env/jdbc/SapHana");SapAseConnectorTest.check(c.validateConfiguration(jndi).isValid(),"JNDI");
		SapAseConnectorTest.assertDescriptor(c.getDescriptor(),"SAP HANA JDBC driver");boolean coordinate=false;for(final ConnectorDependency d:c.getDescriptor().getDependencies())if(("com.sap.cloud.db.jdbc:ngdbc:"+SapHanaConnector.NGDBC_VERSION).equals(d.getMinimumVersion()))coordinate=true;SapAseConnectorTest.check(coordinate,"ngdbc dependency");
		System.out.println("PASS: SAP HANA ngdbc URL, driver, JNDI, bounds, operations, dependency, and acceptance boundary");
	}
	private static ConnectorConfig config(final String url){final ConnectorConfig c=new ConnectorConfig();c.setId("sap-hana-test");c.setType("sap-hana");c.setBaseUrl(url);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("ebx");c.setPassword("secret");return c;}
}
