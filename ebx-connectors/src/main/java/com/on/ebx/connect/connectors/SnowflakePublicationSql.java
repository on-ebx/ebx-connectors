package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.TablePublisher.ColumnContext;
import com.on.ebx.connect.spi.TablePublisher.ColumnSpec;
import com.on.ebx.connect.spi.TablePublisher.Kind;
import com.on.ebx.connect.spi.TablePublisher.PublicationSpec;
import com.on.ebx.connect.spi.TablePublisher.TableSpec;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure SQL generation for {@link SnowflakeConnector}'s {@code TablePublisher} implementation. No
 * EBX type, no I/O: every method here is a static function of its arguments, unit-tested directly
 * by {@code SnowflakePublicationSqlTest}. Every identifier it receives (schema parts, table and
 * column names) is assumed already upper-case and valid ({@code [A-Z_][A-Z0-9_]*}) — this class
 * only double-quotes it.
 */
final class SnowflakePublicationSql
{
	private SnowflakePublicationSql()
	{
	}

	static final String CONTROL_TABLE = "EBX_PUBLICATION";
	static final String STAGING_SUFFIX = "__STG";

	/** Native Snowflake type for a column, e.g. {@code VARCHAR(120)}, {@code NUMBER(38,0)}, {@code VARIANT}. */
	static String nativeType(final ColumnSpec pColumn)
	{
		final Kind kind = pColumn.getKind();
		if (kind == Kind.STRING)
		{
			return pColumn.getMaxLength() == null ? "VARCHAR" : "VARCHAR(" + pColumn.getMaxLength() + ")";
		}
		if (kind == Kind.INTEGER)
		{
			return "NUMBER(38,0)";
		}
		if (kind == Kind.DECIMAL)
		{
			return "NUMBER(38,10)";
		}
		if (kind == Kind.BOOLEAN)
		{
			return "BOOLEAN";
		}
		if (kind == Kind.DATE)
		{
			return "DATE";
		}
		if (kind == Kind.DATETIME)
		{
			return "TIMESTAMP_NTZ";
		}
		if (kind == Kind.TIME)
		{
			return "TIME";
		}
		return "VARIANT"; // JSON
	}

	/** {@code "IDENTIFIER"}. */
	static String quote(final String pIdentifier)
	{
		return "\"" + pIdentifier + "\"";
	}

	/** {@code "DB"."SCHEMA"} from {@code spec.getSchema()} ({@code DB.SCHEMA} or {@code SCHEMA}) and the connector's own database property. */
	static String[] databaseAndSchema(final String pSpecSchema, final String pConnectorDatabase) throws ConnectorException
	{
		final String schema = pSpecSchema == null ? "" : pSpecSchema.trim();
		final String[] parts = schema.split("\\.");
		if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty())
		{
			return new String[] { parts[0], parts[1] };
		}
		if (parts.length == 1 && !parts[0].isEmpty())
		{
			if (pConnectorDatabase == null || pConnectorDatabase.trim().isEmpty())
			{
				throw new ConnectorException("Snowflake publish: schema '" + pSpecSchema
					+ "' names no database, and the connector has no 'database' property either");
			}
			return new String[] { pConnectorDatabase.trim().toUpperCase(java.util.Locale.ROOT), parts[0] };
		}
		throw new ConnectorException("Snowflake publish: invalid schema '" + pSpecSchema + "' (expected DB.SCHEMA or SCHEMA)");
	}

	static String qualified(final String pDatabase, final String pSchema, final String pTable)
	{
		return SnowflakePublicationSql.quote(pDatabase) + "." + SnowflakePublicationSql.quote(pSchema) + "." + SnowflakePublicationSql.quote(pTable);
	}

	static String stagingName(final String pTable)
	{
		return pTable + SnowflakePublicationSql.STAGING_SUFFIX;
	}

	static String createSchemaSql(final String pDatabase, final String pSchema)
	{
		return "CREATE SCHEMA IF NOT EXISTS " + SnowflakePublicationSql.quote(pDatabase) + "." + SnowflakePublicationSql.quote(pSchema);
	}

	static String createControlTableSql(final String pDatabase, final String pSchema)
	{
		return "CREATE TABLE IF NOT EXISTS " + SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.CONTROL_TABLE)
			+ " (RUN_ID VARCHAR, PRODUCT VARCHAR, CONTRACT_VERSION VARCHAR, CUTOFF TIMESTAMP_NTZ, PUBLISHED_AT TIMESTAMP_NTZ, "
			+ "TABLES VARIANT, ROW_COUNT NUMBER(38,0), STATUS VARCHAR)";
	}

	static String describeTableSql(final String pDatabase, final String pSchema, final String pTable)
	{
		return "DESCRIBE TABLE " + SnowflakePublicationSql.qualified(pDatabase, pSchema, pTable);
	}

	static String createTableSql(final String pDatabase, final String pSchema, final TableSpec pTable, final boolean pReplace)
	{
		final StringBuilder sql = new StringBuilder(pReplace ? "CREATE OR REPLACE TABLE " : "CREATE TABLE ")
			.append(SnowflakePublicationSql.qualified(pDatabase, pSchema, pTable.getName())).append(" (");
		for (int i = 0; i < pTable.getColumns().size(); i++)
		{
			final ColumnSpec column = pTable.getColumns().get(i);
			sql.append(i > 0 ? ", " : "").append(SnowflakePublicationSql.quote(column.getName())).append(' ')
				.append(SnowflakePublicationSql.nativeType(column));
		}
		sql.append(')');
		if (pTable.getComment() != null && !pTable.getComment().trim().isEmpty())
		{
			sql.append(" COMMENT = '").append(pTable.getComment().replace("'", "''")).append('\'');
		}
		return sql.toString();
	}

	static String createStagingTableSql(final String pDatabase, final String pSchema, final String pTable)
	{
		return "CREATE OR REPLACE TABLE " + SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.stagingName(pTable))
			+ " LIKE " + SnowflakePublicationSql.qualified(pDatabase, pSchema, pTable);
	}

	/** {@code missing: a, b; extra: c} naming the mismatch between the existing table and the contract. */
	static String columnMismatchMessage(final String pTable, final java.util.Set<String> pMissing, final java.util.Set<String> pExtra)
	{
		final StringBuilder sb = new StringBuilder("Snowflake publish: table ").append(pTable)
			.append(" exists with a different contract (");
		if (!pMissing.isEmpty())
		{
			sb.append("missing: ").append(SnowflakePublicationSql.join(pMissing));
		}
		if (!pMissing.isEmpty() && !pExtra.isEmpty())
		{
			sb.append("; ");
		}
		if (!pExtra.isEmpty())
		{
			sb.append("extra: ").append(SnowflakePublicationSql.join(pExtra));
		}
		sb.append("); publish with recreate=true or a new table name");
		return sb.toString();
	}

	private static String join(final java.util.Set<String> pValues)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String v : pValues)
		{
			sb.append(sb.length() > 0 ? ", " : "").append(v);
		}
		return sb.toString();
	}

	/** The one insert statement of a batch: {@code INSERT INTO staging (cols) SELECT casts FROM TABLE(FLATTEN(INPUT => PARSE_JSON(?))) f}. */
	static String insertFlattenSql(final String pDatabase, final String pSchema, final TableSpec pTable)
	{
		final StringBuilder columns = new StringBuilder();
		final StringBuilder selects = new StringBuilder();
		for (int i = 0; i < pTable.getColumns().size(); i++)
		{
			final ColumnSpec column = pTable.getColumns().get(i);
			if (i > 0)
			{
				columns.append(", ");
				selects.append(", ");
			}
			final String quotedName = SnowflakePublicationSql.quote(column.getName());
			columns.append(quotedName);
			selects.append("f.value:").append(quotedName);
			if (column.getKind() != Kind.JSON)
			{
				selects.append("::").append(SnowflakePublicationSql.nativeType(column));
			}
		}
		return "INSERT INTO " + SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.stagingName(pTable.getName()))
			+ " (" + columns + ") SELECT " + selects + " FROM TABLE(FLATTEN(INPUT => PARSE_JSON(?))) f";
	}

	private static String literal(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	/** The atomic commit: BEGIN, one DELETE+INSERT pair per table, the control-table row, COMMIT. One statement per list entry. */
	static List<String> commitStatements(final String pDatabase, final String pSchema, final PublicationSpec pSpec, final Map<String, Integer> pRowsPerTable)
	{
		final List<String> statements = new ArrayList<String>();
		statements.add("BEGIN");
		int total = 0;
		final ObjectNode tables = Json.object();
		for (final TableSpec table : pSpec.getTables())
		{
			final String qualified = SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName());
			statements.add("DELETE FROM " + qualified);
			statements.add("INSERT INTO " + qualified + " SELECT * FROM "
				+ SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.stagingName(table.getName())));
			final Integer rows = pRowsPerTable.get(table.getName());
			final int r = rows == null ? 0 : rows.intValue();
			total += r;
			tables.put(table.getName(), r);
		}
		statements.add("INSERT INTO " + SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.CONTROL_TABLE)
			+ " SELECT " + SnowflakePublicationSql.literal(pSpec.getRunId()) + ", " + SnowflakePublicationSql.literal(pSpec.getProduct()) + ", "
			+ SnowflakePublicationSql.literal(pSpec.getContractVersion()) + ", TO_TIMESTAMP_NTZ(" + SnowflakePublicationSql.literal(pSpec.getCutoff())
			// SYSDATE(), not CURRENT_TIMESTAMP(): PUBLISHED_AT is TIMESTAMP_NTZ, and CURRENT_TIMESTAMP() stores the
			// Snowflake session's local time zone into it with no offset kept, so it silently reads back as if it
			// were UTC (e.g. 09:06 Los Angeles for an 18:06 CEST cut-off). SYSDATE() is always UTC.
			+ "), SYSDATE(), PARSE_JSON(" + SnowflakePublicationSql.literal(Json.write(tables)) + "), " + total + ", 'COMMITTED'");
		statements.add("COMMIT");
		return statements;
	}

	static final String TAG_PRODUCT = "EBX_PRODUCT";
	static final String TAG_CONTRACT_VERSION = "EBX_CONTRACT_VERSION";
	static final String TAG_SOURCE = "EBX_SOURCE_TABLE";
	static final String TAG_OWNER = "EBX_OWNER";
	static final String TAG_DOMAIN = "EBX_DOMAIN";
	/** 2026-09-30: column tags, from the column's meaning (glossary term, sensitivity, catalog classification). */
	static final String TAG_GLOSSARY_TERM = "EBX_GLOSSARY_TERM";
	static final String TAG_SENSITIVITY = "EBX_SENSITIVITY";
	static final String TAG_CLASSIFICATION = "EBX_CLASSIFICATION";

	/** Snowflake caps a comment at 2000 characters and a tag value at 256. */
	private static String cut(final String pText, final int pMax)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() <= pMax ? text : text.substring(0, pMax - 3) + "...";
	}

	/** What a reader of the target table should know about it: the product, where it comes from, and that it is a copy. */
	static String tableComment(final PublicationSpec pSpec, final TableSpec pTable)
	{
		final StringBuilder sb = new StringBuilder();
		if (!pSpec.getDescription().isEmpty())
		{
			sb.append(pSpec.getDescription()).append(" | ");
		}
		sb.append("Data product ").append(pSpec.getProduct()).append(", contract version ").append(pSpec.getContractVersion());
		if (pTable.getComment() != null && !pTable.getComment().trim().isEmpty())
		{
			sb.append(" | ").append(pTable.getComment().trim());
		}
		sb.append(" | Read-only copy: ON EBX is the source of truth and every publication replaces the whole content.");
		return SnowflakePublicationSql.cut(sb.toString(), 2000);
	}

	/**
	 * A column's comment: what it means first (business name, glossary definition or description, synonyms,
	 * glossary term), then where it comes from in ON EBX. Snowflake's search and Cortex read the comment, so the
	 * business words go where they look. Only the technical origin when nothing more is known.
	 */
	static String columnComment(final ColumnSpec pColumn)
	{
		final String technical = pColumn.getComment() == null ? "" : pColumn.getComment().trim();
		final ColumnContext c = pColumn.getContext();
		if (c == null)
		{
			return technical;
		}
		final StringBuilder sb = new StringBuilder();
		final String meaning = !c.getDefinition().isEmpty() ? c.getDefinition() : c.getDescription();
		sb.append(c.getBusinessName());
		if (!meaning.isEmpty())
		{
			sb.append(sb.length() == 0 ? "" : ": ").append(meaning);
		}
		if (!c.getDefinition().isEmpty() && !c.getDescription().isEmpty() && !c.getDescription().equals(c.getDefinition()))
		{
			sb.append(sb.length() == 0 ? "" : " ").append(c.getDescription());
		}
		if (!c.getSynonyms().isEmpty())
		{
			sb.append(sb.length() == 0 ? "" : " | ").append("Also called: ").append(SnowflakePublicationSql.joinList(c.getSynonyms()));
		}
		if (!c.getTermCode().isEmpty())
		{
			sb.append(sb.length() == 0 ? "" : " | ").append("Glossary term: ").append(c.getTermLabel().isEmpty() ? c.getTermCode() : c.getTermLabel() + " (" + c.getTermCode() + ")");
		}
		if (!technical.isEmpty())
		{
			sb.append(sb.length() == 0 ? "" : " | ").append(technical);
		}
		return sb.toString();
	}

	static String joinList(final List<String> pValues)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String v : pValues)
		{
			sb.append(sb.length() == 0 ? "" : ", ").append(v);
		}
		return sb.toString();
	}

	/** The column tags of one column: {tag, value} for the glossary term, the sensitivity and the classification that are known. */
	static List<String[]> columnTags(final ColumnSpec pColumn)
	{
		final List<String[]> out = new ArrayList<String[]>();
		final ColumnContext c = pColumn.getContext();
		if (c == null)
		{
			return out;
		}
		if (!c.getTermCode().isEmpty()) out.add(new String[] { SnowflakePublicationSql.TAG_GLOSSARY_TERM, c.getTermCode() });
		if (!c.getSensitivity().isEmpty()) out.add(new String[] { SnowflakePublicationSql.TAG_SENSITIVITY, c.getSensitivity() });
		if (!c.getTags().isEmpty()) out.add(new String[] { SnowflakePublicationSql.TAG_CLASSIFICATION, SnowflakePublicationSql.joinList(c.getTags()) });
		return out;
	}

	/** Comments: one statement per table plus one per commented column. Idempotent, safe on an existing table. */
	static List<String> commentStatements(final String pDatabase, final String pSchema, final PublicationSpec pSpec)
	{
		final List<String> statements = new ArrayList<String>();
		for (final TableSpec table : pSpec.getTables())
		{
			final String qualified = SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName());
			statements.add("ALTER TABLE " + qualified + " SET COMMENT = "
				+ SnowflakePublicationSql.literal(SnowflakePublicationSql.tableComment(pSpec, table)));
			for (final ColumnSpec column : table.getColumns())
			{
				final String comment = SnowflakePublicationSql.columnComment(column);
				if (!comment.isEmpty())
				{
					statements.add("ALTER TABLE " + qualified + " ALTER COLUMN " + SnowflakePublicationSql.quote(column.getName())
						+ " COMMENT " + SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(comment, 2000)));
				}
			}
		}
		return statements;
	}

	/** Tags: the tag objects are created in the publication schema, then set on every table. Owner and domain only when known. */
	static List<String> tagStatements(final String pDatabase, final String pSchema, final PublicationSpec pSpec)
	{
		final List<String> statements = new ArrayList<String>();
		final List<String[]> tags = new ArrayList<String[]>();
		tags.add(new String[] { SnowflakePublicationSql.TAG_PRODUCT, pSpec.getProduct() });
		tags.add(new String[] { SnowflakePublicationSql.TAG_CONTRACT_VERSION, pSpec.getContractVersion() });
		tags.add(new String[] { SnowflakePublicationSql.TAG_SOURCE, null }); // per table, below
		if (!pSpec.getOwner().isEmpty())
		{
			tags.add(new String[] { SnowflakePublicationSql.TAG_OWNER, pSpec.getOwner() });
		}
		if (!pSpec.getDomain().isEmpty())
		{
			tags.add(new String[] { SnowflakePublicationSql.TAG_DOMAIN, pSpec.getDomain() });
		}
		for (final String[] tag : tags)
		{
			statements.add("CREATE TAG IF NOT EXISTS " + SnowflakePublicationSql.qualified(pDatabase, pSchema, tag[0]));
		}
		for (final TableSpec table : pSpec.getTables())
		{
			final StringBuilder set = new StringBuilder("ALTER TABLE ")
				.append(SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName())).append(" SET TAG ");
			for (int i = 0; i < tags.size(); i++)
			{
				final String[] tag = tags.get(i);
				final String value = tag[1] == null ? table.getEbxTable() : tag[1];
				set.append(i > 0 ? ", " : "").append(SnowflakePublicationSql.qualified(pDatabase, pSchema, tag[0])).append(" = ")
					.append(SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(value, 256)));
			}
			statements.add(set.toString());
		}
		// 2026-09-30: the column tags; a tag object is created only when some column needs it
		final java.util.Set<String> columnTagNames = new java.util.LinkedHashSet<String>();
		final List<String> columnSets = new ArrayList<String>();
		for (final TableSpec table : pSpec.getTables())
		{
			for (final ColumnSpec column : table.getColumns())
			{
				final List<String[]> columnTags = SnowflakePublicationSql.columnTags(column);
				if (columnTags.isEmpty())
				{
					continue;
				}
				final StringBuilder set = new StringBuilder("ALTER TABLE ").append(SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName()))
					.append(" ALTER COLUMN ").append(SnowflakePublicationSql.quote(column.getName())).append(" SET TAG ");
				for (int i = 0; i < columnTags.size(); i++)
				{
					columnTagNames.add(columnTags.get(i)[0]);
					set.append(i > 0 ? ", " : "").append(SnowflakePublicationSql.qualified(pDatabase, pSchema, columnTags.get(i)[0])).append(" = ")
						.append(SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(columnTags.get(i)[1], 256)));
				}
				columnSets.add(set.toString());
			}
		}
		for (final String name : columnTagNames)
		{
			statements.add("CREATE TAG IF NOT EXISTS " + SnowflakePublicationSql.qualified(pDatabase, pSchema, name));
		}
		statements.addAll(columnSets);
		return statements;
	}

	// ------------------------------------------------------------------ semantic view (Cortex Analyst, Horizon)

	/** {@code EBX_<PRODUCT>_SEMANTIC}: one semantic view per product, in the publication schema. */
	static String semanticViewName(final String pProduct)
	{
		return SnowflakePublicationSql.listingName(pProduct) + "_SEMANTIC";
	}

	/**
	 * The synonyms of a column for a semantic view: the glossary synonyms, then the business name and the term
	 * label when they differ from the column name. Case-insensitive duplicates removed, order kept.
	 */
	static List<String> columnSynonyms(final ColumnSpec pColumn)
	{
		final List<String> out = new ArrayList<String>();
		final ColumnContext c = pColumn.getContext();
		if (c == null)
		{
			return out;
		}
		final List<String> candidates = new ArrayList<String>(c.getSynonyms());
		candidates.add(c.getBusinessName());
		candidates.add(c.getTermLabel());
		final java.util.Set<String> seen = new java.util.HashSet<String>();
		// the column name itself, however it is spelled (LEGAL_FORM, "Legal form"), is no synonym
		seen.add(SnowflakePublicationSql.synonymKey(pColumn.getName()));
		for (final String candidate : candidates)
		{
			final String value = candidate == null ? "" : candidate.trim();
			if (!value.isEmpty() && seen.add(SnowflakePublicationSql.synonymKey(value)))
			{
				out.add(value);
			}
		}
		return out;
	}

	private static String synonymKey(final String pValue)
	{
		return pValue.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').trim();
	}

	/**
	 * A dimension's comment: the business name and what it means, nothing else. The synonyms are in WITH SYNONYMS and
	 * the technical origin is noise for a reader asking in business words (Snowflake's guidance for Cortex Analyst:
	 * descriptions first, no duplicated vocabulary). The technical comment only when no meaning is known.
	 */
	static String dimensionComment(final ColumnSpec pColumn)
	{
		final ColumnContext c = pColumn.getContext();
		if (c == null)
		{
			return pColumn.getComment() == null ? "" : pColumn.getComment().trim();
		}
		final StringBuilder sb = new StringBuilder(c.getBusinessName());
		final String meaning = !c.getDefinition().isEmpty() ? c.getDefinition() : c.getDescription();
		if (!meaning.isEmpty())
		{
			sb.append(sb.length() == 0 ? "" : ": ").append(meaning);
		}
		if (!c.getDefinition().isEmpty() && !c.getDescription().isEmpty() && !c.getDescription().equals(c.getDefinition()))
		{
			sb.append(' ').append(c.getDescription());
		}
		return sb.length() == 0 ? (c.getTermLabel().isEmpty() ? c.getTermCode() : c.getTermLabel()) : sb.toString();
	}

	/** A published column that belongs in the semantic view: a business column (no {@code _EBX} technical one) with a scalar value. */
	static boolean isSemanticDimension(final ColumnSpec pColumn)
	{
		return !pColumn.getName().startsWith("_") && pColumn.getKind() != Kind.JSON;
	}

	/**
	 * {@code CREATE OR REPLACE SEMANTIC VIEW} over every published table: each business column is a dimension carrying
	 * its glossary synonyms and its business comment, each table a {@code RECORD_COUNT} metric. This is what Cortex
	 * Analyst reads to answer questions in business words. No relationship: the tables of a product are published
	 * independently and their links are not known here. {@code null} when no table has a business column.
	 */
	static String semanticViewSql(final String pDatabase, final String pSchema, final PublicationSpec pSpec)
	{
		final StringBuilder tables = new StringBuilder();
		final StringBuilder dimensions = new StringBuilder();
		final StringBuilder metrics = new StringBuilder();
		final java.util.Map<String, Integer> nameCount = new java.util.HashMap<String, Integer>();
		for (final TableSpec table : pSpec.getTables())
		{
			for (final ColumnSpec column : table.getColumns())
			{
				if (SnowflakePublicationSql.isSemanticDimension(column))
				{
					final Integer n = nameCount.get(column.getName());
					nameCount.put(column.getName(), Integer.valueOf(n == null ? 1 : n.intValue() + 1));
				}
			}
		}
		for (final TableSpec table : pSpec.getTables())
		{
			final String alias = SnowflakePublicationSql.quote(table.getName());
			boolean any = false;
			for (final ColumnSpec column : table.getColumns())
			{
				if (!SnowflakePublicationSql.isSemanticDimension(column))
				{
					continue;
				}
				any = true;
				// dimension names must be unique in the whole view: a column name used by two tables is prefixed with its table
				final String name = nameCount.get(column.getName()).intValue() > 1 ? table.getName() + "_" + column.getName() : column.getName();
				dimensions.append(dimensions.length() == 0 ? "" : ",\n").append("    ").append(alias).append('.').append(SnowflakePublicationSql.quote(name))
					.append(" AS ").append(alias).append('.').append(SnowflakePublicationSql.quote(column.getName()));
				final List<String> synonyms = SnowflakePublicationSql.columnSynonyms(column);
				if (!synonyms.isEmpty())
				{
					dimensions.append(" WITH SYNONYMS = (");
					for (int i = 0; i < synonyms.size(); i++)
					{
						dimensions.append(i > 0 ? ", " : "").append(SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(synonyms.get(i), 255)));
					}
					dimensions.append(')');
				}
				final String comment = SnowflakePublicationSql.dimensionComment(column);
				if (!comment.isEmpty())
				{
					dimensions.append(" COMMENT = ").append(SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(comment, 2000)));
				}
			}
			if (!any)
			{
				continue;
			}
			tables.append(tables.length() == 0 ? "" : ",\n").append("    ").append(alias).append(" AS ")
				.append(SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName()));
			final StringBuilder keys = new StringBuilder();
			for (final ColumnSpec column : table.getColumns())
			{
				if (column.isKey())
				{
					keys.append(keys.length() == 0 ? "" : ", ").append(SnowflakePublicationSql.quote(column.getName()));
				}
			}
			if (keys.length() > 0)
			{
				tables.append(" PRIMARY KEY (").append(keys).append(')');
			}
			tables.append(" COMMENT = ").append(SnowflakePublicationSql.literal(SnowflakePublicationSql.tableComment(pSpec, table)));
			final String metric = pSpec.getTables().size() > 1 ? table.getName() + "_RECORD_COUNT" : "RECORD_COUNT";
			metrics.append(metrics.length() == 0 ? "" : ",\n").append("    ").append(alias).append('.').append(SnowflakePublicationSql.quote(metric))
				.append(" AS COUNT(").append(alias).append('.').append(SnowflakePublicationSql.quote("_EBX_KEY")).append(')')
				.append(" COMMENT = ").append(SnowflakePublicationSql.literal("Number of records of " + table.getName()));
		}
		if (tables.length() == 0)
		{
			return null;
		}
		final String title = pSpec.getTitle();
		final String comment = (pSpec.getDescription().isEmpty() ? title : title + ": " + pSpec.getDescription())
			+ " | Semantic view of data product " + pSpec.getProduct() + ", contract version " + pSpec.getContractVersion()
			+ ", generated by ON EBX from its glossary at each publication.";
		return "CREATE OR REPLACE SEMANTIC VIEW " + SnowflakePublicationSql.qualified(pDatabase, pSchema, SnowflakePublicationSql.semanticViewName(pSpec.getProduct()))
			+ "\n  TABLES (\n" + tables + "\n  )\n  DIMENSIONS (\n" + dimensions + "\n  )\n  METRICS (\n" + metrics + "\n  )\n  COMMENT = "
			+ SnowflakePublicationSql.literal(SnowflakePublicationSql.cut(comment, 2000));
	}

	// ------------------------------------------------------------------ listing (Internal Marketplace)

	/** {@code EBX_<PRODUCT>}: upper-cased, every character outside A-Z, 0-9 and _ replaced by _; unique per product in the account. */
	static String listingName(final String pProduct)
	{
		final String source = pProduct == null ? "" : pProduct.trim().toUpperCase(java.util.Locale.ROOT);
		final StringBuilder sb = new StringBuilder("EBX_");
		for (int i = 0; i < source.length(); i++)
		{
			final char c = source.charAt(i);
			sb.append(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' ? c : '_');
		}
		return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
	}

	static String shareName(final String pProduct)
	{
		return SnowflakePublicationSql.listingName(pProduct) + "_SHARE";
	}

	/** The share behind the listing: the schema and every published table, granted read-only. Idempotent. */
	static List<String> shareStatements(final String pDatabase, final String pSchema, final PublicationSpec pSpec)
	{
		final String share = SnowflakePublicationSql.quote(SnowflakePublicationSql.shareName(pSpec.getProduct()));
		final List<String> statements = new ArrayList<String>();
		statements.add("CREATE SHARE IF NOT EXISTS " + share + " SECURE_OBJECTS_ONLY = FALSE");
		statements.add("GRANT USAGE ON DATABASE " + SnowflakePublicationSql.quote(pDatabase) + " TO SHARE " + share);
		statements.add("GRANT USAGE ON SCHEMA " + SnowflakePublicationSql.quote(pDatabase) + "." + SnowflakePublicationSql.quote(pSchema) + " TO SHARE " + share);
		for (final TableSpec table : pSpec.getTables())
		{
			statements.add("GRANT SELECT ON TABLE " + SnowflakePublicationSql.qualified(pDatabase, pSchema, table.getName()) + " TO SHARE " + share);
		}
		return statements;
	}

	/** A YAML double-quoted scalar: backslash, quote and line breaks escaped, and {@code $$} broken up (the manifest is itself dollar-quoted). */
	static String yaml(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ").replace("$$", "$ $") + "\"";
	}

	/** Snowflake region for the manifest: {@code PUBLIC.AWS_EU_WEST_3}; a bare region name gets the {@code PUBLIC.} prefix. */
	static String listingRegion(final String pRegion)
	{
		final String region = pRegion == null ? "" : pRegion.trim().toUpperCase(java.util.Locale.ROOT);
		return region.isEmpty() || region.indexOf('.') >= 0 ? region : "PUBLIC." + region;
	}

	/** The organizational listing manifest: internal profile, every account of the organization may access it. */
	static String listingManifest(final PublicationSpec pSpec, final String pSupportContact, final String pRegion)
	{
		final String description = pSpec.getDescription().isEmpty()
			? "Data product " + pSpec.getProduct() + " (contract version " + pSpec.getContractVersion() + ") published by ON EBX. Read-only copy: ON EBX is the source of truth."
			: pSpec.getDescription() + " (data product " + pSpec.getProduct() + ", contract version " + pSpec.getContractVersion() + ", published by ON EBX; read-only copy).";
		final StringBuilder sb = new StringBuilder();
		sb.append("title: ").append(SnowflakePublicationSql.yaml(pSpec.getTitle())).append('\n');
		sb.append("description: ").append(SnowflakePublicationSql.yaml(description)).append('\n');
		sb.append("organization_profile: INTERNAL\n");
		sb.append("organization_targets:\n  access:\n  - all_accounts: true\n");
		sb.append("support_contact: ").append(SnowflakePublicationSql.yaml(pSupportContact)).append('\n');
		sb.append("locations:\n  access_regions:\n  - name: ").append(SnowflakePublicationSql.yaml(SnowflakePublicationSql.listingRegion(pRegion))).append('\n');
		return sb.toString();
	}

	static String createListingSql(final String pProduct, final String pManifest)
	{
		return "CREATE ORGANIZATION LISTING " + SnowflakePublicationSql.quote(SnowflakePublicationSql.listingName(pProduct)) + " SHARE "
			+ SnowflakePublicationSql.quote(SnowflakePublicationSql.shareName(pProduct)) + " AS $$\n" + pManifest + "$$";
	}

	/** Replaces the manifest of an existing listing in place (verified on a published organizational listing, 2026-09-19). */
	static String alterListingSql(final String pProduct, final String pManifest)
	{
		return "ALTER LISTING " + SnowflakePublicationSql.quote(SnowflakePublicationSql.listingName(pProduct)) + " AS $$\n" + pManifest + "$$";
	}

	static String showListingSql(final String pProduct)
	{
		return "SHOW LISTINGS LIKE " + SnowflakePublicationSql.literal(SnowflakePublicationSql.listingName(pProduct));
	}

	static String joinStatements(final List<String> pStatements)
	{
		final StringBuilder sql = new StringBuilder();
		for (final String statement : pStatements)
		{
			sql.append(sql.length() > 0 ? ";\n" : "").append(statement);
		}
		return sql.toString();
	}
}
