package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorOperationalMetrics;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.SensitiveDataMasker;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** EBX-table adapter for durable connector health and operational metrics. */
public final class EbxConnectorOperationalMetricsPersistence implements ConnectorOperationalMetrics.Persistence
{
	interface Backend
	{
		Row load(String pConnectorId);
		void save(Row pRow);
	}
	static final class Row
	{
		String connectorId, durations, lastFailure;
		Date lastSuccessAt, lastFailureAt, checkpointAt, schemaDriftAt, updatedAt;
		int consecutiveFailures, schemaDriftCount;
		final Map<ConnectorOperationalMetrics.Counter, String> counters = new LinkedHashMap<ConnectorOperationalMetrics.Counter, String>();
	}

	private final Backend backend;
	public EbxConnectorOperationalMetricsPersistence(final Repository pRepository, final Session pSession)
	{
		this(new EbxBackend(pRepository, pSession));
	}
	EbxConnectorOperationalMetricsPersistence(final Backend pBackend)
	{
		if (pBackend == null) throw new IllegalArgumentException("Metrics backend is required");
		backend = pBackend;
	}

	@Override public ConnectorOperationalMetrics.Snapshot load(final String pConnectorId)
	{
		final Row row = backend.load(pConnectorId);
		if (row == null) return null;
		final Map<ConnectorOperationalMetrics.Counter, Long> counters = new LinkedHashMap<ConnectorOperationalMetrics.Counter, Long>();
		for (final ConnectorOperationalMetrics.Counter counter : ConnectorOperationalMetrics.Counter.values())
			counters.put(counter, Long.valueOf(parseCounter(row.counters.get(counter))));
		return new ConnectorOperationalMetrics.Snapshot(row.connectorId, counters, durations(row.durations), row.lastSuccessAt,
			row.lastFailureAt, row.lastFailure, row.consecutiveFailures, row.checkpointAt, row.schemaDriftAt,
			row.schemaDriftCount, row.updatedAt);
	}
	@Override public void save(final ConnectorOperationalMetrics.Snapshot pSnapshot)
	{
		if (pSnapshot == null) throw new IllegalArgumentException("Metrics snapshot is required");
		final Row row = new Row();
		row.connectorId = pSnapshot.getConnectorId();
		for (final ConnectorOperationalMetrics.Counter counter : ConnectorOperationalMetrics.Counter.values())
			row.counters.put(counter, Long.toString(pSnapshot.get(counter)));
		row.durations = durations(pSnapshot.getDurations());
		row.lastSuccessAt = pSnapshot.getLastSuccessAt();
		row.lastFailureAt = pSnapshot.getLastFailureAt();
		row.lastFailure = truncate(SensitiveDataMasker.empty().mask(pSnapshot.getLastFailure()), LAST_FAILURE_MAX_LENGTH);
		row.consecutiveFailures = pSnapshot.getConsecutiveFailures();
		row.checkpointAt = pSnapshot.getCheckpointAt();
		row.schemaDriftAt = pSnapshot.getSchemaDriftAt();
		row.schemaDriftCount = pSnapshot.getSchemaDriftCount();
		row.updatedAt = pSnapshot.getUpdatedAt();
		backend.save(row);
	}

	/** maxLength of ./lastFailure in Connectors.xsd: a longer value is a blocking constraint and the save would fail. */
	static final int LAST_FAILURE_MAX_LENGTH = 2000;
	static String truncate(final String pValue, final int pMax)
	{
		return pValue == null || pValue.length() <= pMax ? pValue : pValue.substring(0, pMax - 1) + "…";
	}

	static long parseCounter(final String pValue)
	{
		if (pValue == null || pValue.isEmpty()) return 0L;
		try
		{
			final long parsed = Long.parseLong(pValue);
			if (parsed < 0L) throw new IllegalArgumentException("Negative metric counter");
			return parsed;
		}
		catch (final NumberFormatException ex) { throw new IllegalArgumentException("Invalid metric counter: " + pValue, ex); }
	}
	static String durations(final List<Long> pValues)
	{
		final StringBuilder out = new StringBuilder(); if (pValues != null) for (final Long value : pValues)
		{
			if (value == null || value.longValue() < 0L) throw new IllegalArgumentException("Invalid duration");
			if (out.length() > 0) out.append(',');
			out.append(value);
		}
		return out.toString();
	}
	static List<Long> durations(final String pValue)
	{
		final List<Long> out = new ArrayList<Long>(); if (pValue != null && !pValue.isEmpty()) for (final String item : pValue.split(","))
		{
			final long value = parseCounter(item);
			out.add(Long.valueOf(value));
		}
		return out;
	}

	private static final class EbxBackend implements Backend
	{
		private final Repository repository;
		private final Session session;
		EbxBackend(final Repository pRepository, final Session pSession)
		{
			if (pRepository == null || pSession == null) throw new IllegalArgumentException("Repository and session are required");
			repository = pRepository;
			session = pSession;
		}
		private AdaptationTable table()
		{
			final Adaptation dataset = new com.on.ebx.connect.core.ConnectorRepository(repository).dataset();
			if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR_OPERATIONAL_METRICS) == null)
				throw new IllegalStateException("ConnectorOperationalMetrics table is unavailable; reload the Connectors data model");
			return dataset.getTable(ConnectorsPaths.CONNECTOR_OPERATIONAL_METRICS);
		}
		@Override public Row load(final String pConnectorId)
		{
			final Adaptation value = table().lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConnectorId));
			if (value == null) return null;
			final Row row = new Row();
			row.connectorId = value.getString(ConnectorsPaths.ConnectorOperationalMetrics.CONNECTOR);
			for (final ConnectorOperationalMetrics.Counter counter : ConnectorOperationalMetrics.Counter.values()) row.counters.put(counter, value.getString(path(counter)));
			row.durations = value.getString(ConnectorsPaths.ConnectorOperationalMetrics.DURATION_SAMPLES);
			row.lastSuccessAt = (Date) value.get(ConnectorsPaths.ConnectorOperationalMetrics.LAST_SUCCESS_AT);
			row.lastFailureAt = (Date) value.get(ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE_AT);
			row.lastFailure = value.getString(ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE);
			row.consecutiveFailures = integer(value, ConnectorsPaths.ConnectorOperationalMetrics.CONSECUTIVE_FAILURES);
			row.checkpointAt = (Date) value.get(ConnectorsPaths.ConnectorOperationalMetrics.CHECKPOINT_AT);
			row.schemaDriftAt = (Date) value.get(ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_AT);
			row.schemaDriftCount = integer(value, ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_COUNT);
			row.updatedAt = (Date) value.get(ConnectorsPaths.ConnectorOperationalMetrics.UPDATED_AT);
			return row;
		}
		@Override public void save(final Row pRow)
		{
			final AdaptationTable table = table();
			final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRow.connectorId));
			final ProcedureResult result = ProgrammaticService.createForSession(session, table.getContainerAdaptation().getHome()).execute(new Procedure() {
				@Override public void execute(final ProcedureContext pContext) throws Exception {
					final ValueContextForUpdate values = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
					values.setValue(pRow.connectorId, ConnectorsPaths.ConnectorOperationalMetrics.CONNECTOR);
					for (final ConnectorOperationalMetrics.Counter counter : ConnectorOperationalMetrics.Counter.values()) values.setValue(pRow.counters.get(counter), path(counter));
					values.setValue(pRow.durations, ConnectorsPaths.ConnectorOperationalMetrics.DURATION_SAMPLES);
					values.setValue(pRow.lastSuccessAt, ConnectorsPaths.ConnectorOperationalMetrics.LAST_SUCCESS_AT);
					values.setValue(pRow.lastFailureAt, ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE_AT);
					values.setValue(pRow.lastFailure, ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE);
					values.setValue(Integer.valueOf(pRow.consecutiveFailures), ConnectorsPaths.ConnectorOperationalMetrics.CONSECUTIVE_FAILURES);
					values.setValue(pRow.checkpointAt, ConnectorsPaths.ConnectorOperationalMetrics.CHECKPOINT_AT);
					values.setValue(pRow.schemaDriftAt, ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_AT);
					values.setValue(Integer.valueOf(pRow.schemaDriftCount), ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_COUNT);
					values.setValue(pRow.updatedAt, ConnectorsPaths.ConnectorOperationalMetrics.UPDATED_AT);
					if (existing == null) pContext.doCreateOccurrence(values, table);
					else pContext.doModifyContent(existing, values);
				}
		});
		if (result.hasFailed()) throw new IllegalStateException("EBX rejected connector metrics", result.getException());
		}
		private static int integer(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
		{
			final Integer value = (Integer) pRow.get(pPath);
			return value == null ? 0 : value.intValue();
		}
		private static com.orchestranetworks.schema.Path path(final ConnectorOperationalMetrics.Counter pCounter)
		{
			switch (pCounter) {
				case READ: return ConnectorsPaths.ConnectorOperationalMetrics.READ_COUNT;
				case WRITE: return ConnectorsPaths.ConnectorOperationalMetrics.WRITE_COUNT;
				case SKIP: return ConnectorsPaths.ConnectorOperationalMetrics.SKIP_COUNT;
				case CONFLICT: return ConnectorsPaths.ConnectorOperationalMetrics.CONFLICT_COUNT;
				case REQUEST: return ConnectorsPaths.ConnectorOperationalMetrics.REQUEST_COUNT;
				case RETRY: return ConnectorsPaths.ConnectorOperationalMetrics.RETRY_COUNT;
				case THROTTLE: return ConnectorsPaths.ConnectorOperationalMetrics.THROTTLE_COUNT;
				default: throw new IllegalArgumentException("Unsupported counter");
			}
		}
	}
}
