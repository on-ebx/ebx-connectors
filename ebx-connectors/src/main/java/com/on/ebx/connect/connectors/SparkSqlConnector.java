package com.on.ebx.connect.connectors;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.*;
/** Read-only Apache Spark SQL profile through a separately managed HiveServer2/Thrift JDBC endpoint. */
public final class SparkSqlConnector extends JdbcConnector{
 private static final List<Operation> OPS=Arrays.asList(Operation.search("query","Bounded read-only Spark SQL SELECT with named parameters","sql"),Operation.search("catalog","Discover bounded catalogs, schemas, tables and columns","source"),Operation.search("profile","Bounded column statistics through the shared JDBC profiler","source"),Operation.search("sample","Bounded distinct text samples for classification","source"));
public String getType()
{
    return "spark-sql";
}
public String getLabel()
{
    return "Apache Spark SQL";
}
public List<Operation>getOperations()
{
    return OPS;
}
public void configure(ConnectorConfig c)
{
    if (c != null)
    {
        if (c.getProperty("driver", null) == null) c.setProperty("driver", "org.apache.hive.jdbc.HiveDriver");
        if (c.getProperty("max.rows", null) == null) c.setProperty("max.rows", "1000");
        if (c.getProperty("fetch.size", null) == null) c.setProperty("fetch.size", "500");
    }
    super.configure(c);
}
public ConfigurationValidationResult validateConfiguration(ConnectorConfig c)
{
    List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(super.validateConfiguration(c).getIssues());
    if (c == null) return new ConfigurationValidationResult(issues);
    String jndi = c.getProperty("jndi", null), url = c.getBaseUrl();
    if (jndi == null && (url == null || !url.startsWith("jdbc:hive2://"))) issues.add(new ConfigurationValidationIssue("baseUrl", "Spark SQL requires jdbc:hive2:// or a JNDI DataSource"));
    if (jndi == null && url != null && !url.toLowerCase(Locale.ROOT).contains("ssl=true")) issues.add(new ConfigurationValidationIssue("baseUrl", "Spark SQL JDBC must enable ssl=true"));
    if (!"org.apache.hive.jdbc.HiveDriver".equals(c.getProperty("driver", "org.apache.hive.jdbc.HiveDriver"))) issues.add(new ConfigurationValidationIssue("driver", "Use the approved org.apache.hive.jdbc.HiveDriver"));
    return new ConfigurationValidationResult(issues);
}
public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode p)throws ConnectorException
{
    if (!("query".equals(op) || "catalog".equals(op) || "profile".equals(op) || "sample".equals(op))) throw new ConnectorException("Spark SQL profile is read-only; operation '"+op+"' is not permitted");
    return super.execute(c, op, in, p);
}
public ConnectorDescriptor getDescriptor()
{
    ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Read-only bounded Spark SQL through an administrator-managed HiveServer2/Thrift JDBC endpoint. No embedded Spark runtime, job submission, arbitrary commands, or streaming control.").authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY).capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).apiVersion("Spark Thrift Server / HiveServer2 JDBC; live endpoint acceptance required").dependency(new ConnectorDependency("Approved Hive JDBC driver", "org.apache.hive:hive-jdbc:deployment-approved Java 8 line", false, "Not packaged: install a reviewed Java 8-compatible Hive JDBC closure or configure a JNDI DataSource")).pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null)).defaults(ConnectorDefaults.standard());
    for (Operation o:OPS)
    {
        b.direction(ConnectorDirection.INBOUND).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(ConnectorDirection.INBOUND), Collections.<ConnectorCapability>emptySet()));
    }
    return b.build();
}
}
