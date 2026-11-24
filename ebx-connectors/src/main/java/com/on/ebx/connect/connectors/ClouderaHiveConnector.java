package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.Operation;

/**
 * Read-only access to the Hive tables of a Cloudera platform: a Hive virtual warehouse of Cloudera Data Warehouse, or
 * a HiveServer2 reached directly or through Knox, with a user name and a password over TLS.
 * <p>
 * URL of a virtual warehouse: {@code jdbc:hive2://HOST/default;transportMode=http;httpPath=cliservice;ssl=true}.
 */
public final class ClouderaHiveConnector extends AbstractHiveServer2Connector
{
	/** Cloudera's own Hive driver; it takes the same {@code jdbc:hive2://} URLs. */
	public static final String CLOUDERA_HIVE_DRIVER = "com.cloudera.hive.jdbc.HS2Driver";

	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		Operation.search("query", "Bounded read-only HiveQL SELECT with named parameters", "sql"),
		Operation.search("catalog", "Discover bounded databases, tables and columns", "source"),
		Operation.search("profile", "Bounded column statistics through the shared JDBC profiler", "source"),
		Operation.search("sample", "Bounded distinct text samples for classification", "source")));

	@Override public String getType() { return "cloudera-hive"; }
	@Override public String getLabel() { return "Cloudera Hive"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override protected List<String> urlPrefixes() { return Collections.singletonList("jdbc:hive2://"); }
	@Override protected List<String> drivers(final String pUrl) { return Arrays.asList(APACHE_HIVE_DRIVER, CLOUDERA_HIVE_DRIVER); }

	@Override
	protected ConnectorDependency driverDependency()
	{
		return new ConnectorDependency("Hive JDBC driver", "org.apache.hive:hive-jdbc (standalone) or the Cloudera JDBC connector for Apache Hive",
			false, "Not packaged: install the driver jar the platform's administrators approve, or configure a JNDI DataSource");
	}

	@Override
	protected String description()
	{
		return "Read-only bounded HiveQL, catalogue scan, profiling and sampling on the Hive tables of a Cloudera platform, through"
			+ " HiveServer2 over TLS with a user name and a password. No write, no job submission, no Kerberos.";
	}
}
