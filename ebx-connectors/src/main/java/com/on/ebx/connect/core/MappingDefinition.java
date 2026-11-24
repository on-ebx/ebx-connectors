package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.SynchronizationStrategy;

/**
 * A mapping between one connector operation and one EBX table, in one direction.
 * <ul>
 * <li>{@code inputs}: operation inputs as expressions on the record, e.g.
 * {@code lei = ${./Identification/LegalEntityIdentifier}}.</li>
 * <li>{@code responseRoot}: JSON path applied to the document before the rules (inbound).</li>
 * <li>{@code rules}: the field rules, applied in order.</li>
 * </ul>
 */
public final class MappingDefinition
{
	public enum Direction
	{
		INBOUND, OUTBOUND
	}

	/** Whether every available item is requested or only changes after a persisted checkpoint. */
	public enum LoadMode { FULL, INCREMENTAL }

	private final int id;
	private final String connectorId;
	private final String operation;
	private final Direction direction;
	private final String label;
	private final String dataspace;
	private final String dataset;
	private final String tablePath;
	private final String responseRoot;
	private final boolean active;
	private final Map<String, String> inputs = new LinkedHashMap<String, String>();
	private final List<MappingRule> rules = new ArrayList<MappingRule>();
	private final List<MappingDefinition> children = new ArrayList<MappingDefinition>();
	private Integer parentId;
	private String collectionPath = "";
	private String parentLinkField = "";
	private String childKeyFields = "";
	private String childFilter = "";
	private boolean deleteMissing;
	private String itemsPath = "";
	private String keyPredicate = "";
	private boolean createIfMissing = true;
	private LoadMode loadMode = LoadMode.FULL;
	private SynchronizationStrategy checkpointStrategy;
	private String checkpointInput = "";
	private String checkpointValuePath = "";
	private String checkpointInitialValue = "";
	private String checkpointBoundary = "";
	private String deleteOperation = "";
	private int maxAttempts = 5;
	private int retryDelayMinutes = 30;
	private boolean provenance = true;
	private String unchangedIfEqual = "";
	private int batchSize = MappingDefinition.DEFAULT_BATCH_SIZE;
	private boolean logEveryRecord;

	/** Volume lot 6: default rows per import-feed write procedure when {@code batchSize} is unset. */
	public static final int DEFAULT_BATCH_SIZE = 1000;
	/** Volume lot 6: hard cap, regardless of what the mapping configuration asks for. */
	public static final int MAX_BATCH_SIZE = 10000;

	public MappingDefinition(final int pId, final String pConnectorId, final String pOperation,
		final Direction pDirection, final String pLabel, final String pDataspace, final String pDataset,
		final String pTablePath, final String pResponseRoot, final boolean pActive)
	{
		this.id = pId;
		this.connectorId = pConnectorId;
		this.operation = pOperation;
		this.direction = pDirection == null ? Direction.INBOUND : pDirection;
		this.label = pLabel;
		this.dataspace = Expressions.isBlank(pDataspace) ? null : pDataspace.trim();
		this.dataset = Expressions.isBlank(pDataset) ? null : pDataset.trim();
		this.tablePath = pTablePath == null ? "" : pTablePath.trim();
		this.responseRoot = pResponseRoot == null ? "" : pResponseRoot.trim();
		this.active = pActive;
	}

	public int getId()
	{
		return this.id;
	}

	public String getConnectorId()
	{
		return this.connectorId;
	}

	public String getOperation()
	{
		return this.operation;
	}

	public Direction getDirection()
	{
		return this.direction;
	}

	public String getLabel()
	{
		return Expressions.isBlank(this.label) ? this.connectorId + " / " + this.operation : this.label;
	}

	/** Dataspace restriction, null for any dataspace holding the dataset. */
	public String getDataspace()
	{
		return this.dataspace;
	}

	/** Dataset restriction, null for any dataset of the table's model. */
	public String getDataset()
	{
		return this.dataset;
	}

	public String getTablePath()
	{
		return this.tablePath;
	}

	public String getResponseRoot()
	{
		return this.responseRoot;
	}

	public boolean isActive()
	{
		return this.active;
	}

	public Map<String, String> getInputs()
	{
		return Collections.unmodifiableMap(this.inputs);
	}

	public void addInput(final String pName, final String pExpression)
	{
		if (!Expressions.isBlank(pName))
		{
			this.inputs.put(pName.trim(), pExpression == null ? "" : pExpression);
		}
	}

	public List<MappingRule> getRules()
	{
		return Collections.unmodifiableList(this.rules);
	}

	public void addRule(final MappingRule pRule)
	{
		this.rules.add(pRule);
	}

	// ---------------------------------------------------------------- sub-objects and feeds

	public List<MappingDefinition> getChildren()
	{
		return Collections.unmodifiableList(this.children);
	}

	public void addChild(final MappingDefinition pChild)
	{
		this.children.add(pChild);
	}

	public Integer getParentId()
	{
		return this.parentId;
	}

	public void setParentId(final Integer pParentId)
	{
		this.parentId = pParentId;
	}

	public boolean isChild()
	{
		return this.parentId != null;
	}

	/** JSON path of the collection in the parent document (sub-object mappings). */
	public String getCollectionPath()
	{
		return this.collectionPath;
	}

	/** Child table field holding the foreign key to the parent record, e.g. {@code ./company}. */
	public String getParentLinkField()
	{
		return this.parentLinkField;
	}

	/** Child fields identifying an existing row, comma-separated; empty means always create. */
	public List<String> getChildKeyFields()
	{
		final List<String> out = new ArrayList<String>();
		for (final String f : this.childKeyFields.split(","))
		{
			if (!f.trim().isEmpty())
			{
				out.add(f.trim());
			}
		}
		return out;
	}

	public String getChildFilter()
	{
		return this.childFilter;
	}

	public boolean isDeleteMissing()
	{
		return this.deleteMissing;
	}

	public void setSubObject(final String pCollectionPath, final String pParentLinkField, final String pChildKeyFields,
		final String pChildFilter, final boolean pDeleteMissing)
	{
		this.collectionPath = pCollectionPath == null ? "" : pCollectionPath.trim();
		this.parentLinkField = pParentLinkField == null ? "" : pParentLinkField.trim();
		this.childKeyFields = pChildKeyFields == null ? "" : pChildKeyFields.trim();
		this.childFilter = pChildFilter == null ? "" : pChildFilter.trim();
		this.deleteMissing = pDeleteMissing;
	}

	/** JSON path of the items array for feeds; empty when the document is a single item. */
	public String getItemsPath()
	{
		return this.itemsPath;
	}

	/** XPath predicate template locating the record of an item; non-empty makes this a feed. */
	public String getKeyPredicate()
	{
		return this.keyPredicate;
	}

	public boolean isCreateIfMissing()
	{
		return this.createIfMissing;
	}

	public boolean isFeed()
	{
		return !this.keyPredicate.isEmpty() && !this.isChild();
	}

	public void setFeed(final String pItemsPath, final String pKeyPredicate, final boolean pCreateIfMissing)
	{
		this.itemsPath = pItemsPath == null ? "" : pItemsPath.trim();
		this.keyPredicate = pKeyPredicate == null ? "" : pKeyPredicate.trim();
		this.createIfMissing = pCreateIfMissing;
	}

	public LoadMode getLoadMode() { return this.loadMode; }
	public SynchronizationStrategy getCheckpointStrategy() { return this.checkpointStrategy; }
	public String getCheckpointInput() { return this.checkpointInput; }
	public String getCheckpointValuePath() { return this.checkpointValuePath; }
	public String getCheckpointInitialValue() { return this.checkpointInitialValue; }

	/** Sets persisted policy only; no runtime checkpoint value is stored on the mapping. */
	public void setSynchronizationPolicy(final String pLoadMode, final String pStrategy,
		final String pInput, final String pValuePath, final String pInitialValue)
	{
		try { this.loadMode = pLoadMode == null ? LoadMode.FULL : LoadMode.valueOf(pLoadMode.trim()); }
		catch (final RuntimeException ex) { this.loadMode = LoadMode.FULL; }
		try { this.checkpointStrategy = pStrategy == null || pStrategy.trim().isEmpty() ? null : SynchronizationStrategy.valueOf(pStrategy.trim()); }
		catch (final RuntimeException ex) { this.checkpointStrategy = null; }
		this.checkpointInput = pInput == null ? "" : pInput.trim();
		this.checkpointValuePath = pValuePath == null ? "" : pValuePath.trim();
		this.checkpointInitialValue = pInitialValue == null ? "" : pInitialValue.trim();
		if (this.loadMode == LoadMode.FULL) { this.checkpointStrategy = null; }
	}

	// ---------------------------------------------------------------- volume lot V2-D: feed watermark

	/** Explicit boundary option, {@code INCLUSIVE} or {@code EXCLUSIVE}; empty means "decided by {@link #isWatermarkInclusive}". */
	public String getCheckpointBoundary()
	{
		return this.checkpointBoundary;
	}

	/** Accepts {@code INCLUSIVE} / {@code EXCLUSIVE} (any case); anything else, blank included, is the automatic choice. */
	public void setCheckpointBoundary(final String pBoundary)
	{
		final String value = pBoundary == null ? "" : pBoundary.trim().toUpperCase();
		this.checkpointBoundary = "INCLUSIVE".equals(value) || "EXCLUSIVE".equals(value) ? value : "";
	}

	/**
	 * Whether a row whose watermark EQUALS the checkpoint is read again. Rows written at the source with the
	 * very value the previous run ended on may have been committed after that run read them, so the safe
	 * choice is to read them again, and it is the automatic one exactly when the mapping has
	 * {@code unchangedIfEqual} (the hash compare then reports them NO_CHANGE without a write); without it
	 * they would be rewritten at every run, so the automatic choice is exclusive. An explicit
	 * {@code checkpointBoundary} wins.
	 */
	public boolean isWatermarkInclusive()
	{
		if ("INCLUSIVE".equals(this.checkpointBoundary))
		{
			return true;
		}
		if ("EXCLUSIVE".equals(this.checkpointBoundary))
		{
			return false;
		}
		return !this.unchangedIfEqual.isEmpty();
	}

	/**
	 * True when an import of this mapping reads only what changed at the source since its last successful
	 * run: a feed, {@code loadMode} INCREMENTAL, a row-watermark strategy ({@code TIMESTAMP},
	 * {@code MONOTONIC_ID}, {@code ORDERED_STRING}) and {@code checkpointValuePath}, the field of the
	 * returned row that carries the watermark.
	 */
	public boolean isWatermarkFeed()
	{
		return this.isFeed() && this.direction == Direction.INBOUND && this.loadMode == LoadMode.INCREMENTAL
			&& Watermark.Kind.of(this.checkpointStrategy) != null && !this.checkpointValuePath.isEmpty();
	}

	/** True when {@code loadMode} is INCREMENTAL with a row-watermark strategy on a feed but the field of the row is missing (a configuration error the policy reports). */
	boolean isWatermarkFeedWithoutField()
	{
		return this.isFeed() && this.direction == Direction.INBOUND && this.loadMode == LoadMode.INCREMENTAL
			&& Watermark.Kind.of(this.checkpointStrategy) != null && this.checkpointValuePath.isEmpty();
	}

	/** True when this mapping applies to a record of the given dataspace / dataset / table. */
	public boolean appliesTo(final String pDataspace, final String pDataset, final String pTablePath)
	{
		if (!this.tablePath.equals(pTablePath))
		{
			return false;
		}
		if (this.dataspace != null && !this.dataspace.equals(pDataspace))
		{
			return false;
		}
		return this.dataset == null || this.dataset.equals(pDataset);
	}

	/** Like {@link #appliesTo}, a null or blank dataspace accepting a mapping bound to any dataspace. */
	public boolean appliesToAnyDataspace(final String pDataspace, final String pDataset, final String pTablePath)
	{
		return this.appliesTo(Expressions.isBlank(pDataspace) ? this.dataspace : pDataspace.trim(), pDataset, pTablePath);
	}

	/**
	 * The dataspace a run of these mappings uses: the one given, else the one they are bound to, else null (the
	 * dataset is searched). Mappings bound to two different dataspaces are ambiguous: the caller must give one.
	 */
	public static String runDataspace(final String pDataspace, final List<MappingDefinition> pMappings)
	{
		if (!Expressions.isBlank(pDataspace))
		{
			return pDataspace.trim();
		}
		String found = null;
		for (final MappingDefinition mapping : pMappings)
		{
			if (mapping.dataspace == null || mapping.dataspace.equals(found))
			{
				continue;
			}
			if (found != null)
			{
				throw new IllegalArgumentException("The selected mappings are bound to dataspaces " + found + " and " + mapping.dataspace
					+ ": give the dataspace");
			}
			found = mapping.dataspace;
		}
		return found;
	}

	@Override
	public String toString()
	{
		return "#" + this.id + " " + this.connectorId + "/" + this.operation + " " + this.direction + " " + this.tablePath;
	}

	/** Operation called for deleted records (outbound scopes changed and dataspaceDelta); empty: deletions ignored. */
	public String getDeleteOperation()
	{
		return this.deleteOperation;
	}

	public int getMaxAttempts()
	{
		return this.maxAttempts;
	}

	public int getRetryDelayMinutes()
	{
		return this.retryDelayMinutes;
	}

	public void setOutboundPolicy(final String pDeleteOperation, final Integer pMaxAttempts, final Integer pRetryDelayMinutes)
	{
		this.deleteOperation = pDeleteOperation == null ? "" : pDeleteOperation.trim();
		this.maxAttempts = pMaxAttempts == null ? 5 : Math.max(1, pMaxAttempts.intValue());
		this.retryDelayMinutes = pRetryDelayMinutes == null ? 30 : Math.max(0, pRetryDelayMinutes.intValue());
	}

	// ---------------------------------------------------------------- volume lot 5

	/**
	 * Whether a synchronisation through this mapping records {@code FieldProvenance} rows (default
	 * {@code true}). A landing-style import feed whose target table already carries its own lineage
	 * fields ({@code _loadId}, {@code _sourceSystem}...) sets this to {@code false} so an import writes
	 * no provenance at all - the dominant per-row cost of a high-volume feed (ConnectorRepository's
	 * {@code recordProvenance}, one row read/write per changed field).
	 */
	public boolean isProvenance()
	{
		return this.provenance;
	}

	public void setProvenance(final boolean pProvenance)
	{
		this.provenance = pProvenance;
	}

	/**
	 * Path of a field of the TARGET table (e.g. {@code ./_rowHash}); empty means the check is off. When
	 * set and the target record already exists, an import feed compares the value already computed for
	 * this field against the record's current value ({@link MappingEngine#unchanged}) before applying
	 * anything: an exact match reports the whole row {@code NO_CHANGE} and skips the write entirely,
	 * including rules whose policy is {@code ALWAYS} on a technical field ({@code _loadId},
	 * {@code _receivedAt}...) that would otherwise always look "changed" on every reload of the same
	 * unchanged source row.
	 */
	public String getUnchangedIfEqual()
	{
		return this.unchangedIfEqual;
	}

	public void setUnchangedIfEqual(final String pUnchangedIfEqual)
	{
		this.unchangedIfEqual = pUnchangedIfEqual == null ? "" : pUnchangedIfEqual.trim();
	}

	// ---------------------------------------------------------------- volume lot 6

	/**
	 * Rows per write procedure for a non-dry-run import feed (default {@value #DEFAULT_BATCH_SIZE},
	 * hard-capped at {@value #MAX_BATCH_SIZE}). Ignored for a dry run (always one item at a time, no
	 * write) and for outbound synchronisation (unaffected by this lot).
	 */
	public int getBatchSize()
	{
		return this.batchSize;
	}

	public void setBatchSize(final Integer pBatchSize)
	{
		final int requested = pBatchSize == null ? MappingDefinition.DEFAULT_BATCH_SIZE : pBatchSize.intValue();
		this.batchSize = Math.max(1, Math.min(requested <= 0 ? MappingDefinition.DEFAULT_BATCH_SIZE : requested, MappingDefinition.MAX_BATCH_SIZE));
	}

	/**
	 * Default {@code false}: an import feed writes one {@code SyncLog} row per batch (counts + first/last
	 * key) instead of one per record. {@code true} restores the pre-lot-6 one-row-per-record logging.
	 */
	public boolean isLogEveryRecord()
	{
		return this.logEveryRecord;
	}

	public void setLogEveryRecord(final boolean pLogEveryRecord)
	{
		this.logEveryRecord = pLogEveryRecord;
	}
}
