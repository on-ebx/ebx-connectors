package com.on.ebx.connect.connectors;
import java.util.Collections;import com.on.ebx.connect.spi.*;
public final class SparkSqlConnectorTest{
 public static void main(String[]a)throws Exception{
  SparkSqlConnector c=new SparkSqlConnector();ConnectorConfig ok=cfg("jdbc:hive2://spark.example:10001/default;transportMode=http;httpPath=cliservice;ssl=true");c.configure(ok);check(c.validateConfiguration(ok).isValid(),"secure URL");check("org.apache.hive.jdbc.HiveDriver".equals(ok.getProperty("driver",null)),"driver default");
  ConnectorConfig clear=cfg("jdbc:hive2://spark.example:10000/default");c.configure(clear);check(!c.validateConfiguration(clear).isValid(),"clear URL");ConnectorConfig wrong=cfg("jdbc:spark://host/db");c.configure(wrong);check(!c.validateConfiguration(wrong).isValid(),"wrong URL");
  try{c.execute(null,"execute",Collections.singletonMap("sql","DROP TABLE x"),null);throw new AssertionError("write exposed");}catch(ConnectorException e){check(e.getMessage().contains("read-only"),"write guard");}
  check(c.getOperations().size()==4&&!c.getDescriptor().getCapabilities().contains(ConnectorCapability.BULK_WRITE),"descriptor scope");check(!c.getDescriptor().getDependencies().get(0).isRequired(),"dependency gate");System.out.println("PASS: Spark SQL secure JDBC/JNDI profile, read-only operations, bounds and external-driver gate");}
 static ConnectorConfig cfg(String u){ConnectorConfig c=new ConnectorConfig();c.setBaseUrl(u);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("u");c.setPassword("p");return c;}static void check(boolean x,String m){if(!x)throw new AssertionError(m);}}
