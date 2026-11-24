package com.on.ebx.connect.ebx;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.DurableExecutionStateStore;
import com.on.ebx.connect.core.RecoverableExecution;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** EBX-table persistence for run/item/dead-letter state and atomic execution leases. */
public final class EbxRecoverableExecutionStore implements DurableExecutionStateStore.Persistence
{
	private final ConnectorRepository repository;
	private final Session session;
	public EbxRecoverableExecutionStore(final ConnectorRepository pRepository, final Session pSession)
	{
		if (pRepository == null || pSession == null) throw new IllegalArgumentException("Repository and session are required");
		repository = pRepository;
		session = pSession;
	}
	private AdaptationTable table()
	{
		final Adaptation dataset = repository.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN) == null)
			throw new IllegalStateException("ExecutionRun table is unavailable; reload the Connectors data model");
		return dataset.getTable(ConnectorsPaths.EXECUTION_RUN);
	}
	private Adaptation row(final String pRunId) { return table().lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRunId)); }
	@Override public DurableExecutionStateStore.RunState load(final String pRunId)
	{
		final Adaptation row = row(pRunId);
		if (row == null) return null;
		final Integer next = (Integer) row.get(ConnectorsPaths.ExecutionRun.NEXT_INDEX);
		return new DurableExecutionStateStore.RunState(new RecoverableExecution.Checkpoint(pRunId, next == null ? 0 : next.intValue(),
			attempts(row.getString(ConnectorsPaths.ExecutionRun.ATTEMPTS)), values(row.getString(ConnectorsPaths.ExecutionRun.COMPLETED)),
			values(row.getString(ConnectorsPaths.ExecutionRun.DEAD_LETTERS))), row.getString(ConnectorsPaths.ExecutionRun.LEASE_OWNER),
			(Date) row.get(ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT));
	}
	@Override public void save(final DurableExecutionStateStore.RunState pState) { write(pState); }
	@Override public boolean acquireLease(final String pRunId, final String pOwner, final Date pNow, final Date pExpiresAt)
	{
		final boolean[] acquired = { false};
		final AdaptationTable table = table();
		final ProcedureResult result = ProgrammaticService.createForSession(session, table.getContainerAdaptation().getHome()).execute(new Procedure() {
			@Override public void execute(final ProcedureContext context) throws Exception {
				final Adaptation existing = row(pRunId);
				final Date expiry = existing == null ? null : (Date) existing.get(ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT);
				final String owner = existing == null ? null : existing.getString(ConnectorsPaths.ExecutionRun.LEASE_OWNER);
				if (owner != null && expiry != null && expiry.after(pNow) && !pOwner.equals(owner)) return;
				final ValueContextForUpdate values = existing == null ? context.getContextForNewOccurrence(table) : context.getContext(existing.getAdaptationName());
				values.setValue(pRunId, ConnectorsPaths.ExecutionRun.RUN_ID);
				values.setValue(pOwner, ConnectorsPaths.ExecutionRun.LEASE_OWNER);
				values.setValue(pExpiresAt, ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT);
				values.setValue(pNow, ConnectorsPaths.ExecutionRun.UPDATED_AT);
				if (existing == null)
				{
					values.setValue(Integer.valueOf(0), ConnectorsPaths.ExecutionRun.NEXT_INDEX);
					context.doCreateOccurrence(values, table);
				}
				else context.doModifyContent(existing, values);
				acquired[0] = true;
			}
		});
		if (result.hasFailed()) throw new IllegalStateException("EBX rejected execution lease", result.getException());
		return acquired[0];
	}
	@Override public void releaseLease(final String pRunId, final String pOwner)
	{
		final DurableExecutionStateStore.RunState state = load(pRunId);
		if (state == null || !pOwner.equals(state.getLeaseOwner())) return;
		write(new DurableExecutionStateStore.RunState(state.getCheckpoint(), null, null));
	}
	private void write(final DurableExecutionStateStore.RunState pState)
	{
		final AdaptationTable table = table(); final ProcedureResult result = ProgrammaticService.createForSession(session, table.getContainerAdaptation().getHome()).execute(new Procedure() {
			@Override public void execute(final ProcedureContext context) throws Exception {
				final RecoverableExecution.Checkpoint checkpoint = pState.getCheckpoint();
				final Adaptation existing = row(checkpoint.getRunId());
				final ValueContextForUpdate values = existing == null ? context.getContextForNewOccurrence(table) : context.getContext(existing.getAdaptationName());
				values.setValue(checkpoint.getRunId(), ConnectorsPaths.ExecutionRun.RUN_ID);
				values.setValue(Integer.valueOf(checkpoint.getNextIndex()), ConnectorsPaths.ExecutionRun.NEXT_INDEX);
				values.setValue(attempts(checkpoint.getAttempts()), ConnectorsPaths.ExecutionRun.ATTEMPTS);
				values.setValue(values(checkpoint.getCompleted()), ConnectorsPaths.ExecutionRun.COMPLETED);
				values.setValue(values(checkpoint.getDeadLetters()), ConnectorsPaths.ExecutionRun.DEAD_LETTERS);
				values.setValue(pState.getLeaseOwner(), ConnectorsPaths.ExecutionRun.LEASE_OWNER);
				values.setValue(pState.getLeaseExpiresAt(), ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT);
				values.setValue(new Date(), ConnectorsPaths.ExecutionRun.UPDATED_AT);
				if (existing == null) context.doCreateOccurrence(values, table);
				else context.doModifyContent(existing, values);
			}
	});
	if (result.hasFailed()) throw new IllegalStateException("EBX rejected execution state", result.getException());
	}
	static String values(final Set<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0) out.append(',');
			out.append(value);
		}
		return out.toString();
	}
	static Set<String> values(final String pText)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pText != null) for (final String value : pText.split(",")) if (!value.isEmpty()) out.add(value);
		return out;
	}
	static String attempts(final Map<String, Integer> pAttempts)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Integer> entry : pAttempts.entrySet())
		{
			if (out.length() > 0) out.append(',');
			out.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return out.toString();
	}
	static Map<String, Integer> attempts(final String pText)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		if (pText != null) for (final String item : pText.split(","))
		{
			final int split = item.lastIndexOf('=');
			if (split > 0) out.put(item.substring(0, split), Integer.valueOf(item.substring(split + 1)));
		}
		return out;
	}
}
