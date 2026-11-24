package com.on.ebx.connect.ebx.workflow;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.RunVerdict;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.ebx.SyncTargets;
import com.on.ebx.connect.ebx.rest.Mappings;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * Workflow script task of the connectors module. Target: one record ({@code dataspace},
 * {@code dataset}, {@code xpath}) or a table ({@code tablePath} + optional {@code predicate},
 * capped by {@code maxRecords}). What runs, in this order of precedence:
 * <ul>
 * <li>{@code mappings} — ordered list of mapping ids, labels or {@code connector/operation}
 * ({@code 2,1,30} or {@code GLEIF/searchByRegistration,VIES/check});</li>
 * <li>{@code connectors} — ordered list of connector ids (all their inbound mappings of the
 * table), optionally {@code connector/operation};</li>
 * <li>{@code connector} + {@code operation} (+ {@code direction}) — one mapping, the historical form;</li>
 * <li>nothing — every active inbound mapping of the table.</li>
 * </ul>
 * Outputs: {@code report} (one line per call), {@code changedCount}, {@code okCount},
 * {@code errorCount}, {@code status} (OK, PARTIAL, ERROR, NO_DATA). {@code failOnError=true}
 * makes the step fail when a call fails. Declared in module.xml.
 * <p>
 * A workflow routes on outputs, so the step does not fail by default. For the same reason nothing
 * that makes the run less than a success may be missing from them: {@code truncated} says that a
 * feed or the table held more than {@code maxRecords}, and {@code problems} lists every cause found
 * ({@code errors}, {@code rejected}, {@code conflicts}, {@code truncated}, {@code stopped} - see
 * {@link RunVerdict}), empty for a clean run. A feed whose call to the source failed as a whole
 * counts as one failed call: its item counters alone would read "no data".
 * <p>
 * {@code warnings} names the run-time {@code inputs} that no mapping of the step read (only a feed reads them, as
 * {@code ${param.name}}): such an input is not used, and the step says so there and at the top of {@code report}
 * instead of dropping it in silence (audit C-35). {@code maxRecords} defaults to
 * {@link com.on.ebx.connect.core.RunLimits#API}.
 */
public final class ConnectorScriptTaskBean extends ScriptTaskBean
{
	private String mappings;
	private String connectors;
	private String connector;
	private String operation;
	private String direction = "INBOUND";
	private String dataspace;
	private String dataset;
	private String xpath;
	private String tablePath;
	private String predicate;
	private String maxRecords = String.valueOf(com.on.ebx.connect.core.RunLimits.API);
	private String dryRun = "false";
	private String failOnError = "false";
	private String scope = "all";
	private String inputs;
	private String runId;
	private String report;
	private String changedCount;
	private String okCount;
	private String errorCount;
	private String status;
	private String conflictCount;
	private String hasConflicts;
	private String truncated;
	private String problems;
	private String warnings;

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), pContext.getSession());
		final SyncTargets.Target target = SyncTargets.resolve(pContext.getSession(), pContext.getRepository(), this.dataspace, this.dataset,
			this.xpath, this.tablePath, this.predicate);
		final ConnectorRepository repo = ConnectorService.get().repository(pContext.getRepository());
		final List<MappingDefinition> selected = this.select(repo, target);
		if (selected.isEmpty())
		{
			throw OperationException.createError("No mapping selected for " + target.getTablePath() + " (mappings=" + this.mappings + ", connectors="
					+ this.connectors + ", connector=" + this.connector + "/" + this.operation + ")");
		}
		final boolean dry = "true".equalsIgnoreCase(this.dryRun);
		final int max = SyncTargets.toInt(this.maxRecords, com.on.ebx.connect.core.RunLimits.API);
		// run-time inputs that no mapping of this step read: said in warnings and in the report, never dropped in silence (audit C-35).
		// Only a feed reads them, as ${param.name}; a run on records or on a scope reads none.
		final java.util.Set<String> usedInputs = new java.util.HashSet<String>();
		final List<SyncResult> results = new ArrayList<SyncResult>();
		// Volume lot 7a: a feed mapping's importFrom now returns counters + a bounded results sample
		// (com.on.ebx.connect.core.ImportOutcome), not one SyncResult per row held for the whole run -
		// a step mixing a feed with record-level mappings merges the feed's SyncTargets.summarise(outcome)
		// into these running totals instead of folding its (possibly huge) results into the shared list.
		int feedOk = 0, feedChanged = 0, feedErrors = 0, feedConflicts = 0;
		final StringBuilder feedText = new StringBuilder();
		final RunVerdict feedVerdict = RunVerdict.clean();
		boolean moreThanMax = false;
		this.runId = ConnectorService.beginRun();
		// the records of this step: mappings read once, bookkeeping written in groups (audit C-13)
		final ConnectorService.RecordRunScope recordRun = ConnectorService.beginRecordRun(pContext.getSession(), pContext.getRepository());
		try
		{
			final com.on.ebx.connect.core.OutboundScope outboundScope = com.on.ebx.connect.core.OutboundScope.parse(this.scope);
			final String origin = "workflow " + pContext.getSession().getUserReference().getUserId();
			if (target.getRecord() != null)
			{
				Adaptation record = target.getRecord();
				final AdaptationTable table = record.getContainerTable();
				for (final MappingDefinition mapping : selected)
				{
					results.add(ConnectorService.get().syncRecord(pContext.getSession(), record, mapping, dry, origin));
					record = table.lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey()); // the next mapping sees what this one wrote
				}
			}
			else
			{
				for (final MappingDefinition mapping : selected)
				{
					if (mapping.isFeed())
					{
						final List<String> ignored = new ArrayList<String>();
						final com.on.ebx.connect.core.ImportOutcome outcome = ConnectorService.get().importFrom(pContext.getSession(), pContext.getRepository(), mapping,
							ConnectorService.runtimeInputs(mapping, this.inputs, ignored), max, dry, origin);
						for (final String name : ConnectorService.inputNames(this.inputs))
						{
							if (!ignored.contains(name))
							{
								usedInputs.add(name);
							}
						}
						final SyncTargets.Summary feedSummary = SyncTargets.summarise(outcome);
						final RunVerdict oneFeed = RunVerdict.of(outcome);
						feedVerdict.merge(oneFeed);
						feedOk += feedSummary.getOk();
						feedChanged += feedSummary.getChanged();
						// A feed that failed as a whole has no item in error: it is one failed call all the same.
						feedErrors += feedSummary.getErrors() == 0 && oneFeed.has(RunVerdict.Cause.ERRORS) ? 1 : feedSummary.getErrors();
						feedConflicts += feedSummary.getConflicts();
						feedText.append(feedSummary.getText());
						continue;
					}
					if (outboundScope != com.on.ebx.connect.core.OutboundScope.ALL && mapping.getDirection() == MappingDefinition.Direction.OUTBOUND)
					{
						try
						{
							results.addAll(ConnectorService.get().sendScope(pContext.getSession(), pContext.getRepository(), mapping,
								target.getDataset().getTable(com.orchestranetworks.schema.Path.parse(target.getTablePath())),
								this.predicate == null || this.predicate.trim().isEmpty() ? null : target.getRequest(), outboundScope, max, dry, origin));
						}
						catch (final com.on.ebx.connect.spi.ConnectorException ex)
						{
							throw OperationException.createError(ex.getMessage());
						}
						continue;
					}
					// each record of the table, all mappings in order on that record
					final List<Adaptation> records = new ArrayList<Adaptation>();
					final RequestResult rows = target.getRequest().execute();
					try
					{
						Adaptation row = rows.nextAdaptation();
						for (; row != null && records.size() < max; row = rows.nextAdaptation())
						{
							records.add(row);
						}
						// A record read and not taken: the table holds more than this step may process.
						moreThanMax |= row != null;
					}
					finally
					{
						rows.close();
					}
					for (final Adaptation row : records)
					{
						results.add(ConnectorService.get().syncRecord(pContext.getSession(), row, mapping, dry, origin));
					}
				}
			}
		}
		finally
		{
			recordRun.close();
			ConnectorService.endRun();
		}
		final SyncTargets.Summary summary = SyncTargets.summarise(results);
		final int totalOk = summary.getOk() + feedOk;
		final int totalChanged = summary.getChanged() + feedChanged;
		final int totalErrors = summary.getErrors() + feedErrors;
		final int totalConflicts = summary.getConflicts() + feedConflicts;
		// a workflow variable holds 180 characters at most (the instance record fails validation beyond);
		// the full report stays in the synchronisation log of the run
		final java.util.Set<String> ignoredInputs = new java.util.LinkedHashSet<String>(ConnectorService.inputNames(this.inputs));
		ignoredInputs.removeAll(usedInputs);
		final String warning = ignoredInputs.isEmpty() ? "" : com.on.ebx.connect.core.RunLimits.ignoredInputsMessage(new ArrayList<String>(ignoredInputs));
		this.warnings = warning.length() <= 180 ? warning : warning.substring(0, 177) + "...";
		final String text = (warning.isEmpty() ? "" : "WARNING: " + warning + "\n") + summary.getText() + feedText;
		this.report = text.length() <= 180 ? text : text.substring(0, 177) + "...";
		this.changedCount = String.valueOf(totalChanged);
		this.okCount = String.valueOf(totalOk);
		this.errorCount = String.valueOf(totalErrors);
		this.conflictCount = String.valueOf(totalConflicts);
		this.hasConflicts = totalConflicts > 0 ? "true" : "false";
		this.status = totalErrors == 0 ? (totalOk == 0 ? "NO_DATA" : "OK") : (totalOk == 0 ? "ERROR" : "PARTIAL");
		// an outbound scope cut at maxRecords ends with its "limit reached" notice
		moreThanMax |= SyncResult.limitMessage(results) != null;
		final RunVerdict verdict = RunVerdict.of(results, moreThanMax).merge(feedVerdict);
		this.truncated = verdict.has(RunVerdict.Cause.TRUNCATED) ? "true" : "false";
		this.problems = verdict.keys();
		if ("true".equalsIgnoreCase(this.failOnError) && totalErrors > 0)
		{
			throw OperationException.createError(totalErrors + " connector call(s) failed:\n" + text);
		}
	}

	private List<MappingDefinition> select(final ConnectorRepository pRepo, final SyncTargets.Target pTarget) throws OperationException
	{
		final List<MappingDefinition> out = new ArrayList<MappingDefinition>();
		final List<MappingDefinition> all = pRepo.mappingsFor(pTarget.getDataspaceName(), pTarget.getDatasetName(), pTarget.getTablePath());
		if (this.mappings != null && !this.mappings.trim().isEmpty())
		{
			for (final String token : this.mappings.split(","))
			{
				final MappingDefinition m = Mappings.mappingNamed(pRepo, token.trim());
				if (m == null)
				{
					throw OperationException.createError("No mapping '" + token.trim() + "'");
				}
				if (!m.appliesTo(pTarget.getDataspaceName(), pTarget.getDatasetName(), pTarget.getTablePath()))
				{
					throw OperationException.createError("Mapping " + m.getId() + " (" + m.getLabel() + ") is not a mapping of " + pTarget.getTablePath());
				}
				out.add(m);
			}
			return out;
		}
		if (this.connectors != null && !this.connectors.trim().isEmpty())
		{
			for (final String wanted : this.connectors.split(","))
			{
				final String token = wanted.trim();
				final int slash = token.indexOf('/');
				final String id = slash < 0 ? token : token.substring(0, slash);
				final String op = slash < 0 ? null : token.substring(slash + 1);
				for (final MappingDefinition m : all)
				{
					if (m.getDirection() == MappingDefinition.Direction.INBOUND && !m.isFeed() && m.getConnectorId().equalsIgnoreCase(id)
						&& (op == null || m.getOperation().equalsIgnoreCase(op)))
					{
						out.add(m);
					}
				}
			}
			return out;
		}
		if (this.connector != null && !this.connector.trim().isEmpty())
		{
			final MappingDefinition one;
			try
			{
				one = pRepo.mappingFor(pTarget.getDataspaceName(), pTarget.getDatasetName(), pTarget.getTablePath(), this.connector.trim(),
					this.operation == null ? "" : this.operation.trim(), SyncTargets.direction(this.direction));
			}
			catch (final IllegalStateException ambiguous)
			{
				throw OperationException.createError(ambiguous.getMessage());
			}
			if (one == null)
			{
				throw OperationException.createError("No " + SyncTargets.direction(this.direction) + " mapping for " + this.connector + "/" + this.operation + " on "
						+ pTarget.getTablePath());
			}
			out.add(one);
			return out;
		}
		for (final MappingDefinition m : all)
		{
			if (m.getDirection() == MappingDefinition.Direction.INBOUND && !m.isFeed())
			{
				out.add(m);
			}
		}
		return out;
	}

	/** {@code true} when a feed, or the table, held more than {@code maxRecords}: the step did not see everything. */
	public String getTruncated()
	{
		return this.truncated;
	}

	public void setTruncated(final String pTruncated)
	{
		this.truncated = pTruncated;
	}

	/** Every cause for which the run is not a plain success, comma-separated; empty for a clean run. */
	public String getProblems()
	{
		return this.problems;
	}

	public void setProblems(final String pProblems)
	{
		this.problems = pProblems;
	}

	/** Output: what the run has to say beyond its counters (run-time inputs no mapping read); empty when nothing. */
	public String getWarnings()
	{
		return this.warnings;
	}

	public void setWarnings(final String pWarnings)
	{
		this.warnings = pWarnings;
	}

	public String getMappings()
	{
		return this.mappings;
	}

	public void setMappings(final String pMappings)
	{
		this.mappings = pMappings;
	}

	public String getConnectors()
	{
		return this.connectors;
	}

	public void setConnectors(final String pConnectors)
	{
		this.connectors = pConnectors;
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

	public String getXpath()
	{
		return this.xpath;
	}

	public void setXpath(final String pXpath)
	{
		this.xpath = pXpath;
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

	public String getFailOnError()
	{
		return this.failOnError;
	}

	public void setFailOnError(final String pFailOnError)
	{
		this.failOnError = pFailOnError;
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

	public String getReport()
	{
		return this.report;
	}

	public void setReport(final String pReport)
	{
		this.report = pReport;
	}

	public String getChangedCount()
	{
		return this.changedCount;
	}

	public void setChangedCount(final String pChangedCount)
	{
		this.changedCount = pChangedCount;
	}

	public String getOkCount()
	{
		return this.okCount;
	}

	public void setOkCount(final String pOkCount)
	{
		this.okCount = pOkCount;
	}

	public String getErrorCount()
	{
		return this.errorCount;
	}

	public void setErrorCount(final String pErrorCount)
	{
		this.errorCount = pErrorCount;
	}

	public String getStatus()
	{
		return this.status;
	}

	public void setStatus(final String pStatus)
	{
		this.status = pStatus;
	}

	public String getConflictCount()
	{
		return this.conflictCount;
	}

	public void setConflictCount(final String pConflictCount)
	{
		this.conflictCount = pConflictCount;
	}

	public String getHasConflicts()
	{
		return this.hasConflicts;
	}

	public void setHasConflicts(final String pHasConflicts)
	{
		this.hasConflicts = pHasConflicts;
	}

	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	public String getRunId()
	{
		return this.runId;
	}

	public void setRunId(final String pRunId)
	{
		this.runId = pRunId;
	}
}
