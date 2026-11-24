package com.on.ebx.connect.ebx.scheduler;

import java.util.List;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.RunVerdict;
import com.on.ebx.connect.core.ScheduledFailureAlert;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.ebx.SyncTargets;
import com.orchestranetworks.scheduler.ScheduledExecutionContext;
import com.orchestranetworks.scheduler.ScheduledTask;
import com.orchestranetworks.scheduler.ScheduledTaskInterruption;
import com.orchestranetworks.service.OperationException;

/**
 * Scheduled task: synchronises the records of a table (optionally filtered by an XPath predicate)
 * with a connector operation, {@code maxRecords} per run. Register it in Administration >
 * Task scheduler > Tasks with this class name; its parameters are the bean properties below.
 * <p>
 * Nobody watches a scheduled run, so the execution <b>fails</b> whenever the run is not a plain
 * success: items or records in error, a run that failed as a whole, rejected items, conflicts left
 * for a steward, a read cut at {@code maxRecords}, a run stopped before its end (see
 * {@link RunVerdict}). The data already written stays written; failing only makes the execution
 * visible as failed in the scheduler. {@code tolerate} lists the causes an operator accepts for this
 * task ({@code errors}, {@code rejected}, {@code conflicts}, {@code truncated}, {@code stopped}, or
 * {@code all}); a tolerated cause is still written in the execution information.
 * <p>
 * {@code maxRecords} keeps its default of 100 ({@link com.on.ebx.connect.core.RunLimits#SCHEDULER}): the maximum is handed to the connector as the
 * {@code max} input of its operation, and connector types bound it differently (a queue accepts 1 to
 * 10 or 1 to 32 messages per call), so there is no value that means "everything" for every source.
 * A feed that holds more than the maximum therefore fails with a message naming the parameter to
 * raise, instead of being cut in silence.
 */
public final class ConnectorScheduledTask extends ScheduledTask
{
	private String connector;
	private String operation;
	private String direction = "INBOUND";
	private String dataspace;
	private String dataset;
	private String tablePath;
	private String predicate;
	private String maxRecords = String.valueOf(com.on.ebx.connect.core.RunLimits.SCHEDULER);
	private String dryRun = "false";
	private String scope = "all";
	private String inputs;
	private String tolerate;

	@Override
	public void execute(final ScheduledExecutionContext pContext) throws OperationException, ScheduledTaskInterruption
	{
		// Read first: a misspelt tolerance must stop the task before it writes anything, not after.
		final java.util.Set<RunVerdict.Cause> tolerated;
		try
		{
			tolerated = RunVerdict.tolerated(this.tolerate);
		}
		catch (final IllegalArgumentException ex)
		{
			throw OperationException.createError("Parameter 'tolerate': " + ex.getMessage());
		}
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), pContext.getSession());
		final SyncTargets.Target target = SyncTargets.resolve(pContext.getSession(), pContext.getRepository(), this.dataspace, this.dataset,
			null, this.tablePath, this.predicate);
		final MappingDefinition.Direction dir = SyncTargets.direction(this.direction);
		final MappingDefinition mapping;
		try
		{
			mapping = ConnectorService.get().repository(pContext.getRepository()).mappingFor(
				target.getDataspaceName(), target.getDatasetName(), target.getTablePath(), this.connector, this.operation, dir);
		}
		catch (final IllegalStateException ambiguous)
		{
			throw OperationException.createError(ambiguous.getMessage());
		}
		if (mapping == null)
		{
			throw OperationException.createError("No " + dir + " mapping for " + this.connector + "/" + this.operation
					+ " on " + target.getTablePath());
		}
		final String runId = ConnectorService.beginRun();
		final int max = SyncTargets.toInt(this.maxRecords, com.on.ebx.connect.core.RunLimits.SCHEDULER);
		final RunVerdict verdict;
		try
		{
			final com.on.ebx.connect.core.OutboundScope outboundScope = com.on.ebx.connect.core.OutboundScope.parse(this.scope);
			final SyncTargets.Summary summary;
			if (mapping.isFeed())
			{
				final List<String> ignored = new java.util.ArrayList<String>();
				// Volume lot 7a: importFrom now reports counters + a bounded results sample
				// (com.on.ebx.connect.core.ImportOutcome) instead of one SyncResult per row held for
				// the whole run - SyncTargets.summarise(ImportOutcome) builds the same execution-log
				// text from those counters, without walking a per-row list a multi-million-row feed no
				// longer keeps in full.
				final com.on.ebx.connect.core.ImportOutcome outcome = ConnectorService.get().importFrom(pContext.getSession(), pContext.getRepository(), mapping,
					ConnectorService.runtimeInputs(mapping, this.inputs, ignored), max, "true".equalsIgnoreCase(this.dryRun), "scheduler");
				summary = SyncTargets.summarise(outcome);
				verdict = RunVerdict.of(outcome);
				if (!ignored.isEmpty())
				{
					pContext.addExecutionInformation("WARNING: " + com.on.ebx.connect.core.RunLimits.ignoredInputsMessage(ignored));
				}
			}
			else if (outboundScope != com.on.ebx.connect.core.OutboundScope.ALL && dir == MappingDefinition.Direction.OUTBOUND)
			{
				final List<SyncResult> results = ConnectorService.get().sendScope(pContext.getSession(), pContext.getRepository(), mapping,
					target.getDataset().getTable(com.orchestranetworks.schema.Path.parse(target.getTablePath())),
					this.predicate == null || this.predicate.trim().isEmpty() ? null : target.getRequest(), outboundScope,
					max, "true".equalsIgnoreCase(this.dryRun), "scheduler");
				summary = SyncTargets.summarise(results);
				// A scope other than "all" is a backlog: what one execution does not take, the next one does.
				// Reaching the maximum is therefore said, not failed.
				verdict = RunVerdict.of(results, false);
				final String limit = SyncResult.limitMessage(results);
				if (limit != null)
				{
					pContext.addExecutionInformation("maxRecords (" + max + ") reached: " + limit);
				}
			}
			else
			{
				final List<SyncResult> results = ConnectorService.get().syncRecords(pContext.getSession(), target.getRequest(), mapping,
					max, "true".equalsIgnoreCase(this.dryRun), "scheduler");
				summary = SyncTargets.summarise(results);
				// the run ends with a "limit reached" notice when the request held more than maxRecords (audit C-35)
				final boolean more = SyncResult.limitMessage(results) != null;
				// Without a predicate every execution starts again from the first record of the table: what
				// lies beyond the maximum is never reached, by this execution or by the next - a cut read.
				// With a predicate the task may be draining a backlog (records leave the predicate once
				// processed, as in a nightly enrichment of what is not enriched yet): that is said, not failed.
				final boolean wholeTable = this.predicate == null || this.predicate.trim().isEmpty();
				verdict = RunVerdict.of(results, more && wholeTable);
				if (more && !wholeTable)
				{
					pContext.addExecutionInformation("maxRecords (" + max + ") reached: more records match the predicate; they are taken by a later execution only if"
						+ " the predicate excludes what this one processed");
				}
			}
			if (!mapping.isFeed() && !ConnectorService.inputNames(this.inputs).isEmpty())
			{
				// a run on records or a scope reads no run-time input
				pContext.addExecutionInformation("WARNING: " + com.on.ebx.connect.core.RunLimits.ignoredInputsMessage(ConnectorService.inputNames(this.inputs)));
			}
			pContext.addExecutionInformation("run " + runId + ": " + summary.getText());
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			// a failed scheduled run like any other: counted towards the alert (audit C-18)
			ScheduledFailureAlert.onScheduledRun(pContext.getSession(), pContext.getRepository(), mapping, runId, true, ex.getMessage());
			throw OperationException.createError(ex.getMessage());
		}
		finally
		{
			ConnectorService.endRun();
		}
		final String failure = verdict.failureMessage(runId, tolerated, "maxRecords", "tolerate");
		// repeated failures of this mapping's scheduled run are told to the one connection the administrator chose, if any
		ScheduledFailureAlert.onScheduledRun(pContext.getSession(), pContext.getRepository(), mapping, runId, failure != null, failure);
		if (failure != null)
		{
			throw OperationException.createError(failure);
		}
		if (!verdict.isClean())
		{
			pContext.addExecutionInformation("tolerated by this task (tolerate=" + this.tolerate + "): " + verdict.describe());
		}
	}

	public String getConnector()
	{
		return this.connector;
	}

	public void setConnector(final String pConnector)
	{
		this.connector = pConnector;
	}

	public String getOperation()
	{
		return this.operation;
	}

	public void setOperation(final String pOperation)
	{
		this.operation = pOperation;
	}

	public String getDirection()
	{
		return this.direction;
	}

	public void setDirection(final String pDirection)
	{
		this.direction = pDirection;
	}

	public String getDataspace()
	{
		return this.dataspace;
	}

	public void setDataspace(final String pDataspace)
	{
		this.dataspace = pDataspace;
	}

	public String getDataset()
	{
		return this.dataset;
	}

	public void setDataset(final String pDataset)
	{
		this.dataset = pDataset;
	}

	public String getTablePath()
	{
		return this.tablePath;
	}

	public void setTablePath(final String pTablePath)
	{
		this.tablePath = pTablePath;
	}

	public String getPredicate()
	{
		return this.predicate;
	}

	public void setPredicate(final String pPredicate)
	{
		this.predicate = pPredicate;
	}

	public String getMaxRecords()
	{
		return this.maxRecords;
	}

	public void setMaxRecords(final String pMaxRecords)
	{
		this.maxRecords = pMaxRecords;
	}

	public String getDryRun()
	{
		return this.dryRun;
	}

	public void setDryRun(final String pDryRun)
	{
		this.dryRun = pDryRun;
	}

	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	/** Run-time inputs of a feed, {@code name=value;name=value}, read by the mapping as {@code ${param.name}}. */
	public String getInputs()
	{
		return this.inputs;
	}

	public void setInputs(final String pInputs)
	{
		this.inputs = pInputs;
	}

	/**
	 * Causes for which the execution is allowed to succeed although the run is not clean: a list among
	 * {@code errors}, {@code rejected}, {@code conflicts}, {@code truncated}, {@code stopped}, or
	 * {@code all}. Empty by default: the execution fails on any of them.
	 */
	public String getTolerate()
	{
		return this.tolerate;
	}

	public void setTolerate(final String pTolerate)
	{
		this.tolerate = pTolerate;
	}
}
