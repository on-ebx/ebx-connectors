package com.on.ebx.connect.connectors;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.List;

import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The SFTP connector decides before any connection whether it can verify the server: refusal
 * without known_hosts file nor pinned key, explicit opt-out, pinned keys compared byte for byte.
 * No SSH server here: what the client does with the repository during a handshake is JSch's.
 */
public final class SftpHostKeyPolicyTest
{
	private SftpHostKeyPolicyTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final ServerSocket reserved = new ServerSocket(0);
		final int closedPort = reserved.getLocalPort();
		reserved.close();
		final String rsa = SftpHostKeyPolicyTest.keyBlob("ssh-rsa", 1);
		final String ecdsa = SftpHostKeyPolicyTest.keyBlob("ecdsa-sha2-nistp256", 2);
		final String other = SftpHostKeyPolicyTest.keyBlob("ssh-rsa", 3);

		// nothing to verify against: refused, and the message says what to set
		final String refusal = SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, null, null, null));
		SftpHostKeyPolicyTest.check(refusal.contains("cannot be verified") && refusal.contains("knownHosts") && refusal.contains("hostKey")
			&& refusal.contains("strictHostKey=false"), "refusal does not say what to configure: " + refusal);
		// the placeholder of a seeded connector is "not set", and strictHostKey=true changes nothing
		SftpHostKeyPolicyTest.check(SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, "TO_BE_SET", null, "true"))
			.contains("cannot be verified"), "a placeholder counted as a known_hosts file");

		// the explicit opt-out goes on to the network (nobody listens there)
		final String optedOut = SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, null, null, "false"));
		SftpHostKeyPolicyTest.check(!optedOut.contains("cannot be verified"), "strictHostKey=false did not opt out: " + optedOut);
		SftpHostKeyPolicyTest.check(!SftpConnector.applyHostKeyPolicy(SftpHostKeyPolicyTest.config(closedPort, null, null, "FALSE"), new JSch()),
			"strictHostKey=false still verifies");

		// a pinned key: verification on, the client is given a repository that knows these keys only
		final JSch pinned = new JSch();
		SftpHostKeyPolicyTest.check(SftpConnector.applyHostKeyPolicy(
			SftpHostKeyPolicyTest.config(closedPort, null, "sftp.example.org ssh-rsa " + rsa + "\nsftp.example.org ecdsa-sha2-nistp256 " + ecdsa, null), pinned),
			"a pinned key does not turn verification on");
		final HostKeyRepository repository = pinned.getHostKeyRepository();
		SftpHostKeyPolicyTest.check(repository instanceof SftpConnector.PinnedHostKeys, "the pinned keys are not the client's repository");
		SftpHostKeyPolicyTest.check(repository.check("sftp.example.org", Base64.getDecoder().decode(rsa)) == HostKeyRepository.OK, "pinned RSA key refused");
		SftpHostKeyPolicyTest.check(repository.check("[sftp.example.org]:2222", Base64.getDecoder().decode(ecdsa)) == HostKeyRepository.OK, "pinned ECDSA key refused");
		SftpHostKeyPolicyTest.check(repository.check("sftp.example.org", Base64.getDecoder().decode(other)) == HostKeyRepository.CHANGED, "another key accepted");
		SftpHostKeyPolicyTest.check(repository.check("sftp.example.org", null) == HostKeyRepository.CHANGED, "an absent key accepted");
		final String withPin = SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, null, "ssh-rsa " + rsa, null));
		SftpHostKeyPolicyTest.check(!withPin.contains("cannot be verified"), "a pinned key did not let the connection start: " + withPin);

		final List<byte[]> parsed = SftpConnector.publicKeys(rsa);
		SftpHostKeyPolicyTest.check(parsed.size() == 1 && java.util.Arrays.equals(parsed.get(0), Base64.getDecoder().decode(rsa)), "a bare base64 key is not read");
		SftpHostKeyPolicyTest.check(SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, null, "SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8", null))
			.contains("holds no public key"), "a fingerprint was taken for a key");

		// a known_hosts file: it must exist; when it does, verification is on
		SftpHostKeyPolicyTest.check(SftpHostKeyPolicyTest.failure(SftpHostKeyPolicyTest.connector(closedPort, "/nonexistent/known_hosts", null, null))
			.contains("does not exist or cannot be read"), "a missing known_hosts file was accepted");
		final File knownHosts = File.createTempFile("ebx-connectors-known-hosts", ".txt");
		try
		{
			final FileOutputStream out = new FileOutputStream(knownHosts);
			try
			{
				out.write(("127.0.0.1 ssh-rsa " + rsa + "\n").getBytes("UTF-8"));
			}
			finally
			{
				out.close();
			}
			SftpHostKeyPolicyTest.check(SftpConnector.applyHostKeyPolicy(SftpHostKeyPolicyTest.config(closedPort, knownHosts.getPath(), null, null), new JSch()),
				"a known_hosts file does not turn verification on");
			SftpHostKeyPolicyTest.check(!SftpConnector.applyHostKeyPolicy(SftpHostKeyPolicyTest.config(closedPort, knownHosts.getPath(), null, "false"), new JSch()),
				"strictHostKey=false ignored next to a known_hosts file");
		}
		finally
		{
			knownHosts.delete();
		}
		System.out.println("PASS: SFTP refuses to connect without known_hosts or pinned host key unless strictHostKey=false");
	}

	/** An SSH public key blob: length-prefixed type name, then key material (arbitrary here). */
	private static String keyBlob(final String pType, final int pSeed) throws Exception
	{
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] type = pType.getBytes("US-ASCII");
		out.write(new byte[] { 0, 0, 0, (byte) type.length });
		out.write(type);
		for (int i = 0; i < 64; i++)
		{
			out.write((i * 31 + pSeed * 17) & 0xff);
		}
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	private static ConnectorConfig config(final int pPort, final String pKnownHosts, final String pHostKey, final String pStrict)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("sftp-test");
		config.setBaseUrl("sftp://127.0.0.1:" + pPort);
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("ebx");
		config.setPassword("not-a-real-password");
		config.setConnectTimeoutMs(1500);
		config.setReadTimeoutMs(1500);
		if (pKnownHosts != null)
		{
			config.setProperty("knownHosts", pKnownHosts);
		}
		if (pHostKey != null)
		{
			config.setProperty("hostKey", pHostKey);
		}
		if (pStrict != null)
		{
			config.setProperty("strictHostKey", pStrict);
		}
		return config;
	}

	private static SftpConnector connector(final int pPort, final String pKnownHosts, final String pHostKey, final String pStrict)
	{
		final SftpConnector connector = new SftpConnector();
		connector.configure(SftpHostKeyPolicyTest.config(pPort, pKnownHosts, pHostKey, pStrict));
		return connector;
	}

	/** The message of the failure a listing ends with: every case here fails, by refusal or because nobody listens. */
	private static String failure(final SftpConnector pConnector)
	{
		try
		{
			pConnector.listEntries("", 1);
		}
		catch (final ConnectorException expected)
		{
			SftpHostKeyPolicyTest.check(!String.valueOf(expected.getMessage()).contains("not-a-real-password"), "the password is in the error");
			return String.valueOf(expected.getMessage());
		}
		throw new AssertionError("a listing succeeded without any SFTP server");
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}
}
