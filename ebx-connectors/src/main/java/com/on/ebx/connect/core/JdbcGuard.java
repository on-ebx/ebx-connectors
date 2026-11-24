package com.on.ebx.connect.core;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.ConnectorException;

/**
 * What a JDBC connector configuration may ask the EBX server to do. A connector row is data a designer edits, yet a
 * JDBC URL, a driver class name, a JNDI name and the {@code jdbc.*} driver properties all end up as code the server
 * runs: a JNDI lookup resolves whatever the naming service knows, {@code Class.forName} runs a static initialiser,
 * and several drivers load a class named in an option (a socket factory, an interceptor) or run SQL given in the URL
 * (H2 {@code INIT}). Each check refuses by default and names the JVM property that allows the exception, so a
 * production instance stays closed and an instance that needs more opens exactly that.
 * <ul>
 * <li>JNDI names must start with {@code java:comp/env/} ({@value #PROPERTY_JNDI_PREFIXES} adds prefixes);</li>
 * <li>a driver class must be one of the drivers of the URL's sub-protocol ({@value #PROPERTY_DRIVERS} adds classes);</li>
 * <li>URL options and driver properties that load a class, run SQL or read/write a file are refused
 * ({@value #PROPERTY_OPTIONS} exempts option names);</li>
 * <li>an H2 URL must be {@code tcp:}, {@code ssl:} or {@code mem:}; a file database runs inside the server's own
 * process and filesystem ({@value #PROPERTY_H2_FILE}={@code true} allows it).</li>
 * </ul>
 * Pure: no connection, no I/O; the JVM properties are read at each call.
 */
public final class JdbcGuard
{
	public static final String PROPERTY_JNDI_PREFIXES = "ebx.connectors.jdbc.jndiPrefixes";
	public static final String PROPERTY_DRIVERS = "ebx.connectors.jdbc.allowedDrivers";
	public static final String PROPERTY_OPTIONS = "ebx.connectors.jdbc.allowedOptions";
	public static final String PROPERTY_H2_FILE = "ebx.connectors.jdbc.allowH2File";
	public static final String PROPERTY_H2_CODE = "ebx.connectors.jdbc.allowH2Code";

	/** The only JNDI context a Tomcat web application can see: the container's own resources. */
	public static final String DEFAULT_JNDI_PREFIX = "java:comp/env/";

	/** Sub-protocol (lower case, the word after {@code jdbc:}) -> driver classes that serve it. */
	private static final Map<String, Set<String>> DRIVERS;
	/** Normalised option name -> why it is refused. */
	private static final Map<String, String> DENIED;
	/**
	 * Option names that, by their ending or content, name a file, a folder or a key store of the server: the drivers of the
	 * table above spell them differently ({@code sslrootcert}, {@code trustCertificateKeyStoreUrl}, {@code private_key_file},
	 * {@code oracle.net.wallet_location}...) and new versions add more, so the explicit list is completed by this rule rather
	 * than left to the next audit. A name ending in {@code profile} is not one.
	 */
	private static final Pattern PATH_OPTION = Pattern.compile(
		"(?:.*(?:keystore|truststore|wallet|privatekey|rootcert).*|.*(?:file|path|dir|directory|location))");
	private static final Pattern OPTION_NAME = Pattern.compile("(?:^|[^a-z0-9.])([a-z0-9.]+)\\s*=");
	private static final Pattern H2_CODE_SQL = Pattern.compile(
		"(?is)(\\bcreate\\s+(?:or\\s+replace\\s+)?(?:force\\s+)?(?:alias|aggregate|trigger)\\b|^\\s*(?:script|runscript)\\b"
			+ "|\\b(file_read|file_write|csvread|csvwrite|link_schema|runscript)\\s*\\()");

	static
	{
		final Map<String, Set<String>> drivers = new HashMap<String, Set<String>>();
		JdbcGuard.driver(drivers, "h2", "org.h2.Driver");
		JdbcGuard.driver(drivers, "postgresql", "org.postgresql.Driver");
		JdbcGuard.driver(drivers, "mysql", "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
		JdbcGuard.driver(drivers, "singlestore", "com.singlestore.jdbc.Driver", "com.mysql.cj.jdbc.Driver");
		JdbcGuard.driver(drivers, "mariadb", "org.mariadb.jdbc.Driver");
		JdbcGuard.driver(drivers, "sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
		JdbcGuard.driver(drivers, "oracle", "oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver");
		JdbcGuard.driver(drivers, "db2", "com.ibm.db2.jcc.DB2Driver");
		JdbcGuard.driver(drivers, "iris", "com.intersystems.jdbc.IRISDriver");
		JdbcGuard.driver(drivers, "cache", "com.intersystems.jdbc.CacheDriver");
		JdbcGuard.driver(drivers, "sybase", "com.sybase.jdbc4.jdbc.SybDriver", "com.sybase.jdbc3.jdbc.SybDriver");
		JdbcGuard.driver(drivers, "sap", "com.sap.db.jdbc.Driver");
		JdbcGuard.driver(drivers, "teradata", "com.teradata.jdbc.TeraDriver");
		JdbcGuard.driver(drivers, "vertica", "com.vertica.jdbc.Driver");
		JdbcGuard.driver(drivers, "redshift", "com.amazon.redshift.jdbc42.Driver", "com.amazon.redshift.jdbc.Driver");
		JdbcGuard.driver(drivers, "ingres", "com.ingres.jdbc.IngresDriver");
		JdbcGuard.driver(drivers, "monetdb", "org.monetdb.jdbc.MonetDriver", "nl.cwi.monetdb.jdbc.MonetDriver");
		JdbcGuard.driver(drivers, "monetdbs", "org.monetdb.jdbc.MonetDriver", "nl.cwi.monetdb.jdbc.MonetDriver");
		JdbcGuard.driver(drivers, "mapi", "org.monetdb.jdbc.MonetDriver", "nl.cwi.monetdb.jdbc.MonetDriver");
		JdbcGuard.driver(drivers, "firebirdsql", "org.firebirdsql.jdbc.FBDriver");
		JdbcGuard.driver(drivers, "hive2", "org.apache.hive.jdbc.HiveDriver");
		JdbcGuard.driver(drivers, "snowflake", "net.snowflake.client.jdbc.SnowflakeDriver");
		JdbcGuard.driver(drivers, "databricks", "com.databricks.client.jdbc.Driver", "com.databricks.jdbc.Driver");
		DRIVERS = Collections.unmodifiableMap(drivers);

		final Map<String, String> denied = new LinkedHashMap<String, String>();
		JdbcGuard.deny(denied, "H2 runs the SQL it names when the database opens, and CREATE ALIAS in it runs Java", "INIT");
		JdbcGuard.deny(denied, "H2 loads and instantiates the class it names", "DATABASE_EVENT_LISTENER", "JAVA_OBJECT_SERIALIZER");
		JdbcGuard.deny(denied, "the driver instantiates the class it names", "socketFactory", "socketFactoryClass", "sslfactory",
			"sslSocketFactory", "sslhostnameverifier", "sslpasswordcallback", "authenticationPluginClassName", "accessTokenCallbackClass",
			"trustManagerClass", "propertiesTransform", "clientInfoProvider", "logger", "profilerEventHandler", "loadBalanceStrategy",
			"loadBalanceExceptionChecker", "queryInterceptors", "statementInterceptors", "connectionLifecycleInterceptors",
			"exceptionInterceptors", "authenticationPlugins", "defaultAuthenticationPlugin", "SYBSOCKET_FACTORY", "CHARSET_CONVERTER_CLASS",
			"JCE_PROVIDER_CLASS");
		JdbcGuard.deny(denied, "the argument of a socket factory or trust manager the driver would instantiate", "socketFactoryArg",
			"socketFactoryConstructorArg", "sslfactoryarg", "trustManagerConstructorArg");
		JdbcGuard.deny(denied, "the driver writes a log or trace file at a path the connector row chooses", "loggerFile", "traceFile",
			"traceDirectory");
		// audit C3-06: the Redshift driver loads the class these options name (an IdP plugin, a credentials provider)
		JdbcGuard.deny(denied, "the Redshift driver loads and instantiates the class it names", "plugin_name", "CredentialsProvider");
		JdbcGuard.deny(denied, "MySQL would send a local file of the server to the database server", "allowLoadLocalInfile",
			"allowLocalInfile", "allowLoadLocalInfileInPath", "allowUrlInLocalInfile");
		JdbcGuard.deny(denied, "MySQL would deserialize Java objects sent by the database server", "autoDeserialize");
		// audit C2-20: options that make the driver read a file of the server (certificates, keys, key stores, wallets, scripts)
		JdbcGuard.deny(denied, "the driver reads a certificate, key or wallet file of the server's filesystem", "sslrootcert", "sslcert",
			"sslkey", "sslCA", "sslCert", "sslKey", "serverSslCert", "servercertificate", "clientcertificate", "clientkey", "trustStore",
			"keyStore", "private_key_file", "private_key_file_pwd", "oracle.net.wallet_location", "oracle.net.tns_admin",
			"javax.net.ssl.keyStore", "javax.net.ssl.trustStore", "pgpassfile", "pgservicefile");
		JdbcGuard.deny(denied, "the driver opens a local socket, pipe or shared memory of the server instead of the network", "localSocket",
			"pipe", "sharedMemory", "junixsocketfile");
		JdbcGuard.deny(denied, "H2 runs the script file it names, or writes its trace to a file", "RUNSCRIPT", "TRACE_LEVEL_FILE",
			"SCRIPT");
		DENIED = Collections.unmodifiableMap(denied);
	}

	private JdbcGuard()
	{
	}

	private static void driver(final Map<String, Set<String>> pTable, final String pSubProtocol, final String... pClasses)
	{
		pTable.put(pSubProtocol, Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(pClasses))));
	}

	private static void deny(final Map<String, String> pTable, final String pReason, final String... pNames)
	{
		for (final String name : pNames)
		{
			pTable.put(JdbcGuard.normalize(name), pReason);
		}
	}

	/** Lower case without the separators some drivers ignore ({@code socket_factory} reads like {@code socketFactory}). */
	private static String normalize(final String pName)
	{
		return pName == null ? "" : pName.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
	}

	/** The comma-separated values of a JVM property, trimmed, empty entries dropped. */
	private static Set<String> listed(final String pProperty)
	{
		final Set<String> values = new HashSet<String>();
		final String raw = System.getProperty(pProperty);
		if (raw != null)
		{
			for (final String part : raw.split(","))
			{
				if (!part.trim().isEmpty())
				{
					values.add(part.trim());
				}
			}
		}
		return values;
	}

	/** Refuses a JNDI DataSource name outside the allowed prefixes. */
	public static void checkJndiName(final String pName) throws ConnectorException
	{
		final String name = pName == null ? "" : pName.trim();
		boolean allowed = false;
		final Set<String> prefixes = JdbcGuard.listed(JdbcGuard.PROPERTY_JNDI_PREFIXES);
		prefixes.add(JdbcGuard.DEFAULT_JNDI_PREFIX);
		for (final String prefix : prefixes)
		{
			allowed |= name.startsWith(prefix);
		}
		// a parent segment would leave the allowed context once the naming service resolves it
		if (!allowed || name.contains("..") || name.indexOf('\\') >= 0 || JdbcGuard.hasControl(name))
		{
			throw new ConnectorException("JDBC: the JNDI name is refused: only names under " + JdbcGuard.DEFAULT_JNDI_PREFIX
				+ " are allowed; the JVM property " + JdbcGuard.PROPERTY_JNDI_PREFIXES + " lists other allowed prefixes");
		}
	}

	private static boolean hasControl(final String pText)
	{
		for (int i = 0; i < pText.length(); i++)
		{
			if (pText.charAt(i) < ' ')
			{
				return true;
			}
		}
		return false;
	}

	/** The sub-protocol of a JDBC URL ({@code postgresql} for {@code jdbc:postgresql://...}), lower case; null when there is none. */
	public static String subProtocol(final String pUrl)
	{
		if (pUrl == null || !pUrl.regionMatches(true, 0, "jdbc:", 0, 5))
		{
			return null;
		}
		int end = 5;
		while (end < pUrl.length() && ":/;?@".indexOf(pUrl.charAt(end)) < 0)
		{
			end++;
		}
		return end == 5 ? null : pUrl.substring(5, end).toLowerCase(Locale.ROOT);
	}

	/** Refuses a driver class that does not serve the URL's sub-protocol; a blank driver is left to DriverManager. */
	public static void checkDriver(final String pUrl, final String pDriver) throws ConnectorException
	{
		final String driver = pDriver == null ? "" : pDriver.trim();
		if (driver.isEmpty() || JdbcGuard.listed(JdbcGuard.PROPERTY_DRIVERS).contains(driver))
		{
			return;
		}
		// a sub-protocol the table does not know can only be served by a driver the table does know (a test double, a rebranded
		// URL scheme): any other class is the one this check exists to stop
		final Set<String> known = DRIVERS.containsKey(JdbcGuard.subProtocol(pUrl)) ? DRIVERS.get(JdbcGuard.subProtocol(pUrl)) : JdbcGuard.allDrivers();
		if (!known.contains(driver))
		{
			throw new ConnectorException("JDBC: the driver class is refused: it is not a driver of the sub-protocol of the URL"
				+ " (a class named by a connector row is initialised by the server); the JVM property " + JdbcGuard.PROPERTY_DRIVERS
				+ " lists other allowed driver classes");
		}
	}

	private static Set<String> allDrivers()
	{
		final Set<String> all = new HashSet<String>();
		for (final Set<String> drivers : DRIVERS.values())
		{
			all.addAll(drivers);
		}
		return all;
	}

	/** Refuses a URL that opens a file database, runs SQL at connection time or names a class or file in an option. */
	public static void checkUrl(final String pUrl) throws ConnectorException
	{
		final String protocol = JdbcGuard.subProtocol(pUrl);
		final String lower = pUrl == null ? "" : pUrl.toLowerCase(Locale.ROOT);
		if ("h2".equals(protocol))
		{
			final String rest = lower.substring(lower.indexOf("h2:") + 3);
			final boolean remoteOrMemory = rest.startsWith("tcp:") || rest.startsWith("ssl:") || rest.startsWith("mem:");
			if (!remoteOrMemory && !Boolean.getBoolean(JdbcGuard.PROPERTY_H2_FILE))
			{
				throw new ConnectorException("JDBC: an H2 URL must use tcp:, ssl: or mem:; a file database lives in the server's own"
					+ " filesystem (set the JVM property " + JdbcGuard.PROPERTY_H2_FILE + "=true to allow it)");
			}
		}
		if ("redshift".equals(protocol))
		{
			// audit C3-06: the endpoint form only (a cluster identifier is resolved by the driver through AWS, past the egress checks)
			final String problem = com.on.ebx.connect.connectors.RedshiftConnector.urlProblem(pUrl);
			if (problem != null)
			{
				throw new ConnectorException("JDBC: the Redshift URL is refused: " + problem);
			}
		}
		final String refused = JdbcGuard.deniedOption(JdbcGuard.normalize(JdbcGuard.decode(lower)), true);
		if (refused != null)
		{
			throw JdbcGuard.optionRefused(refused);
		}
	}

	/** Refuses the driver properties (the connector's {@code jdbc.*} entries, prefix removed) that load a class or touch a file. */
	public static void checkProperties(final Map<String, String> pProperties) throws ConnectorException
	{
		if (pProperties == null)
		{
			return;
		}
		for (final String name : pProperties.keySet())
		{
			final String refused = JdbcGuard.deniedOption(JdbcGuard.normalize(name), false);
			if (refused != null)
			{
				throw JdbcGuard.optionRefused(refused);
			}
		}
	}

	/**
	 * The first refused option name in a text. In a URL ({@code pScanUrl}) every {@code name=} token counts; a bare
	 * property name is compared as is.
	 */
	private static String deniedOption(final String pNormalized, final boolean pScanUrl)
	{
		final Set<String> exempt = new HashSet<String>();
		for (final String name : JdbcGuard.listed(JdbcGuard.PROPERTY_OPTIONS))
		{
			exempt.add(JdbcGuard.normalize(name));
		}
		if (!pScanUrl)
		{
			return JdbcGuard.reason(pNormalized) != null && !exempt.contains(pNormalized) ? pNormalized : null;
		}
		final Matcher matcher = OPTION_NAME.matcher(pNormalized);
		while (matcher.find())
		{
			final String name = matcher.group(1);
			if (JdbcGuard.reason(name) != null && !exempt.contains(name))
			{
				return name;
			}
		}
		return null;
	}

	/** Why an option (normalised name) is refused, or null when it is not. */
	private static String reason(final String pName)
	{
		if (DENIED.containsKey(pName))
		{
			return DENIED.get(pName);
		}
		// httpPath (Databricks) is the URL path of the SQL warehouse, not a file
		if (!pName.endsWith("profile") && !"httppath".equals(pName) && PATH_OPTION.matcher(pName).matches())
		{
			return "its name says it points at a file, folder or key store of the server's filesystem";
		}
		return null;
	}

	private static ConnectorException optionRefused(final String pName)
	{
		return new ConnectorException("JDBC: the option " + pName + " is refused: " + JdbcGuard.reason(pName) + "; the JVM property "
			+ JdbcGuard.PROPERTY_OPTIONS + " lists option names to allow");
	}

	/** Percent-decoding as the drivers do it for URL options, twice so that a double-encoded name does not slip by. */
	private static String decode(final String pText)
	{
		String text = pText;
		for (int pass = 0; pass < 2; pass++)
		{
			try
			{
				text = URLDecoder.decode(text, "UTF-8");
			}
			catch (final Exception malformed)
			{
				// a driver reads what it can: keep the text as it is
				return text;
			}
		}
		return text;
	}

	/**
	 * Refuses a statement that runs Java or touches a file of the server on an in-memory H2 database, which lives in
	 * this JVM ({@code CREATE ALIAS}, {@code CREATE TRIGGER}, {@code FILE_READ}, {@code CSVREAD}...). Other databases
	 * run their statements elsewhere and are not checked here.
	 * ({@value #PROPERTY_H2_CODE}={@code true} lifts it.)
	 */
	public static void checkStatement(final String pUrl, final String pSql) throws ConnectorException
	{
		if (pSql == null || !"h2".equals(JdbcGuard.subProtocol(pUrl)) || !pUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:mem:")
			|| Boolean.getBoolean(JdbcGuard.PROPERTY_H2_CODE))
		{
			return;
		}
		final String withoutComments = pSql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
		if (JdbcGuard.H2_CODE_SQL.matcher(withoutComments).find())
		{
			throw new ConnectorException("JDBC: the statement is refused: an in-memory H2 database runs inside the server, and this statement"
				+ " creates code or reads or writes a file (set the JVM property " + JdbcGuard.PROPERTY_H2_CODE + "=true to allow it)");
		}
	}
}
