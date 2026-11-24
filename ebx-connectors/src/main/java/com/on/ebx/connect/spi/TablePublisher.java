package com.on.ebx.connect.spi;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A connector able to receive a whole data product publication (one Snowflake schema, one atomic
 * commit for every table of the product). v1 is full-reload only: every call replaces the content
 * of the target tables, there is no incremental/delta support here.
 * <p>
 * The nested value types below carry no EBX type: they are plain data, safe to build and inspect
 * from an offline test. Identifiers (schema parts, table and column names) are validated by the
 * caller ({@code com.on.ebx.connect.core.ProductPublisher}) to be upper-case and match
 * {@code [A-Z_][A-Z0-9_]*} before a publisher ever sees them; the publisher only double-quotes them.
 */
public interface TablePublisher
{
	/** The kind of value a column holds, connector-neutral. */
	enum Kind
	{
		STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, TIME, JSON
	}

	/** One column of a published table. */
	final class ColumnSpec
	{
		private final String name;
		private final Kind kind;
		private final Integer maxLength;
		private final boolean key;
		private final String source;
		private final String comment;
		private final ColumnContext context;

		public ColumnSpec(final String pName, final Kind pKind, final Integer pMaxLength, final boolean pKey,
			final String pSource, final String pComment)
		{
			this(pName, pKind, pMaxLength, pKey, pSource, pComment, null);
		}

		public ColumnSpec(final String pName, final Kind pKind, final Integer pMaxLength, final boolean pKey,
			final String pSource, final String pComment, final ColumnContext pContext)
		{
			this.context = pContext == null || pContext.isEmpty() ? null : pContext;
			this.name = pName;
			this.kind = pKind;
			this.maxLength = pMaxLength;
			this.key = pKey;
			this.source = pSource == null ? "" : pSource;
			this.comment = pComment;
		}

		public String getName()
		{
			return this.name;
		}

		public Kind getKind()
		{
			return this.kind;
		}

		/** Only meaningful for {@link Kind#STRING}; null when the length is not statically known. */
		public Integer getMaxLength()
		{
			return this.maxLength;
		}

		public boolean isKey()
		{
			return this.key;
		}

		/** Where the value comes from, for the report: an EBX path, a child collection path, or empty for a technical column. */
		public String getSource()
		{
			return this.source;
		}

		/** The technical comment: where the value comes from in ON EBX. */
		public String getComment()
		{
			return this.comment;
		}

		/** What the column means (business name, glossary term, sensitivity...); null when nothing is known. */
		public ColumnContext getContext()
		{
			return this.context;
		}
	}

	/**
	 * What a published column means, structured so each target writes it where its own search and AI
	 * read it (a column comment and tags on Snowflake; a comment and Unity Catalog tags on Databricks) instead
	 * of one text every target would have to parse. Every field is optional. Filled from the ON EBX model
	 * (field label and description) and, when the data catalog knows the column, from its catalog entry
	 * (business name, description, sensitivity, tags) and its glossary term (code, label, definition, synonyms).
	 */
	final class ColumnContext
	{
		private final String businessName;
		private final String description;
		private final String termCode;
		private final String termLabel;
		private final String definition;
		private final List<String> synonyms;
		private final String sensitivity;
		private final List<String> tags;

		public ColumnContext(final String pBusinessName, final String pDescription, final String pTermCode, final String pTermLabel,
			final String pDefinition, final List<String> pSynonyms, final String pSensitivity, final List<String> pTags)
		{
			this.businessName = ColumnContext.clean(pBusinessName);
			this.description = ColumnContext.clean(pDescription);
			this.termCode = ColumnContext.clean(pTermCode);
			this.termLabel = ColumnContext.clean(pTermLabel);
			this.definition = ColumnContext.clean(pDefinition);
			this.synonyms = ColumnContext.clean(pSynonyms);
			this.sensitivity = ColumnContext.clean(pSensitivity);
			this.tags = ColumnContext.clean(pTags);
		}

		static String clean(final String pText)
		{
			return pText == null || pText.trim().isEmpty() ? "" : pText.trim();
		}

		static List<String> clean(final List<String> pValues)
		{
			final List<String> out = new java.util.ArrayList<String>();
			if (pValues != null)
			{
				for (final String v : pValues)
				{
					if (v != null && !v.trim().isEmpty() && !out.contains(v.trim())) out.add(v.trim());
				}
			}
			return java.util.Collections.unmodifiableList(out);
		}

		public boolean isEmpty()
		{
			return this.businessName.isEmpty() && this.description.isEmpty() && this.termCode.isEmpty() && this.definition.isEmpty()
				&& this.synonyms.isEmpty() && this.sensitivity.isEmpty() && this.tags.isEmpty();
		}

		/** The column's business name ("Customer name"); "" when unknown. */
		public String getBusinessName()
		{
			return this.businessName;
		}

		/** The column's own description (catalog, else the ON EBX model); "" when unknown. */
		public String getDescription()
		{
			return this.description;
		}

		/** The code of the column's glossary term ({@code CUSTOMER_NAME}); "" when the column has none. */
		public String getTermCode()
		{
			return this.termCode;
		}

		public String getTermLabel()
		{
			return this.termLabel;
		}

		/** The approved definition of the glossary term; "" when none. */
		public String getDefinition()
		{
			return this.definition;
		}

		/** Other words people use for the term (Client, Buyer...). */
		public List<String> getSynonyms()
		{
			return this.synonyms;
		}

		/** PUBLIC, INTERNAL, CONFIDENTIAL or RESTRICTED; "" when unknown. */
		public String getSensitivity()
		{
			return this.sensitivity;
		}

		/** Classification tag codes of the catalog (PERSON_NAME, IBAN...). */
		public List<String> getTags()
		{
			return this.tags;
		}
	}

	/** One table of a published product. */
	final class TableSpec
	{
		private final String name;
		private final List<ColumnSpec> columns;
		private final String comment;
		private final String ebxTable;
		private final int mapping;

		public TableSpec(final String pName, final List<ColumnSpec> pColumns, final String pComment,
			final String pEbxTable, final int pMapping)
		{
			this.name = pName;
			this.columns = pColumns;
			this.comment = pComment;
			this.ebxTable = pEbxTable;
			this.mapping = pMapping;
		}

		public String getName()
		{
			return this.name;
		}

		public List<ColumnSpec> getColumns()
		{
			return this.columns;
		}

		public String getComment()
		{
			return this.comment;
		}

		/** {@code <dataspace>/<dataset><tablePath>}: the catalog's qualified name of the EBX table that feeds this table. */
		public String getEbxTable()
		{
			return this.ebxTable;
		}

		/** The Connectors root mapping id that produces this table. */
		public int getMapping()
		{
			return this.mapping;
		}
	}

	/** The whole publication: one cut-off, one contract, one or more tables committed together. */
	final class PublicationSpec
	{
		private final String schema;
		private final String runId;
		private final String product;
		private final String contractVersion;
		private final String cutoff;
		private final List<TableSpec> tables;
		private final String description;
		private final String owner;
		private final String domain;
		private final String title;

		public PublicationSpec(final String pSchema, final String pRunId, final String pProduct,
			final String pContractVersion, final String pCutoff, final List<TableSpec> pTables)
		{
			this(pSchema, pRunId, pProduct, pContractVersion, pCutoff, pTables, null, null, null);
		}

		public PublicationSpec(final String pSchema, final String pRunId, final String pProduct,
			final String pContractVersion, final String pCutoff, final List<TableSpec> pTables,
			final String pDescription, final String pOwner, final String pDomain)
		{
			this(pSchema, pRunId, pProduct, pContractVersion, pCutoff, pTables, pDescription, pOwner, pDomain, null);
		}

		public PublicationSpec(final String pSchema, final String pRunId, final String pProduct,
			final String pContractVersion, final String pCutoff, final List<TableSpec> pTables,
			final String pDescription, final String pOwner, final String pDomain, final String pTitle)
		{
			this.title = pTitle == null || pTitle.trim().isEmpty() ? pProduct : pTitle.trim();
			this.description = pDescription == null ? "" : pDescription.trim();
			this.owner = pOwner == null ? "" : pOwner.trim();
			this.domain = pDomain == null ? "" : pDomain.trim();
			this.schema = pSchema;
			this.runId = pRunId;
			this.product = pProduct;
			this.contractVersion = pContractVersion;
			this.cutoff = pCutoff;
			this.tables = pTables;
		}

		/** {@code DB.SCHEMA} or {@code SCHEMA}, upper-case, already validated by the caller. */
		public String getSchema()
		{
			return this.schema;
		}

		public String getRunId()
		{
			return this.runId;
		}

		public String getProduct()
		{
			return this.product;
		}

		public String getContractVersion()
		{
			return this.contractVersion;
		}

		/** ISO local date-time, {@code yyyy-MM-dd'T'HH:mm:ss}. */
		public String getCutoff()
		{
			return this.cutoff;
		}

		public List<TableSpec> getTables()
		{
			return this.tables;
		}

		/** Display name of the product (its code when none was given), for the listing title. */
		public String getTitle()
		{
			return this.title;
		}

		/** Business description of the product, for comments on the target; empty when unknown. */
		public String getDescription()
		{
			return this.description;
		}

		/** Who owns the product (a name or a team), for tags on the target; empty when unknown. */
		public String getOwner()
		{
			return this.owner;
		}

		/** Business domain of the product, for tags on the target; empty when unknown. */
		public String getDomain()
		{
			return this.domain;
		}
	}

	/** Native type the connector will use for a column (for reports and DDL), e.g. {@code VARCHAR(120)}, {@code NUMBER(38,0)}, {@code VARIANT}. */
	String nativeType(ColumnSpec pColumn);

	/** Statements {@link #prepare} and {@link #commit} would run, in order; nothing is executed (dry run). */
	List<String> plan(PublicationSpec pSpec, boolean pRecreate);

	/**
	 * Ensures the schema, the control table, every target table (created, or checked: a table whose
	 * columns differ is replaced when {@code pRecreate} is true, else this throws naming the added and
	 * missing columns) and an empty staging table per target table. Returns, per table name, one of
	 * {@code CREATED}, {@code EXISTS} or {@code RECREATED}.
	 */
	Map<String, String> prepare(ConnectorContext pContext, PublicationSpec pSpec, boolean pRecreate) throws ConnectorException;

	/** Loads documents into the staging table of {@code pTable}; document keys are exactly the column names. Returns rows loaded. */
	int load(ConnectorContext pContext, PublicationSpec pSpec, TableSpec pTable, List<ObjectNode> pDocuments) throws ConnectorException;

	/** Atomically replaces the content of every target table by its staging content and records the publication row. */
	void commit(ConnectorContext pContext, PublicationSpec pSpec, Map<String, Integer> pRowsPerTable) throws ConnectorException;

	/**
	 * Describes the published tables to whoever finds them on the target: comments on each table and column,
	 * and tags carrying the product, contract version, source, owner and domain. Runs after {@link #commit},
	 * on every publication (an existing table keeps no stale text). Best effort: a failure here (for example
	 * a target edition without tags) never undoes a committed publication; it comes back as a warning.
	 * The default does nothing.
	 */
	default List<String> annotate(final ConnectorContext pContext, final PublicationSpec pSpec)
	{
		return java.util.Collections.emptyList();
	}

	/** Statements {@link #annotate} would run, in order; nothing is executed. The default is empty. */
	default List<String> annotationPlan(final PublicationSpec pSpec)
	{
		return java.util.Collections.emptyList();
	}

	/** Drops the staging tables; best effort, never throws; called after {@link #commit} and after any failure. */
	void cleanup(ConnectorContext pContext, PublicationSpec pSpec);
}
