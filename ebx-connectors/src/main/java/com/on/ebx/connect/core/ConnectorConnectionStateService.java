package com.on.ebx.connect.core;

import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;

/** Persists a verified logical connection without retaining an external socket. */
public final class ConnectorConnectionStateService
{
	public enum Status { NOT_CONNECTED, CONNECTED, STALE, FAILED }

	public static final class State
	{
		private final Status status;
		private final Date verifiedAt;
		private final String fingerprint;
		private final String message;
		public State(final Status pStatus, final Date pVerifiedAt, final String pFingerprint, final String pMessage)
		{
			this.status = pStatus == null ? Status.NOT_CONNECTED : pStatus;
			this.verifiedAt = pVerifiedAt == null ? null : new Date(pVerifiedAt.getTime());
			this.fingerprint = pFingerprint;
			this.message = pMessage;
		}
		public Status getStatus() { return this.status; }
		public Date getVerifiedAt() { return this.verifiedAt == null ? null : new Date(this.verifiedAt.getTime()); }
		public String getFingerprint() { return this.fingerprint; }
		public String getMessage() { return this.message; }
	}

	public interface Store
	{
		State load(String pConnectorId);
		void save(String pConnectorId, State pState);
	}

	private final Store store;
	public ConnectorConnectionStateService(final Store pStore)
	{
		if (pStore == null) throw new IllegalArgumentException("store is required");
		this.store = pStore;
	}

	public State status(final ConnectorConfig pConfig)
	{
		if (pConfig == null || blank(pConfig.getId())) return new State(Status.NOT_CONNECTED, null, null, null);
		final State stored = this.store.load(pConfig.getId());
		if (stored == null || stored.getStatus() == Status.NOT_CONNECTED) return new State(Status.NOT_CONNECTED, null, null, stored == null ? null : stored.getMessage());
		if (stored.getStatus() == Status.CONNECTED && !materialFingerprint(pConfig).equals(stored.getFingerprint()))
			return new State(Status.STALE, stored.getVerifiedAt(), stored.getFingerprint(), "Connection settings changed; connect again to verify them");
		return stored;
	}

	public State verifyAndRecord(final Connector pConnector, final ConnectorContext pContext, final ConnectorConfig pConfig)
		throws ConnectorException
	{
		if (pConnector == null || pConfig == null || blank(pConfig.getId())) throw new IllegalArgumentException("connector and identified configuration are required");
		try
		{
			final ConnectionTestResult result = pConnector.testConnection(pContext);
			if (!result.isSupported())
			{
				// nothing could be checked: not a failure, the connector simply stays unverified
				final State unverified = new State(Status.NOT_CONNECTED, null, null, safe(result.getMessage()));
				this.store.save(pConfig.getId(), unverified);
				return unverified;
			}
			if (!result.isSuccessful())
			{
				final State failed = new State(Status.FAILED, null, materialFingerprint(pConfig), safe(result.getMessage()));
				this.store.save(pConfig.getId(), failed);
				return failed;
			}
			final State connected = new State(Status.CONNECTED, new Date(), materialFingerprint(pConfig), safe(result.getMessage()));
			this.store.save(pConfig.getId(), connected);
			return connected;
		}
		catch (final ConnectorException ex)
		{
			this.store.save(pConfig.getId(), new State(Status.FAILED, null, materialFingerprint(pConfig), "Connection verification failed"));
			throw ex;
		}
	}

	public State disconnect(final ConnectorConfig pConfig)
	{
		if (pConfig == null || blank(pConfig.getId())) throw new IllegalArgumentException("identified configuration is required");
		final State state = new State(Status.NOT_CONNECTED, null, null, "Disconnected");
		this.store.save(pConfig.getId(), state);
		return state;
	}

	public static String materialFingerprint(final ConnectorConfig pConfig)
	{
		if (pConfig == null) return Hashing.sha256("");
		final StringBuilder value = new StringBuilder();
		value.append(pConfig.getType()).append('\u0001').append(pConfig.getBaseUrl()).append('\u0001')
			.append(pConfig.getAuthType()).append('\u0001').append(pConfig.getUsername()).append('\u0001')
			.append(pConfig.getPassword()).append('\u0001').append(pConfig.getApiKeyHeader()).append('\u0001')
			.append(pConfig.getApiKey()).append('\u0001').append(pConfig.getConnectTimeoutMs()).append('\u0001')
			.append(pConfig.getReadTimeoutMs());
		for (final Map.Entry<String, String> property : new TreeMap<String, String>(pConfig.getProperties()).entrySet())
			value.append('\u0002').append(property.getKey()).append('=').append(property.getValue());
		return Hashing.sha256(value.toString());
	}

	private static String safe(final String pMessage)
	{
		final String redacted = Redaction.redact(pMessage == null ? "" : pMessage);
		return redacted.length() <= 500 ? redacted : redacted.substring(0, 500);
	}
	private static boolean blank(final String pValue) { return pValue == null || pValue.trim().isEmpty(); }
}
