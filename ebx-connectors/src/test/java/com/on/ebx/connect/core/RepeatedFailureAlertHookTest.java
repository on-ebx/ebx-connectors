package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class RepeatedFailureAlertHookTest
{
	public static void main(final String[] pArgs)
	{
		final List<RepeatedFailureAlertHook.Alert> alerts = new ArrayList<RepeatedFailureAlertHook.Alert>();
		final RepeatedFailureAlertHook hook = new RepeatedFailureAlertHook(3,
			new RepeatedFailureAlertHook.MemoryFailureHistory(), alerts::add,
			(pActor, pConnector) -> "operator".equals(pActor));
		final Date time = new Date(123456L);
		check(!hook.record("operator", result(SyncResult.Status.ERROR, "https://host/path?token=secret"), time), "first failure must not alert");
		check(!hook.record("operator", result(SyncResult.Status.ERROR, "https://host/path?token=secret"), time), "second failure must not alert");
		check(hook.record("operator", result(SyncResult.Status.ERROR, "https://host/path?token=secret"), time), "threshold must alert");
		check(!hook.record("operator", result(SyncResult.Status.ERROR, "https://host/path?token=secret"), time), "streak must not spam alerts");
		check(alerts.size() == 1 && alerts.get(0).getConsecutiveFailures() == 3, "one threshold alert expected");
		check(!alerts.get(0).getMessage().contains("secret"), "alert message must be redacted");
		hook.record("operator", result(SyncResult.Status.OK, ""), time);
		hook.record("operator", result(SyncResult.Status.ERROR, "again"), time);
		hook.record("operator", result(SyncResult.Status.ERROR, "again"), time);
		check(!hook.record("reader", result(SyncResult.Status.ERROR, "again"), time), "unauthorized actor must not publish");
		check(alerts.size() == 1, "permission policy must be enforced");
		System.out.println("Repeated-failure alert hook verified.");
	}

	private static SyncResult result(final SyncResult.Status pStatus, final String pMessage)
	{
		final SyncResult result = new SyncResult("Kafka", "publish", "OUTBOUND", "Main", "/root/Company", "1", "One");
		result.setStatus(pStatus); result.setMessage(pMessage); result.setMappingId("company-kafka"); return result;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
