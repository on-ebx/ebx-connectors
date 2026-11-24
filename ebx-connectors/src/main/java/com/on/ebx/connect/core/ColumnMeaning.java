package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.connect.spi.TablePublisher.ColumnContext;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/**
 * What a published column means (2026-09-30): the ON EBX model's own label and description of the
 * source field, completed by the data catalog when it is installed and has scanned the table (the
 * column's business name, description, sensitivity and tags, and its glossary term: code, label,
 * definition, synonyms). The catalog is read as data, like every other catalog access of this module;
 * without it, only the model's text is published. One instance per publication: catalog lookups are
 * cached per table.
 */
final class ColumnMeaning
{
	private final Repository repository;
	private Adaptation catalog;
	private boolean catalogLooked;
	/** Catalog qualified name of an EBX table → its catalog columns by name (empty when the catalog does not know the table). */
	private final Map<String, Map<String, Adaptation>> columns = new HashMap<String, Map<String, Adaptation>>();

	ColumnMeaning(final Repository pRepository)
	{
		this.repository = pRepository;
	}

	ColumnContext of(final SchemaNode pNode, final String pSourcePath, final String pEbxTable)
	{
		String modelLabel = null, modelDescription = null;
		if (pNode != null)
		{
			modelLabel = pNode.getLabel(Locale.ENGLISH);
			modelDescription = pNode.getDescription(Locale.ENGLISH);
			// EBX answers the field name when the model gives no label: that is no business name
			if (modelLabel != null && modelLabel.equals(pNode.getPathInAdaptation().getLastStep().format())) modelLabel = null;
		}
		final Adaptation column = this.catalogColumn(pEbxTable, pSourcePath);
		if (column == null)
		{
			return ColumnMeaning.merge(modelLabel, modelDescription, null, null, null, null, null, null, null, null);
		}
		String termCode = column.getString(Path.parse("./glossaryTerm"));
		Adaptation term = null;
		if (termCode != null)
		{
			term = this.catalog.getTable(Path.parse("/root/GlossaryTerm")).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(termCode));
			if (term == null) termCode = null;
		}
		return ColumnMeaning.merge(modelLabel, modelDescription, column.getString(Path.parse("./businessName")), column.getString(Path.parse("./description")),
			termCode, term == null ? null : term.getString(Path.parse("./label")), term == null ? null : term.getString(Path.parse("./definition")),
			term == null ? null : ColumnMeaning.strings(term.get(Path.parse("./synonyms"))), column.getString(Path.parse("./sensitivity")),
			ColumnMeaning.strings(column.get(Path.parse("./tags"))));
	}

	/**
	 * The catalog wins over the model for the business name and the description (a steward wrote them);
	 * without a business name, the glossary term's label names the column. Pure function, for the tests.
	 */
	static ColumnContext merge(final String pModelLabel, final String pModelDescription, final String pBusinessName, final String pDescription,
		final String pTermCode, final String pTermLabel, final String pDefinition, final List<String> pSynonyms, final String pSensitivity, final List<String> pTags)
	{
		final String name = ColumnMeaning.first(pBusinessName, pModelLabel, pTermLabel);
		final String description = ColumnMeaning.first(pDescription, pModelDescription);
		return new ColumnContext(name, description, pTermCode, pTermLabel, pDefinition, pSynonyms, pSensitivity, pTags);
	}

	static String first(final String... pValues)
	{
		for (final String v : pValues)
		{
			if (v != null && !v.trim().isEmpty()) return v.trim();
		}
		return null;
	}

	static List<String> strings(final Object pList)
	{
		final List<String> out = new ArrayList<String>();
		if (pList instanceof List)
		{
			for (final Object o : (List<?>) pList)
			{
				if (o != null) out.add(String.valueOf(o));
			}
		}
		return out;
	}

	/** The catalog column of an EBX table's field: its full relative path first, then its last segment when that is unique. */
	Adaptation catalogColumn(final String pEbxTable, final String pSourcePath)
	{
		if (pEbxTable == null || pSourcePath == null)
		{
			return null;
		}
		Map<String, Adaptation> byName = this.columns.get(pEbxTable);
		if (byName == null)
		{
			byName = this.load(pEbxTable);
			this.columns.put(pEbxTable, byName);
		}
		if (byName.isEmpty())
		{
			return null;
		}
		final String full = ColumnMeaning.fullPath(pSourcePath);
		if (full != null && byName.containsKey(full))
		{
			return byName.get(full);
		}
		final String last = full == null ? null : full.substring(full.lastIndexOf('/') + 1);
		Adaptation match = null;
		for (final Map.Entry<String, Adaptation> e : byName.entrySet())
		{
			if (last != null && (e.getKey().equals(last) || e.getKey().endsWith("/" + last)))
			{
				if (match != null) return null; // not unique
				match = e.getValue();
			}
		}
		return match;
	}

	Map<String, Adaptation> load(final String pEbxTable)
	{
		final Map<String, Adaptation> out = new HashMap<String, Adaptation>();
		if (!this.catalogLooked)
		{
			this.catalogLooked = true;
			try
			{
				this.catalog = CatalogDetection.local(this.repository).adaptation;
			}
			catch (final RuntimeException ex)
			{
				this.catalog = null;
			}
		}
		if (this.catalog == null)
		{
			return out;
		}
		final Request assets = this.catalog.getTable(Path.parse("/root/Asset")).createRequest();
		assets.setXPathFilter("./qualifiedName=" + Xpath.quote(pEbxTable));
		final String assetId;
		final RequestResult found = assets.execute();
		try
		{
			final Adaptation asset = found.nextAdaptation();
			if (asset == null)
			{
				return out;
			}
			assetId = asset.getOccurrencePrimaryKey().format();
		}
		finally
		{
			found.close();
		}
		final Request request = this.catalog.getTable(Path.parse("/root/Column")).createRequest();
		request.setXPathFilter("./asset=" + Xpath.quote(assetId));
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation c; (c = rows.nextAdaptation()) != null;)
			{
				final String name = c.getString(Path.parse("./name"));
				if (name != null) out.put(name, c);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** {@code ./Identification/Id} → {@code Identification/Id}, how the catalog names an EBX table's columns (a collection's {@code []} dropped). */
	static String fullPath(final String pSourcePath)
	{
		String p = pSourcePath.trim();
		if (p.startsWith("./")) p = p.substring(2);
		else if (p.startsWith("/")) p = p.substring(1);
		p = p.replaceAll("\\[\\]$", "");
		return p.isEmpty() ? null : p;
	}
}
