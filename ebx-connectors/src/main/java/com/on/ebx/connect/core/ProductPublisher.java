package com.on.ebx.connect.core;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.TablePublisher;
import com.on.ebx.connect.spi.TablePublisher.ColumnSpec;
import com.on.ebx.connect.spi.TablePublisher.Kind;
import com.on.ebx.connect.spi.TablePublisher.PublicationSpec;
import com.on.ebx.connect.spi.TablePublisher.TableSpec;
import com.orchestranetworks.service.Session;

/**
 * Publishes a data product — one or more Connectors root outbound mappings — to a
 * {@link TablePublisher} connector, one atomic commit for every table (§1, §2.3 of
 * {@code SPEC-product-publication.md}). v1: full reload only, Snowflake only.
 * <p>
 * EBX access is isolated in two seams so the interesting logic is unit-testable without a
 * repository: {@link #compute} takes already-resolved {@link MappingDefinition}s, a
 * {@link TablePublisher} instance and a {@link RecordSource} and touches no EBX API at all;
 * {@link #publish(Request)} is the thin, EBX-backed wrapper that resolves mappings and the
 * connector from the {@link ConnectorRepository}, builds the real {@link EbxRecordSource}, calls
 * {@link #compute}, then writes the bookkeeping (§2.3 step 7) best-effort around it.
 */
public final class ProductPublisher
{
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Z_][A-Z0-9_]*");
	private static final String[] TECHNICAL_COLUMNS = { "_EBX_KEY", "_RUN_ID", "_PUBLISHED_AT", "_CONTRACT_VERSION" };

	private final Session session;
	private final ConnectorRepository repository;

	public ProductPublisher(final Session pSession, final ConnectorRepository pRepository)
	{
		this.session = pSession;
		this.repository = pRepository;
	}

	// ------------------------------------------------------------------ request / report

	public static final class Request
	{
		private List<Integer> mappings = new ArrayList<Integer>();
		private String schema;
		private String product;
		private String contractVersion = "1";
		private boolean dryRun;
		private boolean recreate;
		private int max;
		private String dataspace;
		private String description = "";
		private String owner = "";
		private String domain = "";
		private String title = "";

		public List<Integer> getMappings()
		{
			return this.mappings;
		}

		public void setMappings(final List<Integer> pMappings)
		{
			this.mappings = pMappings == null ? new ArrayList<Integer>() : pMappings;
		}

		public String getSchema()
		{
			return this.schema;
		}

		public void setSchema(final String pSchema)
		{
			this.schema = pSchema;
		}

		public String getProduct()
		{
			return this.product;
		}

		public void setProduct(final String pProduct)
		{
			this.product = pProduct;
		}

		public String getContractVersion()
		{
			return this.contractVersion;
		}

		public void setContractVersion(final String pContractVersion)
		{
			this.contractVersion = pContractVersion == null || pContractVersion.trim().isEmpty() ? "1" : pContractVersion.trim();
		}

		public boolean isDryRun()
		{
			return this.dryRun;
		}

		public void setDryRun(final boolean pDryRun)
		{
			this.dryRun = pDryRun;
		}

		public boolean isRecreate()
		{
			return this.recreate;
		}

		public void setRecreate(final boolean pRecreate)
		{
			this.recreate = pRecreate;
		}

		/**
		 * Rows read per table; 0 (the default) reads every row. A positive value cuts each table there and the
		 * publication then ends {@code PARTIAL}, never {@code OK} (audit C-17). The operator ceiling
		 * {@code -Debx.connectors.publish.maxRowsPerTable} applies on top.
		 */
		public int getMax()
		{
			return this.max;
		}

		public void setMax(final int pMax)
		{
			this.max = Math.max(0, pMax);
		}

		/** Optional: business description of the product, written as a comment on the published tables. */
		public String getDescription()
		{
			return this.description;
		}

		public void setDescription(final String pDescription)
		{
			this.description = pDescription == null ? "" : pDescription.trim();
		}

		/** Optional: display name of the product, used as the title of the listing on the target. */
		public String getTitle()
		{
			return this.title;
		}

		public void setTitle(final String pTitle)
		{
			this.title = pTitle == null ? "" : pTitle.trim();
		}

		/** Optional: owner of the product, written as a tag on the published tables. */
		public String getOwner()
		{
			return this.owner;
		}

		public void setOwner(final String pOwner)
		{
			this.owner = pOwner == null ? "" : pOwner.trim();
		}

		/** Optional: business domain of the product, written as a tag on the published tables. */
		public String getDomain()
		{
			return this.domain;
		}

		public void setDomain(final String pDomain)
		{
			this.domain = pDomain == null ? "" : pDomain.trim();
		}

		/** Optional: given &gt; the mappings' own dataspace &gt; error if ambiguous ({@link MappingDefinition#runDataspace}). */
		public String getDataspace()
		{
			return this.dataspace;
		}

		public void setDataspace(final String pDataspace)
		{
			this.dataspace = pDataspace;
		}
	}

	/** One table of the report (§4). */
	public static final class TableReport
	{
		private int mapping;
		private String label = "";
		private String ebxTable = "";
		private String table = "";
		private String action = "";
		private int rows;
		private int batches;
		private int skippedMerged;
		private int skippedDeleted;
		private boolean truncated;
		private final List<ColumnSpec> columns = new ArrayList<ColumnSpec>();
		private final List<ObjectNode> sample = new ArrayList<ObjectNode>();

		public int getMapping()
		{
			return this.mapping;
		}

		public String getTable()
		{
			return this.table;
		}

		public String getAction()
		{
			return this.action;
		}

		public int getRows()
		{
			return this.rows;
		}

		public int getBatches()
		{
			return this.batches;
		}

		/** Records left out by the {@code mergedRecords=exclude} filter: state MERGED. */
		public int getSkippedMerged()
		{
			return this.skippedMerged;
		}

		/** Records left out by the {@code mergedRecords=exclude} filter: state DELETED. */
		public int getSkippedDeleted()
		{
			return this.skippedDeleted;
		}

		/** True when this table was cut at the row cap: the target holds only the rows read before it. */
		public boolean isTruncated()
		{
			return this.truncated;
		}

		public List<ColumnSpec> getColumns()
		{
			return this.columns;
		}

		public List<ObjectNode> getSample()
		{
			return this.sample;
		}

		ObjectNode toJson(final TablePublisher pPublisher, final boolean pDryRun)
		{
			final ObjectNode node = Json.object();
			node.put("mapping", this.mapping);
			node.put("label", this.label);
			node.put("ebxTable", this.ebxTable);
			node.put("table", this.table);
			node.put("action", this.action);
			node.put("rows", this.rows);
			node.put("batches", this.batches);
			if (this.truncated)
			{
				node.put("truncated", true);
			}
			if (this.skippedMerged > 0 || this.skippedDeleted > 0)
			{
				final ObjectNode skipped = node.putObject("skipped");
				skipped.put("merged", this.skippedMerged);
				skipped.put("deleted", this.skippedDeleted);
			}
			final ArrayNode columnsArray = node.putArray("columns");
			for (final ColumnSpec column : this.columns)
			{
				final ObjectNode c = columnsArray.addObject();
				c.put("name", column.getName());
				c.put("kind", column.getKind().name());
				c.put("type", pPublisher == null ? "" : pPublisher.nativeType(column));
				c.put("source", column.getSource());
				c.put("key", column.isKey());
				// 2026-09-30: what the column means, as published (the catalog keeps it on the contract's ProductField)
				final TablePublisher.ColumnContext meaning = column.getContext();
				if (meaning != null)
				{
					final ObjectNode m = c.putObject("meaning");
					if (!meaning.getBusinessName().isEmpty()) m.put("businessName", meaning.getBusinessName());
					if (!meaning.getDescription().isEmpty()) m.put("description", meaning.getDescription());
					if (!meaning.getTermCode().isEmpty()) m.put("glossaryTerm", meaning.getTermCode());
					if (!meaning.getDefinition().isEmpty()) m.put("definition", meaning.getDefinition());
					if (!meaning.getSynonyms().isEmpty())
					{
						final ArrayNode synonyms = m.putArray("synonyms");
						for (final String sy : meaning.getSynonyms()) synonyms.add(sy);
					}
					if (!meaning.getSensitivity().isEmpty()) m.put("sensitivity", meaning.getSensitivity());
					if (!meaning.getTags().isEmpty())
					{
						final ArrayNode tags = m.putArray("tags");
						for (final String t : meaning.getTags()) tags.add(t);
					}
				}
			}
			if (pDryRun)
			{
				final ArrayNode sampleArray = node.putArray("sample");
				for (final ObjectNode row : this.sample)
				{
					sampleArray.add(row);
				}
			}
			return node;
		}
	}

	/** Outcome of {@link #publish(Request)} (§4): the contract stored verbatim in {@code Publication.report}. */
	public static final class Report
	{
		private String runId = "";
		private String status = "ERROR";
		private String message = "";
		private String product = "";
		private String contractVersion = "";
		private String connector = "";
		private String schema = "";
		private String cutoff = "";
		private String startedAt = "";
		private String endedAt = "";
		private long durationMs;
		private int rowCount;
		private boolean truncated;
		private final List<TableReport> tables = new ArrayList<TableReport>();
		private final List<String> plan = new ArrayList<String>();
		private final List<String> errors = new ArrayList<String>();
		private final List<String> warnings = new ArrayList<String>();
		private TablePublisher publisher;

		public String getRunId()
		{
			return this.runId;
		}

		public String getStatus()
		{
			return this.status;
		}

		public String getMessage()
		{
			return this.message;
		}

		public String getProduct()
		{
			return this.product;
		}

		public String getContractVersion()
		{
			return this.contractVersion;
		}

		public String getConnector()
		{
			return this.connector;
		}

		public String getSchema()
		{
			return this.schema;
		}

		public String getCutoff()
		{
			return this.cutoff;
		}

		public int getRowCount()
		{
			return this.rowCount;
		}

		public boolean isTruncated()
		{
			return this.truncated;
		}

		public List<TableReport> getTables()
		{
			return this.tables;
		}

		public List<String> getPlan()
		{
			return this.plan;
		}

		public List<String> getErrors()
		{
			return this.errors;
		}

		/** Non-fatal problems after a committed publication, e.g. comments or tags the target refused. */
		public List<String> getWarnings()
		{
			return this.warnings;
		}

		public boolean isError()
		{
			return "ERROR".equals(this.status);
		}

		/** True when the publication committed but at least one table was cut at the row cap (status {@code PARTIAL}). */
		public boolean isPartial()
		{
			return "PARTIAL".equals(this.status);
		}

		public ObjectNode toJson()
		{
			final ObjectNode node = Json.object();
			node.put("runId", this.runId);
			node.put("status", this.status);
			node.put("message", this.message);
			node.put("product", this.product);
			node.put("contractVersion", this.contractVersion);
			node.put("connector", this.connector);
			node.put("schema", this.schema);
			node.put("cutoff", this.cutoff);
			node.put("startedAt", this.startedAt);
			node.put("endedAt", this.endedAt);
			node.put("durationMs", this.durationMs);
			node.put("rowCount", this.rowCount);
			node.put("truncated", this.truncated);
			final boolean dryRun = "DRY_RUN".equals(this.status);
			final ArrayNode tablesArray = node.putArray("tables");
			for (final TableReport table : this.tables)
			{
				tablesArray.add(table.toJson(this.publisher, dryRun));
			}
			if (dryRun)
			{
				final ArrayNode planArray = node.putArray("plan");
				for (final String statement : this.plan)
				{
					planArray.add(statement);
				}
			}
			final ArrayNode errorsArray = node.putArray("errors");
			for (final String error : this.errors)
			{
				errorsArray.add(error);
			}
			final ArrayNode warningsArray = node.putArray("warnings");
			for (final String warning : this.warnings)
			{
				warningsArray.add(warning);
			}
			return node;
		}
	}

	// ------------------------------------------------------------------ the EBX-free seam

	/** What building a table spec and its rows needs from EBX; isolated so {@code ProductPublisherTest} can fake it. */
	interface RecordSource
	{
		/** Row errors listed in the report; a table of millions of rows with a bad rule must not fill the memory with messages. */
		int MAX_LISTED_ERRORS = 100;

		/** The kind of {@code pRule}'s source EBX node (STRING for a constant rule or one whose source is not a node). */
		Kind kindOf(MappingDefinition pMapping, MappingRule pRule);

		/** The static max-length facet of {@code pRule}'s source node, or null when not statically known. */
		Integer maxLengthOf(MappingDefinition pMapping, MappingRule pRule);

		/** True when {@code pRule}'s source path is one of the table's primary-key fields. */
		boolean isKeyPath(MappingDefinition pMapping, MappingRule pRule);

		/**
		 * Streams the rows of the mapping's table to {@code pSink}, one at a time, outbound documents already
		 * built (children included), up to {@code pMax} (the source reads one record more to know whether it
		 * cut). Nothing is accumulated: memory does not grow with the table (audit C-17). At most
		 * {@link #MAX_LISTED_ERRORS} row errors are added to {@code pErrors}; the rest are only counted.
		 */
		StreamSummary stream(MappingDefinition pMapping, int pMax, List<String> pErrors, RowSink pSink) throws ConnectorException;

		/**
		 * What {@code pRule}'s source field means (model label and description, catalog entry, glossary term),
		 * written by the publisher as comments and tags; null when nothing is known. {@code pEbxTable} is the
		 * catalog's qualified name of the mapping's table.
		 */
		default TablePublisher.ColumnContext contextOf(final MappingDefinition pMapping, final MappingRule pRule, final String pEbxTable)
		{
			return null;
		}
	}

	/** One record's outbound document (§2.3 step 4) paired with its formatted primary key. */
	static final class Row
	{
		final ObjectNode document;
		final String recordKey;

		Row(final ObjectNode pDocument, final String pRecordKey)
		{
			this.document = pDocument;
			this.recordKey = pRecordKey;
		}
	}

	/** Receives the rows of {@link RecordSource#stream}, one at a time. */
	interface RowSink
	{
		void accept(Row pRow) throws ConnectorException;
	}

	/** What {@link RecordSource#stream} saw: no rows, only the counts. */
	static final class StreamSummary
	{
		final int rows;
		final boolean truncated;
		final int skippedMerged;
		final int skippedDeleted;
		final int unlistedErrors;

		StreamSummary(final int pRows, final boolean pTruncated)
		{
			this(pRows, pTruncated, 0, 0, 0);
		}

		/** {@code pSkippedMerged}/{@code pSkippedDeleted}: MERGED/DELETED records left out by the
		 * {@code mergedRecords=exclude} filter; they do not count towards the cap. {@code pUnlistedErrors}: row errors
		 * beyond {@link RecordSource#MAX_LISTED_ERRORS}, counted but not listed. */
		StreamSummary(final int pRows, final boolean pTruncated, final int pSkippedMerged, final int pSkippedDeleted, final int pUnlistedErrors)
		{
			this.rows = pRows;
			this.truncated = pTruncated;
			this.skippedMerged = pSkippedMerged;
			this.skippedDeleted = pSkippedDeleted;
			this.unlistedErrors = pUnlistedErrors;
		}
	}

	/** Where a column's value comes from in the outbound document; technical columns are filled separately. */
	private static final class ColumnBinding
	{
		final String columnName;
		final String documentKey;

		ColumnBinding(final String pColumnName, final String pDocumentKey)
		{
			this.columnName = pColumnName;
			this.documentKey = pDocumentKey;
		}
	}

	static final class TableWork
	{
		final MappingDefinition mapping;
		final TableSpec spec;
		final List<ColumnBinding> bindings;
		boolean truncated;
		int skippedMerged;
		int skippedDeleted;

		TableWork(final MappingDefinition pMapping, final TableSpec pSpec, final List<ColumnBinding> pBindings)
		{
			this.mapping = pMapping;
			this.spec = pSpec;
			this.bindings = pBindings;
		}
	}

	// ------------------------------------------------------------------ identifiers

	/** Upper-case, {@code [^A-Za-z0-9_]} to {@code _}, a leading digit prefixed with {@code _}; never blank. */
	static String sanitizeIdentifier(final String pRaw)
	{
		String id = pRaw == null ? "" : pRaw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Za-z0-9_]", "_");
		if (id.isEmpty())
		{
			id = "COLUMN";
		}
		if (Character.isDigit(id.charAt(0)))
		{
			id = "_" + id;
		}
		return id;
	}

	/** Up to (not including) the first {@code .} or {@code [}. */
	static String firstSegment(final String pTarget)
	{
		int cut = pTarget.length();
		final int dot = pTarget.indexOf('.');
		final int bracket = pTarget.indexOf('[');
		if (dot >= 0)
		{
			cut = dot;
		}
		if (bracket >= 0 && bracket < cut)
		{
			cut = bracket;
		}
		return pTarget.substring(0, cut);
	}

	/** After the last {@code .}, or the whole (trimmed) string when it names no schema. */
	static String lastSegment(final String pQualified)
	{
		final String trimmed = pQualified.trim();
		final int dot = trimmed.lastIndexOf('.');
		return dot < 0 ? trimmed : trimmed.substring(dot + 1);
	}

	/**
	 * When the mapping's {@code table} input is schema-qualified ({@code DB.SCHEMA.TABLE} or
	 * {@code SCHEMA.TABLE}) and names a schema different from {@code pSchema} (the publication's own
	 * {@code schema} request field, already upper-cased), the error message naming both — publishing
	 * never silently retargets to the input's schema. Null when the input is absent, blank, unqualified,
	 * or names the same schema. An unqualified {@code table} (the common case) is never flagged: only
	 * its last segment is ever used as the table name (see {@link #buildTableWork}).
	 */
	static String qualifiedTableSchemaMismatch(final MappingDefinition pMapping, final String pSchema)
	{
		final String tableInput = pMapping.getInputs().get("table");
		if (tableInput == null || tableInput.trim().isEmpty())
		{
			return null;
		}
		final String trimmed = tableInput.trim();
		final int lastDot = trimmed.lastIndexOf('.');
		if (lastDot < 0)
		{
			return null; // unqualified: nothing to compare
		}
		final String schemaPart = trimmed.substring(0, lastDot).trim().toUpperCase(Locale.ROOT);
		if (schemaPart.isEmpty() || ProductPublisher.schemaMatches(schemaPart, pSchema))
		{
			return null;
		}
		return "mapping " + pMapping.getId() + ": input 'table' (" + trimmed + ") names schema '" + schemaPart
			+ "' but the publication schema is '" + pSchema + "'";
	}

	/**
	 * Whether a qualified {@code table} input's schema part refers to the same schema as the
	 * publication's own (both already upper-cased); tolerant of one side carrying a database prefix
	 * ({@code DB.SCHEMA}) and the other not — only the schema segment then has to match.
	 */
	private static boolean schemaMatches(final String pTableSchemaPart, final String pRequestSchema)
	{
		if (pTableSchemaPart.equals(pRequestSchema))
		{
			return true;
		}
		final boolean tableHasDb = pTableSchemaPart.indexOf('.') >= 0;
		final boolean requestHasDb = pRequestSchema.indexOf('.') >= 0;
		if (tableHasDb != requestHasDb)
		{
			return ProductPublisher.lastSegment(pTableSchemaPart).equals(ProductPublisher.lastSegment(pRequestSchema));
		}
		return false;
	}

	// ------------------------------------------------------------------ merged filter / crosswalk

	/**
	 * The mapping's resolved {@code mergedRecords} mode: the input's value, trimmed and lower-cased,
	 * when present; else {@code "include"} when {@code crosswalk=true}; else {@code "exclude"} (the
	 * default). Call only once {@link #mergedRecordsError} has returned null for this mapping — an
	 * invalid input value is not re-validated here.
	 */
	static String mergedRecordsMode(final MappingDefinition pMapping)
	{
		final String raw = pMapping.getInputs().get("mergedRecords");
		if (raw == null || raw.trim().isEmpty())
		{
			return "true".equalsIgnoreCase(pMapping.getInputs().get("crosswalk")) ? "include" : "exclude";
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Null when the mapping's {@code mergedRecords} input is absent, blank, or exactly {@code exclude}/
	 * {@code include} (case-insensitive); else an error naming the mapping and the offending value —
	 * reported like the other request errors, before anything is written.
	 */
	static String mergedRecordsError(final MappingDefinition pMapping)
	{
		final String raw = pMapping.getInputs().get("mergedRecords");
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		final String trimmed = raw.trim();
		if ("exclude".equalsIgnoreCase(trimmed) || "include".equalsIgnoreCase(trimmed))
		{
			return null;
		}
		return "mapping " + pMapping.getId() + ": input 'mergedRecords' must be 'exclude' or 'include', got '" + trimmed + "'";
	}

	/**
	 * Whether a record whose Match and Merge state is {@code pState} (one of {@code GoldenRecords}'
	 * state names, or {@code UNMANAGED}) is published under {@code pMode}: {@code "exclude"}
	 * (the default) leaves out {@code MERGED} and {@code DELETED}; any other mode (in practice only
	 * {@code "include"}, {@link #mergedRecordsError} having already rejected anything else) publishes
	 * every state. Pure: no EBX API, unit-tested directly.
	 */
	static boolean publishes(final String pState, final String pMode)
	{
		if (!"exclude".equalsIgnoreCase(pMode))
		{
			return true;
		}
		return !"MERGED".equals(pState) && !"DELETED".equals(pState);
	}

	// ------------------------------------------------------------------ table spec derivation (pure given a RecordSource)

	static TableWork buildTableWork(final MappingDefinition pMapping, final RecordSource pSource, final String pDataspace, final List<String> pErrors)
	{
		final String ebxTable = OpenLineageEmitter.ebxName(pMapping, pDataspace);
		final String tableInput = pMapping.getInputs().get("table");
		if (tableInput == null || tableInput.trim().isEmpty())
		{
			pErrors.add("mapping " + pMapping.getId() + ": input 'table' is required to publish");
			return null;
		}
		// a schema-qualified input (DB.SCHEMA.TABLE or SCHEMA.TABLE) contributes only its last segment as the
		// table name; the schema part, when present, is validated against the publication's own schema by
		// qualifiedTableSchemaMismatch (called from compute(), which alone has the resolved schema at hand)
		final String tableName = ProductPublisher.sanitizeIdentifier(ProductPublisher.lastSegment(tableInput));

		final List<ColumnSpec> columns = new ArrayList<ColumnSpec>();
		final List<ColumnBinding> bindings = new ArrayList<ColumnBinding>();
		// seeded with the technical column names so a rule/child whose sanitized name collides with one
		// is reported as a duplicate column instead of silently producing two columns of the same name
		final Set<String> used = new LinkedHashSet<String>(Arrays.asList(ProductPublisher.TECHNICAL_COLUMNS));
		final Map<String, String> jsonSegments = new LinkedHashMap<String, String>(); // segment text -> column name already added

		for (final MappingRule rule : pMapping.getRules())
		{
			final String target = rule.getTargetPath();
			if (target.isEmpty())
			{
				continue;
			}
			final boolean nested = target.indexOf('.') >= 0 || target.indexOf('[') >= 0;
			if (!nested)
			{
				final String name = ProductPublisher.sanitizeIdentifier(target);
				if (used.contains(name))
				{
					pErrors.add("mapping " + pMapping.getId() + ": duplicate column " + name);
					continue;
				}
				final boolean constant = rule.getSourcePath().isEmpty() || Transformations.hasConstantStep(rule.getTransformation());
				// mdmState(...) yields a state name whatever the source field is; golden(...) keeps the type of
				// the key it resolves, but the key of another record is no key of this table.
				final boolean state = Transformations.hasStep(rule.getTransformation(), "mdmstate");
				final boolean resolved = state || Transformations.hasStep(rule.getTransformation(), "golden");
				final Kind kind = constant || state ? Kind.STRING : pSource.kindOf(pMapping, rule);
				final Integer maxLength = kind == Kind.STRING && !constant && !state ? pSource.maxLengthOf(pMapping, rule) : null;
				final boolean key = !constant && !resolved && pSource.isKeyPath(pMapping, rule);
				// 2026-09-30: the meaning travels with the column (a computed state or another record's key means something else)
				final TablePublisher.ColumnContext context = constant || resolved ? null : pSource.contextOf(pMapping, rule, ebxTable);
				columns.add(new ColumnSpec(name, kind, maxLength, key, rule.getSourcePath(),
						constant ? "Fixed value set by the publication mapping" : ProductPublisher.sourceComment(rule.getSourcePath(), key), context));
				bindings.add(new ColumnBinding(name, target));
				used.add(name);
			}
			else
			{
				final String segment = ProductPublisher.firstSegment(target);
				final String name = ProductPublisher.sanitizeIdentifier(segment);
				if (jsonSegments.containsKey(segment))
				{
					continue; // same first segment already produced this JSON column: merge
				}
				if (used.contains(name))
				{
					pErrors.add("mapping " + pMapping.getId() + ": duplicate column " + name);
					continue;
				}
				columns.add(new ColumnSpec(name, Kind.JSON, null, false, target, "Nested JSON object built from EBX " + target));
				bindings.add(new ColumnBinding(name, segment));
				used.add(name);
				jsonSegments.put(segment, name);
			}
		}
		for (final MappingDefinition child : pMapping.getChildren())
		{
			if (!child.isActive())
			{
				continue;
			}
			final String path = child.getCollectionPath();
			if (path == null || path.trim().isEmpty())
			{
				continue;
			}
			final String name = ProductPublisher.sanitizeIdentifier(path);
			if (used.contains(name))
			{
				pErrors.add("mapping " + pMapping.getId() + ": duplicate column " + name);
				continue;
			}
			columns.add(new ColumnSpec(name, Kind.JSON, null, false, path + "[]",
				"JSON array of the linked records (" + child.getLabel() + "), one element per record"));
			bindings.add(new ColumnBinding(name, path));
			used.add(name);
		}
		boolean hasBusinessKey = false;
		for (final ColumnSpec column : columns)
		{
			if (column.isKey())
			{
				hasBusinessKey = true;
				break;
			}
		}
		columns.add(new ColumnSpec(ProductPublisher.TECHNICAL_COLUMNS[0], Kind.STRING, Integer.valueOf(255), !hasBusinessKey, "",
			"Technical: key of the record in ON EBX"));
		columns.add(new ColumnSpec(ProductPublisher.TECHNICAL_COLUMNS[1], Kind.STRING, Integer.valueOf(160), false, "",
			"Technical: identifier of the publication run that wrote this row"));
		columns.add(new ColumnSpec(ProductPublisher.TECHNICAL_COLUMNS[2], Kind.DATETIME, null, false, "",
			"Technical: when the publication wrote this row (UTC)"));
		columns.add(new ColumnSpec(ProductPublisher.TECHNICAL_COLUMNS[3], Kind.STRING, Integer.valueOf(40), false, "",
			"Technical: version of the data product contract this row follows"));

		final TableSpec spec = new TableSpec(tableName, columns, "Published from Connectors mapping " + pMapping.getId()
			+ " (" + pMapping.getLabel() + ")", ebxTable, pMapping.getId());
		return new TableWork(pMapping, spec, bindings);
	}

	private static String sourceComment(final String pSourcePath, final boolean pKey)
	{
		final String path = pSourcePath == null ? "" : pSourcePath.trim();
		return (path.isEmpty() ? "Value from ON EBX" : "ON EBX field " + path) + (pKey ? " (business key)" : "");
	}

	static ObjectNode buildRow(final TableWork pWork, final Row pRow, final String pRunId, final String pPublishedAt, final String pContractVersion)
	{
		final ObjectNode out = Json.object();
		for (final ColumnBinding binding : pWork.bindings)
		{
			final JsonNode value = pRow.document.get(binding.documentKey);
			out.set(binding.columnName, value == null ? NullNode.getInstance() : value);
		}
		out.put(ProductPublisher.TECHNICAL_COLUMNS[0], pRow.recordKey);
		out.put(ProductPublisher.TECHNICAL_COLUMNS[1], pRunId);
		out.put(ProductPublisher.TECHNICAL_COLUMNS[2], pPublishedAt);
		out.put(ProductPublisher.TECHNICAL_COLUMNS[3], pContractVersion);
		return out;
	}

	// ------------------------------------------------------------------ EBX-backed entry point

	public Report publish(final Request pRequest)
	{
		final String runId = ConnectorService.beginRun();
		List<MappingDefinition> mappings = null;
		try
		{
			final List<String> errors = new ArrayList<String>();
			mappings = this.resolveMappings(pRequest, errors);
			if (!errors.isEmpty())
			{
				return ProductPublisher.errorReport(runId, ProductPublisher.join(errors));
			}
			final String connectorId = mappings.get(0).getConnectorId();
			final Connector connectorInstance;
			try
			{
				connectorInstance = ConnectorRegistry.get().connector(connectorId, this.repository);
			}
			catch (final ConnectorException ex)
			{
				return ProductPublisher.errorReport(runId, ex.getMessage());
			}
			if (!(connectorInstance instanceof TablePublisher))
			{
				final ConnectorConfig config = this.repository.connector(connectorId);
				return ProductPublisher.errorReport(runId,
					"connector " + connectorId + " (type " + (config == null ? "?" : config.getType()) + ") cannot receive a publication");
			}
			final String dataspace;
			try
			{
				dataspace = MappingDefinition.runDataspace(pRequest.getDataspace(), mappings);
			}
			catch (final IllegalArgumentException ambiguous)
			{
				return ProductPublisher.errorReport(runId, ambiguous.getMessage());
			}
			final TablePublisher publisher = (TablePublisher) connectorInstance;
			final ConnectorConfig connectorConfig = this.repository.connector(connectorId);
			final RecordSource source = new EbxRecordSource(this.session, this.repository.getRepository(), dataspace);
			final ConnectorContext ctx = new ConnectorContext(this.session, this.repository.getRepository(), null, this.repository.dataset());
			final Report report = ProductPublisher.compute(pRequest, mappings, publisher, connectorConfig, connectorId, dataspace, source, runId, ctx);
			this.bookkeep(pRequest, mappings, publisher, report, dataspace);
			return report;
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	private List<MappingDefinition> resolveMappings(final Request pRequest, final List<String> pErrors)
	{
		final List<MappingDefinition> mappings = new ArrayList<MappingDefinition>();
		if (pRequest.getMappings() == null || pRequest.getMappings().isEmpty())
		{
			pErrors.add("no mapping given");
			return mappings;
		}
		for (final Integer id : pRequest.getMappings())
		{
			final MappingDefinition m = id == null ? null : this.repository.mapping(id.intValue());
			if (m == null)
			{
				pErrors.add("mapping " + id + " does not exist");
				continue;
			}
			if (m.isChild())
			{
				pErrors.add("mapping " + id + " is a child mapping, not a root");
				continue;
			}
			if (m.getDirection() != MappingDefinition.Direction.OUTBOUND)
			{
				pErrors.add("mapping " + id + " is not OUTBOUND");
				continue;
			}
			if (!m.isActive())
			{
				pErrors.add("mapping " + id + " is not active");
				continue;
			}
			mappings.add(m);
		}
		if (!pErrors.isEmpty())
		{
			return mappings;
		}
		String connectorId = null;
		for (final MappingDefinition m : mappings)
		{
			if (connectorId == null)
			{
				connectorId = m.getConnectorId();
			}
			else if (!connectorId.equals(m.getConnectorId()))
			{
				pErrors.add("mappings use different connectors (" + connectorId + " and " + m.getConnectorId() + "): publish one connector at a time");
			}
		}
		return mappings;
	}

	private void bookkeep(final Request pRequest, final List<MappingDefinition> pMappings, final TablePublisher pPublisher, final Report pReport,
		final String pDataspace)
	{
		try
		{
			final StringBuilder mappingIds = new StringBuilder();
			for (final MappingDefinition m : pMappings)
			{
				mappingIds.append(mappingIds.length() > 0 ? "," : "").append(m.getId());
			}
			final StringBuilder tablesCsv = new StringBuilder();
			final List<OpenLineageEmitter.PublicationTable> lineageTables = new ArrayList<OpenLineageEmitter.PublicationTable>();
			for (final TableReport table : pReport.tables)
			{
				tablesCsv.append(tablesCsv.length() > 0 ? "," : "").append(table.getTable()).append('=').append(table.getRows());
				final List<String[]> columns = new ArrayList<String[]>();
				for (final ColumnSpec column : table.getColumns())
				{
					columns.add(new String[] { column.getName(), pPublisher == null ? "" : pPublisher.nativeType(column) });
				}
				lineageTables.add(new OpenLineageEmitter.PublicationTable(table.getMapping(), table.getTable(), table.getRows(), columns));
			}
			Date cutoffDate = null;
			try
			{
				cutoffDate = Date.from(LocalDateTime.parse(pReport.cutoff, CatalogOperations.TIMESTAMP).atZone(ZoneId.systemDefault()).toInstant());
			}
			catch (final RuntimeException ex)
			{
				// malformed cutoff (should not happen): the Publication row keeps a null cutoff rather than failing bookkeeping
			}
			Date startedAtDate = null;
			Date endedAtDate = null;
			try
			{
				startedAtDate = Date.from(LocalDateTime.parse(pReport.startedAt, CatalogOperations.TIMESTAMP).atZone(ZoneId.systemDefault()).toInstant());
				endedAtDate = Date.from(LocalDateTime.parse(pReport.endedAt, CatalogOperations.TIMESTAMP).atZone(ZoneId.systemDefault()).toInstant());
			}
			catch (final RuntimeException ex)
			{
				// same as above
			}
			if (!this.repository.logPublication(this.session, pReport.runId, pReport.product, pReport.contractVersion, pReport.connector, pReport.schema,
				pReport.status, pReport.message, cutoffDate, startedAtDate, endedAtDate, pReport.rowCount, mappingIds.toString(), tablesCsv.toString(),
				Json.write(pReport.toJson())))
			{
				// The publication itself is what the report says; only its row could not be kept.
				pReport.warnings.add("The Publication row of run " + pReport.runId + " could not be saved (see the kernel log): this report will not be readable later by run id");
			}
			boolean syncLogSaved = true;
			for (final TableReport table : pReport.tables)
			{
				final SyncResult result = new SyncResult(pReport.connector, "publish", "OUTBOUND", null,
					this.tablePathOf(pMappings, table.getMapping()), "", table.getTable());
				result.setStatus("ERROR".equals(pReport.status) ? SyncResult.Status.ERROR
					: "DRY_RUN".equals(pReport.status) ? SyncResult.Status.DRY_RUN
						: table.isTruncated() ? SyncResult.Status.PARTIAL : SyncResult.Status.OK);
				result.setMessage(table.getRows() + " rows -> " + pReport.schema + "." + table.getTable() + (table.isTruncated() ? " (cut at the row cap)" : ""));
				result.setRunId(pReport.runId);
				result.setMappingId(String.valueOf(table.getMapping()));
				syncLogSaved &= this.repository.log(this.session, result, "publish");
			}
			if (!syncLogSaved)
			{
				pReport.warnings.add("The synchronisation log of run " + pReport.runId + " is incomplete (see the kernel log)");
			}
			if (!"DRY_RUN".equals(pReport.status))
			{
				OpenLineageEmitter.emitPublication(this.session, this.repository, pMappings, pDataspace, pReport.runId, pReport.product, pReport.contractVersion,
					pReport.schema, pReport.cutoff, lineageTables, pReport.isError(), pReport.message);
			}
		}
		catch (final RuntimeException ex)
		{
			// bookkeeping is best effort: it must never mask the publication outcome already computed
		}
	}

	private String tablePathOf(final List<MappingDefinition> pMappings, final int pMappingId)
	{
		for (final MappingDefinition m : pMappings)
		{
			if (m.getId() == pMappingId)
			{
				return m.getTablePath();
			}
		}
		return "";
	}

	// ------------------------------------------------------------------ the EBX-free computation (unit-tested directly)

	/**
	 * Everything from validated inputs to the finished {@link Report}: no EBX API is touched here —
	 * only {@code pSource} and {@code pPublisher}, both interfaces a test can fake. {@code pContext} is
	 * only threaded through to the {@link TablePublisher} calls (real run); a test's fake publisher may
	 * ignore it, so a test may pass {@code null}.
	 */
	static Report compute(final Request pRequest, final List<MappingDefinition> pMappings, final TablePublisher pPublisher,
		final ConnectorConfig pConnectorConfig, final String pConnectorId, final String pDataspace, final RecordSource pSource, final String pRunId,
		final ConnectorContext pContext)
	{
		final LocalDateTime now = LocalDateTime.now();
		final String cutoff = now.format(CatalogOperations.TIMESTAMP);
		final Date startedAtDate = Date.from(now.atZone(ZoneId.systemDefault()).toInstant());

		final String schema = pRequest.getSchema() == null ? "" : pRequest.getSchema().trim().toUpperCase(Locale.ROOT);
		if (schema.isEmpty())
		{
			return ProductPublisher.errorReport(pRunId, "schema is required");
		}
		for (final String part : schema.split("\\."))
		{
			if (!ProductPublisher.IDENTIFIER.matcher(part).matches())
			{
				return ProductPublisher.errorReport(pRunId, "invalid schema '" + pRequest.getSchema() + "': '" + part + "' must match [A-Z_][A-Z0-9_]*");
			}
		}
		final String product = pRequest.getProduct() == null ? "" : pRequest.getProduct().trim();
		if (product.isEmpty())
		{
			return ProductPublisher.errorReport(pRunId, "product is required");
		}

		final List<String> errors = new ArrayList<String>();
		final List<TableWork> works = new ArrayList<TableWork>();
		for (final MappingDefinition mapping : pMappings)
		{
			final String schemaMismatch = ProductPublisher.qualifiedTableSchemaMismatch(mapping, schema);
			if (schemaMismatch != null)
			{
				errors.add(schemaMismatch);
			}
			final String mergedRecordsError = ProductPublisher.mergedRecordsError(mapping);
			if (mergedRecordsError != null)
			{
				errors.add(mergedRecordsError);
			}
			final TableWork work = ProductPublisher.buildTableWork(mapping, pSource, pDataspace, errors);
			if (work != null)
			{
				works.add(work);
			}
		}
		if (!errors.isEmpty())
		{
			return ProductPublisher.errorReport(pRunId, ProductPublisher.join(errors));
		}

		final List<TableSpec> specs = new ArrayList<TableSpec>();
		for (final TableWork work : works)
		{
			specs.add(work.spec);
		}
		final PublicationSpec spec = new PublicationSpec(schema, pRunId, product, pRequest.getContractVersion(), cutoff, specs,
			pRequest.getDescription(), pRequest.getOwner(), pRequest.getDomain(), pRequest.getTitle());

		// Rows are read while they are loaded (real run) or counted (dry run), never held: see computeReal / computeDryRun.
		final int max = PublicationBatcher.effectiveMax(pRequest.getMax(), PublicationBatcher.operatorCap());

		final Report report = new Report();
		report.publisher = pPublisher;
		report.runId = pRunId;
		report.product = product;
		report.contractVersion = pRequest.getContractVersion();
		report.connector = pConnectorId;
		report.schema = schema;
		report.cutoff = cutoff;

		if (pRequest.isDryRun())
		{
			ProductPublisher.computeDryRun(pRequest, works, spec, pPublisher, report, cutoff, pConnectorConfig, pRunId, pSource, max, errors);
		}
		else
		{
			ProductPublisher.computeReal(pRequest, works, spec, pPublisher, pContext, report, cutoff, pConnectorConfig, pSource, max, errors);
		}

		final Date endedAtDate = new Date();
		report.startedAt = ProductPublisher.format(startedAtDate);
		report.endedAt = ProductPublisher.format(endedAtDate);
		report.durationMs = endedAtDate.getTime() - startedAtDate.getTime();
		return report;
	}

	private static void computeDryRun(final Request pRequest, final List<TableWork> pWorks, final PublicationSpec pSpec, final TablePublisher pPublisher,
		final Report pReport, final String pCutoff, final ConnectorConfig pConnectorConfig, final String pRunId, final RecordSource pSource, final int pMax,
		final List<String> pErrors)
	{
		pReport.status = "DRY_RUN";
		pReport.plan.addAll(pPublisher.plan(pSpec, pRequest.isRecreate()));
		// 2026-09-30: the comments and tags too (the meaning of the columns), so a dry run shows what the target will read
		pReport.plan.addAll(pPublisher.annotationPlan(pSpec));
		int totalRows = 0;
		int totalSkipped = 0;
		int unlisted = 0;
		boolean cut = false;
		try
		{
			for (final TableWork work : pWorks)
			{
				final TableReport tr = new TableReport();
				tr.mapping = work.mapping.getId();
				tr.label = work.mapping.getLabel();
				tr.ebxTable = work.spec.getEbxTable();
				tr.table = work.spec.getName();
				tr.action = "PLANNED";
				tr.columns.addAll(work.spec.getColumns());
				// the rows are counted as they stream by; only the first three are kept as the sample
				final StreamSummary summary = pSource.stream(work.mapping, pMax, pErrors, new RowSink()
				{
					@Override
					public void accept(final Row pRow)
					{
						if (tr.sample.size() < 3)
						{
							tr.sample.add(ProductPublisher.buildRow(work, pRow, pRunId, pCutoff, pRequest.getContractVersion()));
						}
					}
				});
				work.truncated = summary.truncated;
				work.skippedMerged = summary.skippedMerged;
				work.skippedDeleted = summary.skippedDeleted;
				tr.rows = summary.rows;
				tr.batches = ProductPublisher.batchCount(summary.rows, pConnectorConfig);
				tr.truncated = summary.truncated;
				tr.skippedMerged = summary.skippedMerged;
				tr.skippedDeleted = summary.skippedDeleted;
				totalRows += summary.rows;
				totalSkipped += summary.skippedMerged + summary.skippedDeleted;
				unlisted += summary.unlistedErrors;
				cut = cut || summary.truncated;
				pReport.tables.add(tr);
			}
		}
		catch (final ConnectorException | RuntimeException ex)
		{
			pReport.status = "ERROR";
			pReport.message = Redaction.redact(String.valueOf(ex.getMessage()));
			pReport.errors.add(pReport.message);
			ProductPublisher.addRowErrors(pReport, pErrors, unlisted);
			return;
		}
		pReport.truncated = cut;
		pReport.rowCount = totalRows;
		pReport.message = ProductPublisher.cut((cut ? "dry run: cut at " + pMax + " row(s) per table, a real run would end PARTIAL" : "dry run: nothing executed")
			+ ProductPublisher.skippedSuffix(totalSkipped), 180);
		ProductPublisher.addRowErrors(pReport, pErrors, unlisted);
	}

	private static void computeReal(final Request pRequest, final List<TableWork> pWorks, final PublicationSpec pSpec, final TablePublisher pPublisher,
		final ConnectorContext pContext, final Report pReport, final String pCutoff, final ConnectorConfig pConnectorConfig, final RecordSource pSource,
		final int pMax, final List<String> pErrors)
	{
		Map<String, String> actions;
		try
		{
			actions = pPublisher.prepare(pContext, pSpec, pRequest.isRecreate());
		}
		catch (final ConnectorException ex)
		{
			pPublisher.cleanup(pContext, pSpec);
			pReport.status = "ERROR";
			pReport.message = Redaction.redact(ex.getMessage());
			pReport.errors.add(pReport.message);
			ProductPublisher.addRowErrors(pReport, pErrors, 0);
			return;
		}
		final Map<String, Integer> rowsPerTable = new LinkedHashMap<String, Integer>();
		final int batchSize = ProductPublisher.batchSizeOf(pConnectorConfig);
		int unlisted = 0;
		try
		{
			int total = 0;
			int totalSkipped = 0;
			boolean cut = false;
			for (final TableWork work : pWorks)
			{
				// read, flatten and load one batch at a time: memory holds one batch, never the table (audit C-17)
				final PublicationBatcher batcher = new PublicationBatcher(batchSize, new PublicationBatcher.Loader()
				{
					@Override
					public int load(final List<ObjectNode> pDocuments) throws ConnectorException
					{
						return pPublisher.load(pContext, pSpec, work.spec, pDocuments);
					}
				});
				final StreamSummary summary = pSource.stream(work.mapping, pMax, pErrors, new RowSink()
				{
					@Override
					public void accept(final Row pRow) throws ConnectorException
					{
						batcher.add(ProductPublisher.buildRow(work, pRow, pReport.runId, pCutoff, pRequest.getContractVersion()));
					}
				});
				batcher.flush();
				final int loaded = batcher.loaded();
				work.truncated = summary.truncated;
				work.skippedMerged = summary.skippedMerged;
				work.skippedDeleted = summary.skippedDeleted;
				rowsPerTable.put(work.spec.getName(), Integer.valueOf(loaded));
				total += loaded;
				totalSkipped += work.skippedMerged + work.skippedDeleted;
				unlisted += summary.unlistedErrors;
				cut = cut || summary.truncated;

				final TableReport tr = new TableReport();
				tr.mapping = work.mapping.getId();
				tr.label = work.mapping.getLabel();
				tr.ebxTable = work.spec.getEbxTable();
				tr.table = work.spec.getName();
				tr.action = actions.get(work.spec.getName()) == null ? "EXISTS" : actions.get(work.spec.getName());
				tr.rows = loaded;
				tr.batches = batcher.batches();
				tr.truncated = summary.truncated;
				tr.skippedMerged = work.skippedMerged;
				tr.skippedDeleted = work.skippedDeleted;
				tr.columns.addAll(work.spec.getColumns());
				pReport.tables.add(tr);
			}
			pPublisher.commit(pContext, pSpec, rowsPerTable);
			// after the commit and best effort: a target that refuses a comment or a tag must not turn a good publication into an error
			pReport.warnings.addAll(pPublisher.annotate(pContext, pSpec));
			pReport.truncated = cut;
			// a table cut at the row cap was replaced by the cut set: the run says so, it is never OK
			pReport.status = PublicationBatcher.statusOf(cut);
			pReport.rowCount = total;
			pReport.message = ProductPublisher.cut((cut ? "PARTIAL: published, cut at " + pMax + " row(s) per table, the target holds the cut set" : "published")
				+ ProductPublisher.skippedSuffix(totalSkipped), 180);
		}
		catch (final ConnectorException ex)
		{
			// nothing was committed: the live tables are untouched, only the staging tables (dropped below) were written
			pReport.status = "ERROR";
			pReport.message = Redaction.redact(ex.getMessage());
			pReport.errors.add(pReport.message);
		}
		catch (final RuntimeException ex)
		{
			pReport.status = "ERROR";
			pReport.message = Redaction.redact("publication stopped before the commit, nothing was replaced: " + ex);
			pReport.errors.add(pReport.message);
		}
		finally
		{
			pPublisher.cleanup(pContext, pSpec);
			ProductPublisher.addRowErrors(pReport, pErrors, unlisted);
		}
	}

	/** Row-level errors found while building the documents, then a note for the ones beyond the listing limit. */
	private static void addRowErrors(final Report pReport, final List<String> pErrors, final int pUnlisted)
	{
		pReport.errors.addAll(pErrors);
		if (pUnlisted > 0)
		{
			pReport.errors.add(pUnlisted + " more row error(s) not listed");
		}
	}

	private static int batchSizeOf(final ConnectorConfig pConfig)
	{
		if (pConfig == null)
		{
			return 500;
		}
		try
		{
			return Math.max(1, Integer.parseInt(pConfig.getProperty("publish.batchSize", "500").trim()));
		}
		catch (final RuntimeException ex)
		{
			return 500;
		}
	}

	private static int batchCount(final int pRows, final ConnectorConfig pConfig)
	{
		final int size = ProductPublisher.batchSizeOf(pConfig);
		return pRows == 0 ? 0 : (pRows + size - 1) / size;
	}

	private static String format(final Date pDate)
	{
		return LocalDateTime.ofInstant(pDate.toInstant(), ZoneId.systemDefault()).format(CatalogOperations.TIMESTAMP);
	}

	private static String join(final List<String> pMessages)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String m : pMessages)
		{
			sb.append(sb.length() > 0 ? "; " : "").append(m);
		}
		return sb.toString();
	}

	/** Appended to a successful run's message when the {@code mergedRecords=exclude} filter left
	 * out at least one record; empty otherwise. */
	private static String skippedSuffix(final int pTotalSkipped)
	{
		return pTotalSkipped > 0 ? ", " + pTotalSkipped + " merged/deleted record(s) excluded" : "";
	}

	/** The catalog stores the report {@code message} in a 180-character field: never exceed it. */
	static String cut(final String pValue, final int pMax)
	{
		return pValue.length() <= pMax ? pValue : pValue.substring(0, pMax);
	}

	private static Report errorReport(final String pRunId, final String pMessage)
	{
		final Report report = new Report();
		report.runId = pRunId == null ? "" : pRunId;
		report.status = "ERROR";
		report.message = pMessage == null ? "" : pMessage;
		report.errors.add(report.message);
		report.startedAt = report.endedAt = ProductPublisher.format(new Date());
		return report;
	}
}
