package com.on.ebx.connect.core;

import java.io.InputStream;
import java.util.Properties;

import com.on.ebx.connect.spi.ConnectorException;

/** The frequent failures are told as a cause and a next action, in both languages, without anything taken from the failure text (audit C-38). */
public final class ErrorCatalogTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// the refusals the module decides itself name the property to set
		final ErrorCatalog.Entry priv = ErrorCatalog.classify(new IllegalStateException("outbound call to db.internal refused: the address is private and "
			+ Egress.BLOCK_PRIVATE_PROPERTY + " is on (to allow it, add the host to -D" + Egress.PRIVATE_EXCEPTIONS_PROPERTY + ")"));
		check(priv.getKind() == ErrorCatalog.Kind.PRIVATE_ADDRESS && priv.getProperties().equals(Egress.PRIVATE_EXCEPTIONS_PROPERTY), "private address: " + priv.getKind());
		final ErrorCatalog.Entry notAllowed = ErrorCatalog.classify("outbound call to api.example.com refused: not in " + Egress.PROPERTY + " (a.com,b.com)");
		check(notAllowed.getKind() == ErrorCatalog.Kind.HOST_NOT_ALLOWED && notAllowed.getProperties().equals(Egress.PROPERTY), "host not allowed: " + notAllowed.getKind());
		final ErrorCatalog.Entry clear = ErrorCatalog.classify("property apiToken holds a secret stored in clear text, which is refused because " + Secrets.ALLOW_CLEAR_PROPERTY + " is not true");
		check(clear.getKind() == ErrorCatalog.Kind.CLEAR_SECRET && clear.getProperties().equals(Secrets.ALLOW_CLEAR_PROPERTY), "clear secret: " + clear.getKind());
		check(ErrorCatalog.classify(new MappingAlreadyRunningException(MappingRunLocks.refusal(7, "Companies", "R1"))).getKind() == ErrorCatalog.Kind.RUN_ALREADY_RUNNING, "already running by type");
		check(ErrorCatalog.classify(MappingRunLocks.refusal(7, "Companies", "R1")).getKind() == ErrorCatalog.Kind.RUN_ALREADY_RUNNING, "already running by text");
		final ErrorCatalog.Entry many = ErrorCatalog.classify(new TooManyRunsException("Too many asynchronous runs: 4 may run"));
		check(many.getKind() == ErrorCatalog.Kind.TOO_MANY_RUNS && many.getProperties().contains(ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY), "too many runs");

		// the network, by type and by text; a private-address refusal that mentions a host that does not resolve is a refusal, not an unknown host
		check(ErrorCatalog.classify(new java.net.ConnectException("Connection refused (Connection refused)")).getKind() == ErrorCatalog.Kind.CONNECTION_REFUSED, "refused by type");
		check(ErrorCatalog.classify(new java.net.ConnectException("Connection timed out")).getKind() == ErrorCatalog.Kind.TIMEOUT, "connect timeout");
		check(ErrorCatalog.classify(new RuntimeException("sync failed", new java.net.SocketTimeoutException("Read timed out"))).getKind() == ErrorCatalog.Kind.TIMEOUT, "timeout under a wrapper");
		check(ErrorCatalog.classify(new java.net.UnknownHostException("nowhere.example")).getKind() == ErrorCatalog.Kind.HOST_UNKNOWN, "unknown host by type");
		check(ErrorCatalog.classify(new RuntimeException("outbound call to x refused: the host does not resolve and " + Egress.BLOCK_PRIVATE_PROPERTY + " is on",
			new java.net.UnknownHostException("x"))).getKind() == ErrorCatalog.Kind.PRIVATE_ADDRESS, "the refusal wins over its cause");
		check(ErrorCatalog.classify("Timeout when sending a GET request using REST configuration 'GLEIF'").getKind() == ErrorCatalog.Kind.TIMEOUT, "the EBX timeout text");

		// credentials, rate, mapping
		check(ErrorCatalog.classify(new ConnectorException("Cosmos request failed (HTTP 401)", 401, null)).getKind() == ErrorCatalog.Kind.AUTH_REFUSED, "401 by status");
		check(ErrorCatalog.classify("HTTP endpoint responded with status 403").getKind() == ErrorCatalog.Kind.AUTH_REFUSED, "403 by text");
		check(ErrorCatalog.classify("FATAL: password authentication failed for user x").getKind() == ErrorCatalog.Kind.AUTH_REFUSED, "database login");
		check(ErrorCatalog.classify(new ConnectorException("busy", 429, null)).getKind() == ErrorCatalog.Kind.RATE_LIMITED, "429");
		check(ErrorCatalog.classify("Incremental load requires a checkpoint strategy").getKind() == ErrorCatalog.Kind.MAPPING_INVALID, "mapping invalid");
		check(ErrorCatalog.classify("Mapping direction INBOUND is not compatible with jdbc/query").getKind() == ErrorCatalog.Kind.MAPPING_INVALID, "direction");

		// nothing else is guessed
		check(ErrorCatalog.classify("Value must not exceed 10 character(s)").getKind() == ErrorCatalog.Kind.UNKNOWN, "an ordinary message stays unknown");
		check(ErrorCatalog.classify((String) null).getKind() == ErrorCatalog.Kind.UNKNOWN && ErrorCatalog.classify((Throwable) null).getKind() == ErrorCatalog.Kind.UNKNOWN, "nothing is unknown");

		// nothing of the failure text reaches the entry: a secret quoted in a message is not an argument
		final ErrorCatalog.Entry quoting = ErrorCatalog.classify("GET https://api.example.com/v1?api_key=SECRETVALUE123 answered HTTP 401");
		check(quoting.getKind() == ErrorCatalog.Kind.AUTH_REFUSED && !quoting.getProperties().contains("SECRET") && !quoting.causeKey().contains("SECRET"), "no value leaks");

		// every kind has its cause and its action, in both languages
		for (final String language : new String[] { "en", "fr" })
		{
			final Properties bundle = new Properties();
			try (InputStream in = ErrorCatalogTest.class.getResourceAsStream("/com/on/ebx/connect/ebx/ui/Messages_" + language + ".properties"))
			{
				check(in != null, "bundle " + language);
				bundle.load(in);
			}
			for (final ErrorCatalog.Kind kind : ErrorCatalog.Kind.values())
			{
				final ErrorCatalog.Entry entry = ErrorCatalog.of(kind);
				check(bundle.getProperty(entry.causeKey(), "").trim().length() > 10, language + " cause of " + kind);
				check(bundle.getProperty(entry.actionKey(), "").trim().length() > 10, language + " action of " + kind);
			}
		}
		System.out.println("PASS: the frequent failures have a cause and a next action in en and fr, from fixed text only");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
