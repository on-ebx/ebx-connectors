package com.on.ebx.connect.connectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * SFTP through the JSch client packaged in {@code WEB-INF/lib/jsch.jar}. {@code baseUrl} = {@code sftp://host:22}; BASIC with
 * user / password, or property {@code privateKey} = path of an OpenSSH private key on the EBX
 * server (with the password as passphrase if any).
 * <p>
 * The key of the server is always verified, against the property {@code knownHosts} (path of a
 * known_hosts file on the EBX server) or {@code hostKey} (the public key itself, as
 * {@code ssh-keyscan} prints it; several keys may be given). With neither, the connector refuses
 * to connect: an unverified server can be anyone on the path, and it receives the password.
 * {@code strictHostKey=false} is the explicit way out, for a test server.
 */
public final class SftpConnector extends AbstractFileConnector
{
	@Override
	public String getType()
	{
		return "sftp";
	}

	@Override
	public String getLabel()
	{
		return "SFTP (files)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("CLOUD_STORAGE");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).authentication(com.on.ebx.connect.spi.AuthenticationMethod.SSH_KEY).capability(com.on.ebx.connect.spi.ConnectorCapability.DISCOVER_OBJECTS).capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS).apiVersion("SSH library-defined").dependency(new com.on.ebx.connect.spi.ConnectorDependency("JSch-compatible SSH client", null, true, "Optional connector runtime library")).pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null)).configurationProperty(property("knownHosts", com.on.ebx.connect.spi.ConfigurationPropertyType.FILE, false)).configurationProperty(property("hostKey", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("privateKey", com.on.ebx.connect.spi.ConfigurationPropertyType.FILE, false)).configurationProperty(property("strictHostKey", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, false));
		return b.build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c);
		for (final com.on.ebx.connect.spi.Operation op:getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String n,final com.on.ebx.connect.spi.ConfigurationPropertyType t,final boolean r){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,t).required(r).build();}

	private interface Action<T>
	{
		T run(ChannelSftp pChannel) throws Exception;
	}

	private <T> T withChannel(final Action<T> pAction) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		Session session = null;
		ChannelSftp channel = null;
		try
		{
			final URI uri = URI.create(cfg.getBaseUrl().contains("://") ? cfg.getBaseUrl() : "sftp://" + cfg.getBaseUrl());
			final JSch jsch = new JSch();
			final boolean verifyHostKey = SftpConnector.applyHostKeyPolicy(cfg, jsch);
			final String privateKey = cfg.getProperty("privateKey", null);
			if (privateKey != null)
			{
				if (cfg.getPassword() != null && !cfg.isPlaceholder(cfg.getPassword()))
				{
					jsch.addIdentity(privateKey, cfg.getPassword());
				}
				else
				{
					jsch.addIdentity(privateKey);
				}
			}
			if (cfg.getUsername() == null || cfg.getUsername().isEmpty())
			{
				throw new ConnectorException("SFTP: set the user name");
			}
			session = jsch.getSession(cfg.getUsername(), uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 22);
			if (privateKey == null)
			{
				if (cfg.isPlaceholder(cfg.getPassword()))
				{
					throw new ConnectorException("SFTP: set the password or the property privateKey");
				}
				session.setPassword(cfg.getPassword());
			}
			session.setConfig("StrictHostKeyChecking", verifyHostKey ? "yes" : "no");
			session.setConfig("PreferredAuthentications", privateKey == null ? "password,keyboard-interactive" : "publickey");
			session.setTimeout(cfg.getReadTimeoutMs());
			session.connect(cfg.getConnectTimeoutMs());
			channel = (ChannelSftp) session.openChannel("sftp");
			channel.connect(cfg.getConnectTimeoutMs());
			return pAction.run(channel);
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("SFTP: " + ex.getMessage(), ex);
		}
		finally
		{
			if (channel != null)
			{
				channel.disconnect();
			}
			if (session != null)
			{
				session.disconnect();
			}
		}
	}

	/** Connectors already reported as running without host key verification (one warning each per JVM). */
	private static final java.util.Set<String> UNVERIFIED = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	/** A public key in its SSH wire form, base64: every such blob starts with a four-byte length, hence "AAAA". */
	private static final java.util.regex.Pattern PUBLIC_KEY = java.util.regex.Pattern.compile("AAAA[A-Za-z0-9+/]+={0,2}");

	/**
	 * Gives the client what it verifies the server's key against and says whether it verifies at
	 * all. Refuses (the message names the properties to set) when nothing is configured and the
	 * connector did not opt out with {@code strictHostKey=false}.
	 */
	static boolean applyHostKeyPolicy(final ConnectorConfig pConfig, final JSch pJsch) throws ConnectorException
	{
		final String knownHosts = SftpConnector.configured(pConfig, "knownHosts");
		final String hostKey = SftpConnector.configured(pConfig, "hostKey");
		if ("false".equalsIgnoreCase(String.valueOf(pConfig.getProperty("strictHostKey", "true")).trim()))
		{
			if (SftpConnector.UNVERIFIED.add(String.valueOf(pConfig.getId())))
			{
				com.orchestranetworks.service.LoggingCategory.getKernel().warn("[connectors] SFTP connector '" + pConfig.getId()
						+ "': strictHostKey=false, the key of the server is NOT verified. Set knownHosts or hostKey and remove strictHostKey.");
			}
			return false;
		}
		if (knownHosts == null && hostKey == null)
		{
			throw new ConnectorException("SFTP: the key of the server cannot be verified. Set the property knownHosts (path of a known_hosts"
				+ " file on the EBX server that lists this host) or hostKey (the public key of the server, as printed by"
				+ " `ssh-keyscan <host>`). strictHostKey=false connects without verification: for a test server only");
		}
		if (hostKey != null)
		{
			// the pinned keys are the whole truth for this connector: a known_hosts file is not consulted, and never written
			pJsch.setHostKeyRepository(new PinnedHostKeys(SftpConnector.publicKeys(hostKey)));
			return true;
		}
		final java.io.File file = new java.io.File(knownHosts);
		if (!file.isFile() || !file.canRead())
		{
			throw new ConnectorException("SFTP: the known_hosts file of the property knownHosts does not exist or cannot be read on the EBX server");
		}
		try
		{
			pJsch.setKnownHosts(knownHosts);
		}
		catch (final com.jcraft.jsch.JSchException ex)
		{
			throw new ConnectorException("SFTP: the known_hosts file of the property knownHosts cannot be read: " + ex.getMessage(), ex);
		}
		return true;
	}

	private static String configured(final ConnectorConfig pConfig, final String pProperty)
	{
		final String value = pConfig.getProperty(pProperty, null);
		return value == null || value.trim().isEmpty() || pConfig.isPlaceholder(value) ? null : value.trim();
	}

	/** The keys of a {@code hostKey} value: the base64 blobs of one or several {@code [host] <type> <base64>} entries. */
	static java.util.List<byte[]> publicKeys(final String pHostKey) throws ConnectorException
	{
		final java.util.List<byte[]> keys = new ArrayList<byte[]>();
		final java.util.regex.Matcher m = SftpConnector.PUBLIC_KEY.matcher(pHostKey);
		while (m.find())
		{
			try
			{
				keys.add(java.util.Base64.getDecoder().decode(m.group()));
			}
			catch (final IllegalArgumentException ex)
			{
				throw new ConnectorException("SFTP: the property hostKey holds a public key that is not valid base64");
			}
		}
		if (keys.isEmpty())
		{
			throw new ConnectorException("SFTP: the property hostKey holds no public key; expected the output of `ssh-keyscan <host>`,"
				+ " for example \"ssh-rsa AAAAB3Nza...\"");
		}
		return keys;
	}

	/** Accepts the server only when the key it presents is one of the pinned ones; holds nothing else and stores nothing. */
	static final class PinnedHostKeys implements com.jcraft.jsch.HostKeyRepository
	{
		private final java.util.List<byte[]> keys;

		PinnedHostKeys(final java.util.List<byte[]> pKeys)
		{
			this.keys = pKeys;
		}

		@Override
		public int check(final String pHost, final byte[] pKey)
		{
			for (final byte[] key : this.keys)
			{
				if (pKey != null && java.security.MessageDigest.isEqual(key, pKey))
				{
					return com.jcraft.jsch.HostKeyRepository.OK;
				}
			}
			// CHANGED, not NOT_INCLUDED: the client then refuses without offering to learn the key
			return com.jcraft.jsch.HostKeyRepository.CHANGED;
		}

		@Override
		public void add(final com.jcraft.jsch.HostKey pHostKey, final com.jcraft.jsch.UserInfo pUserInfo)
		{
			// the pinned keys come from the connector configuration only
		}

		@Override
		public void remove(final String pHost, final String pType)
		{
		}

		@Override
		public void remove(final String pHost, final String pType, final byte[] pKey)
		{
		}

		@Override
		public String getKnownHostsRepositoryID()
		{
			return "property hostKey";
		}

		@Override
		public com.jcraft.jsch.HostKey[] getHostKey()
		{
			return new com.jcraft.jsch.HostKey[0];
		}

		@Override
		public com.jcraft.jsch.HostKey[] getHostKey(final String pHost, final String pType)
		{
			return new com.jcraft.jsch.HostKey[0];
		}
	}

	@Override
	protected byte[] readBytes(final String pPath) throws ConnectorException
	{
		return this.withChannel(new Action<byte[]>()
		{
			@Override
			public byte[] run(final ChannelSftp pChannel) throws Exception
			{
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				try
				{
					pChannel.get(pPath, out);
				}
				catch (final SftpException ex)
				{
					if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE)
					{
						return null;
					}
					throw ex;
				}
				return out.toByteArray();
			}
		});
	}

	@Override
	protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		this.withChannel(new Action<Void>()
		{
			@Override
			public Void run(final ChannelSftp pChannel) throws Exception
			{
				final int slash = pPath.lastIndexOf('/');
				if (slash > 0)
				{
					SftpConnector.mkdirs(pChannel, pPath.substring(0, slash));
				}
				pChannel.put(new ByteArrayInputStream(pContent), pPath, ChannelSftp.OVERWRITE);
				return null;
			}
		});
	}

	private static void mkdirs(final ChannelSftp pChannel, final String pDirectory) throws SftpException
	{
		try
		{
			pChannel.stat(pDirectory);
			return;
		}
		catch (final SftpException ex)
		{
			if (ex.id != ChannelSftp.SSH_FX_NO_SUCH_FILE)
			{
				throw ex;
			}
		}
		final int slash = pDirectory.lastIndexOf('/');
		if (slash > 0)
		{
			SftpConnector.mkdirs(pChannel, pDirectory.substring(0, slash));
		}
		pChannel.mkdir(pDirectory);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		return this.withChannel(new Action<List<Entry>>()
		{
			@Override
			public List<Entry> run(final ChannelSftp pChannel) throws Exception
			{
				final List<Entry> entries = new ArrayList<Entry>();
				String directory = pPrefix.isEmpty() ? "." : pPrefix;
				String namePrefix = "";
				try
				{
					if (!pChannel.stat(directory).isDir())
					{
						throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "not a directory");
					}
				}
				catch (final SftpException ex)
				{
					final int slash = pPrefix.lastIndexOf('/');
					directory = slash > 0 ? pPrefix.substring(0, slash) : ".";
					namePrefix = pPrefix.substring(slash + 1);
				}
				final Vector<ChannelSftp.LsEntry> list = pChannel.ls(directory);
				for (final ChannelSftp.LsEntry entry : list)
				{
					if (entry.getAttrs().isDir() || !entry.getFilename().startsWith(namePrefix) || entries.size() >= pMax)
					{
						continue;
					}
					entries.add(new Entry((".".equals(directory) ? "" : directory + "/") + entry.getFilename(), entry.getAttrs().getSize(),
						java.time.Instant.ofEpochSecond(entry.getAttrs().getMTime()).toString()));
				}
				return entries;
			}
		});
	}
}
