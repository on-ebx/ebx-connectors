package com.on.ebx.connect.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Which run holds each mapping (audit C-20): two runs of one feed at once read the same source, race to create
 * the same records and advance the same checkpoint, so a second start is refused while the first runs.
 * <p>
 * In JVM, which is the module's model (one node); a restart drops every lock, and a run that ended however
 * it ended gives its lock back in a {@code finally}. A cluster of several nodes would need a lock in EBX
 * (the {@code ExecutionRun} lease columns exist for that and are not used by this check: finding the run
 * of a mapping there is a scan on a field that is not a key, on every start).
 * <p>
 * Pure: {@code MappingRunLocksTest}.
 */
final class MappingRunLocks
{
	private final ConcurrentMap<Integer, String> holders = new ConcurrentHashMap<Integer, String>();

	/**
	 * Takes the mapping for {@code pRunId}.
	 *
	 * @return null when the mapping was taken; otherwise the id of the run that holds it (the mapping is not taken)
	 */
	String tryAcquire(final int pMappingId, final String pRunId)
	{
		return this.holders.putIfAbsent(Integer.valueOf(pMappingId), pRunId);
	}

	/** Gives the mapping back; does nothing when it is held by another run (a stale release must not free a live run). */
	void release(final int pMappingId, final String pRunId)
	{
		this.holders.remove(Integer.valueOf(pMappingId), pRunId);
	}

	/** The run holding the mapping, null when none. */
	String holder(final int pMappingId)
	{
		return this.holders.get(Integer.valueOf(pMappingId));
	}

	/** Forgets every lock: the module stopped and the runs that held them are gone. */
	void clear()
	{
		this.holders.clear();
	}

	/** What a refused start says. */
	static String refusal(final int pMappingId, final String pLabel, final String pHolderRunId)
	{
		return "Mapping " + pMappingId + (pLabel == null || pLabel.isEmpty() ? "" : " (" + pLabel + ")") + " is already running (run " + pHolderRunId
			+ "): this start was refused and nothing was read or written for it. Wait for that run to end (connectors_run_get), or stop it"
			+ " (connectors_run_stop), then start again.";
	}
}
