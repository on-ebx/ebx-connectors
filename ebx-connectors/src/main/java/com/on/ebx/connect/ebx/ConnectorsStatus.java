package com.on.ebx.connect.ebx;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.BackgroundWork;
import com.on.ebx.connect.core.ClearSecrets;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ModuleVersion;
import com.on.ebx.connect.core.Retention;
import com.on.ebx.connect.core.Secrets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;

/**
 * What {@code GET /ebx-connectors/rest/v1/status} answers: whether the module is installed and what protects it
 * right now, for an operator or a monitoring probe. It only reads. No secret and no allow-list entry appears in it,
 * only counts, on/off postures and, under {@code secrets}, the ids of the connectors that hold a clear secret, and it never installs or repairs anything: a module that is not installed
 * must say so, not fix itself because someone asked.
 */
public final class ConnectorsStatus
{
	/** Most runs counted as running; a number above it is reported as this one with {@code runningCapped}. */
	static final int MAX_RUNNING_COUNTED = 1000;
	/** Most change-data-capture flows listed; more are counted and flagged {@code capped} (audit C3-12). */
	static final int MAX_CDC_FLOWS = 50;
	/** Longest resume position written in the answer; a longer one is cut and flagged {@code positionCut}. */
	static final int MAX_POSITION_CHARS = 500;

	private ConnectorsStatus()
	{
	}

	/** The whole answer. */
	public static ObjectNode build(final Repository pRepository)
	{
		final ObjectNode out = Json.object();
		out.put("module", "ebx-connectors");
		ConnectorsStatus.version(out);
		final Adaptation dataset = ConnectorsStatus.dataset(pRepository, out);
		final ObjectNode install = out.putObject("installation");
		install.put("datasetPresent", dataset != null);
		install.put("modelPresent", dataset != null && dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN) != null
			&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING) != null);
		install.put("installerCompleted", ConnectorsInstaller.isInstallComplete());
		ConnectorsStatus.posture(out);
		ConnectorsStatus.clearSecrets(out, dataset);
		ConnectorsStatus.runs(out, dataset);
		ConnectorsStatus.outboundQueue(out, dataset);
		ConnectorsStatus.cdc(out, dataset);
		ConnectorsStatus.pool(out.putObject("asyncPool"), ConnectorService.asyncSlots());
		out.put("backgroundWorkStopped", BackgroundWork.isStopped());
		ConnectorsStatus.addOn(out);
		ConnectorsStatus.addOff(out);
		out.set("preview", com.on.ebx.connect.core.PreviewFeatures.status());
		return out;
	}

	/** The build this module is: what a post-install check compares with the package it just installed (audit C-08). */
	static void version(final ObjectNode pOut)
	{
		final ObjectNode version = pOut.putObject("version");
		version.put("version", ModuleVersion.version());
		version.put("commit", ModuleVersion.commit());
		version.put("builtAt", ModuleVersion.builtAt());
	}

	private static Adaptation dataset(final Repository pRepository, final ObjectNode pOut)
	{
		try
		{
			return new ConnectorRepository(pRepository == null ? Repository.getDefault() : pRepository).dataset();
		}
		catch (final RuntimeException ex)
		{
			pOut.put("datasetError", "the Connectors dataset could not be read; see the kernel log");
			com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] status: the dataset could not be read: "
				+ com.on.ebx.connect.core.Redaction.redact(String.valueOf(ex.getMessage())));
			return null;
		}
	}

	/** The protections that depend on a JVM property, as they are right now. */
	static void posture(final ObjectNode pOut)
	{
		final ObjectNode egress = pOut.putObject("egress");
		egress.put("allowListSet", Egress.enabled());
		egress.put("requireAllowList", Egress.allowListRequired());
		egress.put("blockPrivateHosts", Egress.privateHostsBlocked());
		egress.put("privateExceptions", Egress.privateExceptionCount());
		// audit X3-01: where each list comes from, how many entries were read and how many can never match (a path without file: reads as 1 ignored)
		ConnectorsStatus.listReport(egress.putObject("privateExceptionsList"), Egress.privateExceptionsReport());
		ConnectorsStatus.listReport(egress.putObject("allowListSource"), Egress.allowedHostsReport());
		// audit C2-06: what the module accepts from a caller, as these JVM properties read right now
		final ObjectNode limits = pOut.putObject("requestLimits");
		limits.put("mcpMaxBodyBytes", com.on.ebx.connect.ebx.rest.BodyLimit.maxMcpBodyBytes());
		limits.put("mcpMaxBatch", com.on.ebx.connect.ebx.rest.BodyLimit.maxMcpBatch());
		limits.put("mcpMaxJsonDepth", com.on.ebx.connect.ebx.rest.BodyLimit.maxMcpDepth());
		limits.put("restMaxBodyBytes", com.on.ebx.connect.ebx.rest.BodyLimit.maxRestBodyBytes());
		limits.put("restMaxJsonDepth", com.on.ebx.connect.ebx.rest.BodyLimit.maxRestDepth());
		final ObjectNode retention = pOut.putObject("retention");
		retention.put("enabled", Retention.enabled());
		retention.put("days", Retention.days());
		// the purge is on unless -Debx.connectors.log.retentionDays=0 (owner decision of 2026-10-06): said as such
		retention.put("onByDefault", Retention.onByDefault());
		pOut.putObject("secrets").put("clearValuesAllowed", Secrets.clearSecretsAllowed());
	}

	private static void listReport(final ObjectNode pOut, final Egress.ListReport pReport)
	{
		pOut.put("property", pReport.property);
		pOut.put("source", pReport.source);
		if (pReport.path != null)
		{
			pOut.put("path", pReport.path);
			pOut.put("fileReadable", pReport.fileReadable);
		}
		pOut.put("entries", pReport.entries);
		pOut.put("ignored", pReport.ignored);
		pOut.put("looksLikePath", pReport.looksLikePath);
		if (pReport.warning != null)
		{
			pOut.put("warning", pReport.warning);
		}
	}

	/**
	 * The connections that hold a secret in clear: the exact count and the first {@link ClearSecrets#MAX_LISTED}, each as
	 * the connector id and where ({@code password}, {@code apiKey}, {@code property <key>}). Never a value.
	 */
	static void clearSecrets(final ObjectNode pOut, final Adaptation pDataset)
	{
		final ObjectNode secrets = (ObjectNode) pOut.get("secrets");
		try
		{
			final ClearSecrets.Report report = ClearSecrets.cachedScan(pDataset);
			secrets.put("clearSecretCount", report.count());
			final ArrayNode listed = secrets.putArray("clearSecrets");
			for (final ClearSecrets.Finding finding : report.listed())
			{
				listed.addObject().put("connector", finding.connectorId()).put("where", finding.where());
			}
			secrets.put("clearSecretsKnown", pDataset != null);
			// file:/env: references this instance refuses (no -D...secrets.dir / envAllowed): the connections that will fail at use
			final ClearSecrets.Report refused = ClearSecrets.scanRefusedReferences(pDataset);
			secrets.put("refusedReferenceCount", refused.count());
			final ArrayNode refusedList = secrets.putArray("refusedReferences");
			for (final ClearSecrets.Finding finding : refused.listed())
			{
				refusedList.addObject().put("connector", finding.connectorId()).put("where", finding.where());
			}
		}
		catch (final RuntimeException ex)
		{
			secrets.put("clearSecretsKnown", false);
		}
	}

	/** Runs the table says are running, counted from one read bounded at {@link #MAX_RUNNING_COUNTED}. */
	private static void runs(final ObjectNode pOut, final Adaptation pDataset)
	{
		final ObjectNode runs = pOut.putObject("runs");
		if (pDataset == null || pDataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN) == null
			|| pDataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.STATUS)) == null)
		{
			runs.put("running", 0);
			runs.put("known", false);
			return;
		}
		try
		{
			final Request request = pDataset.getTable(ConnectorsPaths.EXECUTION_RUN).createRequest();
			request.setXPathFilter("./status='RUNNING'");
			int count = 0;
			boolean capped = false;
			final RequestResult rows = request.execute();
			try
			{
				while (rows.nextAdaptation() != null)
				{
					if (++count >= ConnectorsStatus.MAX_RUNNING_COUNTED)
					{
						capped = true;
						break;
					}
				}
			}
			finally
			{
				rows.close();
			}
			runs.put("running", count);
			runs.put("runningCapped", capped);
			runs.put("known", true);
		}
		catch (final RuntimeException ex)
		{
			runs.put("running", 0);
			runs.put("known", false);
		}
	}

	/**
	 * The outbound queue of the table triggers (audit C3-12): rows waiting, rows already tried and waiting again, rows set
	 * aside (DEAD) and how long the oldest waiting row has waited. Every figure is one count by predicate
	 * ({@code RequestResult.getSize()}, no walk of the table) except the age, which reads ONE row: the first waiting row in
	 * key order, the key being an auto-increment, i.e. the first queued. The table holds only waiting and set-aside rows
	 * (the sender deletes what it sent and the purge deletes old DEAD rows), so its size is the backlog an operator wants to see.
	 */
	private static void outboundQueue(final ObjectNode pOut, final Adaptation pDataset)
	{
		final ObjectNode queue = pOut.putObject("outboundQueue");
		if (pDataset == null || pDataset.getSchemaNode().getNode(ConnectorsPaths.OUTBOUND_QUEUE) == null)
		{
			queue.put("known", false);
			return;
		}
		try
		{
			final AdaptationTable table = pDataset.getTable(ConnectorsPaths.OUTBOUND_QUEUE);
			final int pending = ConnectorsStatus.count(table, "./status='PENDING'");
			final int retrying = pending == 0 ? 0 : ConnectorsStatus.count(table, "./status='PENDING' and ./attempts > 0");
			final int dead = ConnectorsStatus.count(table, "./status='DEAD'");
			Date oldest = null;
			if (pending > 0)
			{
				final Request request = table.createRequest();
				request.setXPathFilter("./status='PENDING'");
				final RequestResult rows = request.execute();
				try
				{
					final Adaptation first = rows.nextAdaptation();
					oldest = first == null ? null : (Date) first.get(ConnectorsPaths.OutboundQueue.ENQUEUED_AT);
				}
				finally
				{
					rows.close();
				}
			}
			ConnectorsStatus.queueFigures(queue, pending, retrying, dead, oldest, System.currentTimeMillis());
		}
		catch (final RuntimeException ex)
		{
			queue.put("known", false);
			ConnectorService.log().warn("[connectors] status: the outbound queue could not be counted: "
				+ com.on.ebx.connect.core.Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	/** One count by predicate: the table's own count, no walk of its rows. */
	private static int count(final AdaptationTable pTable, final String pPredicate)
	{
		final Request request = pTable.createRequest();
		request.setXPathFilter(pPredicate);
		final RequestResult rows = request.execute();
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The figures of the outbound queue as the answer writes them. {@code retrying} = waiting rows that were already tried
	 * (a failure is being retried), {@code dead} = set aside after the mapping's maximum number of attempts (an administrator
	 * deletes the row or sets it back to PENDING with attempts 0). {@code pPendingOldest} is null when nothing waits.
	 */
	static void queueFigures(final ObjectNode pQueue, final int pPending, final int pRetrying, final int pDead, final Date pPendingOldest, final long pNowMs)
	{
		pQueue.put("known", true);
		pQueue.put("pending", pPending);
		pQueue.put("retrying", pRetrying);
		pQueue.put("dead", pDead);
		if (pPendingOldest != null)
		{
			pQueue.put("oldestPendingAt", ConnectorsStatus.iso(pPendingOldest));
			pQueue.put("oldestPendingAgeSeconds", Math.max(0L, (pNowMs - pPendingOldest.getTime()) / 1000L));
		}
	}

	/**
	 * The change-data-capture flows (mappings on operation {@code cdc}, audit C3-12): per flow whether a run holds it now on this
	 * node, and the resume position the last applied batch saved (the {@code SynchronizationCheckpoint} row of the mapping, one read
	 * by key: the Kafka offsets, the time they were saved and the number of saves). At most {@link #MAX_CDC_FLOWS} are listed.
	 * The last FAILURE of a flow is not computed here: it lives in {@code ExecutionRun}, whose rows are found only by walking the
	 * table (no usable index), which a status probe must not do. Read it with {@code connectors_run_get} or the kernel log.
	 */
	private static void cdc(final ObjectNode pOut, final Adaptation pDataset)
	{
		final ObjectNode cdc = pOut.putObject("cdc");
		if (pDataset == null || pDataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING) == null)
		{
			cdc.put("known", false);
			return;
		}
		try
		{
			final AdaptationTable checkpoints = pDataset.getSchemaNode().getNode(ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT) == null ? null
				: pDataset.getTable(ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT);
			final Request request = pDataset.getTable(ConnectorsPaths.MAPPING).createRequest();
			request.setXPathFilter("./operation='cdc'");
			final ArrayNode flows = cdc.putArray("flows");
			int listed = 0;
			boolean capped = false;
			final RequestResult rows = request.execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (listed >= ConnectorsStatus.MAX_CDC_FLOWS)
					{
						capped = true;
						break;
					}
					listed++;
					final int id = ((Number) row.get(ConnectorsPaths.Mapping.ID)).intValue();
					final Adaptation checkpoint = checkpoints == null ? null : checkpoints.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(String.valueOf(id)));
					final Number version = checkpoint == null ? null : (Number) checkpoint.get(ConnectorsPaths.SynchronizationCheckpoint.VERSION);
					ConnectorsStatus.cdcFlow(flows.addObject(), id, row.getString(ConnectorsPaths.Mapping.LABEL), row.getString(ConnectorsPaths.Mapping.CONNECTOR),
						!Boolean.FALSE.equals(row.get(ConnectorsPaths.Mapping.ACTIVE)), ConnectorService.runningRun(id), checkpoint != null,
						checkpoint == null ? null : checkpoint.getString(ConnectorsPaths.SynchronizationCheckpoint.VALUE),
						checkpoint == null ? null : (Date) checkpoint.get(ConnectorsPaths.SynchronizationCheckpoint.UPDATED_AT),
						version == null ? 0L : version.longValue(), System.currentTimeMillis());
				}
			}
			finally
			{
				rows.close();
			}
			cdc.put("known", true);
			cdc.put("flowCount", listed);
			cdc.put("capped", capped);
			cdc.put("lastFailure", "not computed here: read it with connectors_run_get (ExecutionRun) or the kernel log");
		}
		catch (final RuntimeException ex)
		{
			cdc.put("known", false);
			ConnectorService.log().warn("[connectors] status: the change-data-capture flows could not be read: "
				+ com.on.ebx.connect.core.Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	/**
	 * One CDC flow as the answer writes it. {@code savedAt} is the last time a batch was applied and its position saved: a pass
	 * that found no event does not move it, so an old {@code savedAtAgeSeconds} on an active flow is a flow with nothing to read
	 * OR a flow that cannot apply; the run history says which. No checkpoint yet: the first run has not applied a batch.
	 */
	static void cdcFlow(final ObjectNode pFlow, final int pMappingId, final String pLabel, final String pConnector, final boolean pActive,
		final String pRunningRunId, final boolean pHasCheckpoint, final String pPosition, final Date pSavedAt, final long pSaves, final long pNowMs)
	{
		pFlow.put("mapping", pMappingId);
		pFlow.put("label", pLabel == null ? "" : pLabel);
		pFlow.put("connector", pConnector == null ? "" : pConnector);
		pFlow.put("active", pActive);
		pFlow.put("runningNow", pRunningRunId != null);
		pFlow.put("checkpointSaved", pHasCheckpoint);
		if (!pHasCheckpoint)
		{
			return;
		}
		final String position = pPosition == null ? "" : pPosition;
		final boolean cut = position.length() > ConnectorsStatus.MAX_POSITION_CHARS;
		pFlow.put("position", cut ? position.substring(0, ConnectorsStatus.MAX_POSITION_CHARS) : position);
		pFlow.put("positionCut", cut);
		pFlow.put("saves", pSaves);
		if (pSavedAt != null)
		{
			pFlow.put("savedAt", ConnectorsStatus.iso(pSavedAt));
			pFlow.put("savedAtAgeSeconds", Math.max(0L, (pNowMs - pSavedAt.getTime()) / 1000L));
		}
	}

	/** UTC with the seconds always written (LocalDateTime.toString drops them when they are zero, which EBX then refuses). */
	private static String iso(final Date pDate)
	{
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(pDate);
	}

	/** Slots of the asynchronous pool: {@code {inUse, maxConcurrent, maxQueued}} as {@link ConnectorService#asyncSlots()} gives them. */
	static void pool(final ObjectNode pOut, final int[] pSlots)
	{
		final int inUse = pSlots[0];
		final int maxConcurrent = pSlots[1];
		final int maxQueued = pSlots[2];
		pOut.put("maxConcurrent", maxConcurrent);
		pOut.put("maxQueued", maxQueued);
		pOut.put("running", Math.min(inUse, maxConcurrent));
		pOut.put("queued", Math.max(0, inUse - maxConcurrent));
		pOut.put("free", Math.max(0, maxConcurrent + maxQueued - inUse));
	}

	/** In words, what protects the module without being asked for: the retention purge, which is on unless its age is set to 0. */
	private static void addOn(final ObjectNode pOut)
	{
		final ArrayNode on = pOut.putArray("onByDefault");
		if (Retention.enabled())
		{
			on.add("retention: ON" + (Retention.onByDefault() ? " by default" : "") + ": sync log lines, decided conflicts, finished runs and publication reports older than "
				+ Retention.days() + " days are deleted by a background task (open conflicts and running runs never; -D" + Retention.DAYS_PROPERTY + "=0 turns it off)");
		}
	}

	/** In words, what is off until its property is set: the part of the answer a person reads first. */
	private static void addOff(final ObjectNode pOut)
	{
		final ArrayNode off = pOut.putArray("offUntilConfigured");
		if (!Egress.enabled() && !Egress.allowListRequired())
		{
			off.add("outbound allow-list: every host is reachable (set -D" + Egress.PROPERTY + ", or -D" + Egress.REQUIRE_PROPERTY + "=true to refuse calls until it is)");
		}
		if (!Egress.privateHostsBlocked())
		{
			off.add("private hosts: loopback and private addresses are reachable because -D" + Egress.BLOCK_PRIVATE_PROPERTY
				+ "=false is set (remove it, and list the hosts that need it in -D" + Egress.PRIVATE_EXCEPTIONS_PROPERTY + ")");
		}
		if (!Retention.enabled())
		{
			off.add("retention: the log, decided conflicts, finished runs and publication reports are kept for ever (-D" + Retention.DAYS_PROPERTY
				+ " is 0; unset it, or give a number of days, to purge)");
		}
		if (Secrets.clearSecretsAllowed())
		{
			off.add("secret storage: a secret typed in clear in the configuration is accepted because -D" + Secrets.ALLOW_CLEAR_PROPERTY
				+ "=true is set (remove it, and re-enter the secrets in the EBX user interface or use vault:/env:/file: references)");
		}
	}
}
