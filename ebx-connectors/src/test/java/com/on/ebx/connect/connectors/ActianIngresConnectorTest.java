package com.on.ebx.connect.connectors;

import java.util.HashSet;
import java.util.Set;
import com.on.ebx.connect.spi.*;

/** Offline contract checks for the distinct Actian Ingres profile. */
public final class ActianIngresConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final ActianIngresConnector connector=new ActianIngresConnector();
		check("actian-ingres".equals(connector.getType())&&"Actian Ingres".equals(connector.getLabel()),"identity");
		check(!connector.getType().equals(new ActianVectorConnector().getType()),"Ingres and Vector types conflated");
		final ConnectorConfig valid=config("jdbc:ingres://ingres.example:II7/governed;encryption=on");connector.configure(valid);
		check(ActianIngresConnector.DRIVER.equals(valid.getProperty("driver",null)),"driver default");check(connector.validateConfiguration(valid).isValid(),"valid config");
		final ConnectorConfig badUrl=config("jdbc:postgresql://db.example:5432/mdm");connector.configure(badUrl);check(has(connector.validateConfiguration(badUrl),"baseUrl"),"wrong URL");
		final ConnectorConfig badDriver=config("jdbc:ingres://ingres.example:II7/governed");badDriver.setProperty("driver","ca.ingres.jdbc.IngresDriver");check(has(connector.validateConfiguration(badDriver),"driver"),"legacy driver");
		final ConnectorConfig unsafe=config("jdbc:ingres://ingres.example:II7/governed");unsafe.setProperty("max.rows","0");unsafe.setProperty("fetch.size","10001");unsafe.setProperty("batch.size","1001");final ConfigurationValidationResult r=connector.validateConfiguration(unsafe);check(has(r,"max.rows")&&has(r,"fetch.size")&&has(r,"batch.size"),"bounds");
		final ConnectorConfig jndi=config(null);jndi.setProperty("jndi","java:comp/env/jdbc/ActianIngres");jndi.setProperty("fetch.size","0");check(has(connector.validateConfiguration(jndi),"fetch.size"),"JNDI bypassed bounds");jndi.setProperty("fetch.size","10");check(connector.validateConfiguration(jndi).isValid(),"JNDI");
		final ConnectorDescriptor d=connector.getDescriptor();check(d.getDescription().contains("does not imply Actian Avalanche or Vector certification")&&d.getDescription().contains("update-then-insert"),"scope/upsert disclosure");check(d.getDirections().contains(ConnectorDirection.INBOUND)&&d.getDirections().contains(ConnectorDirection.OUTBOUND),"directions");check(d.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)&&d.getCapabilities().contains(ConnectorCapability.TRANSACTIONS),"capabilities");
		boolean dep=false,boundary=false;for(final ConnectorDependency x:d.getDependencies())if(x.getDescription().contains(ActianIngresConnector.DRIVER_ARCHIVE))dep=true;for(final String v:d.getApiVersions())if(v.contains("live target acceptance required"))boundary=true;check(dep&&boundary,"dependency/boundary");
		final Set<String> ops=new HashSet<String>();for(final ConnectorOperationDescriptor o:d.getOperations())ops.add(o.getName());for(final String n:new String[]{"query","get","insert","update","upsert","execute","catalog","profile","sample"})check(ops.contains(n),"operation "+n);
		System.out.println("PASS: distinct Actian Ingres URL, driver, JNDI, bounds, operations, dependency, and acceptance boundary");
	}
	private static ConnectorConfig config(final String url){final ConnectorConfig c=new ConnectorConfig();c.setId("actian-ingres-test");c.setType("actian-ingres");c.setBaseUrl(url);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("ebx");c.setPassword("secret");return c;}
	private static boolean has(final ConfigurationValidationResult r,final String field){for(final ConfigurationValidationIssue i:r.getIssues())if(field.equals(i.getPropertyName()))return true;return false;}
	private static void check(final boolean ok,final String message){if(!ok)throw new AssertionError(message);}
}
