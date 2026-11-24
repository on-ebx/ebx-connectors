package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.Operation;

/**
 * Read-only Apache Spark SQL profile through a separately managed Spark Thrift Server, which speaks the HiveServer2
 * protocol: the rules of {@link AbstractHiveServer2Connector} apply (no read-only connection mode, no rollback, a
 * {@code TRANSFORM ... USING} statement refused, table prefixes dropped from the columns of a {@code SELECT *}).
 */
public final class SparkSqlConnector extends AbstractHiveServer2Connector
{
	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		Operation.search("query", "Bounded read-only Spark SQL SELECT with named parameters", "sql"),
		Operation.search("catalog", "Discover bounded catalogs, schemas, tables and columns", "source"),
		Operation.search("profile", "Bounded column statistics through the shared JDBC profiler", "source"),
		Operation.search("sample", "Bounded distinct text samples for classification", "source")));

	@Override public String getType() { return "spark-sql"; }
	@Override public String getLabel() { return "Apache Spark SQL"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override protected List<String> urlPrefixes() { return Collections.singletonList("jdbc:hive2://"); }
	@Override protected List<String> drivers(final String pUrl) { return Collections.singletonList(APACHE_HIVE_DRIVER); }

	@Override
	protected ConnectorDependency driverDependency()
	{
		return new ConnectorDependency("Approved Hive JDBC driver", "org.apache.hive:hive-jdbc:deployment-approved Java 8 line", false,
			"Not packaged: install a reviewed Java 8-compatible Hive JDBC closure or configure a JNDI DataSource");
	}

	@Override
	protected String description()
	{
		return "Read-only bounded Spark SQL through an administrator-managed HiveServer2/Thrift JDBC endpoint. No embedded Spark runtime,"
			+ " job submission, arbitrary commands, or streaming control.";
	}
}
