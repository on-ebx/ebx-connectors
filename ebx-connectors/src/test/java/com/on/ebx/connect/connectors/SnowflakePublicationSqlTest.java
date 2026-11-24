package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.TablePublisher.ColumnSpec;
import com.on.ebx.connect.spi.TablePublisher.Kind;
import com.on.ebx.connect.spi.TablePublisher.PublicationSpec;
import com.on.ebx.connect.spi.TablePublisher.TableSpec;

/** Offline checks of the pure SQL generation behind {@code SnowflakeConnector}'s product-publication support (§2.2). */
public final class SnowflakePublicationSqlTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// type mapping
		check("VARCHAR(120)".equals(SnowflakePublicationSql.nativeType(column("A", Kind.STRING, 120))), "string with length");
		check("VARCHAR".equals(SnowflakePublicationSql.nativeType(column("A", Kind.STRING, null))), "string without length");
		check("NUMBER(38,0)".equals(SnowflakePublicationSql.nativeType(column("A", Kind.INTEGER, null))), "integer");
		check("NUMBER(38,10)".equals(SnowflakePublicationSql.nativeType(column("A", Kind.DECIMAL, null))), "decimal");
		check("BOOLEAN".equals(SnowflakePublicationSql.nativeType(column("A", Kind.BOOLEAN, null))), "boolean");
		check("DATE".equals(SnowflakePublicationSql.nativeType(column("A", Kind.DATE, null))), "date");
		check("TIMESTAMP_NTZ".equals(SnowflakePublicationSql.nativeType(column("A", Kind.DATETIME, null))), "datetime");
		check("TIME".equals(SnowflakePublicationSql.nativeType(column("A", Kind.TIME, null))), "time");
		check("VARIANT".equals(SnowflakePublicationSql.nativeType(column("A", Kind.JSON, null))), "json");

		// identifier quoting
		check("\"FOO\"".equals(SnowflakePublicationSql.quote("FOO")), "quote");
		check("\"DB\".\"SCHEMA\".\"T\"".equals(SnowflakePublicationSql.qualified("DB", "SCHEMA", "T")), "qualified");
		check("T__STG".equals(SnowflakePublicationSql.stagingName("T")), "staging name");

		// database/schema resolution
		String[] ds = SnowflakePublicationSql.databaseAndSchema("DB.SCHEMA", null);
		check("DB".equals(ds[0]) && "SCHEMA".equals(ds[1]), "DB.SCHEMA splits");
		ds = SnowflakePublicationSql.databaseAndSchema("SCHEMA", "mydb");
		check("MYDB".equals(ds[0]) && "SCHEMA".equals(ds[1]), "bare schema uses the connector's database, upper-cased");
		boolean threw = false;
		try
		{
			SnowflakePublicationSql.databaseAndSchema("SCHEMA", null);
		}
		catch (final ConnectorException ex)
		{
			threw = true;
			check(ex.getMessage().contains("no database"), "message names the missing database: " + ex.getMessage());
		}
		check(threw, "bare schema without a connector database is refused");
		threw = false;
		try
		{
			SnowflakePublicationSql.databaseAndSchema("A.B.C", "db");
		}
		catch (final ConnectorException ex)
		{
			threw = true;
		}
		check(threw, "three-part schema is refused");

		// DDL
		check(SnowflakePublicationSql.createSchemaSql("DB", "SCHEMA").equals("CREATE SCHEMA IF NOT EXISTS \"DB\".\"SCHEMA\""), "create schema");
		final String control = SnowflakePublicationSql.createControlTableSql("DB", "SCHEMA");
		check(control.startsWith("CREATE TABLE IF NOT EXISTS \"DB\".\"SCHEMA\".\"EBX_PUBLICATION\""), "control table name: " + control);
		check(control.contains("TABLES VARIANT") && control.contains("ROW_COUNT NUMBER(38,0)") && control.contains("STATUS VARCHAR"), "control table columns: " + control);

		final TableSpec table = table();
		final String create = SnowflakePublicationSql.createTableSql("DB", "SCHEMA", table, false);
		check(create.startsWith("CREATE TABLE \"DB\".\"SCHEMA\".\"COMPANY_360\""), "create table: " + create);
		check(create.contains("\"ID\" NUMBER(38,0)") && create.contains("\"NAME\" VARCHAR(120)") && create.contains("\"ADDRESSES\" VARIANT"), "columns typed: " + create);
		check(create.contains("COMMENT = 'a ''quoted'' comment'"), "comment quoted: " + create);
		final String replace = SnowflakePublicationSql.createTableSql("DB", "SCHEMA", table, true);
		check(replace.startsWith("CREATE OR REPLACE TABLE"), "recreate uses CREATE OR REPLACE: " + replace);

		final String staging = SnowflakePublicationSql.createStagingTableSql("DB", "SCHEMA", "COMPANY_360");
		check(staging.equals("CREATE OR REPLACE TABLE \"DB\".\"SCHEMA\".\"COMPANY_360__STG\" LIKE \"DB\".\"SCHEMA\".\"COMPANY_360\""), "staging: " + staging);

		check(SnowflakePublicationSql.describeTableSql("DB", "SCHEMA", "T").equals("DESCRIBE TABLE \"DB\".\"SCHEMA\".\"T\""), "describe");

		final java.util.Set<String> missing = new java.util.LinkedHashSet<String>(java.util.Arrays.asList("A", "B"));
		final java.util.Set<String> extra = new java.util.LinkedHashSet<String>(java.util.Arrays.asList("C"));
		final String mismatch = SnowflakePublicationSql.columnMismatchMessage("T", missing, extra);
		check(mismatch.contains("missing: A, B") && mismatch.contains("extra: C") && mismatch.contains("recreate=true"), "mismatch message: " + mismatch);

		// FLATTEN insert
		final String insert = SnowflakePublicationSql.insertFlattenSql("DB", "SCHEMA", table);
		check(insert.startsWith("INSERT INTO \"DB\".\"SCHEMA\".\"COMPANY_360__STG\" (\"ID\", \"NAME\", \"ADDRESSES\")"), "insert columns: " + insert);
		check(insert.contains("f.value:\"ID\"::NUMBER(38,0)"), "scalar cast: " + insert);
		check(insert.contains("f.value:\"NAME\"::VARCHAR(120)"), "string cast: " + insert);
		check(insert.contains("f.value:\"ADDRESSES\"") && !insert.contains("f.value:\"ADDRESSES\"::"), "JSON column has no cast: " + insert);
		check(insert.endsWith("FROM TABLE(FLATTEN(INPUT => PARSE_JSON(?))) f"), "flatten clause: " + insert);

		// atomic commit
		final PublicationSpec spec = new PublicationSpec("DB.SCHEMA", "R20260919-140000-0001", "COMPANY_360", "1", "2026-09-19T14:00:00",
			java.util.Arrays.asList(table, table("OTHER_TABLE")));
		final Map<String, Integer> rows = new LinkedHashMap<String, Integer>();
		rows.put("COMPANY_360", Integer.valueOf(632));
		rows.put("OTHER_TABLE", Integer.valueOf(10));
		final List<String> statements = SnowflakePublicationSql.commitStatements("DB", "SCHEMA", spec, rows);
		check(statements.size() == 2 * 2 + 3, "one BEGIN, 2 statements per table, one control insert, one COMMIT: " + statements.size());
		check("BEGIN".equals(statements.get(0)), "starts with BEGIN");
		check("COMMIT".equals(statements.get(statements.size() - 1)), "ends with COMMIT");
		check(statements.get(1).equals("DELETE FROM \"DB\".\"SCHEMA\".\"COMPANY_360\""), "delete: " + statements.get(1));
		check(statements.get(2).equals("INSERT INTO \"DB\".\"SCHEMA\".\"COMPANY_360\" SELECT * FROM \"DB\".\"SCHEMA\".\"COMPANY_360__STG\""), "insert-select: " + statements.get(2));
		final String controlInsert = statements.get(statements.size() - 2);
		check(controlInsert.contains("INSERT INTO \"DB\".\"SCHEMA\".\"EBX_PUBLICATION\""), "control insert target: " + controlInsert);
		check(controlInsert.contains("'R20260919-140000-0001'") && controlInsert.contains("'COMPANY_360'") && controlInsert.contains("'1'"), "control insert literals: " + controlInsert);
		check(controlInsert.contains("TO_TIMESTAMP_NTZ('2026-09-19T14:00:00')"), "cutoff literal: " + controlInsert);
		check(controlInsert.contains("642") , "total row count 632+10=642: " + controlInsert);
		check(controlInsert.contains("'COMMITTED'"), "status literal: " + controlInsert);
		// PUBLISHED_AT must be SYSDATE() (always UTC), never CURRENT_TIMESTAMP() (the Snowflake session's own time
		// zone, stored into a TIMESTAMP_NTZ column with no offset kept: it silently reads back as if it were UTC).
		check(controlInsert.contains("SYSDATE()"), "PUBLISHED_AT uses SYSDATE(): " + controlInsert);
		check(!controlInsert.contains("CURRENT_TIMESTAMP()"), "PUBLISHED_AT must never use CURRENT_TIMESTAMP() (session-local, not UTC): " + controlInsert);

		// literal quoting doubles embedded single quotes
		final PublicationSpec quoted = new PublicationSpec("DB.SCHEMA", "R1", "O'Reilly", "1", "2026-01-01T00:00:00", java.util.Collections.singletonList(table));
		final List<String> quotedStatements = SnowflakePublicationSql.commitStatements("DB", "SCHEMA", quoted, java.util.Collections.singletonMap("COMPANY_360", Integer.valueOf(1)));
		final String quotedInsert = quotedStatements.get(quotedStatements.size() - 2);
		check(quotedInsert.contains("'O''Reilly'"), "single quote doubled: " + quotedInsert);

		final String joined = SnowflakePublicationSql.joinStatements(java.util.Arrays.asList("A", "B", "C"));
		check(joined.equals("A;\nB;\nC"), "joined statements: " + joined);

		// comments and tags (annotation step)
		final List<ColumnSpec> annotated = new ArrayList<ColumnSpec>();
		annotated.add(new ColumnSpec("ID", Kind.INTEGER, null, true, "./Id", "ON EBX field ./Id (business key)"));
		annotated.add(new ColumnSpec("NOTE", Kind.STRING, Integer.valueOf(10), false, "", null));
		annotated.add(new ColumnSpec("_RUN_ID", Kind.STRING, Integer.valueOf(160), false, "", "it's technical"));
		final TableSpec annotatedTable = new TableSpec("COMPANY_360", annotated, "from mapping 213", "Companies/Companies/root/Companies", 213);
		final PublicationSpec described = new PublicationSpec("DB.SCHEMA", "R1", "COMPANY_360", "2", "2026-01-01T00:00:00",
			java.util.Collections.singletonList(annotatedTable), "Golden company view", "Data Office", "Customer");
		final List<String> comments = SnowflakePublicationSql.commentStatements("DB", "SCHEMA", described);
		check(comments.size() == 3, "one table comment + 2 commented columns (the uncommented one is skipped): " + comments.size());
		check(comments.get(0).startsWith("ALTER TABLE \"DB\".\"SCHEMA\".\"COMPANY_360\" SET COMMENT = 'Golden company view | Data product COMPANY_360, contract version 2"),
			"table comment starts with the description: " + comments.get(0));
		check(comments.get(0).contains("Read-only copy"), "table comment says it is a copy: " + comments.get(0));
		check(comments.get(1).equals("ALTER TABLE \"DB\".\"SCHEMA\".\"COMPANY_360\" ALTER COLUMN \"ID\" COMMENT 'ON EBX field ./Id (business key)'"), "column comment: " + comments.get(1));
		check(comments.get(2).contains("'it''s technical'"), "quote doubled in a column comment: " + comments.get(2));
		final PublicationSpec bare = new PublicationSpec("DB.SCHEMA", "R1", "P", "1", "2026-01-01T00:00:00", java.util.Collections.singletonList(annotatedTable));
		check(SnowflakePublicationSql.commentStatements("DB", "SCHEMA", bare).get(0).contains("SET COMMENT = 'Data product P, contract version 1"), "no description: starts with the product");
		final List<String> tags = SnowflakePublicationSql.tagStatements("DB", "SCHEMA", described);
		check(tags.size() == 5 + 1, "5 CREATE TAG (product, version, source, owner, domain) + 1 SET TAG: " + tags.size());
		check(tags.get(0).equals("CREATE TAG IF NOT EXISTS \"DB\".\"SCHEMA\".\"EBX_PRODUCT\""), "create tag: " + tags.get(0));
		final String setTag = tags.get(tags.size() - 1);
		check(setTag.startsWith("ALTER TABLE \"DB\".\"SCHEMA\".\"COMPANY_360\" SET TAG "), "set tag target: " + setTag);
		check(setTag.contains("\"EBX_PRODUCT\" = 'COMPANY_360'") && setTag.contains("\"EBX_CONTRACT_VERSION\" = '2'")
			&& setTag.contains("\"EBX_SOURCE_TABLE\" = 'Companies/Companies/root/Companies'") && setTag.contains("\"EBX_OWNER\" = 'Data Office'")
			&& setTag.contains("\"EBX_DOMAIN\" = 'Customer'"), "tag values: " + setTag);
		final List<String> bareTags = SnowflakePublicationSql.tagStatements("DB", "SCHEMA", bare);
		check(bareTags.size() == 3 + 1 && !bareTags.get(bareTags.size() - 1).contains("EBX_OWNER") && !bareTags.get(bareTags.size() - 1).contains("EBX_DOMAIN"),
			"owner and domain tags only when known: " + bareTags);

		// 2026-09-30: the meaning of a column goes first in its comment, and into column tags
		final com.on.ebx.connect.spi.TablePublisher.ColumnContext meaning = new com.on.ebx.connect.spi.TablePublisher.ColumnContext("Customer name",
			"Name shown on invoices.", "CUSTOMER_NAME", "Customer name", "Name under which a customer is known.", java.util.Arrays.asList("Client name", "Buyer's name"),
			"CONFIDENTIAL", java.util.Arrays.asList("PERSON_NAME", "PERSONAL_DATA"));
		final ColumnSpec named = new ColumnSpec("NAME", Kind.STRING, Integer.valueOf(120), false, "./name", "ON EBX field ./name", meaning);
		final String namedComment = SnowflakePublicationSql.columnComment(named);
		check(namedComment.equals("Customer name: Name under which a customer is known. Name shown on invoices. | Also called: Client name, Buyer's name"
			+ " | Glossary term: Customer name (CUSTOMER_NAME) | ON EBX field ./name"), "business comment: " + namedComment);
		check(SnowflakePublicationSql.columnComment(annotated.get(0)).equals("ON EBX field ./Id (business key)"), "no meaning: technical comment only");
		final ColumnSpec labelOnly = new ColumnSpec("CITY", Kind.STRING, null, false, "./city", "ON EBX field ./city",
			new com.on.ebx.connect.spi.TablePublisher.ColumnContext("City", null, null, null, null, null, null, null));
		check(SnowflakePublicationSql.columnComment(labelOnly).equals("City | ON EBX field ./city"), "label only: " + SnowflakePublicationSql.columnComment(labelOnly));
		check(new ColumnSpec("X", Kind.STRING, null, false, "", null, new com.on.ebx.connect.spi.TablePublisher.ColumnContext(null, " ", null, null, null, null, null, null))
			.getContext() == null, "an empty meaning is no meaning");
		final List<ColumnSpec> withMeaning = new ArrayList<ColumnSpec>(annotated);
		withMeaning.add(named);
		final PublicationSpec meaningful = new PublicationSpec("DB.SCHEMA", "R1", "P", "1", "2026-01-01T00:00:00",
			java.util.Collections.singletonList(new TableSpec("CUSTOMER", withMeaning, null, "Customers/Customers/root/Customer", 7)));
		final List<String> meaningfulComments = SnowflakePublicationSql.commentStatements("DB", "SCHEMA", meaningful);
		check(meaningfulComments.get(meaningfulComments.size() - 1).contains("COMMENT 'Customer name: Name under which a customer is known.")
			&& meaningfulComments.get(meaningfulComments.size() - 1).contains("Buyer''s name"), "business comment statement, quote doubled: " + meaningfulComments);
		final List<String> meaningfulTags = SnowflakePublicationSql.tagStatements("DB", "SCHEMA", meaningful);
		check(meaningfulTags.contains("CREATE TAG IF NOT EXISTS \"DB\".\"SCHEMA\".\"EBX_GLOSSARY_TERM\"")
			&& meaningfulTags.contains("CREATE TAG IF NOT EXISTS \"DB\".\"SCHEMA\".\"EBX_SENSITIVITY\"")
			&& meaningfulTags.contains("CREATE TAG IF NOT EXISTS \"DB\".\"SCHEMA\".\"EBX_CLASSIFICATION\""), "column tag objects: " + meaningfulTags);
		check(meaningfulTags.get(meaningfulTags.size() - 1).equals("ALTER TABLE \"DB\".\"SCHEMA\".\"CUSTOMER\" ALTER COLUMN \"NAME\" SET TAG "
			+ "\"DB\".\"SCHEMA\".\"EBX_GLOSSARY_TERM\" = 'CUSTOMER_NAME', \"DB\".\"SCHEMA\".\"EBX_SENSITIVITY\" = 'CONFIDENTIAL', "
			+ "\"DB\".\"SCHEMA\".\"EBX_CLASSIFICATION\" = 'PERSON_NAME, PERSONAL_DATA'"), "column set tag: " + meaningfulTags.get(meaningfulTags.size() - 1));
		check(SnowflakePublicationSql.tagStatements("DB", "SCHEMA", described).size() == 6, "no column meaning: no column tag statement");

		// 2026-10-01: semantic view, one dimension per business column with its glossary synonyms
		check("EBX_COMPANY_360_SEMANTIC".equals(SnowflakePublicationSql.semanticViewName("Company 360")), "semantic view name");
		check(SnowflakePublicationSql.columnSynonyms(named).equals(java.util.Arrays.asList("Client name", "Buyer's name", "Customer name")),
			"synonyms + business name, term label deduplicated: " + SnowflakePublicationSql.columnSynonyms(named));
		check(SnowflakePublicationSql.columnSynonyms(annotated.get(0)).isEmpty(), "no meaning: no synonym");
		check(SnowflakePublicationSql.columnSynonyms(new ColumnSpec("LEGAL_FORM", Kind.STRING, null, false, "./legalForm", null,
			new com.on.ebx.connect.spi.TablePublisher.ColumnContext("Legal form", null, null, null, null, null, null, null))).isEmpty(),
			"the column name spelled with spaces is no synonym");
		final String view = SnowflakePublicationSql.semanticViewSql("DB", "SCHEMA", meaningful);
		check(view.startsWith("CREATE OR REPLACE SEMANTIC VIEW \"DB\".\"SCHEMA\".\"EBX_P_SEMANTIC\""), "view target: " + view);
		check(view.contains("\"CUSTOMER\" AS \"DB\".\"SCHEMA\".\"CUSTOMER\" PRIMARY KEY (\"ID\") COMMENT = 'Data product P"), "logical table with its key: " + view);
		check(view.contains("COMMENT = 'Customer name: Name under which a customer is known. Name shown on invoices.'"), "dimension comment is the meaning only: " + view);
		check(!view.contains("Also called") && !view.contains("Glossary term:"), "no synonym or term repeated in a dimension comment: " + view);
		check(view.contains("\"CUSTOMER\".\"NAME\" AS \"CUSTOMER\".\"NAME\" WITH SYNONYMS = ('Client name', 'Buyer''s name', 'Customer name') COMMENT = 'Customer name: "),
			"dimension with synonyms and comment: " + view);
		check(view.contains("\"CUSTOMER\".\"ID\" AS \"CUSTOMER\".\"ID\" COMMENT = 'ON EBX field ./Id (business key)'"), "dimension without synonym: " + view);
		check(!view.contains("\"_RUN_ID\""), "technical columns are not dimensions: " + view);
		check(view.contains("\"CUSTOMER\".\"RECORD_COUNT\" AS COUNT(\"CUSTOMER\".\"_EBX_KEY\")"), "record count metric: " + view);
		final List<ColumnSpec> other = new ArrayList<ColumnSpec>();
		other.add(new ColumnSpec("NAME", Kind.STRING, null, false, "./name", "ON EBX field ./name"));
		other.add(new ColumnSpec("DETAILS", Kind.JSON, null, false, "./details[]", "Nested JSON"));
		final List<TableSpec> two = new ArrayList<TableSpec>();
		two.add(new TableSpec("CUSTOMER", withMeaning, null, "Customers/Customers/root/Customer", 7));
		two.add(new TableSpec("SUPPLIER", other, null, "Suppliers/Suppliers/root/Supplier", 8));
		final String twoView = SnowflakePublicationSql.semanticViewSql("DB", "SCHEMA",
			new PublicationSpec("DB.SCHEMA", "R1", "P", "1", "2026-01-01T00:00:00", two));
		check(twoView.contains("\"CUSTOMER\".\"CUSTOMER_NAME\" AS \"CUSTOMER\".\"NAME\"") && twoView.contains("\"SUPPLIER\".\"SUPPLIER_NAME\" AS \"SUPPLIER\".\"NAME\""),
			"a name shared by two tables is prefixed: " + twoView);
		check(!twoView.contains("DETAILS"), "JSON columns are not dimensions: " + twoView);
		check(twoView.contains("\"SUPPLIER_RECORD_COUNT\"") && twoView.contains("\"CUSTOMER_RECORD_COUNT\""), "one metric per table, prefixed: " + twoView);
		final List<ColumnSpec> onlyTechnical = new ArrayList<ColumnSpec>();
		onlyTechnical.add(new ColumnSpec("_EBX_KEY", Kind.STRING, Integer.valueOf(255), true, "", "technical"));
		check(SnowflakePublicationSql.semanticViewSql("DB", "SCHEMA", new PublicationSpec("DB.SCHEMA", "R1", "P", "1", "2026-01-01T00:00:00",
			java.util.Collections.singletonList(new TableSpec("T", onlyTechnical, null, "x", 1)))) == null, "no business column: no view");

		// listing (Internal Marketplace)
		check("EBX_COMPANY_360".equals(SnowflakePublicationSql.listingName("Company 360")), "listing name: " + SnowflakePublicationSql.listingName("Company 360"));
		check("EBX_A_B_C".equals(SnowflakePublicationSql.listingName("a-b.c")), "listing name replaces non-identifier characters");
		check("EBX_COMPANY_360_SHARE".equals(SnowflakePublicationSql.shareName("COMPANY_360")), "share name");
		check("PUBLIC.AWS_EU_WEST_3".equals(SnowflakePublicationSql.listingRegion("aws_eu_west_3")), "bare region gets the PUBLIC. prefix");
		check("PUBLIC.AWS_EU_WEST_3".equals(SnowflakePublicationSql.listingRegion("PUBLIC.AWS_EU_WEST_3")), "qualified region kept");
		final PublicationSpec forListing = new PublicationSpec("DB.SCHEMA", "R1", "COMPANY_360", "2", "2026-01-01T00:00:00",
			java.util.Collections.singletonList(annotatedTable), "Golden \"company\" view; $$ risky", "Data Office", "Customer", "Company 360");
		check("Company 360".equals(forListing.getTitle()), "title kept");
		check("P".equals(bare.getTitle()), "title falls back to the product code");
		final List<String> shareSql = SnowflakePublicationSql.shareStatements("DB", "SCHEMA", forListing);
		check(shareSql.size() == 3 + 1, "share: create, usage database, usage schema, one select per table: " + shareSql.size());
		check(shareSql.get(0).equals("CREATE SHARE IF NOT EXISTS \"EBX_COMPANY_360_SHARE\" SECURE_OBJECTS_ONLY = FALSE"), "create share: " + shareSql.get(0));
		check(shareSql.get(1).equals("GRANT USAGE ON DATABASE \"DB\" TO SHARE \"EBX_COMPANY_360_SHARE\""), "usage database: " + shareSql.get(1));
		check(shareSql.get(2).equals("GRANT USAGE ON SCHEMA \"DB\".\"SCHEMA\" TO SHARE \"EBX_COMPANY_360_SHARE\""), "usage schema: " + shareSql.get(2));
		check(shareSql.get(3).equals("GRANT SELECT ON TABLE \"DB\".\"SCHEMA\".\"COMPANY_360\" TO SHARE \"EBX_COMPANY_360_SHARE\""), "select table: " + shareSql.get(3));
		final String manifest = SnowflakePublicationSql.listingManifest(forListing, "me@example.com", "AWS_EU_WEST_3");
		check(manifest.contains("title: \"Company 360\"\n"), "manifest title: " + manifest);
		check(manifest.contains("organization_profile: INTERNAL\n") && manifest.contains("- all_accounts: true\n"), "manifest internal profile and targets: " + manifest);
		check(manifest.contains("support_contact: \"me@example.com\"\n"), "manifest contact: " + manifest);
		check(manifest.contains("- name: \"PUBLIC.AWS_EU_WEST_3\"\n"), "manifest region: " + manifest);
		check(manifest.contains("Golden \\\"company\\\" view; $ $ risky"), "description escaped and $$ broken up: " + manifest);
		check(!manifest.contains("$$"), "the manifest never contains $$");
		check(SnowflakePublicationSql.listingManifest(bare, "me@example.com", "R").contains("description: \"Data product P (contract version 1) published by ON EBX."), "fallback description");
		final String createListing = SnowflakePublicationSql.createListingSql("COMPANY_360", manifest);
		check(createListing.startsWith("CREATE ORGANIZATION LISTING \"EBX_COMPANY_360\" SHARE \"EBX_COMPANY_360_SHARE\" AS $$\n") && createListing.endsWith("$$"), "create listing: " + createListing);
		final String alterListing = SnowflakePublicationSql.alterListingSql("COMPANY_360", manifest);
		check(alterListing.startsWith("ALTER LISTING \"EBX_COMPANY_360\" AS $$\n") && alterListing.endsWith("$$"), "alter listing: " + alterListing);
		check(SnowflakePublicationSql.showListingSql("COMPANY_360").equals("SHOW LISTINGS LIKE 'EBX_COMPANY_360'"), "show listing");

		System.out.println("PASS: Snowflake publication SQL (DDL, FLATTEN insert, atomic multi-statement commit, type mapping, identifier quoting)");
	}

	private static ColumnSpec column(final String pName, final Kind pKind, final Integer pMaxLength)
	{
		return new ColumnSpec(pName, pKind, pMaxLength, false, "", null);
	}

	private static TableSpec table()
	{
		return table("COMPANY_360");
	}

	private static TableSpec table(final String pName)
	{
		final List<ColumnSpec> columns = new ArrayList<ColumnSpec>();
		columns.add(new ColumnSpec("ID", Kind.INTEGER, null, true, "./Identification/Id", null));
		columns.add(new ColumnSpec("NAME", Kind.STRING, Integer.valueOf(120), false, "./Identification/Name", null));
		columns.add(new ColumnSpec("ADDRESSES", Kind.JSON, null, false, "addresses[]", null));
		return new TableSpec(pName, columns, "a 'quoted' comment", "Companies/Companies/root/Companies", 210);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
