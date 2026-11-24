package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Date;

/** Deterministic lease, authorization, resume and replay contract. */
public final class DurableExecutionStateStoreTest
{
	public static void main(final String[] pArgs)
	{
		leasePreventsConcurrentOwnersAndExpires();
		operationsRequirePermissionAndReplayOnlyDeadLetters();
		System.out.println("PASS: durable execution state and operations");
	}

	private static void leasePreventsConcurrentOwnersAndExpires()
	{
		final DurableExecutionStateStore.MemoryPersistence persistence = new DurableExecutionStateStore.MemoryPersistence();
		final DurableExecutionStateStore store = new DurableExecutionStateStore(persistence);
		final Date zero = new Date(1000L);
		final DurableExecutionStateStore.Lease first = store.acquire("run", "scheduler-a", 1000L, zero);
		boolean blocked = false;
		try { store.acquire("run", "scheduler-b", 1000L, new Date(1500L)); } catch (final IllegalStateException expected) { blocked = true; }
		check(blocked, "concurrent owner was allowed");
		final DurableExecutionStateStore.Lease expired = store.acquire("run", "scheduler-b", 1000L, new Date(2001L));
		expired.close(); first.close();
	}

	private static void operationsRequirePermissionAndReplayOnlyDeadLetters()
	{
		final DurableExecutionStateStore store = new DurableExecutionStateStore(new DurableExecutionStateStore.MemoryPersistence());
		final OperationalExecutionService operations = new OperationalExecutionService(store,
			new OperationalExecutionService.PermissionChecker() { public boolean mayOperate(final String a, final String r, final String action) { return "operator".equals(a); } });
		boolean denied = false;
		try { operations.replay("viewer", "run-2"); } catch (final SecurityException expected) { denied = true; }
		check(denied, "permission was not enforced");
		final RecoverableExecution.Processor<String> failure = new RecoverableExecution.Processor<String>() {
			public void execute(final String v, final String k) throws Exception { throw new Exception("failed"); }
			public void delete(final String v, final String k) { }
			public void tombstone(final String v, final String k) { }
		};
		operations.resume("operator", "run-2", Arrays.asList(new RecoverableExecution.Work<String>("item", "value", false)),
			new RecoverableExecution.Policy(1, 1, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(1, false), null, null, failure);
		check(store.load("run-2").getDeadLetters().contains("item"), "dead letter missing");
		operations.replay("operator", "run-2");
		check(store.load("run-2").getDeadLetters().isEmpty() && !store.load("run-2").getCompleted().contains("item"), "replay did not reopen item");
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
