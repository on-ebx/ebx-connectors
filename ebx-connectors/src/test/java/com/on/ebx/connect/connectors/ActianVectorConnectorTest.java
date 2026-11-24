package com.on.ebx.connect.connectors;

import java.util.HashSet;
import java.util.Set;
import com.on.ebx.connect.spi.*;

/** Offline contract checks for the canonical Actian Avalanche / Vector profile. */
public final class ActianVectorConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final ActianVectorConnector connector = new ActianVectorConnector();
		check("actian-vector".equals(connector.getType()) && "Actian Avalanche / Vector".equals(connector.getLabel()), "identity");
		final ConnectorConfig valid = config("jdbc:ingres://vector.example:VW7/governed;encryption=on"); connector.configure(valid);
		check(ActianVectorConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid configuration rejected");
		final ConnectorConfig wrongUrl = config("jdbc:postgresql://db.example:5432/mdm"); connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "wrong URL accepted");
		final ConnectorConfig wrongDriver = config("jdbc:ingres://vector.example:VW7/governed"); wrongDriver.setProperty("driver", "ca.ingres.jdbc.IngresDriver");
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "legacy driver class accepted");
		final ConnectorConfig unsafe = config("jdbc:ingres://vector.example:VW7/governed"); unsafe.setProperty("max.rows", "10001"); unsafe.setProperty("fetch.size", "0"); unsafe.setProperty("batch.size", "1001");
		final ConfigurationValidationResult result = connector.validateConfiguration(unsafe);
		check(has(result,"max.rows") && has(result,"fetch.size") && has(result,"batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = config(null); jndi.setProperty("jndi", "java:comp/env/jdbc/ActianVector"); jndi.setProperty("batch.size", "0");
		check(has(connector.validateConfiguration(jndi), "batch.size"), "JNDI bypassed bounds"); jndi.setProperty("batch.size", "10");
		check(connector.validateConfiguration(jndi).isValid(), "valid JNDI rejected");
		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDescription().contains("VectorWise") && descriptor.getDescription().contains("update-then-insert"), "compatibility/upsert wording");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND) && descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY) && descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency=false,boundary=false; for(final ConnectorDependency d:descriptor.getDependencies()) if(d.getDescription().contains(ActianVectorConnector.DRIVER_ARCHIVE))dependency=true;
		for(final String v:descriptor.getApiVersions())if(v.contains("live target acceptance required"))boundary=true; check(dependency&&boundary,"dependency/boundary");
		final Set<String> operations=new HashSet<String>();for(final ConnectorOperationDescriptor o:descriptor.getOperations())operations.add(o.getName());
		for(final String name:new String[]{"query","get","insert","update","upsert","execute","catalog","profile","sample"})check(operations.contains(name),"operation "+name);
		System.out.println("PASS: Actian Avalanche / Vector URL, driver, aliases, JNDI, bounds, operations, dependency, and acceptance boundary");
	}
	private static ConnectorConfig config(final String url){final ConnectorConfig c=new ConnectorConfig();c.setId("actian-vector-test");c.setType("actian-vector");c.setBaseUrl(url);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("ebx");c.setPassword("secret");return c;}
	private static boolean has(final ConfigurationValidationResult r,final String field){for(final ConfigurationValidationIssue i:r.getIssues())if(field.equals(i.getPropertyName()))return true;return false;}
	private static void check(final boolean ok,final String message){if(!ok)throw new AssertionError(message);}
}
