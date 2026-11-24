package com.on.ebx.connect.connectors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/** Offline checks that connection probes only use JDBC metadata or bounded file listings. */
public final class ReadOnlyConnectionProbeTest
{
	private ReadOnlyConnectionProbeTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		testFileProbeUsesOnlyBoundedListing();
		testJdbcProbeUsesOnlyMetadata();
		System.out.println("PASS: JDBC and file connection probes are bounded and read-only");
	}

	private static void testFileProbeUsesOnlyBoundedListing() throws Exception
	{
		final ProbeFileConnector connector = new ProbeFileConnector();
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("root", "safe/root");
		connector.configure(config);
		final ConnectionTestResult result = connector.testConnection(null);
		check(result.isSuccessful(), "File connection probe did not succeed");
		check(connector.listed && connector.max == 1, "File probe was not bounded to one entry");
		check("safe/root/".equals(connector.prefix), "File probe did not respect configured root");
		check(!connector.read && !connector.wrote, "File probe read or wrote object content");
	}

	private static void testJdbcProbeUsesOnlyMetadata() throws Exception
	{
		final AtomicBoolean closed = new AtomicBoolean();
		final AtomicBoolean unexpected = new AtomicBoolean();
		final Driver driver = new MetadataOnlyDriver(closed, unexpected);
		DriverManager.registerDriver(driver);
		try
		{
			final JdbcConnector connector = new JdbcConnector();
			final ConnectorConfig config = new ConnectorConfig();
			config.setBaseUrl("jdbc:ebx-probe:test");
			connector.configure(config);
			final ConnectionTestResult result = connector.testConnection(null);
			check(result.isSuccessful(), "JDBC metadata probe did not succeed");
			check(result.getMessage().contains("Test Database 1.0"), "JDBC product metadata is absent");
			check(closed.get(), "JDBC probe did not close its connection");
			check(!unexpected.get(), "JDBC probe invoked a non-metadata connection method");
		}
		finally
		{
			DriverManager.deregisterDriver(driver);
		}
	}

	private static final class ProbeFileConnector extends AbstractFileConnector
	{
		boolean listed;
		boolean read;
		boolean wrote;
		String prefix;
		int max;

		public String getType() { return "probe-file"; }
		public String getLabel() { return "Probe file"; }
		protected byte[] readBytes(final String pPath) { this.read = true; return null; }
		protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType)
		{
			this.wrote = true;
		}
		protected List<Entry> listEntries(final String pPrefix, final int pMax)
		{
			this.listed = true;
			this.prefix = pPrefix;
			this.max = pMax;
			return Collections.emptyList();
		}
	}

	private static final class MetadataOnlyDriver implements Driver
	{
		private final AtomicBoolean closed;
		private final AtomicBoolean unexpected;

		MetadataOnlyDriver(final AtomicBoolean pClosed, final AtomicBoolean pUnexpected)
		{
			this.closed = pClosed;
			this.unexpected = pUnexpected;
		}

		public boolean acceptsURL(final String pUrl) { return pUrl.startsWith("jdbc:ebx-probe:"); }
		public Connection connect(final String pUrl, final Properties pInfo)
		{
			if (!this.acceptsURL(pUrl)) return null;
			final DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
				ReadOnlyConnectionProbeTest.class.getClassLoader(), new Class<?>[] { DatabaseMetaData.class },
				new DefaultHandler("getDatabaseProductName", "Test Database",
					"getDatabaseProductVersion", "1.0"));
			return (Connection) Proxy.newProxyInstance(ReadOnlyConnectionProbeTest.class.getClassLoader(),
				new Class<?>[] { Connection.class }, new InvocationHandler()
				{
					public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
					{
						if ("getMetaData".equals(pMethod.getName())) return metadata;
						if ("close".equals(pMethod.getName())) { closed.set(true); return null; }
						unexpected.set(true);
						return defaultValue(pMethod.getReturnType());
					}
				});
		}
		public DriverPropertyInfo[] getPropertyInfo(final String pUrl, final Properties pInfo) { return new DriverPropertyInfo[0]; }
		public int getMajorVersion() { return 1; }
		public int getMinorVersion() { return 0; }
		public boolean jdbcCompliant() { return false; }
		public Logger getParentLogger() { return Logger.getGlobal(); }
	}

	private static final class DefaultHandler implements InvocationHandler
	{
		private final String firstName;
		private final Object firstValue;
		private final String secondName;
		private final Object secondValue;
		DefaultHandler(final String pFirstName, final Object pFirstValue,
			final String pSecondName, final Object pSecondValue)
		{
			this.firstName = pFirstName;
			this.firstValue = pFirstValue;
			this.secondName = pSecondName;
			this.secondValue = pSecondValue;
		}
		public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
		{
			if (this.firstName.equals(pMethod.getName())) return this.firstValue;
			if (this.secondName.equals(pMethod.getName())) return this.secondValue;
			return defaultValue(pMethod.getReturnType());
		}
	}

	private static Object defaultValue(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return null;
		if (pType == Boolean.TYPE) return false;
		if (pType == Character.TYPE) return '\0';
		if (pType == Byte.TYPE) return (byte) 0;
		if (pType == Short.TYPE) return (short) 0;
		if (pType == Integer.TYPE) return 0;
		if (pType == Long.TYPE) return 0L;
		if (pType == Float.TYPE) return 0F;
		if (pType == Double.TYPE) return 0D;
		return null;
	}

	private static void check(final boolean pCondition, final String pMessage) throws ConnectorException
	{
		if (!pCondition) throw new ConnectorException(pMessage);
	}
}
