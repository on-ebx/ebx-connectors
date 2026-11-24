package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.Operation;

/**
 * Read-only access to the Impala tables of a Cloudera platform, with a user name and a password over TLS: either
 * Cloudera's Impala driver ({@code jdbc:impala://HOST:443/default;AuthMech=3;transportMode=http;httpPath=cliservice;SSL=1}),
 * or the Apache Hive driver on Impala's HiveServer2 port ({@code jdbc:hive2://HOST:21050/default;ssl=true}).
 * <p>
 * No {@code profile} operation: the shared profiler asks for several {@code COUNT(DISTINCT ...)} in one statement,
 * which Impala refuses unless a query option makes them approximate.
 */
public final class ClouderaImpalaConnector extends AbstractHiveServer2Connector
{
	public static final String CLOUDERA_IMPALA_DRIVER = "com.cloudera.impala.jdbc.Driver";

	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		Operation.search("query", "Bounded read-only Impala SQL SELECT with named parameters", "sql"),
		Operation.search("catalog", "Discover bounded databases, tables and columns", "source"),
		Operation.search("sample", "Bounded distinct text samples for classification", "source")));

	@Override public String getType() { return "cloudera-impala"; }
	@Override public String getLabel() { return "Cloudera Impala"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override protected List<String> urlPrefixes() { return Arrays.asList("jdbc:impala://", "jdbc:hive2://"); }

	@Override
	protected List<String> drivers(final String pUrl)
	{
		// one driver per URL scheme: each driver only answers its own
		return Collections.singletonList(pUrl != null && pUrl.trim().startsWith("jdbc:impala://") ? CLOUDERA_IMPALA_DRIVER : APACHE_HIVE_DRIVER);
	}

	@Override
	protected ConnectorDependency driverDependency()
	{
		return new ConnectorDependency("Impala or Hive JDBC driver", "the Cloudera JDBC connector for Apache Impala, or org.apache.hive:hive-jdbc (standalone)",
			false, "Not packaged: install the driver jar the platform's administrators approve, or configure a JNDI DataSource");
	}

	@Override
	protected String description()
	{
		return "Read-only bounded Impala SQL, catalogue scan and sampling on the Impala tables of a Cloudera platform, over TLS with a"
			+ " user name and a password. No write, no profiling, no Kerberos.";
	}
}
