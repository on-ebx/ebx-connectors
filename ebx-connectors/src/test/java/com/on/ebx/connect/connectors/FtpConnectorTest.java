package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/** Offline FTP/FTPS mode, bounds, traversal, transport and redaction contract. */
public final class FtpConnectorTest
{
	private FtpConnectorTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final Fake fake = new Fake();
		final FtpConnector connector = new FtpConnector(c -> { fake.opened = c; return fake; });

		final ConnectorConfig defaultTls = config("files.example:21");
		check(connector.validateConfiguration(defaultTls).isValid(), "scheme-less secure default rejected before configure");
		connector.configure(defaultTls);
		check("ftps://files.example:21".equals(defaultTls.getBaseUrl()), "FTPS was not the scheme-less default");
		check(connector.validateConfiguration(defaultTls).isValid(), "default explicit FTPS rejected");
		check(connector.validateConfiguration(config("ftps-implicit://files.example:990")).isValid(), "implicit FTPS rejected");
		final ConnectorConfig clear = config("ftp://files.example:21");
		check(has(connector.validateConfiguration(clear), "allowCleartext"), "clear FTP accepted without opt-in");
		clear.setProperty("allowCleartext", "true"); check(connector.validateConfiguration(clear).isValid(), "explicit clear FTP opt-in rejected");
		check(has(connector.validateConfiguration(config("sftp://files.example:22")), "baseUrl"), "SFTP was conflated with FTP");

		final ConnectorConfig bounded = config("ftps://files.example:21"); bounded.setProperty("root", "governed");
		bounded.setProperty("max.list", "2"); bounded.setProperty("max.bytes", "16"); connector.configure(bounded);
		fake.entries = Arrays.asList(new AbstractFileConnector.Entry("governed/a.csv", 4, "2026-01-01T00:00:00Z"),
			new AbstractFileConnector.Entry("governed/b.csv", 5, "2026-01-01T00:00:00Z"));
		final JsonNode listed = connector.execute(null, "list", java.util.Collections.singletonMap("prefix", ""), null);
		check(listed.path("count").asInt() == 2 && fake.listMax == 2 && "governed/".equals(fake.prefix), "bounded rooted listing");

		final java.util.Map<String,String> write = new java.util.LinkedHashMap<String,String>(); write.put("path", "out/data.json"); write.put("format", "json");
		connector.execute(null, "write", write, Json.object().put("id", 1));
		check("governed/out/data.json".equals(fake.writtenPath) && fake.written.length <= 16, "bounded rooted write");

		try { connector.execute(null, "list", java.util.Collections.singletonMap("prefix", "../private"), null); throw new AssertionError("traversal accepted"); }
		catch (final IllegalArgumentException expected) { check(expected.getMessage().contains("traversal"), "traversal error"); }
		try { connector.execute(null, "list", java.util.Collections.singletonMap("prefix", "safe\r\nDELE secret"), null); throw new AssertionError("command injection accepted"); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains("Invalid FTP path"), "CRLF rejection"); }

		fake.failure = true;
		try { connector.execute(null, "list", java.util.Collections.singletonMap("prefix", ""), null); throw new AssertionError("transport failure ignored"); }
		catch (final ConnectorException expected) { check(!expected.getMessage().contains("TOP-SECRET"), "transport secret leaked"); }

		check("CLOUD_STORAGE".equals(connector.getDescriptor().getCategory()), "category");
		check(connector.getDescriptor().getCapabilities().contains(ConnectorCapability.DISCOVER_OBJECTS)
			&& connector.getDescriptor().getDescription().contains("SFTP is a separate"), "descriptor safety contract");
		System.out.println("PASS: FTP explicit/implicit TLS, cleartext opt-in, bounds, root traversal, CRLF, and redaction");
	}

	private static final class Fake implements FtpConnector.Transport
	{
		ConnectorConfig opened; List<AbstractFileConnector.Entry> entries=new ArrayList<AbstractFileConnector.Entry>();
		String prefix,writtenPath;int listMax;byte[] written;boolean failure;
		public byte[] read(final String p,final int m)throws Exception{if(failure)throw new Exception("TOP-SECRET");return null;}
		public void write(final String p,final byte[]b)throws Exception{if(failure)throw new Exception("TOP-SECRET");writtenPath=p;written=b;}
		public List<AbstractFileConnector.Entry> list(final String p,final int m)throws Exception{if(failure)throw new Exception("TOP-SECRET");prefix=p;listMax=m;return entries.subList(0,Math.min(m,entries.size()));}
		public void close(){}
	}
	private static ConnectorConfig config(final String pUrl){final ConnectorConfig c=new ConnectorConfig();c.setBaseUrl(pUrl);c.setUsername("user");c.setPassword("secret");return c;}
	private static boolean has(final ConfigurationValidationResult r,final String f){for(final ConfigurationValidationIssue i:r.getIssues())if(f.equals(i.getPropertyName()))return true;return false;}
	private static void check(final boolean v,final String m){if(!v)throw new AssertionError(m);}
}
