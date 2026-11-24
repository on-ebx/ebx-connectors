package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.service.Session;

/**
 * How an import feed finds, for each row, the record it will update, built once per run (audit C-10).
 * Three ways, cheapest first, the last one being what the module always did:
 * <ol>
 * <li><b>The whole primary key</b> ({@code ./id='${x}'} for every key field, strings, ints, integers or decimals):
 * {@code lookupAdaptationByPrimaryKey}, no query.</li>
 * <li><b>A business key</b> ({@code ./sourceId='${customerId}'} on a table keyed otherwise, the pattern the module
 * recommends): an in-memory map business key to record key ({@link BusinessKeyIndex}), built by one streaming read of
 * the table once the run has asked a few times, bounded by {@code -Debx.connectors.feed.keyIndex.maxRows} (default
 * 2000000, 0 turns it off). Every XPath predicate on a field that is not the primary key scans the table, so the
 * alternative is one scan per row (CLAUDE.md sections 5 and 15).</li>
 * <li><b>The XPath predicate</b>, for any other shape and whenever the two above cannot answer exactly.</li>
 * </ol>
 * The index answers what the predicate would: a record is trusted only after it has been read again and still holds
 * the key (a record changed by someone else since the index was built is then found by the XPath instead), a key held
 * by two records is left to EBX (it returns the first in its own order), and the records the run writes are added as
 * they are written. It is a snapshot of the table taken during the run: a record created by someone else after that
 * is not seen until the next run.
 */
final class FeedKeyLookup
{
	/** {@code -Debx.connectors.feed.keyIndex.maxRows}: most records the business-key index may hold. */
	static final String KEY_INDEX_MAX_ROWS_PROPERTY = "ebx.connectors.feed.keyIndex.maxRows";
	static final int DEFAULT_KEY_INDEX_MAX_ROWS = 2000000;
	/** Lookups answered by XPath before the table is read for the index: a run of a few rows does not pay for it. */
	static final int KEY_INDEX_MIN_LOOKUPS = 10;

	private static final Pattern INTEGER_SHAPE = Pattern.compile("-?[0-9]+");
	private static final Pattern DECIMAL_SHAPE = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

	private final KeyPredicatePrimaryKey primaryKey;
	private final BusinessKeyPredicate business;
	private final List<Path> businessPaths;
	private final int maxRows;
	private final String label;

	private BusinessKeyIndex<String> index;
	private boolean indexUnavailable;
	private int lookups;

	private FeedKeyLookup(final KeyPredicatePrimaryKey pPrimaryKey, final BusinessKeyPredicate pBusiness, final List<Path> pBusinessPaths,
		final int pMaxRows, final String pLabel)
	{
		this.primaryKey = pPrimaryKey;
		this.business = pBusiness;
		this.businessPaths = pBusinessPaths;
		this.maxRows = pMaxRows;
		this.label = pLabel;
	}

	/** @return the lookup of this feed, or null when its predicate is neither a primary key nor a business key of plain fields (EBX is asked, as always) */
	static FeedKeyLookup of(final MappingDefinition pFeed, final AdaptationTable pTable)
	{
		final KeyPredicatePrimaryKey primaryKey = FeedKeyLookup.primaryKeyOf(pFeed, pTable);
		if (primaryKey != null)
		{
			return new FeedKeyLookup(primaryKey, null, null, 0, null);
		}
		final int maxRows = Integer.getInteger(FeedKeyLookup.KEY_INDEX_MAX_ROWS_PROPERTY, FeedKeyLookup.DEFAULT_KEY_INDEX_MAX_ROWS).intValue();
		if (maxRows <= 0)
		{
			return null;
		}
		final BusinessKeyPredicate business = BusinessKeyPredicate.parse(pFeed.getKeyPredicate());
		if (business == null)
		{
			return null;
		}
		final SchemaNode tableNode = pTable.getTableOccurrenceRootNode();
		final List<Path> paths = new ArrayList<Path>(business.getFields().size());
		for (final String field : business.getFields())
		{
			final Path path = Path.parse("./" + field);
			if (!FeedKeyLookup.isPlainString(tableNode.getNode(path)))
			{
				return null;
			}
			paths.add(path);
		}
		return new FeedKeyLookup(null, business, paths, maxRows, "mapping " + pFeed.getId());
	}

	/**
	 * A top-level field whose XPath equality is the plain equality of two strings: a string, stored (not computed),
	 * not a foreign key, one value per record.
	 */
	static boolean isPlainString(final SchemaNode pNode)
	{
		return pNode != null && FeedKeyLookup.isStringTyped(pNode) && pNode.getFacetOnTableReference() == null
			&& pNode.isTerminalValue() && !pNode.isTerminalValueComputed() && pNode.getMaxOccurs() == 1;
	}

	/**
	 * True for an {@code xs:string} field, named or not. A field declared with its own restriction (a maximum length,
	 * an enumeration) has an anonymous type, for which {@link SchemaNode#getXsTypeName()} answers null (its Javadoc
	 * says so): it is recognized by the Java class of its values instead. Named types other than {@code xs:string}
	 * ({@code osd:text}, {@code osd:email}...) keep the XPath, as before.
	 */
	static boolean isStringTyped(final SchemaNode pNode)
	{
		final SchemaTypeName type = pNode.getXsTypeName();
		return type == null ? String.class.equals(pNode.getOccurrencesJavaClass()) : SchemaTypeName.XS_STRING.equals(type);
	}

	/**
	 * The numeric kind of a key field: {@code "int"} for {@code xs:int} / {@code xs:integer}, {@code "decimal"} for
	 * {@code xs:decimal}, null for anything else. An anonymous restriction of one of them (null type name) is
	 * recognized by the Java class of its values.
	 */
	static String numberKindOf(final SchemaNode pNode)
	{
		final SchemaTypeName type = pNode.getXsTypeName();
		if (type == null)
		{
			final Class<?> javaClass = pNode.getOccurrencesJavaClass();
			return Integer.class.equals(javaClass) ? "int" : java.math.BigDecimal.class.equals(javaClass) ? "decimal" : null;
		}
		return SchemaTypeName.XS_DECIMAL.equals(type) ? "decimal"
			: SchemaTypeName.XS_INT.equals(type) || SchemaTypeName.XS_INTEGER.equals(type) ? "int" : null;
	}

	/**
	 * Recognizes the common case: the feed's {@code keyPredicate} addresses the table's whole primary key with simple
	 * equality clauses on plain {@code xs:string}, {@code xs:int}, {@code xs:integer} or {@code xs:decimal},
	 * non-foreign-key fields. Null (always the XPath) when the table has no primary key, a key field is of another
	 * type (a boolean needs {@link MappingEngine#predicate}'s own quoting; a foreign key is itself a record) or the
	 * template does not address the key exactly.
	 */
	private static KeyPredicatePrimaryKey primaryKeyOf(final MappingDefinition pFeed, final AdaptationTable pTable)
	{
		final Path[] pkPaths = pTable.getPrimaryKeySpec();
		if (pkPaths == null || pkPaths.length == 0)
		{
			return null;
		}
		final SchemaNode tableNode = pTable.getTableOccurrenceRootNode();
		final List<String> pkFields = new ArrayList<String>(pkPaths.length);
		final List<KeyPredicatePrimaryKey.NumberParser> parsers = new ArrayList<KeyPredicatePrimaryKey.NumberParser>(pkPaths.length);
		for (final Path p : pkPaths)
		{
			final String name = p.format().replaceFirst("^/+", "");
			final SchemaNode node = tableNode.getNode(Path.parse("./" + name));
			if (node == null || node.getFacetOnTableReference() != null)
			{
				return null;
			}
			if (FeedKeyLookup.isStringTyped(node))
			{
				parsers.add(null);
			}
			else
			{
				final KeyPredicatePrimaryKey.NumberParser parser = FeedKeyLookup.numberParserOf(node);
				if (parser == null)
				{
					return null;
				}
				parsers.add(parser);
			}
			pkFields.add(name);
		}
		return KeyPredicatePrimaryKey.forTemplate(pFeed.getKeyPredicate(), pkFields, parsers);
	}

	private static KeyPredicatePrimaryKey.NumberParser numberParserOf(final SchemaNode pNode)
	{
		final String kind = FeedKeyLookup.numberKindOf(pNode);
		if (kind == null)
		{
			return null;
		}
		final boolean decimal = "decimal".equals(kind);
		final Pattern shape = decimal ? FeedKeyLookup.DECIMAL_SHAPE : FeedKeyLookup.INTEGER_SHAPE;
		return new KeyPredicatePrimaryKey.NumberParser()
		{
			@Override
			public Object parse(final String pText)
			{
				if (pText == null || !shape.matcher(pText).matches())
				{
					return null;
				}
				try
				{
					// the Java type EBX keys this very field by, whatever the XSD type is
					return pNode.parseXsString(pText);
				}
				catch (final Exception ex)
				{
					return null;
				}
			}

			@Override
			public boolean missIsFinal()
			{
				return !decimal;
			}
		};
	}

	/**
	 * The record the feed row addresses, or null when there is none (or the predicate is empty: the row is then always
	 * new). Falls back to the XPath predicate whenever a faster way cannot answer exactly: a placeholder is missing
	 * from the item (an incomplete key is never guessed), a number is not a number of the key's type, a decimal key
	 * finds nothing, the business key is held twice or was found stale.
	 */
	Adaptation locate(final Session pSession, final AdaptationTable pTable, final JsonNode pItem, final String pPredicate) throws ConnectorException
	{
		if (pPredicate.isEmpty())
		{
			return null;
		}
		if (this.primaryKey != null)
		{
			final List<String> placeholders = this.primaryKey.getPlaceholdersInKeyOrder();
			final Object[] values = new Object[placeholders.size()];
			boolean complete = true;
			for (int i = 0; i < values.length; i++)
			{
				final JsonNode node = Json.at(pItem, placeholders.get(i));
				final String text = node == null || node.isNull() ? null : (node.isValueNode() ? node.asText() : Json.write(node));
				final Object value = text == null ? null : this.primaryKey.keyValue(i, text);
				if (value == null)
				{
					complete = false;
					break;
				}
				values[i] = value;
			}
			if (complete)
			{
				final Adaptation found = pTable.lookupAdaptationByPrimaryKey(pTable.computePrimaryKey(values));
				if (found != null || this.primaryKey.missIsFinal())
				{
					return found;
				}
			}
		}
		else if (this.business != null && !this.indexUnavailable)
		{
			return this.locateByBusinessKey(pSession, pTable, pItem, pPredicate);
		}
		return FeedLookups.locate(pSession, pTable, pPredicate);
	}

	private Adaptation locateByBusinessKey(final Session pSession, final AdaptationTable pTable, final JsonNode pItem, final String pPredicate)
		throws ConnectorException
	{
		final List<String> values = new ArrayList<String>(this.business.getPlaceholders().size());
		for (final String expression : this.business.getPlaceholders())
		{
			// the very text resolvePredicate puts between the quotes of the XPath predicate
			values.add(FeedLookups.placeholderText(pItem, expression));
		}
		this.lookups++;
		if (this.index == null && this.lookups > FeedKeyLookup.KEY_INDEX_MIN_LOOKUPS)
		{
			this.buildIndex(pSession, pTable);
		}
		if (this.index != null)
		{
			final BusinessKeyIndex.Match<String> match = this.index.find(values);
			if (match.getKind() == BusinessKeyIndex.Kind.NONE)
			{
				return null;
			}
			if (match.getKind() == BusinessKeyIndex.Kind.ONE)
			{
				final Adaptation record = pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(match.getRecord()));
				if (record != null && values.equals(this.valuesOf(record)))
				{
					return record;
				}
				// the record was changed or deleted since the index was read: it no longer answers for this key
				this.index.forget(values);
			}
		}
		final Adaptation found = FeedLookups.locate(pSession, pTable, pPredicate);
		if (found != null)
		{
			this.remember(found);
		}
		return found;
	}

	/** One streaming read of the table, as the session sees it, as the XPath predicate would. Never fails the run: without the index EBX is asked. */
	private void buildIndex(final Session pSession, final AdaptationTable pTable)
	{
		final long start = System.currentTimeMillis();
		final BusinessKeyIndex<String> built = new BusinessKeyIndex<String>(this.maxRows);
		RequestResult rows = null;
		try
		{
			final Request request = pTable.createRequest();
			request.setSession(pSession);
			rows = request.execute();
			if (rows.isSizeGreaterOrEqual(this.maxRows + 1))
			{
				this.indexUnavailable = true;
				FeedLookups.log().warn("[connectors] " + this.label + ": " + pTable.getTablePath().format() + " holds more than " + this.maxRows
					+ " records (-D" + FeedKeyLookup.KEY_INDEX_MAX_ROWS_PROPERTY + "): each row is looked up with the XPath predicate, which scans the table");
				return;
			}
			for (Adaptation row = rows.nextAdaptation(); row != null && !built.isOverflowed(); row = rows.nextAdaptation())
			{
				built.add(this.valuesOf(row), row.getOccurrencePrimaryKey().format());
			}
		}
		catch (final RuntimeException ex)
		{
			this.indexUnavailable = true;
			FeedLookups.log().warn("[connectors] " + this.label + ": the key index of " + pTable.getTablePath().format() + " could not be built ("
				+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()) + "): each row is looked up with the XPath predicate");
			return;
		}
		finally
		{
			if (rows != null)
			{
				rows.close();
			}
		}
		if (built.isOverflowed())
		{
			this.indexUnavailable = true;
			return;
		}
		this.index = built;
		FeedLookups.log().info("[connectors] " + this.label + ": key index of " + pTable.getTablePath().format() + " built over " + built.size()
			+ " business key(s) in " + (System.currentTimeMillis() - start) + " ms");
	}

	private List<String> valuesOf(final Adaptation pRecord)
	{
		final List<String> values = new ArrayList<String>(this.businessPaths.size());
		for (final Path path : this.businessPaths)
		{
			values.add(pRecord.getString(path));
		}
		return values;
	}

	/**
	 * Tells the index about a record the run has just written (created, or changed): it is read again and added, so
	 * that the next row with its key finds it, as the XPath predicate would. Does nothing before the index exists
	 * (the build reads the table as it then is). An index that cannot follow is dropped: EBX is asked from then on.
	 */
	void wrote(final AdaptationTable pTable, final Adaptation pRecord)
	{
		if (this.index == null || pRecord == null)
		{
			return;
		}
		try
		{
			final Adaptation fresh = pTable.lookupAdaptationByPrimaryKey(pRecord.getOccurrencePrimaryKey());
			if (fresh != null)
			{
				this.remember(fresh);
			}
		}
		catch (final RuntimeException ex)
		{
			this.index = null;
			this.indexUnavailable = true;
		}
	}

	/** Adds a record to the index; an index that outgrows its bound is dropped and EBX is asked from then on. */
	private void remember(final Adaptation pRecord)
	{
		if (this.index == null)
		{
			return;
		}
		this.index.add(this.valuesOf(pRecord), pRecord.getOccurrencePrimaryKey().format());
		if (this.index.isOverflowed())
		{
			this.index = null;
			this.indexUnavailable = true;
		}
	}
}
