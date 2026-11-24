package com.on.ebx.connect.connectors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** FTP and TLS-protected FTPS file connector. This is not SSH/SFTP. */
public final class FtpConnector extends AbstractFileConnector
{
	interface Transport extends AutoCloseable
	{
		byte[] read(String pPath, int pMaxBytes) throws Exception;
		void write(String pPath, byte[] pContent) throws Exception;
		List<Entry> list(String pPrefix, int pMax) throws Exception;
		void close() throws Exception;
	}

	interface Factory { Transport open(ConnectorConfig pConfig) throws Exception; }

	private static final int MAX_LIST = 10000;
	private static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
	private final Factory factory;

	public FtpConnector() { this(new SocketFactory()); }
	FtpConnector(final Factory pFactory) { this.factory = pFactory; }

	@Override public String getType() { return "ftp"; }
	@Override public String getLabel() { return "FTP / FTPS (files)"; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getBaseUrl() != null && pConfig.getBaseUrl().indexOf("://") < 0)
			pConfig.setBaseUrl("ftps://" + pConfig.getBaseUrl());
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null) return new ConfigurationValidationResult(issues);
		final String url = pConfig.getBaseUrl();
		URI uri = null;
		try { uri = url == null ? null : URI.create(url.indexOf("://") < 0 ? "ftps://" + url : url); }
		catch (final RuntimeException ignored) { }
		if (uri == null || uri.getHost() == null || !("ftps".equalsIgnoreCase(uri.getScheme())
			|| "ftps-implicit".equalsIgnoreCase(uri.getScheme()) || "ftp".equalsIgnoreCase(uri.getScheme())))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Use ftps://host for explicit TLS, ftps-implicit://host, or explicitly permitted ftp://host"));
		if (uri != null && "ftp".equalsIgnoreCase(uri.getScheme())
			&& !Boolean.parseBoolean(pConfig.getProperty("allowCleartext", "false")))
			issues.add(new ConfigurationValidationIssue("allowCleartext", "Clear FTP is refused unless allowCleartext=true is explicitly recorded"));
		bounded(pConfig, "max.list", 1, MAX_LIST, issues);
		bounded(pConfig, "max.bytes", 1, 100 * 1024 * 1024, issues);
		if (pConfig.getConnectTimeoutMs() <= 0) issues.add(new ConfigurationValidationIssue("connectTimeoutMs", "Connection timeout must be positive"));
		if (pConfig.getReadTimeoutMs() <= 0) issues.add(new ConfigurationValidationIssue("readTimeoutMs", "Read timeout must be positive"));
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "CLOUD_STORAGE")
			.description("Bounded FTP file exchange with explicit or implicit FTPS. Clear FTP requires an explicit opt-in; SFTP is a separate SSH connector.")
			.authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS)
			.apiVersion("FTP/FTPS (RFC 959, AUTH TLS, protected passive data channels)")
			.dependency(new ConnectorDependency("Java TLS and socket runtime", null, true,
				"Uses the JVM trust store with endpoint identification; no SSH/SFTP library"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, MAX_LIST, null))
			.configurationProperty(property("root", ConfigurationPropertyType.STRING))
			.configurationProperty(property("allowCleartext", ConfigurationPropertyType.BOOLEAN))
			.configurationProperty(property("max.list", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("max.bytes", ConfigurationPropertyType.INTEGER));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			builder.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return builder.build();
	}

	@Override protected byte[] readBytes(final String pPath) throws ConnectorException
	{
		final int max = boundedInt(getConfig().getProperty("max.bytes", null), 1, 100 * 1024 * 1024, DEFAULT_MAX_BYTES);
		final String path = safeCommandPath(pPath);
		return withTransport(new Action<byte[]>()
		{
			public byte[] run(final Transport t) throws Exception
			{
				return t.read(path, max);
			}
		});
	}
	@Override protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		final int max = boundedInt(getConfig().getProperty("max.bytes", null), 1, 100 * 1024 * 1024, DEFAULT_MAX_BYTES);
		if (pContent == null || pContent.length > max) throw new ConnectorException("FTP write exceeds the configured byte limit");
		final String path = safeCommandPath(pPath);
		withTransport(new Action<Void>()
		{
			public Void run(final Transport t) throws Exception
			{
				t.write(path, pContent);
				return null;
			}
		});
	}
	@Override protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		final int configured = boundedInt(getConfig().getProperty("max.list", null), 1, MAX_LIST, 1000);
		final int max = Math.min(Math.max(1, pMax), configured);
		final String prefix = safeCommandPath(pPrefix);
		return withTransport(new Action<List<Entry>>()
		{
			public List<Entry> run(final Transport t) throws Exception
			{
				return t.list(prefix, max);
			}
		});
	}

	private interface Action<T> { T run(Transport pTransport) throws Exception; }
	private <T> T withTransport(final Action<T> pAction) throws ConnectorException
	{
		Transport transport = null;
		try
		{
			transport = this.factory.open(getConfig());
			return pAction.run(transport);
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("FTP/FTPS operation failed", ex); }
		finally { if (transport != null) try { transport.close(); } catch (final Exception ignored) { } }
	}

	private static int boundedInt(final String pValue, final int pMin, final int pMax, final int pDefault)
	{
		try
		{
			return Math.max(pMin, Math.min(pMax, Integer.parseInt(pValue)));
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}
	private static String safeCommandPath(final String pPath) throws ConnectorException
	{
		if (pPath == null || pPath.indexOf('\r') >= 0 || pPath.indexOf('\n') >= 0) throw new ConnectorException("Invalid FTP path");
		return pPath;
	}
	private static void bounded(final ConnectorConfig c, final String n, final int min, final int max, final List<ConfigurationValidationIssue> out)
	{
		final String v = c.getProperty(n, null);
		if (v == null) return;
		try
		{
			final long x = Long.parseLong(v);
			if (x < min || x > max) out.add(new ConfigurationValidationIssue(n, "Must be between "+min+" and "+max));
		}
		catch (final NumberFormatException ex)
		{
			out.add(new ConfigurationValidationIssue(n, "Must be an integer"));
		}
	}
	private static ConfigurationPropertyDefinition property(final String n, final ConfigurationPropertyType t)
	{ return ConfigurationPropertyDefinition.builder(n,t).required(false).build(); }

	private static final class SocketFactory implements Factory
	{ public Transport open(final ConnectorConfig pConfig) throws Exception { return new SocketTransport(pConfig); } }

	private static final class SocketTransport implements Transport
	{
		private final ConnectorConfig config;
		private final URI uri;
		private final boolean tls;
		private Socket socket;
		private java.io.BufferedReader reader;
		private java.io.BufferedWriter writer;

		SocketTransport(final ConnectorConfig pConfig) throws Exception
		{
			this.config = pConfig;
			this.uri = URI.create(pConfig.getBaseUrl());
			this.tls = !"ftp".equalsIgnoreCase(this.uri.getScheme());
			if (!this.tls && !Boolean.parseBoolean(pConfig.getProperty("allowCleartext", "false")))
				throw new ConnectorException("Clear FTP requires allowCleartext=true");
			final boolean implicit = "ftps-implicit".equalsIgnoreCase(this.uri.getScheme());
			this.socket = new Socket();
			this.socket.connect(new InetSocketAddress(this.uri.getHost(), port(implicit ? 990 : 21)), pConfig.getConnectTimeoutMs());
			this.socket.setSoTimeout(pConfig.getReadTimeoutMs());
			if (implicit) this.socket = tlsSocket(this.socket, this.uri.getHost(), port(990));
			streams();
			expect(readReply(), 220);
			if (this.tls && !implicit)
			{
				command("AUTH TLS", 234);
				this.socket = tlsSocket(this.socket, this.uri.getHost(), port(21));
				streams();
			}
			login();
			command("TYPE I", 200);
			if (this.tls)
			{
				command("PBSZ 0", 200);
				command("PROT P", 200);
			}
		}

		private int port(final int pDefault) { return this.uri.getPort() > 0 ? this.uri.getPort() : pDefault; }
		private void streams() throws Exception
		{
			this.reader = new java.io.BufferedReader(new java.io.InputStreamReader(this.socket.getInputStream(), StandardCharsets.US_ASCII));
			this.writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(this.socket.getOutputStream(), StandardCharsets.US_ASCII));
		}
		private void login() throws Exception
		{
			if (this.config.getUsername() == null || this.config.getUsername().isEmpty()) throw new ConnectorException("FTP user name is required");
			final int user = send("USER " + this.config.getUsername());
			if (user == 331) command("PASS " + (this.config.getPassword() == null ? "" : this.config.getPassword()), 230);
			else expect(user, 230);
		}
		private int send(final String pCommand) throws Exception
		{
			this.writer.write(pCommand);
			this.writer.write("\r\n");
			this.writer.flush();
			return readReply();
		}
		private void command(final String pCommand, final int pExpected) throws Exception { expect(send(pCommand), pExpected); }
		private static void expect(final int pActual, final int pExpected) throws ConnectorException
		{ if (pActual != pExpected) throw new ConnectorException("FTP command failed (code " + pActual + ")"); }
		private int readReply() throws Exception
		{
			String line = this.reader.readLine();
			if (line == null || line.length() < 3) throw new ConnectorException("FTP control connection closed");
			final int code;
			try
			{
				code = Integer.parseInt(line.substring(0, 3));
			}
			catch (final NumberFormatException ex)
			{
				throw new ConnectorException("Invalid FTP reply");
			}
			if (line.length() > 3 && line.charAt(3) == '-')
			{
				final String end = String.valueOf(code) + ' ';
				do
				{
					line = this.reader.readLine();
				}
				while (line != null && !line.startsWith(end));
			}
			return code;
		}
		private Socket passive(final String pCommand) throws Exception
		{
			this.writer.write("EPSV\r\n");
			this.writer.flush();
			String line = this.reader.readLine();
			if (line == null || !line.startsWith("229")) throw new ConnectorException("FTP passive mode failed");
			final int close=line.lastIndexOf('|'), open=close<0?-1:line.lastIndexOf('|',close-1);
			if(open<0||close<=open+1)throw new ConnectorException("Invalid FTP passive reply");
			final int dataPort=Integer.parseInt(line.substring(open+1,close));
			Socket data = new Socket();
			data.connect(new InetSocketAddress(this.uri.getHost(), dataPort), this.config.getConnectTimeoutMs());
			data.setSoTimeout(this.config.getReadTimeoutMs());
			if(this.tls)data=tlsSocket(data,this.uri.getHost(),dataPort);
			this.writer.write(pCommand);
			this.writer.write("\r\n");
			this.writer.flush();
			final int start = readReply();
			if (start != 125 && start != 150)
			{
				data.close();
				throw new ConnectorException("FTP transfer refused (code "+start+")");
			}
			return data;
		}
		public byte[] read(final String pPath, final int pMaxBytes) throws Exception
		{
			final Socket data = passive("RETR "+pPath);
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final InputStream in = data.getInputStream();
			final byte[] buffer = new byte[8192];
			int n, total = 0;
			while ((n = in.read(buffer)) >= 0)
			{
				total += n;
				if (total > pMaxBytes)
				{
					data.close();
					throw new ConnectorException("FTP read exceeds the configured byte limit");
				}
				out.write(buffer, 0, n);
			}
			data.close();
			expect(readReply(), 226);
			return out.toByteArray();
		}
		public void write(final String pPath, final byte[] pContent) throws Exception
		{
			final Socket data = passive("STOR "+pPath);
			final OutputStream out = data.getOutputStream();
			out.write(pContent);
			out.flush();
			data.close();
			expect(readReply(), 226);
		}
		public List<Entry> list(final String pPrefix, final int pMax) throws Exception
		{
			final Socket data = passive("MLSD "+(pPrefix.isEmpty() ? "." : pPrefix));
			final java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8));
			final List<Entry> out = new ArrayList<Entry>();
			String line;
			while (out.size() < pMax && (line = lines.readLine()) != null)
			{
				final int space = line.indexOf(' ');
				if (space < 0) continue;
				final String facts = line.substring(0, space), name = line.substring(space+1).trim();
				if (name.isEmpty() || facts.toLowerCase().contains("type=dir")) continue;
				long size = 0;
				String modified = "";
				for (final String fact:facts.split(";"))
				{
					if (fact.startsWith("size=")) try
					{
						size = Long.parseLong(fact.substring(5));
					}
					catch (final RuntimeException ignored) { }
					if (fact.startsWith("modify=")) modified = fact.substring(7);
				}
				out.add(new Entry((pPrefix.isEmpty() ? "" : pPrefix+"/")+name, size, modified));
			}
			data.close();
			expect(readReply(), 226);
			return out;
		}
		public void close() throws Exception
		{
			try
			{
				if (this.writer != null)
				{
					this.writer.write("QUIT\r\n");
					this.writer.flush();
				}
			}
			finally
			{
				if (this.socket != null) this.socket.close();
			}
		}
		private static SSLSocket tlsSocket(final Socket pSocket, final String pHost, final int pPort) throws Exception
		{
			final SSLSocketFactory factory=SSLContext.getDefault().getSocketFactory();
			final SSLSocket ssl=(SSLSocket)factory.createSocket(pSocket,pHost,pPort,true);
			final List<String> protocols=new ArrayList<String>();
			for(final String protocol:ssl.getSupportedProtocols())if("TLSv1.3".equals(protocol)||"TLSv1.2".equals(protocol))protocols.add(protocol);
			if(protocols.isEmpty())throw new ConnectorException("FTPS requires TLS 1.2 or newer");
			ssl.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
			final SSLParameters params = ssl.getSSLParameters();
			params.setEndpointIdentificationAlgorithm("HTTPS");
			ssl.setSSLParameters(params);
			ssl.startHandshake();
			return ssl;
		}
	}
}
