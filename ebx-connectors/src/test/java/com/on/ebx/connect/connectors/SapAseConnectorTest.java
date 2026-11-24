package com.on.ebx.connect.connectors;

import java.util.HashSet;
import java.util.Set;
import com.on.ebx.connect.spi.*;

/** Offline contract checks for the SAP ASE JDBC profile. */
public final class SapAseConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final SapAseConnector c=new SapAseConnector();check("sap-ase".equals(c.getType())&&"SAP ASE".equals(c.getLabel()),"identity");
		final ConnectorConfig valid=config("jdbc:sybase:Tds:ase.example:5000/governed");c.configure(valid);check(SapAseConnector.DRIVER.equals(valid.getProperty("driver",null)),"driver default");check(c.validateConfiguration(valid).isValid(),"valid config");
		final ConnectorConfig badUrl=config("jdbc:sap://hana.example:30015");c.configure(badUrl);check(has(c.validateConfiguration(badUrl),"baseUrl"),"wrong URL");
		final ConnectorConfig badDriver=config("jdbc:sybase:Tds:ase.example:5000/governed");badDriver.setProperty("driver","bad.Driver");check(has(c.validateConfiguration(badDriver),"driver"),"wrong driver");
		final ConnectorConfig unsafe=config("jdbc:sybase:Tds:ase.example:5000/governed");unsafe.setProperty("max.rows","0");unsafe.setProperty("fetch.size","10001");unsafe.setProperty("batch.size","1001");final ConfigurationValidationResult r=c.validateConfiguration(unsafe);check(has(r,"max.rows")&&has(r,"fetch.size")&&has(r,"batch.size"),"bounds");
		final ConnectorConfig jndi=config(null);jndi.setProperty("jndi","java:comp/env/jdbc/SapAse");jndi.setProperty("max.rows","0");check(!c.validateConfiguration(jndi).isValid()&&has(c.validateConfiguration(jndi),"max.rows"),"JNDI must retain bounds");jndi.setProperty("max.rows","10");check(c.validateConfiguration(jndi).isValid(),"JNDI");
		assertDescriptor(c.getDescriptor(),"SAP jConnect JDBC driver");System.out.println("PASS: SAP ASE jConnect URL, driver, JNDI, bounds, operations, dependency, and acceptance boundary");
	}
	static void assertDescriptor(final ConnectorDescriptor d,final String dependencyName){check(d.getDirections().contains(ConnectorDirection.INBOUND)&&d.getDirections().contains(ConnectorDirection.OUTBOUND),"directions");check(d.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)&&d.getCapabilities().contains(ConnectorCapability.TRANSACTIONS),"capabilities");check(d.getDescription().contains("update-then-insert"),"upsert disclosure");boolean dep=false,boundary=false;for(final ConnectorDependency x:d.getDependencies())if(dependencyName.equals(x.getName()))dep=true;for(final String v:d.getApiVersions())if(v.contains("live target acceptance required"))boundary=true;check(dep&&boundary,"dependency/boundary");final Set<String> ops=new HashSet<String>();for(final ConnectorOperationDescriptor o:d.getOperations())ops.add(o.getName());for(final String n:new String[]{"query","get","insert","update","upsert","execute","catalog","profile","sample"})check(ops.contains(n),"operation "+n);}
	static ConnectorConfig config(final String url){final ConnectorConfig c=new ConnectorConfig();c.setId("sap-ase-test");c.setType("sap-ase");c.setBaseUrl(url);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("ebx");c.setPassword("secret");return c;}
	static boolean has(final ConfigurationValidationResult r,final String field){for(final ConfigurationValidationIssue i:r.getIssues())if(field.equals(i.getPropertyName()))return true;return false;}
	static void check(final boolean ok,final String message){if(!ok)throw new AssertionError(message);}
}
