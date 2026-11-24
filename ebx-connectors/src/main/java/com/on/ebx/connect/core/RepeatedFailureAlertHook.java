package com.on.ebx.connect.core;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-neutral hook for operational alerts. The caller supplies durable failure history and
 * the delivery provider (mail, webhook, incident platform); this class owns thresholding,
 * authorization and duplicate suppression semantics.
 */
public final class RepeatedFailureAlertHook
{
	public interface FailureHistory
	{
		int record(String pConnector, String pMapping, boolean pFailed);
	}
	public interface AlertProvider { void publish(Alert pAlert); }
	public interface PermissionPolicy { boolean mayPublish(String pActor, String pConnector); }

	public static final class Alert
	{
		private final String connector, mapping, message, actor;
		private final int consecutiveFailures;
		private final Date occurredAt;
		public Alert(final String pConnector, final String pMapping, final String pMessage, final String pActor,
			final int pConsecutiveFailures, final Date pOccurredAt)
		{
			connector = pConnector;
			mapping = pMapping;
			message = Redaction.redact(pMessage);
			actor = pActor;
			consecutiveFailures = pConsecutiveFailures;
			occurredAt = new Date(pOccurredAt.getTime());
		}
		public String getConnector() { return connector; }
		public String getMapping() { return mapping; }
		public String getMessage() { return message; }
		public String getActor() { return actor; }
		public int getConsecutiveFailures() { return consecutiveFailures; }
		public Date getOccurredAt() { return new Date(occurredAt.getTime()); }
	}

	private final int threshold;
	private final FailureHistory history;
	private final AlertProvider provider;
	private final PermissionPolicy permissions;
	public RepeatedFailureAlertHook(final int pThreshold, final FailureHistory pHistory,
		final AlertProvider pProvider, final PermissionPolicy pPermissions)
	{
		if (pThreshold < 2 || pHistory == null || pProvider == null || pPermissions == null)
			throw new IllegalArgumentException("Threshold (at least 2), history, provider and permissions are required");
		threshold = pThreshold;
		history = pHistory;
		provider = pProvider;
		permissions = pPermissions;
	}

	/** Publishes only when a failure streak first reaches the configured threshold. */
	public boolean record(final String pActor, final SyncResult pResult, final Date pOccurredAt)
	{
		if (pResult == null || pResult.getConnectorId() == null) throw new IllegalArgumentException("Connector result is required");
		final boolean failed = pResult.countsAsFailure();
		final int count = history.record(pResult.getConnectorId(), pResult.getMappingId(), failed);
		if (!failed || count != threshold || !permissions.mayPublish(pActor, pResult.getConnectorId())) return false;
		provider.publish(new Alert(pResult.getConnectorId(), pResult.getMappingId(), pResult.getMessage(), pActor,
			count, pOccurredAt == null ? new Date() : pOccurredAt));
		return true;
	}

	/** Deterministic implementation for tests and single-node embedded deployments. */
	public static final class MemoryFailureHistory implements FailureHistory
	{
		private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		@Override public synchronized int record(final String pConnector, final String pMapping, final boolean pFailed)
		{
			final String key = pConnector + "\u0000" + (pMapping == null ? "" : pMapping);
			final int count = pFailed ? (counts.containsKey(key) ? counts.get(key).intValue() : 0) + 1 : 0;
			if (count == 0) counts.remove(key);
			else counts.put(key, Integer.valueOf(count));
			return count;
		}
	}
}
