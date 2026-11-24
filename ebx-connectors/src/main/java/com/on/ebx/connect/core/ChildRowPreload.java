package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.Session;

/**
 * The existing child rows of the parents of one batch segment, read once before the write procedure (audit C-11), so
 * that {@link MappingEngine#applyChildren} matches each child element in memory instead of asking EBX for it inside
 * the procedure. Without it every element is one XPath scan of the child table (the key and link fields are not
 * indexed), taken in a procedure that writes the same table: the pattern that forces an index refresh at every
 * lookup (CLAUDE.md sections 5 and 15). Here a segment costs one read of the child table per 200 parents.
 * <p>
 * Only what can be answered exactly is answered, and for anything else {@link #child} says null and the mapping
 * is applied as it always was:
 * <ul>
 * <li>a direct child mapping of the feed (not a nested one) that owns its table: no other mapping of the feed's tree
 * writes it, so the rows this code made are the only ones the procedure can add;</li>
 * <li>link field and key fields that are plain stored top-level strings (the link may also be a foreign key), no
 * {@code childFilter}: the XPath it replaces is then a plain equality on strings;</li>
 * <li>a parent that was read (an existing record of the segment), or a parent just created in a table keyed by one
 * auto-increment field - its key was never used, no row can be linked to it. A new parent with another kind of key
 * is asked to EBX: a row left behind by a record deleted earlier could carry the same key.</li>
 * </ul>
 * Only the batched write path (batch size above 1) uses it. {@code -Debx.connectors.feed.childPreload=false} turns
 * it off.
 */
final class ChildRowPreload
{
	/** {@code -Debx.connectors.feed.childPreload}: {@code false} applies sub-object mappings with one XPath per element, as before. */
	static final String PROPERTY = "ebx.connectors.feed.childPreload";
	/** Parents per read: the XPath is one equality per parent, joined by {@code or}. */
	static final int PARENTS_PER_READ = 200;

	/** One child mapping's rows and the paths to read them by. */
	static final class Child
	{
		private final Path linkPath;
		private final List<Path> keyPaths;
		private final ChildRowGroups<Adaptation> groups = new ChildRowGroups<Adaptation>();
		private final boolean newParentsHaveNoRows;

		private Child(final Path pLinkPath, final List<Path> pKeyPaths, final boolean pNewParentsHaveNoRows)
		{
			this.linkPath = pLinkPath;
			this.keyPaths = pKeyPaths;
			this.newParentsHaveNoRows = pNewParentsHaveNoRows;
		}

		/**
		 * The groups to match in, once this parent's rows are known: read before the write, or, for a parent created
		 * by it, safe to take as having none. Null when the parent must be asked to EBX.
		 */
		ChildRowGroups<Adaptation> groupsFor(final String pParentKey, final boolean pParentIsNew)
		{
			if (pParentIsNew)
			{
				if (!this.newParentsHaveNoRows)
				{
					return null;
				}
				this.groups.declareNewParent(pParentKey);
				return this.groups;
			}
			return this.groups.knows(pParentKey) ? this.groups : null;
		}

		List<String> keysOf(final Adaptation pRow)
		{
			final List<String> keys = new ArrayList<String>(this.keyPaths.size());
			for (final Path path : this.keyPaths)
			{
				keys.add(pRow.getString(path));
			}
			return keys;
		}

		/** Tells the groups what the procedure just wrote ({@code pOld} is the row a change replaces, null for a creation). */
		void wrote(final Adaptation pOld, final String pOldParent, final Adaptation pRow)
		{
			this.groups.wrote(pOld == null ? null : pOld.getAdaptationName(), pOldParent, pRow.getString(this.linkPath), pRow.getAdaptationName(),
				pRow, this.keysOf(pRow));
		}
	}

	private final Map<MappingDefinition, Child> children = new IdentityHashMap<MappingDefinition, Child>();

	private ChildRowPreload() { }

	/** The child mapping's preloaded rows, or null (ask EBX for each element). */
	Child child(final MappingDefinition pChild)
	{
		return this.children.get(pChild);
	}

	/** A new write attempt (the previous one was rolled back): the rows are as they were read. */
	void beginAttempt()
	{
		for (final Child child : this.children.values())
		{
			child.groups.beginAttempt();
		}
	}

	/**
	 * Reads the existing children of {@code pExistingParents}, one read per child mapping and 200 parents. Null when the
	 * feed has no sub-object mapping that can use it. Never fails the write: a mapping whose read fails is applied
	 * one XPath per element.
	 */
	static ChildRowPreload load(final Session pSession, final MappingDefinition pFeed, final Adaptation pDataset, final AdaptationTable pParentTable,
		final List<Adaptation> pExistingParents)
	{
		if (pFeed.getChildren().isEmpty() || !Boolean.parseBoolean(System.getProperty(ChildRowPreload.PROPERTY, "true")))
		{
			return null;
		}
		final Map<String, Integer> tableUses = new HashMap<String, Integer>();
		ChildRowPreload.countTables(pFeed, tableUses);
		final ChildRowPreload preload = new ChildRowPreload();
		final Set<String> parentKeys = new LinkedHashSet<String>();
		for (final Adaptation parent : pExistingParents)
		{
			parentKeys.add(parent.getOccurrencePrimaryKey().format());
		}
		for (final MappingDefinition mapping : pFeed.getChildren())
		{
			try
			{
				final Child child = ChildRowPreload.read(pSession, mapping, pDataset, pParentTable, parentKeys, tableUses);
				if (child != null)
				{
					preload.children.put(mapping, child);
				}
			}
			catch (final RuntimeException ex)
			{
				FeedLookups.log().warn("[connectors] sub-objects " + mapping.getCollectionPath() + ": existing rows could not be read ("
					+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()) + "): each element is looked up with the XPath predicate");
			}
		}
		return preload.children.isEmpty() ? null : preload;
	}

	/** Whether the table is keyed by one auto-increment field: the key of a record just created in it was never used. */
	private static boolean hasAutoIncrementKey(final AdaptationTable pTable)
	{
		final Path[] spec = pTable.getPrimaryKeySpec();
		if (spec == null || spec.length != 1)
		{
			return false;
		}
		final SchemaNode node = pTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + spec[0].format().replaceFirst("^/+", "")));
		return node != null && node.isAutoIncrement();
	}

	private static String tableKey(final String pTablePath)
	{
		return Path.parse(pTablePath).format();
	}

	private static void countTables(final MappingDefinition pMapping, final Map<String, Integer> pUses)
	{
		final String key = ChildRowPreload.tableKey(pMapping.getTablePath());
		final Integer seen = pUses.get(key);
		pUses.put(key, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
		for (final MappingDefinition child : pMapping.getChildren())
		{
			ChildRowPreload.countTables(child, pUses);
		}
	}

	private static Child read(final Session pSession, final MappingDefinition pChild, final Adaptation pDataset, final AdaptationTable pParentTable,
		final Set<String> pParentKeys, final Map<String, Integer> pTableUses)
	{
		final Integer uses = pTableUses.get(ChildRowPreload.tableKey(pChild.getTablePath()));
		if (uses == null || uses.intValue() != 1 || !pChild.getChildFilter().isEmpty())
		{
			return null;
		}
		final String link = MappingEngine.normalise(pChild.getParentLinkField());
		if (link.isEmpty())
		{
			return null; // applyChild reports it
		}
		final AdaptationTable table = pDataset.getTable(Path.parse(pChild.getTablePath()));
		final SchemaNode tableNode = table.getTableOccurrenceRootNode();
		final SchemaNode linkNode = tableNode.getNode(Path.parse(link));
		if (linkNode == null || !linkNode.isTerminalValue() || linkNode.isTerminalValueComputed() || linkNode.getMaxOccurs() != 1
			|| linkNode.getFacetOnTableReference() == null && !FeedKeyLookup.isStringTyped(linkNode))
		{
			return null;
		}
		final List<Path> keyPaths = new ArrayList<Path>();
		for (final String keyField : pChild.getChildKeyFields())
		{
			final Path path = Path.parse(MappingEngine.normalise(keyField));
			if (!FeedKeyLookup.isPlainString(tableNode.getNode(path)))
			{
				return null;
			}
			keyPaths.add(path);
		}
		final Path linkPath = Path.parse(link);
		final Child child = new Child(linkPath, keyPaths, ChildRowPreload.hasAutoIncrementKey(pParentTable));
		final List<String> parents = new ArrayList<String>(pParentKeys);
		for (final String parent : parents)
		{
			child.groups.declareParent(parent);
		}
		final Set<String> wanted = new HashSet<String>(parents);
		for (int from = 0; from < parents.size(); from += ChildRowPreload.PARENTS_PER_READ)
		{
			final StringBuilder predicate = new StringBuilder();
			for (final String parent : parents.subList(from, Math.min(parents.size(), from + ChildRowPreload.PARENTS_PER_READ)))
			{
				if (predicate.length() > 0)
				{
					predicate.append(" or ");
				}
				predicate.append(MappingEngine.predicate(tableNode, link, parent));
			}
			final Request request = table.createRequest();
			request.setSession(pSession);
			request.setXPathFilter(predicate.toString());
			final RequestResult rows = request.execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String linked = row.getString(linkPath);
					if (linked == null || !wanted.contains(linked))
					{
						// The predicate only returns rows linked to one of the parents, so the link read from a row must be
						// that parent's key as the predicate wrote it. If it is not, this reading of the field is not the
						// one the predicate compares, and matching in memory could miss rows: leave the mapping to EBX.
						return null;
					}
					child.groups.addRead(linked, row.getAdaptationName(), row, child.keysOf(row));
				}
			}
			finally
			{
				rows.close();
			}
		}
		return child;
	}
}
