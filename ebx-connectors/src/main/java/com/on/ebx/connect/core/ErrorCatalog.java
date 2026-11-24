package com.on.ebx.connect.core;

import java.util.Locale;

import com.on.ebx.connect.spi.ConnectorException;

/**
 * The frequent failures of a run or a connection test, told as a cause and a next action instead of the raw text of the
 * exception (audit C-38). Pure logic, no locale: {@link #classify} answers a {@link Kind} and the names of the JVM
 * properties the next action mentions; the screens turn the kind into a sentence in the reader's language
 * ({@code error.<KIND>.cause} and {@code error.<KIND>.action} of the UI bundles).
 * <p>
 * Nothing the catalogue answers comes from the failure text but the kind: the arguments are fixed property names, so the
 * cause and the action can never carry a secret, a host or a record value, whatever the exception quoted.
 */
public final class ErrorCatalog
{
	/** What went wrong, from the most specific kind to {@link #UNKNOWN}. */
	public enum Kind
	{
		/** The address is private, loopback or unresolvable and the instance refuses those by default. */
		PRIVATE_ADDRESS,
		/** The host is not in the allow-list of outbound hosts. */
		HOST_NOT_ALLOWED,
		/** A secret is stored in clear text and the instance refuses that by default. */
		CLEAR_SECRET,
		/** The session lacks the Connectors right the action asks (the module's own refusal, not a remote system's). */
		ACCESS_DENIED,
		/** Another run of the same mapping is going on. */
		RUN_ALREADY_RUNNING,
		/** As many asynchronous runs as the instance allows are running or waiting. */
		TOO_MANY_RUNS,
		/** The remote system accepted no connection on that address. */
		CONNECTION_REFUSED,
		/** The host name does not resolve. */
		HOST_UNKNOWN,
		/** The remote system did not answer in time. */
		TIMEOUT,
		/** The remote system refused the credentials or the rights of the connection. */
		AUTH_REFUSED,
		/** The remote system asked to slow down. */
		RATE_LIMITED,
		/** The mapping asks something the connection or its operation cannot do. */
		MAPPING_INVALID,
		/** Not a frequent failure: the screens show the technical detail and say where the full trace is. */
		UNKNOWN
	}

	/** The kind of a failure and the property names its action mentions (none for most kinds). */
	public static final class Entry
	{
		private final Kind kind;
		private final String properties;

		Entry(final Kind pKind, final String pProperties)
		{
			this.kind = pKind;
			this.properties = pProperties == null ? "" : pProperties;
		}

		public Kind getKind()
		{
			return this.kind;
		}

		/** The JVM properties the next action names, comma separated; fixed text, never taken from the failure. */
		public String getProperties()
		{
			return this.properties;
		}

		public boolean isKnown()
		{
			return this.kind != Kind.UNKNOWN;
		}

		/** The bundle key of the cause; {@code error.<KIND>.cause}. */
		public String causeKey()
		{
			return "error." + this.kind.name() + ".cause";
		}

		/** The bundle key of the next action; {@code error.<KIND>.action}. */
		public String actionKey()
		{
			return "error." + this.kind.name() + ".action";
		}
	}

	private static final Entry UNKNOWN = new Entry(Kind.UNKNOWN, "");

	private ErrorCatalog()
	{
	}

	/** The entry of a kind, without its property names: what the bundle keys of a kind are read from. */
	public static Entry of(final Kind pKind)
	{
		return new Entry(pKind, "");
	}

	/**
	 * The failure behind an exception. A refusal the module decided itself (the first five kinds) wins wherever it sits in the
	 * chain of causes, because its wrapper's text is what tells the administrator what to set; then the type and status of the
	 * first throwable that settles one; then the first text that is known.
	 */
	public static Entry classify(final Throwable pFailure)
	{
		Entry typed = null;
		Entry byText = null;
		Throwable current = pFailure;
		for (int depth = 0; current != null && depth < 10; depth++)
		{
			final Entry found = ErrorCatalog.classify(current.getMessage());
			if (found.isKnown() && found.getKind().ordinal() <= Kind.TOO_MANY_RUNS.ordinal())
			{
				return found;
			}
			final Entry byType = ErrorCatalog.byType(current);
			if (byType != null && byType.getKind().ordinal() <= Kind.TOO_MANY_RUNS.ordinal())
			{
				return byType;
			}
			if (typed == null)
			{
				typed = byType;
			}
			if (byText == null && found.isKnown())
			{
				byText = found;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return typed != null ? typed : byText == null ? ErrorCatalog.UNKNOWN : byText;
	}

	/** The failure behind a stored or displayed text (a sync log message, the message of a run). */
	public static Entry classify(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return ErrorCatalog.UNKNOWN;
		}
		final String text = pText.toLowerCase(Locale.ROOT);
		// the refusals the module decides itself come first: their text also holds words such as "refused" or "host"
		if (text.contains(Egress.PRIVATE_EXCEPTIONS_PROPERTY.toLowerCase(Locale.ROOT)) || text.contains(Egress.BLOCK_PRIVATE_PROPERTY.toLowerCase(Locale.ROOT)))
		{
			return new Entry(Kind.PRIVATE_ADDRESS, Egress.PRIVATE_EXCEPTIONS_PROPERTY);
		}
		if (text.contains("outbound call") && text.contains("refused") && (text.contains(Egress.PROPERTY.toLowerCase(Locale.ROOT)) || text.contains("allow-list")))
		{
			return new Entry(Kind.HOST_NOT_ALLOWED, Egress.PROPERTY);
		}
		if (text.contains(Secrets.ALLOW_CLEAR_PROPERTY.toLowerCase(Locale.ROOT)) || text.contains("stored in clear text") || text.contains("held in clear"))
		{
			return new Entry(Kind.CLEAR_SECRET, Secrets.ALLOW_CLEAR_PROPERTY);
		}
		// the module's own refusal, in either language: "access denied" would otherwise read as a remote system refusing credentials
		if (text.contains("requires the connectors right") || text.contains("demande le droit connectors"))
		{
			return new Entry(Kind.ACCESS_DENIED, "");
		}
		if (text.contains("is already running"))
		{
			return new Entry(Kind.RUN_ALREADY_RUNNING, "");
		}
		if (text.contains("too many asynchronous runs"))
		{
			return new Entry(Kind.TOO_MANY_RUNS, ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY + ", " + ConnectorService.ASYNC_MAX_QUEUED_PROPERTY);
		}
		// the network
		if (ErrorCatalog.any(text, "unknownhost", "unknown host", "nodename nor servname", "name or service not known", "could not resolve host", "no such host"))
		{
			return new Entry(Kind.HOST_UNKNOWN, "");
		}
		if (ErrorCatalog.any(text, "timed out", "timeout", "deadline exceeded"))
		{
			return new Entry(Kind.TIMEOUT, "");
		}
		if (ErrorCatalog.any(text, "connection refused", "econnrefused", "unable to connect", "failed to connect", "could not connect"))
		{
			return new Entry(Kind.CONNECTION_REFUSED, "");
		}
		// the credentials and the rights
		if (ErrorCatalog.any(text, "http 401", "http 403", "status 401", "status 403", "unauthorized", "forbidden", "authentication failed",
			"invalid credentials", "access denied", "permission denied", "login failed", "invalid api key", "incorrect password", "bad credentials"))
		{
			return new Entry(Kind.AUTH_REFUSED, "");
		}
		if (ErrorCatalog.any(text, "http 429", "status 429", "too many requests", "rate limit", "throttl"))
		{
			return new Entry(Kind.RATE_LIMITED, "");
		}
		// the mapping
		if (ErrorCatalog.any(text, "mapping direction", "is not compatible with", "incremental load", "checkpoint strategy", "checkpointvaluepath",
			"has no descriptor", "has no delete operation", "operation is required", "no mapping '"))
		{
			return new Entry(Kind.MAPPING_INVALID, "");
		}
		return ErrorCatalog.UNKNOWN;
	}

	/** What the type of a throwable settles without reading its text, null when it settles nothing. */
	private static Entry byType(final Throwable pFailure)
	{
		if (pFailure instanceof MappingAlreadyRunningException)
		{
			return new Entry(Kind.RUN_ALREADY_RUNNING, "");
		}
		if (pFailure instanceof TooManyRunsException)
		{
			return new Entry(Kind.TOO_MANY_RUNS, ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY + ", " + ConnectorService.ASYNC_MAX_QUEUED_PROPERTY);
		}
		if (pFailure instanceof java.net.UnknownHostException)
		{
			return new Entry(Kind.HOST_UNKNOWN, "");
		}
		if (pFailure instanceof java.net.SocketTimeoutException || pFailure.getClass().getSimpleName().contains("TimeoutException"))
		{
			return new Entry(Kind.TIMEOUT, "");
		}
		if (pFailure instanceof java.net.ConnectException)
		{
			final String message = pFailure.getMessage() == null ? "" : pFailure.getMessage().toLowerCase(Locale.ROOT);
			return new Entry(message.contains("timed out") ? Kind.TIMEOUT : Kind.CONNECTION_REFUSED, "");
		}
		if (pFailure instanceof ConnectorException)
		{
			final int status = ((ConnectorException) pFailure).getStatus();
			if (status == 401 || status == 403)
			{
				return new Entry(Kind.AUTH_REFUSED, "");
			}
			if (status == 429)
			{
				return new Entry(Kind.RATE_LIMITED, "");
			}
		}
		return null;
	}

	private static boolean any(final String pText, final String... pFragments)
	{
		for (final String fragment : pFragments)
		{
			if (pText.contains(fragment))
			{
				return true;
			}
		}
		return false;
	}
}
