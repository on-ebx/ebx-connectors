package com.on.ebx.connect.core;

import java.util.Date;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The end of a catalog scan, written on its catalog {@code Source}: {@code lastScanAt},
 * {@code lastScanStatus} (OK, PARTIAL, ERROR) and {@code lastScanMessage}. The scan flow the catalog
 * creates is a plain feed on {@code /root/Asset}, so nothing else ever told the Source that it ran
 * (seen 2026-09-16: Salesforce assets refreshed, Source still NEVER). Failures are recorded too.
 * <p>
 * A scan flow is recognised as data, like the rest of {@link CatalogDetection}: a root feed with
 * operation {@code catalog} on {@code /root/Asset} of the verified catalog dataset, whose
 * {@code source} input is a plain Source id. Anything else is left alone. Never throws.
 */
public final class CatalogScanStatus
{
	private CatalogScanStatus()
	{
	}

	static final String OPERATION = "catalog";
	static final String ASSET_TABLE = "/root/Asset";
	static final String SOURCE_INPUT = "source";
	static final Path LAST_SCAN_AT = Path.parse("./lastScanAt");
	static final Path LAST_SCAN_STATUS = Path.parse("./lastScanStatus");
	static final Path LAST_SCAN_MESSAGE = Path.parse("./lastScanMessage");
	static final int MESSAGE_MAX = 1000;

	/** Reject stale source bindings before a recognized catalog scan executes. */
	static String executionIdentityError(final MappingDefinition mapping, final boolean verifiedCatalog,
		final boolean sourceExists, final String sourceConnector)
	{
		if (sourceOf(mapping) == null || !verifiedCatalog) return null;
		if (!sourceExists) return "Catalog scan blocked: Source does not exist. No provider request or import was started.";
		if (!connectorMatches(mapping.getConnectorId(), sourceConnector))
			return "Catalog scan blocked: Source connector differs from scan flow. Reconcile the generated flow before running it.";
		return null;
	}

	/** Read-only preflight for literal-Source root scans; unrelated mappings remain unchanged. */
	static String executionIdentityError(final ConnectorRepository repo, final MappingDefinition mapping)
	{
		if (sourceOf(mapping) == null) return null;
		try
		{
			if (!CatalogDetection.isCatalogFlow(repo, mapping)) return null;
			final CatalogDetection.Local local = CatalogDetection.local(repo.getRepository());
			if (local.adaptation == null || local.adaptation.getSchemaNode().getNode(CatalogDetection.SOURCE_TABLE) == null)
				return "Catalog scan blocked: Source identity cannot be verified.";
			final Adaptation source = local.adaptation.getTable(CatalogDetection.SOURCE_TABLE)
				.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceOf(mapping)));
			return executionIdentityError(mapping, true, source != null,
				source == null ? null : source.getString(CatalogDetection.SOURCE_CONNECTOR));
		}
		catch (final RuntimeException unavailable)
		{
			return "Catalog scan blocked: Source identity cannot be verified.";
		}
	}

	/** Status and message of a scan run. */
	static final class Outcome
	{
		final String status;
		final String message;

		Outcome(final String pStatus, final String pMessage)
		{
			this.status = pStatus;
			this.message = pMessage;
		}
	}

	/** The Source id a scan flow names, or null when the mapping is not a scan flow (pure). */
	static String sourceOf(final MappingDefinition pMapping)
	{
		if (pMapping == null || pMapping.isChild() || !CatalogScanStatus.OPERATION.equals(pMapping.getOperation())
			|| !CatalogScanStatus.ASSET_TABLE.equals(pMapping.getTablePath()))
		{
			return null;
		}
		final String source = pMapping.getInputs().get(CatalogScanStatus.SOURCE_INPUT);
		if (source == null || source.trim().isEmpty() || source.contains("${"))
		{
			return null;
		}
		return source.trim();
	}

	/**
	 * What a run's outcome says (pure). Volume lot 7b: reads the run's EXACT, unbounded per-result
	 * classification ({@link ImportOutcome#getCatalogTally()}, fed by every {@link ImportOutcome#addResult}
	 * call of a catalog-scan run) instead of walking the bounded {@link ImportOutcome#getResults()}
	 * sample - a schema inventory larger than {@code resultsMax} (default 1000) used to be undercounted
	 * here (2026-09-28 audit), and a failure past position 1000 never surfaced in the message at all.
	 * The two header-only special cases (nothing was ever attempted: the whole call failed outright, or
	 * the source returned nothing) still need the actual result text, so they are read directly off the
	 * run's lone result when {@link ImportOutcome#getTotalResultsAdded()} says this is genuinely the
	 * only one the run ever added (a count the bounded {@code results.size()} cannot answer once a scan
	 * exceeds {@code resultsMax}, but a run of exactly one result is by definition never past it).
	 */
	static Outcome outcome(final ImportOutcome pOutcome)
	{
		if (pOutcome.getTotalResultsAdded() == 1)
		{
			final List<SyncResult> results = pOutcome.getResults();
			final SyncResult r = results.isEmpty() ? null : results.get(0);
			if (r != null)
			{
				final boolean header = r.getRecordKey() == null || r.getRecordKey().isEmpty();
				if (header && r.getStatus() == SyncResult.Status.ERROR)
				{
					return new Outcome("ERROR", CatalogScanStatus.cut("Scan failed: " + r.getMessage()));
				}
				if (header && r.getStatus() == SyncResult.Status.NO_DATA)
				{
					return new Outcome("PARTIAL", CatalogScanStatus.cut("The scan returned no object: " + r.getMessage()
						+ ". Check the connector's access and the schema filter."));
				}
			}
		}
		final CatalogScanTally tally = pOutcome.getCatalogTally();
		final int total = tally == null ? 0 : tally.total();
		if (total == 0)
		{
			return new Outcome("ERROR", "The scan produced no result");
		}
		final StringBuilder message = new StringBuilder().append(total).append(" object(s) scanned: ").append(tally.written).append(" written, ")
			.append(tally.unchanged).append(" unchanged");
		if (tally.skipped > 0) message.append(", ").append(tally.skipped).append(" skipped");
		if (tally.failed > 0) message.append(", ").append(tally.failed).append(" failed");
		if (tally.warned > 0) message.append(", ").append(tally.warned).append(" with field errors");
		message.append('.');
		if (tally.getFirstError() != null) message.append(" First error: ").append(tally.getFirstError()).append('.');
		final String limit = tally.getLimitMessage();
		if (limit != null) message.append(' ').append(limit).append('.');
		final String status = tally.failed == total ? "ERROR"
			: tally.failed > 0 || tally.warned > 0 || tally.skipped > 0 || limit != null ? "PARTIAL" : "OK";
		return new Outcome(status, CatalogScanStatus.cut(message.toString()));
	}

	/** Records the end of a feed run on its catalog Source when the feed is a scan flow. Never throws. */
	public static SyncResult record(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping, final ImportOutcome pOutcome)
	{
		try
		{
			final String sourceId = CatalogScanStatus.sourceOf(pMapping);
			if (sourceId == null || pOutcome == null || pSession == null || !CatalogDetection.isCatalogFlow(pRepo, pMapping))
			{
				return null;
			}
			final CatalogDetection.Local local = CatalogDetection.local(pRepo.getRepository());
			if (local.adaptation == null || local.adaptation.getSchemaNode().getNode(CatalogDetection.SOURCE_TABLE) == null)
			{
				return bookkeepingFailure(pMapping, "Catalog Source table is unavailable");
			}
			final AdaptationTable sources = local.adaptation.getTable(CatalogDetection.SOURCE_TABLE);
			if (sources.getTableOccurrenceRootNode().getNode(CatalogScanStatus.LAST_SCAN_STATUS) == null)
			{
				return bookkeepingFailure(pMapping, "Catalog model does not expose scan status");
			}
			final Adaptation source = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceId));
			if (source == null)
			{
				return bookkeepingFailure(pMapping, "Catalog Source does not exist");
			}
			if (!connectorMatches(pMapping.getConnectorId(), source.getString(CatalogDetection.SOURCE_CONNECTOR)))
			{
				return bookkeepingFailure(pMapping, "Source connector differs from scan flow; reconcile the generated flow");
			}
			final Outcome outcome = CatalogScanStatus.outcome(pOutcome);
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, local.adaptation.getHome());
			service.setSessionTrackingInfo("Connector catalog scan status " + sourceId);
			final ProcedureResult result = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					// bookkeeping of a run the caller was allowed to start, like the scan's own writes
					pContext.setAllPrivileges(true);
					final Adaptation current = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceId));
					if (current == null || !connectorMatches(pMapping.getConnectorId(), current.getString(CatalogDetection.SOURCE_CONNECTOR)))
						throw new IllegalStateException("Source identity changed before scan status write");
					final ValueContextForUpdate row = pContext.getContext(current.getAdaptationName());
					row.setValue(new Date(), CatalogScanStatus.LAST_SCAN_AT);
					row.setValue(outcome.status, CatalogScanStatus.LAST_SCAN_STATUS);
					row.setValue(outcome.message, CatalogScanStatus.LAST_SCAN_MESSAGE);
					pContext.doModifyContent(current, row);
				}
			});
			if (result.hasFailed())
			{
				return bookkeepingFailure(pMapping, "Scan status persistence failed");
			}
			return null;
		}
		catch (final Throwable ex)
		{
			return bookkeepingFailure(pMapping, "Scan status bookkeeping failed");
		}
	}

	static boolean connectorMatches(final String mappingConnector, final String sourceConnector)
	{
		return mappingConnector != null && !mappingConnector.trim().isEmpty()
			&& mappingConnector.equals(sourceConnector);
	}

	/** Empty record key deliberately makes this completion notice ineligible for record replay. */
	static SyncResult bookkeepingFailure(final MappingDefinition mapping, final String reason)
	{
		final SyncResult notice = new SyncResult(mapping == null ? "" : mapping.getConnectorId(),
			"catalog-bookkeeping", "IMPORT", mapping == null ? "" : mapping.getDataspace(),
			mapping == null ? "" : mapping.getTablePath(), "", "Catalog scan bookkeeping");
		notice.setStatus(SyncResult.Status.PARTIAL);
		notice.setRunId(ConnectorService.currentRunId());
		if (mapping != null) notice.setMappingId(String.valueOf(mapping.getId()));
		notice.setMessage(reason + ". Imported records are not rolled back; do not replay committed imports to repair bookkeeping.");
		return notice;
	}

	static String cut(final String pValue)
	{
		final String value = Redaction.redact(pValue == null ? "" : pValue);
		return value.length() <= CatalogScanStatus.MESSAGE_MAX ? value : value.substring(0, CatalogScanStatus.MESSAGE_MAX - 3) + "...";
	}
}
