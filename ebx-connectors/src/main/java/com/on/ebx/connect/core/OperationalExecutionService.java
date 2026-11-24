package com.on.ebx.connect.core;

import java.util.Date;
import java.util.List;

/** Permission-checked operational entrypoints for resuming and replaying durable runs. */
public final class OperationalExecutionService
{
	public interface PermissionChecker { boolean mayOperate(String pActor, String pRunId, String pAction); }
	private final DurableExecutionStateStore store;
	private final PermissionChecker permissions;
	public OperationalExecutionService(final DurableExecutionStateStore pStore, final PermissionChecker pPermissions)
	{
		if (pStore == null || pPermissions == null) throw new IllegalArgumentException("Store and permissions are required");
		store = pStore;
		permissions = pPermissions;
	}

	public <T> List<RecoverableExecution.ItemResult> resume(final String pActor, final String pRunId,
		final Iterable<RecoverableExecution.Work<T>> pWork, final RecoverableExecution.Policy pPolicy,
		final RecoverableExecution.Overrides pOverrides, final RecoverableExecution.Cancellation pCancellation,
		final RecoverableExecution.RetryClassifier pRetry, final RecoverableExecution.Processor<T> pProcessor)
	{
		authorize(pActor, pRunId, "resume");
		final DurableExecutionStateStore.Lease lease = store.acquire(pRunId, pActor, 5L * 60L * 1000L, new Date());
		try { return RecoverableExecution.run(pRunId, pWork, pPolicy, pOverrides, store, pCancellation, pRetry, pProcessor); }
		finally { lease.close(); }
	}

	public void replay(final String pActor, final String pRunId)
	{
		authorize(pActor, pRunId, "replay");
		final DurableExecutionStateStore.Lease lease = store.acquire(pRunId, pActor, 5L * 60L * 1000L, new Date());
		try
		{
			final RecoverableExecution.Checkpoint checkpoint = store.load(pRunId);
			if (checkpoint == null) throw new IllegalArgumentException("Unknown execution run");
			if (checkpoint.getDeadLetters().isEmpty()) throw new IllegalStateException("Execution has no dead letters");
			store.save(RecoverableExecution.replayDeadLetters(checkpoint));
		}
		finally { lease.close(); }
	}

	private void authorize(final String pActor, final String pRunId, final String pAction)
	{
		if (pActor == null || !permissions.mayOperate(pActor, pRunId, pAction)) throw new SecurityException("Execution operation is not permitted");
	}
}
