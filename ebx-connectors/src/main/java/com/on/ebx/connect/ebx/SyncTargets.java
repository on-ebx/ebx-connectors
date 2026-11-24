package com.on.ebx.connect.ebx;

import java.util.List;

import com.on.ebx.connect.core.ImportOutcome;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.SyncResult;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.XPathExpressionHelper;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/** Resolves "what to synchronise" from the string parameters of beans, tasks and triggers. */
public final class SyncTargets
{
	public static final class Target
	{
		private final Adaptation dataset;
		private final Adaptation record;
		private final Request request;
		private final String tablePath;

		Target(final Adaptation pDataset, final Adaptation pRecord, final Request pRequest, final String pTablePath)
		{
			this.dataset = pDataset;
			this.record = pRecord;
			this.request = pRequest;
			this.tablePath = pTablePath;
		}

		public Adaptation getDataset()
		{
			return this.dataset;
		}

		/** One record, or null when a whole table is targeted. */
		public Adaptation getRecord()
		{
			return this.record;
		}

		public Request getRequest()
		{
			return this.request;
		}

		public String getTablePath()
		{
			return this.tablePath;
		}

		public String getDataspaceName()
		{
			return this.dataset.getHome().getKey().getName();
		}

		public String getDatasetName()
		{
			return this.dataset.getAdaptationName().getStringName();
		}
	}

	public static final class Summary
	{
		private int ok;
		private int changed;
		private int noData;
		private int skipped;
		private int errors;
		private int conflicts;
		private final StringBuilder text = new StringBuilder();

		public int getOk()
		{
			return this.ok;
		}

		public int getChanged()
		{
			return this.changed;
		}

		public int getNoData()
		{
			return this.noData;
		}

		public int getSkipped()
		{
			return this.skipped;
		}

		public int getConflicts()
		{
			return this.conflicts;
		}

		public int getErrors()
		{
			return this.errors;
		}

		public String getText()
		{
			return this.text.toString();
		}
	}

	private SyncTargets()
	{
	}

	public static Target resolve(final Session pSession, final Repository pRepository, final String pDataspace, final String pDataset,
		final String pXpath, final String pTablePath, final String pPredicate) throws OperationException
	{
		if (SyncTargets.isBlank(pDataspace) || SyncTargets.isBlank(pDataset))
		{
			throw OperationException.createError("dataspace and dataset are required");
		}
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pDataspace.trim()));
		if (home == null)
		{
			throw OperationException.createError("Dataspace '" + pDataspace + "' not found");
		}
		final Adaptation dataset = home.findAdaptationOrNull(AdaptationName.forName(pDataset.trim()));
		if (dataset == null)
		{
			throw OperationException.createError("Dataset '" + pDataset + "' not found in " + pDataspace);
		}
		if (pSession == null || pSession.getPermissions().getAdaptationAccessPermission(dataset).isHidden())
		{
			throw OperationException.createError("Dataset is unavailable or restricted");
		}
		if (!SyncTargets.isBlank(pXpath))
		{
			// A workflow launched from an after-create trigger can reach this step before the creating
			// transaction is committed: give the record a few seconds to appear before giving up.
			Adaptation record = XPathExpressionHelper.lookupFirstRecordMatchingXPath(true, dataset, pXpath.trim());
			for (int attempt = 0; record == null && attempt < 10; attempt++)
			{
				try
				{
					Thread.sleep(500L);
				}
				catch (final InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					break;
				}
				record = XPathExpressionHelper.lookupFirstRecordMatchingXPath(true, dataset, pXpath.trim());
			}
			if (record == null)
			{
				throw OperationException.createError("No record matches " + pXpath);
			}
			if (pSession.getPermissions().getAdaptationAccessPermission(record).isHidden())
			{
				throw OperationException.createError("Record is unavailable or restricted");
			}
			return new Target(dataset, record, null, record.getContainerTable().getTablePath().format());
		}
		if (SyncTargets.isBlank(pTablePath))
		{
			throw OperationException.createError("Either xpath or tablePath is required");
		}
		final AdaptationTable table = dataset.getTable(Path.parse(pTablePath.trim()));
		final Request request = table.createRequest();
		request.setSession(pSession);
		if (!SyncTargets.isBlank(pPredicate))
		{
			request.setXPathFilter(pPredicate.trim());
		}
		return new Target(dataset, null, request, table.getTablePath().format());
	}

	public static MappingDefinition.Direction direction(final String pValue)
	{
		return "OUTBOUND".equalsIgnoreCase(pValue == null ? "" : pValue.trim()) ? MappingDefinition.Direction.OUTBOUND
				: MappingDefinition.Direction.INBOUND;
	}

	public static Summary summarise(final List<SyncResult> pResults)
	{
		final Summary s = new Summary();
		String limit = null;
		for (final SyncResult r : pResults)
		{
			if (r.isLimitNotice())
			{
				// the run's "limit reached" notice is not a record: said once, after the counts (audit C-35)
				limit = r.getMessage();
				continue;
			}
			s.conflicts += r.getConflicts().size();
			switch (r.hasFailure() ? SyncResult.Status.ERROR : r.getStatus())
			{
			case OK:
				s.ok++;
				s.changed++;
				break;
			case DRY_RUN:
			case NO_CHANGE:
				s.ok++;
				break;
			case NO_DATA:
				s.noData++;
				break;
			case SKIPPED:
				s.skipped++;
				break;
			case ERROR:
			default:
				s.errors++;
				break;
			}
			if (s.text.length() < 3500)
			{
				s.text.append(r.getRecordLabel()).append(": ").append(r.getStatus()).append(" - ").append(r.getMessage())
						.append('\n');
			}
		}
		s.text.insert(0, SyncResult.countWithoutLimitNotices(pResults) + " record(s): " + s.changed + " updated, " + (s.ok - s.changed)
				+ " unchanged, " + s.noData + " without data, " + s.skipped + " skipped, " + s.errors + " error(s)\n"
				+ (limit == null ? "" : limit + "\n"));
		SyncTargets.appendBookkeeping(s, com.on.ebx.connect.core.Bookkeeping.lostIn(pResults));
		return s;
	}

	/**
	 * Volume lot 7a: the same {@link Summary} shape, built directly from an import feed's running
	 * counters ({@link ImportOutcome}) instead of walking a per-row list that a multi-million-row feed
	 * no longer keeps in full - see {@code ConnectorService.importFrom}. The text sample only ever
	 * covers {@link ImportOutcome#getResults()}'s bounded sample; {@link ImportOutcome#isResultsOmitted()}
	 * is noted in the header line when the run had more rows than that.
	 */
	public static Summary summarise(final ImportOutcome pOutcome)
	{
		final Summary s = new Summary();
		s.ok = pOutcome.getCreated() + pOutcome.getUpdated() + pOutcome.getNoChange();
		s.changed = pOutcome.getCreated() + pOutcome.getUpdated();
		s.skipped = pOutcome.getSkipped() + pOutcome.getRejected();
		s.errors = pOutcome.getErrorCount();
		s.conflicts = pOutcome.getConflicts();
		for (final SyncResult r : pOutcome.getResults())
		{
			if (s.text.length() < 3500)
			{
				s.text.append(r.getRecordLabel()).append(": ").append(r.getStatus()).append(" - ").append(r.getMessage()).append('\n');
			}
		}
		final int total = pOutcome.getCount() + pOutcome.getSkipped();
		s.text.insert(0, total + " item(s): " + s.changed + " created/updated, " + pOutcome.getNoChange() + " unchanged, "
				+ s.skipped + " skipped/rejected, " + s.errors + " error(s)"
				+ (pOutcome.isResultsOmitted() ? " (sample of " + pOutcome.getResults().size() + " shown; see the sync log for the rest)" : "") + "\n");
		SyncTargets.appendBookkeeping(s, pOutcome.getBookkeepingLost());
		return s;
	}

	/** A scheduler execution or a workflow report must say when rows about the run were lost: nobody else will. */
	private static void appendBookkeeping(final Summary pSummary, final java.util.Map<String, Integer> pLost)
	{
		final String notice = com.on.ebx.connect.core.Bookkeeping.describe(pLost);
		if (notice != null)
		{
			pSummary.text.append(notice).append('\n');
		}
	}

	public static int toInt(final String pValue, final int pDefault)
	{
		try
		{
			return Integer.parseInt(pValue.trim());
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}

	public static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
