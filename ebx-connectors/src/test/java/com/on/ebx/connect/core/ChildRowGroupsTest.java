package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Audit C-11: matching child elements in rows read beforehand gives the decisions the per-element XPath gave. The
 * reference is the old algorithm of {@code MappingEngine.applyChild} run on a live table: first row linked to the
 * parent whose key fields equal the element's (never for a null or empty value), create when none, optionally delete
 * the rows of the parent that no element matched. The new one reads the parents' rows once, matches in
 * {@link ChildRowGroups}, falls back to the table when two rows answer, and tells the groups what it wrote.
 */
public final class ChildRowGroupsTest
{
	private static int ambiguous;

	private ChildRowGroupsTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		basics();
		for (long seed = 1; seed <= 300; seed++)
		{
			equivalence(seed);
		}
		check(ambiguous > 0, "the scenarios did meet a key held by two rows");
		System.out.println("PASS: ChildRowGroups matches child elements as the per-element XPath did");
	}

	private static void basics()
	{
		final ChildRowGroups<String> groups = new ChildRowGroups<String>();
		groups.declareParent("p1");
		groups.addRead("p1", "r1", "row1", Arrays.asList("HOME"));
		groups.addRead("p1", "r2", "row2", Arrays.asList("WORK"));
		groups.declareParent("p2");
		check(groups.knows("p1") && groups.knows("p2") && !groups.knows("p3"), "parents read are known, others are not");
		check("row1".equals(groups.find("p1", Arrays.asList("HOME")).getRecord()), "a key finds its row");
		check(groups.find("p1", Arrays.asList("OTHER")).getKind() == BusinessKeyIndex.Kind.NONE, "an unknown key: none");
		check(groups.find("p2", Arrays.asList("HOME")).getKind() == BusinessKeyIndex.Kind.NONE, "rows of another parent are not this parent's");
		check(groups.find("p1", Arrays.asList("")).getKind() == BusinessKeyIndex.Kind.NONE, "an empty value never matches");
		check(groups.find("p1", Arrays.asList((String) null)).getKind() == BusinessKeyIndex.Kind.NONE, "nor a null one");
		check(groups.find("p3", Arrays.asList("HOME")).getKind() == BusinessKeyIndex.Kind.NONE, "an unknown parent has no rows (the caller checks knows first)");

		groups.addRead("p1", "r3", "row3", Arrays.asList("HOME"));
		check(groups.find("p1", Arrays.asList("HOME")).getKind() == BusinessKeyIndex.Kind.AMBIGUOUS, "two rows with the key: EBX decides which is first");

		// the attempt's own writes are an overlay that a new attempt forgets
		groups.declareNewParent("n1");
		groups.wrote(null, null, "n1", "r9", "row9", Arrays.asList("A"));
		check("row9".equals(groups.find("n1", Arrays.asList("A")).getRecord()), "a row created in the attempt is found by the next element");
		groups.wrote("r2", "p1", "p1", "r2", "row2b", Arrays.asList("WORK2"));
		check(groups.find("p1", Arrays.asList("WORK")).getKind() == BusinessKeyIndex.Kind.NONE, "a changed key is no longer the old one");
		check("row2b".equals(groups.find("p1", Arrays.asList("WORK2")).getRecord()), "it is the new one");
		groups.beginAttempt();
		check(!groups.knows("n1"), "a parent created by the rolled-back attempt is unknown again");
		check("row2".equals(groups.find("p1", Arrays.asList("WORK")).getRecord()), "a rolled-back change leaves the row as it was read");
		check(groups.rows("p1").equals(Arrays.asList("row1", "row2", "row3")), "rows come back in the order read: " + groups.rows("p1"));

		// a row moved to another known parent
		groups.wrote("r1", "p1", "p2", "r1", "row1m", Arrays.asList("HOME"));
		check(groups.rows("p1").equals(Arrays.asList("row2", "row3")) && !groups.knows("p2"), "a row linked elsewhere leaves its group and the receiving parent is asked to EBX");
		groups.beginAttempt();
		// and one moved to a parent nobody asked about is in no group
		groups.wrote("r2", "p1", "zz", "r2", "row2z", Arrays.asList("WORK"));
		check(groups.rows("p1").equals(Arrays.asList("row1", "row3")), "p1 lost row2: " + groups.rows("p1"));
		check(!groups.knows("zz"), "a row linked to an unknown parent is kept nowhere");
	}

	private static final class Row
	{
		final int id;
		String link;
		List<String> keys;

		Row(final int pId, final String pLink, final List<String> pKeys)
		{
			this.id = pId;
			this.link = pLink;
			this.keys = pKeys;
		}
	}

	private static String randomValue(final Random pRandom)
	{
		final String[] alphabet = { "HOME", "WORK", "SHIP", "O'Brien", "", null, "a b", "x", "y" };
		return alphabet[pRandom.nextInt(alphabet.length)];
	}

	/** What the XPath answers: the first row of the table linked to the parent whose keys equal the values; none for null/empty values. */
	private static Row xpathFirst(final List<Row> pTable, final String pParent, final List<String> pValues)
	{
		for (final String value : pValues)
		{
			if (value == null || value.isEmpty())
			{
				return null;
			}
		}
		for (final Row row : pTable)
		{
			if (pParent.equals(row.link) && row.keys.equals(pValues))
			{
				return row;
			}
		}
		return null;
	}

	private static List<Row> copy(final List<Row> pTable)
	{
		final List<Row> out = new ArrayList<Row>();
		for (final Row row : pTable)
		{
			out.add(new Row(row.id, row.link, new ArrayList<String>(row.keys)));
		}
		return out;
	}

	private static String dump(final List<Row> pTable)
	{
		final StringBuilder b = new StringBuilder();
		for (final Row row : pTable)
		{
			b.append(row.id).append('@').append(row.link).append(row.keys).append(';');
		}
		return b.toString();
	}

	/** One scenario: parents with elements, applied the old way on one copy of the table and the new way on another. */
	private static void equivalence(final long pSeed)
	{
		final Random random = new Random(pSeed);
		final int width = 1 + random.nextInt(2);
		final boolean deleteMissing = random.nextBoolean();
		final List<Row> initial = new ArrayList<Row>();
		int nextId = 1;
		// no row is linked to p4 yet: it is a parent the procedure creates, whose key was never used
		final String[] parents = { "p1", "p2", "p3", "old1" };
		for (int i = 0; i < random.nextInt(25); i++)
		{
			final List<String> keys = new ArrayList<String>();
			for (int k = 0; k < width; k++)
			{
				String value = randomValue(random);
				while (value == null || value.isEmpty())
				{
					value = randomValue(random); // a stored key is never empty: EBX stores that as null
				}
				keys.add(value);
			}
			initial.add(new Row(nextId++, parents[random.nextInt(parents.length)], keys));
		}
		// existing parents of the segment: p1..p3 (p3 may have no rows); p4 is created by the procedure
		final List<String> existingParents = Arrays.asList("p1", "p2", "p3");
		final List<String> newParents = Arrays.asList("p4");
		final List<String> order = new ArrayList<String>(existingParents);
		order.addAll(newParents);
		java.util.Collections.shuffle(order, random);

		// the elements of each parent, and what the procedure writes (same random choices for both runs)
		final List<List<List<String>>> elementsOf = new ArrayList<List<List<String>>>();
		final List<List<Boolean>> keyChanges = new ArrayList<List<Boolean>>();
		for (int p = 0; p < order.size(); p++)
		{
			final List<List<String>> elements = new ArrayList<List<String>>();
			final List<Boolean> changes = new ArrayList<Boolean>();
			for (int e = 0; e < random.nextInt(5); e++)
			{
				List<String> values = new ArrayList<String>();
				for (int k = 0; k < width; k++)
				{
					values.add(randomValue(random));
				}
				if (random.nextBoolean() && !initial.isEmpty())
				{
					values = new ArrayList<String>(initial.get(random.nextInt(initial.size())).keys);
				}
				elements.add(values);
				changes.add(Boolean.valueOf(random.nextInt(4) == 0));
			}
			elementsOf.add(elements);
			keyChanges.add(changes);
		}
		final int failAttemptAfter = random.nextInt(4); // the first attempt of the new run is rolled back after that many parents

		// old: live table
		final List<Row> oldTable = copy(initial);
		final List<String> oldLog = new ArrayList<String>();
		int oldNext = nextId;
		for (int p = 0; p < order.size(); p++)
		{
			oldNext = applyOld(oldTable, order.get(p), elementsOf.get(p), keyChanges.get(p), deleteMissing, oldNext, oldLog);
		}

		// new: rows read once, matched in memory; a first attempt that is rolled back leaves the table as it was
		final List<Row> newTable = copy(initial);
		final List<String> newLog = new ArrayList<String>();
		final ChildRowGroups<Row> groups = new ChildRowGroups<Row>();
		for (final String parent : existingParents)
		{
			groups.declareParent(parent);
			for (final Row row : newTable)
			{
				if (parent.equals(row.link))
				{
					groups.addRead(parent, Integer.valueOf(row.id), copyRow(row), new ArrayList<String>(row.keys));
				}
			}
		}
		final List<Row> beforeFailed = copy(newTable);
		int failedNext = nextId;
		final List<String> scrap = new ArrayList<String>();
		for (int p = 0; p < Math.min(failAttemptAfter, order.size()); p++)
		{
			failedNext = applyNew(newTable, groups, existingParents, order.get(p), elementsOf.get(p), keyChanges.get(p), deleteMissing, failedNext, scrap);
		}
		newTable.clear();
		newTable.addAll(beforeFailed);
		groups.beginAttempt();
		int newNext = nextId;
		for (int p = 0; p < order.size(); p++)
		{
			newNext = applyNew(newTable, groups, existingParents, order.get(p), elementsOf.get(p), keyChanges.get(p), deleteMissing, newNext, newLog);
		}
		check(oldLog.equals(newLog), "seed " + pSeed + ": decisions differ\nold " + oldLog + "\nnew " + newLog);
		check(dump(oldTable).equals(dump(newTable)), "seed " + pSeed + ": tables differ\nold " + dump(oldTable) + "\nnew " + dump(newTable));
	}

	private static Row copyRow(final Row pRow)
	{
		return new Row(pRow.id, pRow.link, new ArrayList<String>(pRow.keys));
	}

	/** The old applyChild on a live table. */
	private static int applyOld(final List<Row> pTable, final String pParent, final List<List<String>> pElements, final List<Boolean> pKeyChanges,
		final boolean pDeleteMissing, final int pNextId, final List<String> pLog)
	{
		int nextId = pNextId;
		final Set<Integer> matched = new HashSet<Integer>();
		for (int e = 0; e < pElements.size(); e++)
		{
			final List<String> values = pElements.get(e);
			boolean complete = true;
			for (final String value : values)
			{
				if (value == null)
				{
					complete = false;
				}
			}
			final Row existing = complete ? xpathFirst(pTable, pParent, values) : null;
			if (existing == null)
			{
				final Row created = new Row(nextId++, pParent, stored(values, false));
				pTable.add(created);
				matched.add(Integer.valueOf(created.id));
				pLog.add("create " + created.id + " " + created.keys);
			}
			else
			{
				if (pKeyChanges.get(e).booleanValue())
				{
					existing.keys = stored(values, true);
					existing.link = movedLink(pParent, e);
				}
				matched.add(Integer.valueOf(existing.id));
				pLog.add("update " + existing.id);
			}
		}
		if (pDeleteMissing)
		{
			final List<Row> stale = new ArrayList<Row>();
			for (final Row row : pTable)
			{
				if (pParent.equals(row.link) && !matched.contains(Integer.valueOf(row.id)))
				{
					stale.add(row);
				}
			}
			for (final Row row : stale)
			{
				pTable.remove(row);
				pLog.add("delete " + row.id);
			}
		}
		return nextId;
	}

	/** A rule that rewrites the key may also rewrite the link: the row then belongs to another parent. */
	private static String movedLink(final String pParent, final int pElement)
	{
		if (pElement % 2 == 1 || "p4".equals(pParent))
		{
			return pParent;
		}
		return "p1".equals(pParent) ? "p2" : "p1";
	}

	/** What is stored for the element's values: the values, or (a rule rewriting the key) another text; never empty. */
	private static List<String> stored(final List<String> pValues, final boolean pRewritten)
	{
		final List<String> out = new ArrayList<String>();
		for (final String value : pValues)
		{
			final String text = value == null || value.isEmpty() ? "filled" : value;
			out.add(pRewritten ? text + "!" : text);
		}
		return out;
	}

	/** The new applyChild: rows read before, matched in memory, the table consulted only for an ambiguous key. */
	private static int applyNew(final List<Row> pTable, final ChildRowGroups<Row> pGroups, final List<String> pExistingParents, final String pParent,
		final List<List<String>> pElements, final List<Boolean> pKeyChanges, final boolean pDeleteMissing, final int pNextId, final List<String> pLog)
	{
		int nextId = pNextId;
		final boolean usable;
		if (pExistingParents.contains(pParent))
		{
			usable = pGroups.knows(pParent);
		}
		else
		{
			pGroups.declareNewParent(pParent);
			usable = true;
		}
		final Set<Integer> matched = new HashSet<Integer>();
		for (int e = 0; e < pElements.size(); e++)
		{
			final List<String> values = pElements.get(e);
			boolean complete = true;
			for (final String value : values)
			{
				if (value == null)
				{
					complete = false;
				}
			}
			Row existing = null;
			if (complete && !usable)
			{
				existing = xpathFirst(pTable, pParent, values); // the parent is asked to EBX
			}
			else if (complete)
			{
				final BusinessKeyIndex.Match<Row> match = pGroups.find(pParent, values);
				if (match.getKind() == BusinessKeyIndex.Kind.AMBIGUOUS)
				{
					ambiguous++;
					existing = xpathFirst(pTable, pParent, values);
				}
				else if (match.getKind() == BusinessKeyIndex.Kind.ONE)
				{
					existing = findInTable(pTable, match.getRecord().id);
				}
			}
			if (existing == null)
			{
				final Row created = new Row(nextId++, pParent, stored(values, false));
				pTable.add(created);
				pGroups.wrote(null, null, created.link, Integer.valueOf(created.id), copyRow(created), new ArrayList<String>(created.keys));
				matched.add(Integer.valueOf(created.id));
				pLog.add("create " + created.id + " " + created.keys);
			}
			else
			{
				if (pKeyChanges.get(e).booleanValue())
				{
					existing.keys = stored(values, true);
					existing.link = movedLink(pParent, e);
					pGroups.wrote(Integer.valueOf(existing.id), pParent, existing.link, Integer.valueOf(existing.id), copyRow(existing), new ArrayList<String>(existing.keys));
				}
				matched.add(Integer.valueOf(existing.id));
				pLog.add("update " + existing.id);
			}
		}
		if (pDeleteMissing)
		{
			final List<Row> stale = new ArrayList<Row>();
			final List<Row> candidates = usable ? pGroups.rows(pParent) : new ArrayList<Row>(pTable);
			for (final Row row : candidates)
			{
				if ((usable || pParent.equals(findInTable(pTable, row.id).link)) && !matched.contains(Integer.valueOf(row.id)))
				{
					stale.add(row);
				}
			}
			for (final Row row : stale)
			{
				pTable.remove(findInTable(pTable, row.id));
				pLog.add("delete " + row.id);
			}
		}
		return nextId;
	}

	private static Row findInTable(final List<Row> pTable, final int pId)
	{
		for (final Row row : pTable)
		{
			if (row.id == pId)
			{
				return row;
			}
		}
		throw new AssertionError("row " + pId + " is in the groups but not in the table");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
