package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Steward decisions on the conflicts logged by the TRUSTED policy: accept (the proposed value is
 * written into the record and becomes its provenance) or reject (the current value stays). Used by
 * the conflict manager user service (UI and workflow user task) and by the REST API.
 * <p>
 * Accepting is three transactions, and a failure between two of them must be neither silent nor stuck: the
 * conflict is first claimed ({@code OPEN} to {@link #APPLYING}, checked in the transaction itself), then the
 * record is written, then the conflict is closed. If the write fails the claim is given back; if the process
 * dies or the closing fails, the conflict stays {@code APPLYING}, which {@link #list} shows with the open ones,
 * and the next accept (or reject) on it settles it: closed when the record already holds the proposed value,
 * reopened and applied again otherwise.
 */
public final class ConflictResolver
{
	public static final String OPEN = "OPEN";
	/**
	 * Claimed by an acceptance that has not finished: the record may already hold the proposed value while the
	 * conflict is still not closed. Shown with the open conflicts, and settled by the next decision on it.
	 */
	public static final String APPLYING = "APPLYING";
	public static final String ACCEPTED = "ACCEPTED";
	public static final String REJECTED = "REJECTED";

	private static final Object[] CONFLICT_LOCKS = new Object[256];
	static
	{
		for (int i = 0; i < ConflictResolver.CONFLICT_LOCKS.length; i++)
		{
			ConflictResolver.CONFLICT_LOCKS[i] = new Object();
		}
	}

	/**
	 * Per-conflict-id striped lock: serializes concurrent {@link #accept} / {@link #reject} calls for
	 * the same conflict on this JVM, and {@link #accept}/{@link #reject} re-read the conflict's live
	 * status under the lock instead of trusting the caller's snapshot. This closes the double-decision
	 * race for a single-node deployment; a multi-node deployment still needs a real claim/version
	 * mechanism (not implemented here — see the security review of September 2026, finding 11).
	 */
	private static Object lockFor(final String pId)
	{
		return ConflictResolver.CONFLICT_LOCKS[(pId.hashCode() & Integer.MAX_VALUE) % ConflictResolver.CONFLICT_LOCKS.length];
	}

	/** Result of one decision. */
	public static final class Outcome
	{
		public final boolean ok;
		public final String message;

		Outcome(final boolean pOk, final String pMessage)
		{
			this.ok = pOk;
			this.message = pMessage;
		}
	}

	private final ConnectorRepository repository;

	/**
	 * What deciding many conflicts in one go reads again and again, kept for the life of this resolver (one request, one screen
	 * round trip): the mappings, all read by one pass over their three tables, and the connectors' configuration.
	 */
	private java.util.Map<Integer, MappingDefinition> mappingCache;
	private final java.util.Map<String, ConnectorConfig> connectorCache = new java.util.HashMap<String, ConnectorConfig>();

	public ConflictResolver(final ConnectorRepository pRepository)
	{
		this.repository = pRepository;
	}

	private MappingDefinition mappingOf(final int pId)
	{
		if (this.mappingCache == null)
		{
			final java.util.Map<Integer, MappingDefinition> loaded = new java.util.HashMap<Integer, MappingDefinition>();
			for (final MappingDefinition mapping : this.repository.mappings())
			{
				loaded.put(Integer.valueOf(mapping.getId()), mapping);
			}
			this.mappingCache = loaded;
		}
		return this.mappingCache.get(Integer.valueOf(pId));
	}

	private ConnectorConfig connectorOf(final String pId)
	{
		ConnectorConfig config = this.connectorCache.get(pId);
		if (config == null)
		{
			config = this.repository.connector(pId);
			if (config != null)
			{
				this.connectorCache.put(pId, config);
			}
		}
		return config;
	}

	private static String quote(final String pValue)
	{
		return Xpath.quote(pValue);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/**
	 * The XPath on the status of a listing: {@code OPEN} (the default) also lists what is {@code APPLYING}, since an
	 * acceptance that did not finish still needs a decision; any other status lists that status only.
	 */
	static String statusFilter(final String pStatus)
	{
		final String status = ConflictResolver.blank(pStatus) ? ConflictResolver.OPEN : pStatus.trim();
		if (ConflictResolver.OPEN.equals(status))
		{
			return "(./status=" + ConflictResolver.quote(ConflictResolver.OPEN) + " or ./status=" + ConflictResolver.quote(ConflictResolver.APPLYING) + ")";
		}
		return "./status=" + ConflictResolver.quote(status);
	}

	/** What an accept does with the status it finds, given whether the record already holds the proposed value. */
	enum Step
	{
		/** Claim the conflict (again), write the record, then close it. */
		APPLY,
		/** The record already holds the value: only the provenance and the closing are left. */
		FINISH,
		/** Close as rejected. */
		REJECT,
		/** Nothing to do: the conflict is decided, or the decision contradicts what happened. */
		REFUSE
	}

	static Step planAccept(final String pStatus, final boolean pRecordHoldsProposed)
	{
		if (ConflictResolver.OPEN.equals(pStatus))
		{
			return Step.APPLY;
		}
		return ConflictResolver.APPLYING.equals(pStatus) ? (pRecordHoldsProposed ? Step.FINISH : Step.APPLY) : Step.REFUSE;
	}

	/** A conflict being applied can be rejected only while the record does not hold the proposed value: otherwise the rejection would say the opposite of the data. */
	static Step planReject(final String pStatus, final boolean pRecordHoldsProposed)
	{
		if (ConflictResolver.OPEN.equals(pStatus))
		{
			return Step.REJECT;
		}
		return ConflictResolver.APPLYING.equals(pStatus) && !pRecordHoldsProposed ? Step.REJECT : Step.REFUSE;
	}

	/** True when the field already reads the proposed value, both as XML Schema text; an empty value and no value are the same. */
	static boolean holds(final String pCurrent, final String pProposed)
	{
		final String current = pCurrent == null ? "" : pCurrent;
		final String proposed = pProposed == null ? "" : pProposed;
		return current.equals(proposed);
	}

	/** {@code -Debx.connectors.conflicts.pageSize}: conflicts one listing returns or one screen shows. Default 200, at most 2000. */
	public static final String PAGE_SIZE_PROPERTY = "ebx.connectors.conflicts.pageSize";
	static final int DEFAULT_PAGE_SIZE = 200;
	static final int MAX_PAGE_SIZE = 2000;

	/** The page size asked for by the property: unset or unreadable gives the default, a value is kept between 1 and the maximum. */
	public static int pageSize()
	{
		return ConflictResolver.pageSize(System.getProperty(ConflictResolver.PAGE_SIZE_PROPERTY));
	}

	static int pageSize(final String pRaw)
	{
		if (ConflictResolver.blank(pRaw))
		{
			return ConflictResolver.DEFAULT_PAGE_SIZE;
		}
		try
		{
			return Math.min(ConflictResolver.MAX_PAGE_SIZE, Math.max(1, Integer.parseInt(pRaw.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return ConflictResolver.DEFAULT_PAGE_SIZE;
		}
	}

	/** One page of a listing: the conflicts read, newest first, and how many conflicts match in all (audit C-39). */
	public static final class Page
	{
		public final List<Adaptation> rows;
		/** Every conflict that matches, the ones not in {@link #rows} included. */
		public final int total;

		Page(final List<Adaptation> pRows, final int pTotal)
		{
			this.rows = pRows;
			this.total = pTotal;
		}

		public boolean truncated()
		{
			return this.total > this.rows.size();
		}
	}

	/** The XPath of a listing: a status (see {@link #statusFilter}) narrowed by the non-empty dataspace, dataset, table and record key. */
	static String filter(final String pStatus, final String pDataspace, final String pDataset, final String pTable, final String pRecordKey)
	{
		final StringBuilder filter = new StringBuilder(ConflictResolver.statusFilter(pStatus));
		if (!ConflictResolver.blank(pDataspace))
		{
			filter.append(" and ./dataspace=").append(ConflictResolver.quote(pDataspace));
		}
		if (!ConflictResolver.blank(pDataset))
		{
			filter.append(" and ./dataset=").append(ConflictResolver.quote(pDataset));
		}
		if (!ConflictResolver.blank(pTable))
		{
			filter.append(" and ./tablePath=").append(ConflictResolver.quote(pTable));
		}
		if (!ConflictResolver.blank(pRecordKey))
		{
			filter.append(" and ./recordKey=").append(ConflictResolver.quote(pRecordKey));
		}
		return filter.toString();
	}

	/**
	 * What {@link #filter} says, decided on the values of one conflict: used for conflicts fetched by key, which no request
	 * selects.
	 */
	static boolean matches(final String pStatus, final String pDataspace, final String pDataset, final String pTable, final String pRecordKey,
		final String pRowStatus, final String pRowDataspace, final String pRowDataset, final String pRowTable, final String pRowRecordKey)
	{
		final String wanted = ConflictResolver.blank(pStatus) ? ConflictResolver.OPEN : pStatus.trim();
		final boolean statusOk = ConflictResolver.OPEN.equals(wanted) ? ConflictResolver.OPEN.equals(pRowStatus) || ConflictResolver.APPLYING.equals(pRowStatus)
			: wanted.equals(pRowStatus);
		return statusOk && ConflictResolver.same(pDataspace, pRowDataspace) && ConflictResolver.same(pDataset, pRowDataset) && ConflictResolver.same(pTable, pRowTable)
			&& ConflictResolver.same(pRecordKey, pRowRecordKey);
	}

	private static boolean same(final String pWanted, final String pActual)
	{
		return ConflictResolver.blank(pWanted) || pWanted.equals(pActual);
	}

	/**
	 * The first {@code pMax} conflicts in a status (OPEN by default), narrowed by the non-empty filters, newest first, and the
	 * number of conflicts that match in all (audit C-39). The rows are read from one request that stops after {@code pMax}:
	 * a steward's screen, a REST call or a tool no longer loads every open conflict, and the total is the size EBX gives for
	 * the request, not a count of rows read. Newest first is the order of the primary key, an auto-increment, so no sort on
	 * a field that EBX does not index is asked for.
	 */
	public Page page(final String pStatus, final String pDataspace, final String pDataset, final String pTable, final String pRecordKey, final int pMax)
	{
		return this.page(null, pStatus, pDataspace, pDataset, pTable, pRecordKey, pMax);
	}

	/**
	 * Like {@link #page(String, String, String, String, String, int)} for a reader (audit C2-03): a conflict whose record the
	 * session may not read ({@link OriginVisibility}) is left out, and the total is lowered by the ones left out among the rows
	 * read. A null session is the platform: nothing is filtered.
	 */
	public Page page(final Session pSession, final String pStatus, final String pDataspace, final String pDataset, final String pTable, final String pRecordKey,
		final int pMax)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final Adaptation dataset = this.repository.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.CONFLICT) == null)
		{
			return new Page(out, 0);
		}
		final Request request = dataset.getTable(ConnectorsPaths.CONFLICT).createRequest();
		request.setXPathFilter(ConflictResolver.filter(pStatus, pDataspace, pDataset, pTable, pRecordKey));
		request.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(ConnectorsPaths.Conflict.ID, false));
		final RequestResult rows = request.execute();
		try
		{
			final int total = rows.getSize();
			int hidden = 0;
			while (out.size() < pMax)
			{
				final Adaptation row = rows.nextAdaptation();
				if (row == null)
				{
					break;
				}
				if (!OriginVisibility.canRead(pSession, row.getString(ConnectorsPaths.Conflict.DATASPACE), row.getString(ConnectorsPaths.Conflict.DATASET),
					row.getString(ConnectorsPaths.Conflict.TABLE_PATH), row.getString(ConnectorsPaths.Conflict.RECORD_KEY)))
				{
					hidden++;
					continue;
				}
				out.add(row);
			}
			return new Page(out, Math.max(total - hidden, out.size()));
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The conflicts of a selection (the rows ticked in the Conflict table), by key: each is read by its primary key and kept
	 * when it is in the status and the narrowing asked for. At most {@code pMax} are returned, newest first, and the total
	 * counts every selected conflict that qualifies.
	 */
	public Page pageOfIds(final java.util.Collection<String> pIds, final String pStatus, final String pDataspace, final String pDataset, final String pTable,
		final String pRecordKey, final int pMax)
	{
		return this.pageOfIds(null, pIds, pStatus, pDataspace, pDataset, pTable, pRecordKey, pMax);
	}

	/** Like {@link #pageOfIds(java.util.Collection, String, String, String, String, String, int)} for a reader (audit C2-03): a conflict whose record the session may not read is left out. A null session is the platform. */
	public Page pageOfIds(final Session pSession, final java.util.Collection<String> pIds, final String pStatus, final String pDataspace, final String pDataset,
		final String pTable, final String pRecordKey, final int pMax)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		int total = 0;
		for (final String id : ConflictResolver.newestFirst(pIds))
		{
			final Adaptation row = this.conflict(id);
			if (row == null || !ConflictResolver.matches(pStatus, pDataspace, pDataset, pTable, pRecordKey, row.getString(ConnectorsPaths.Conflict.STATUS),
				row.getString(ConnectorsPaths.Conflict.DATASPACE), row.getString(ConnectorsPaths.Conflict.DATASET), row.getString(ConnectorsPaths.Conflict.TABLE_PATH),
				row.getString(ConnectorsPaths.Conflict.RECORD_KEY)))
			{
				continue;
			}
			if (!OriginVisibility.canRead(pSession, row.getString(ConnectorsPaths.Conflict.DATASPACE), row.getString(ConnectorsPaths.Conflict.DATASET),
				row.getString(ConnectorsPaths.Conflict.TABLE_PATH), row.getString(ConnectorsPaths.Conflict.RECORD_KEY)))
			{
				continue;
			}
			total++;
			if (out.size() < pMax)
			{
				out.add(row);
			}
		}
		return new Page(out, total);
	}

	/** The keys of a selection, the highest number first (the newest conflict), keys that are not numbers last in their own order. */
	static List<String> newestFirst(final java.util.Collection<String> pIds)
	{
		final List<String> ids = new ArrayList<String>(pIds);
		java.util.Collections.sort(ids, new java.util.Comparator<String>()
		{
			@Override
			public int compare(final String pA, final String pB)
			{
				final Long a = ConflictResolver.number(pA);
				final Long b = ConflictResolver.number(pB);
				if (a != null && b != null)
				{
					return b.compareTo(a);
				}
				return a != null ? -1 : b != null ? 1 : pA.compareTo(pB);
			}
		});
		return ids;
	}

	private static Long number(final String pId)
	{
		try
		{
			return Long.valueOf(pId.trim());
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Conflicts in a status (OPEN by default), narrowed by the non-empty filters: at most {@link #pageSize()} of them, newest first. */
	public List<Adaptation> list(final String pStatus, final String pDataspace, final String pDataset, final String pTable,
		final String pRecordKey)
	{
		return this.page(pStatus, pDataspace, pDataset, pTable, pRecordKey, ConflictResolver.pageSize()).rows;
	}

	/** How many conflicts are open (APPLYING ones included), counted by EBX without reading them. */
	public int countOpen(final String pDataspace, final String pDataset, final String pTable, final String pRecordKey)
	{
		return this.page(ConflictResolver.OPEN, pDataspace, pDataset, pTable, pRecordKey, 0).total;
	}

	public Adaptation conflict(final String pId)
	{
		final Adaptation dataset = this.repository.dataset();
		if (dataset == null || ConflictResolver.blank(pId))
		{
			return null;
		}
		return dataset.getTable(ConnectorsPaths.CONFLICT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId.trim()));
	}

	/** The record a conflict is about, or null when it no longer exists. */
	public Adaptation recordOf(final Adaptation pConflict)
	{
		final String dataspace = pConflict.getString(ConnectorsPaths.Conflict.DATASPACE);
		final AdaptationHome home = this.repository.getRepository().lookupHome(HomeKey.forBranchName(dataspace));
		if (home == null)
		{
			return null;
		}
		final Adaptation dataset = home.findAdaptationOrNull(AdaptationName.forName(pConflict.getString(ConnectorsPaths.Conflict.DATASET)));
		if (dataset == null)
		{
			return null;
		}
		final AdaptationTable table = dataset.getTable(Path.parse(pConflict.getString(ConnectorsPaths.Conflict.TABLE_PATH)));
		return table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConflict.getString(ConnectorsPaths.Conflict.RECORD_KEY)));
	}

	/**
	 * Writes the proposed value into the record, records its provenance, and closes the conflict as ACCEPTED, after
	 * claiming it so that a failure on the way leaves a conflict that says so (see the class comment).
	 */
	public Outcome accept(final Session pSession, final Adaptation pConflict, final String pComment)
	{
		if (!com.on.ebx.connect.ebx.ConnectorsAccess.has(pSession, com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN))
		{
			// the conflict row is then written with all privileges: the right is checked here too, not only at the entry points
			return new Outcome(false, com.on.ebx.connect.ebx.ConnectorsAccess.refusal("deciding a conflict", com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN));
		}
		final String id = pConflict.getOccurrencePrimaryKey().format();
		synchronized (ConflictResolver.lockFor(id))
		{
		final Adaptation fresh = this.conflict(id);
		final String found = fresh == null ? null : fresh.getString(ConnectorsPaths.Conflict.STATUS);
		if (!ConflictResolver.OPEN.equals(found) && !ConflictResolver.APPLYING.equals(found))
		{
			return new Outcome(false, "conflict " + id + " is not open");
		}
		if (DamaProposals.isAssetPath(pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH)))
		{
			final String closed = ConflictResolver.assetRefusal(pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH));
			if (closed != null)
			{
				return new Outcome(false, closed);
			}
			return this.acceptOnAsset(pSession, pConflict, found, pComment);
		}
		final Adaptation record = this.recordOf(pConflict);
		if (record == null)
		{
			return new Outcome(false, "record " + pConflict.getString(ConnectorsPaths.Conflict.RECORD_KEY) + " no longer exists in "
					+ pConflict.getString(ConnectorsPaths.Conflict.TABLE_PATH));
		}
		final String fieldPath = pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH);
		final String proposed = pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE);
		final String before = ConflictResolver.xs(record, fieldPath);
		// on a list field (the documents connector's tags), a proposal is one value to add, never the whole list
		final boolean list = ConflictResolver.isList(record, fieldPath);
		final Step step = ConflictResolver.planAccept(found, ConflictResolver.holdsOn(record, fieldPath, proposed));
		if (step == Step.APPLY)
		{
			final Outcome claimed = this.change(pSession, pConflict, found, ConflictResolver.APPLYING, null, false);
			if (!claimed.ok)
			{
				return claimed;
			}
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, record.getHome());
			service.setSessionTrackingInfo("Connector conflict accepted: " + pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_CONNECTOR));
			final ProcedureResult result = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final ValueContextForUpdate update = pContext.getContext(record.getAdaptationName());
					if (list)
					{
						final com.orchestranetworks.schema.SchemaNode node = record.getSchemaNode().getNode(Path.parse(fieldPath));
						final Adaptation now = record.getContainerTable().lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey());
						final Object current = now == null ? null : now.get(node);
						final List<Object> values = current instanceof List ? new ArrayList<Object>((List<?>) current) : new ArrayList<Object>();
						if (proposed != null) values.add(node.parseXsString(proposed));
						update.setValue(values, Path.parse(fieldPath));
					}
					else if (proposed == null)
					{
						update.setValue(null, Path.parse(fieldPath));
					}
					else
					{
						update.setValueFromXsString(proposed, Path.parse(fieldPath));
					}
					pContext.doModifyContent(record, update);
				}
			});
			if (result.hasFailed())
			{
				// nothing was written: the claim is given back so that the conflict reads as it did
				final Outcome reopened = this.change(pSession, pConflict, ConflictResolver.APPLYING, found, null, false);
				return new Outcome(false, String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH))
					+ (reopened.ok ? "" : " (the conflict stays " + ConflictResolver.APPLYING + ": accept it again to retry)"));
			}
		}
		// the accepted source becomes the provenance of the field, with the trust it had when it proposed
		boolean provenanceSaved = true;
		final Adaptation written = record.getContainerTable().lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey());
		final ConnectorConfig config = this.connectorOf(pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_CONNECTOR));
		if (config != null && written != null)
		{
			final Integer trust = (Integer) pConflict.get(ConnectorsPaths.Conflict.PROPOSED_TRUST);
			final ConnectorConfig asProposed = ConflictResolver.copy(config, trust == null ? config.getTrust() : trust.intValue());
			MappingDefinition mapping = null;
			try
			{
				final String mappingId = pConflict.getString(ConnectorsPaths.Conflict.MAPPING);
				mapping = mappingId == null ? null : this.mappingOf(Integer.parseInt(mappingId));
			}
			catch (final RuntimeException ex)
			{
				mapping = null;
			}
			final List<MappingResult.Change> changes = new ArrayList<MappingResult.Change>();
			// Gartner lot A: a document-feed proposal keeps its document, page and snippet in the provenance of the accepted value
			final String detected = pConflict.getString(ConnectorsPaths.Conflict.COMMENT);
			changes.add(new MappingResult.Change(fieldPath, before, proposed, detected != null && detected.startsWith("confidence ") ? detected : null));
			provenanceSaved = this.repository.recordProvenance(pSession, written, mapping, asProposed, changes);
		}
		final String claimedStatus = step == Step.APPLY ? ConflictResolver.APPLYING : found;
		final Outcome closed = this.close(pSession, pConflict, claimedStatus, ConflictResolver.ACCEPTED, pComment);
		if (!closed.ok)
		{
			// the record holds the value and the conflict is not closed: say so, the next accept settles it
			return new Outcome(false, closed.message + " (the record was changed; the conflict stays " + ConflictResolver.APPLYING
				+ ": accept it again to close it)");
		}
		// The value is accepted either way; the steward is told when its provenance could not follow,
		// because a later TRUSTED rule will then compare against the previous source.
		return !provenanceSaved
			? new Outcome(true, closed.message + " (the provenance of the field could not be updated: see the kernel log)") : closed;
		}
	}

	/** Keeps the current value and closes the conflict as REJECTED. */
	public Outcome reject(final Session pSession, final Adaptation pConflict, final String pComment)
	{
		if (!com.on.ebx.connect.ebx.ConnectorsAccess.has(pSession, com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN))
		{
			// the conflict row is then written with all privileges: the right is checked here too, not only at the entry points
			return new Outcome(false, com.on.ebx.connect.ebx.ConnectorsAccess.refusal("deciding a conflict", com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN));
		}
		final String id = pConflict.getOccurrencePrimaryKey().format();
		synchronized (ConflictResolver.lockFor(id))
		{
		final Adaptation fresh = this.conflict(id);
		final String found = fresh == null ? null : fresh.getString(ConnectorsPaths.Conflict.STATUS);
		if (!ConflictResolver.OPEN.equals(found) && !ConflictResolver.APPLYING.equals(found))
		{
			return new Outcome(false, "conflict " + id + " is not open");
		}
		boolean holds = false;
		if (ConflictResolver.APPLYING.equals(found) && DamaProposals.isAssetPath(pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH)))
		{
			// a DAMA proposal: the asset, read with the steward's session, is what may already hold the value
			final DamaProposals.Target target = DamaProposals.parse(pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH));
			try
			{
				final DamaAssets.Asset asset = target == null ? null : this.assetOf(pSession, pConflict, target, false);
				holds = asset != null && target.uuid.equals(asset.uuid) && DamaProposals.holds(asset, target, pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE));
			}
			catch (final com.on.ebx.connect.spi.ConnectorException ex)
			{
				holds = false;
			}
		}
		else if (ConflictResolver.APPLYING.equals(found))
		{
			final Adaptation record = this.recordOf(pConflict);
			holds = record != null && ConflictResolver.holdsOn(record, pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH),
				pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE));
		}
		if (ConflictResolver.planReject(found, holds) != Step.REJECT)
		{
			return new Outcome(false, "conflict " + id + " was being accepted and the record already holds the proposed value: accept it to close it");
		}
		return this.close(pSession, pConflict, found, ConflictResolver.REJECTED, pComment);
		}
	}

	/**
	 * Why a conflict on this field cannot be accepted, or null: a {@code dama:} proposal writes into DAMA, which belongs to the {@code documents}
	 * preview function (closed by default, audit C3-05 and bilan 1.4.1 gap 1). The conflict row stays open; opening the function makes it
	 * acceptable again.
	 */
	static String assetRefusal(final String pFieldPath)
	{
		if (!DamaProposals.isAssetPath(pFieldPath))
		{
			return null;
		}
		return PreviewFeatures.isOpen(PreviewFeatures.Feature.DOCUMENTS) ? null : PreviewFeatures.closedMessage(PreviewFeatures.Feature.DOCUMENTS);
	}

	/**
	 * Accepts a proposal on a DAMA asset (documents connector, {@code annotate}): the asset is opened again from the record that holds it,
	 * under the steward's session (DAMA's rights, and the media field read-write for the steward), must still be the asset of the
	 * proposal, and then gets the metadata value or the tag, with the same claim / write / close steps as a record field.
	 */
	private Outcome acceptOnAsset(final Session pSession, final Adaptation pConflict, final String pFound, final String pComment)
	{
		final String fieldPath = pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH);
		final String proposed = pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE);
		final DamaProposals.Target target = DamaProposals.parse(fieldPath);
		if (target == null)
		{
			return new Outcome(false, "conflict field " + fieldPath + " is not a DAMA proposal");
		}
		final DamaAssets.Asset asset;
		try
		{
			asset = this.assetOf(pSession, pConflict, target, true);
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			return new Outcome(false, ex.getMessage());
		}
		if (!target.uuid.equals(asset.uuid))
		{
			return new Outcome(false, "the document of this record changed since the proposal (asset " + target.uuid + ", now " + asset.uuid + "): reject it");
		}
		final Step step = ConflictResolver.planAccept(pFound, DamaProposals.holds(asset, target, proposed));
		if (step == Step.APPLY)
		{
			final Outcome claimed = this.change(pSession, pConflict, pFound, ConflictResolver.APPLYING, null, false);
			if (!claimed.ok)
			{
				return claimed;
			}
			try
			{
				DamaProposals.apply(DamaAssets.get(), asset, target, proposed);
			}
			catch (final com.on.ebx.connect.spi.ConnectorException ex)
			{
				// nothing was kept by DAMA: the claim is given back so that the conflict reads as it did
				final Outcome reopened = this.change(pSession, pConflict, ConflictResolver.APPLYING, pFound, null, false);
				return new Outcome(false, ex.getMessage() + (reopened.ok ? "" : " (the conflict stays " + ConflictResolver.APPLYING + ": accept it again to retry)"));
			}
		}
		final Outcome closed = this.close(pSession, pConflict, step == Step.APPLY ? ConflictResolver.APPLYING : pFound, ConflictResolver.ACCEPTED, pComment);
		return closed.ok ? closed
			: new Outcome(false, closed.message + " (the asset was changed; the conflict stays " + ConflictResolver.APPLYING + ": accept it again to close it)");
	}

	/** The asset a DAMA proposal is about, opened from the record that holds it with the given session. */
	private DamaAssets.Asset assetOf(final Session pSession, final Adaptation pConflict, final DamaProposals.Target pTarget, final boolean pForWrite)
		throws com.on.ebx.connect.spi.ConnectorException
	{
		final DamaAssets.Ref ref = new DamaAssets.Ref(pConflict.getString(ConnectorsPaths.Conflict.DATASPACE), pConflict.getString(ConnectorsPaths.Conflict.DATASET),
			pConflict.getString(ConnectorsPaths.Conflict.TABLE_PATH), pConflict.getString(ConnectorsPaths.Conflict.RECORD_KEY), pTarget.field, null);
		return DamaAssets.get().open(this.repository.getRepository(), pSession, ref, pForWrite);
	}

	private Outcome close(final Session pSession, final Adaptation pConflict, final String pFrom, final String pStatus, final String pComment)
	{
		final Outcome done = this.change(pSession, pConflict, pFrom, pStatus, pComment, true);
		if (!done.ok)
		{
			return done;
		}
		return new Outcome(true, pStatus.equals(ConflictResolver.ACCEPTED) ? "accepted: " + pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH)
				+ " = " + pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE) : "rejected: " + pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH)
				+ " keeps " + pConflict.getString(ConnectorsPaths.Conflict.CURRENT_VALUE));
	}

	/**
	 * Moves a conflict from one status to another in one transaction that first checks, on the stored row, that it
	 * still has the expected status: a decision made meanwhile by another session is never overwritten.
	 *
	 * @param pDecided true for the decision that closes it: comment, user and date are written
	 */
	private Outcome change(final Session pSession, final Adaptation pConflict, final String pFrom, final String pTo, final String pComment, final boolean pDecided)
	{
		final String user = pSession.getUserReference() == null ? null : pSession.getUserReference().getUserId();
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pConflict.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// the conflict row is read only to a RUN holder by hand (audit C-01, ConnectorsTableAccessRule): the decision
				// writes it with all privileges, once accept/reject checked the RUN right. The RECORD the value goes into is
				// still written with the caller's own session and permissions (accept)
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate update = pContext.getContext(pConflict.getAdaptationName());
				final Object currentStatus = update.getValue(ConnectorsPaths.Conflict.STATUS);
				if (!pFrom.equals(currentStatus))
				{
					throw new IllegalStateException("conflict " + pConflict.getOccurrencePrimaryKey().format()
							+ " was already " + currentStatus + " by another decision");
				}
				update.setValue(pTo, ConnectorsPaths.Conflict.STATUS);
				if (pDecided)
				{
					if (!ConflictResolver.blank(pComment))
					{
						update.setValue(pComment.length() > 500 ? pComment.substring(0, 500) : pComment, ConnectorsPaths.Conflict.COMMENT);
					}
					if (pConflict.getSchemaNode().getNode(ConnectorsPaths.Conflict.RESOLVED_BY) != null)
					{
						update.setValue(user, ConnectorsPaths.Conflict.RESOLVED_BY);
						update.setValue(new Date(), ConnectorsPaths.Conflict.RESOLVED_AT);
					}
				}
				pContext.doModifyContent(pConflict, update);
			}
		});
		return result.hasFailed() ? new Outcome(false, String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH))) : new Outcome(true, "");
	}

	/** True when the field is a list (maxOccurs above 1): a proposal on it is one value to add. */
	static boolean isList(final Adaptation pRecord, final String pFieldPath)
	{
		try
		{
			final com.orchestranetworks.schema.SchemaNode node = pRecord.getSchemaNode().getNode(Path.parse(pFieldPath));
			return node != null && node.getMaxOccurs() > 1;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/** True when the record already holds the proposal: the same value, or, on a list field, the value among the list's. */
	private static boolean holdsOn(final Adaptation pRecord, final String pFieldPath, final String pProposed)
	{
		if (!ConflictResolver.isList(pRecord, pFieldPath)) return ConflictResolver.holds(ConflictResolver.xs(pRecord, pFieldPath), pProposed);
		final com.orchestranetworks.schema.SchemaNode node = pRecord.getSchemaNode().getNode(Path.parse(pFieldPath));
		final Object current = pRecord.get(Path.parse(pFieldPath));
		if (!(current instanceof List)) return pProposed == null;
		for (final Object item : (List<?>) current)
		{
			if (item != null && node.formatToXsString(item).equals(pProposed)) return true;
		}
		return false;
	}

	private static String xs(final Adaptation pRecord, final String pFieldPath)
	{
		try
		{
			final com.orchestranetworks.schema.SchemaNode node = pRecord.getSchemaNode().getNode(Path.parse(pFieldPath));
			final Object value = pRecord.get(Path.parse(pFieldPath));
			return value == null || node == null ? null : node.formatToXsString(value);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static ConnectorConfig copy(final ConnectorConfig pConfig, final int pTrust)
	{
		final ConnectorConfig c = new ConnectorConfig();
		c.setId(pConfig.getId());
		c.setType(pConfig.getType());
		c.setLabel(pConfig.getLabel());
		c.setTrust(pTrust);
		return c;
	}
}
