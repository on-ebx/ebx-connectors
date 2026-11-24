package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A mapping that names no dataspace must never be run, nor stored, on a guess: the resolution is unique, ambiguous or absent,
 * and only a unique one is acted on. The walk of the dataspace tree needs a repository; what it concludes does not, and
 * that is what is tested here (the verdict, the messages, the upsert's decision, the boot repair's decision).
 */
public final class DatasetResolutionTest
{
	private static int checks;

	public static void main(final String[] pArgs) throws Exception
	{
		verdicts();
		pruning();
		messages();
		upsertDecisions();
		bootRepairDecisions();
		System.out.println("PASS: dataspace resolution is unique or refused (" + checks + " checks)");
	}

	private static void verdicts()
	{
		eq(DatasetLocator.Verdict.ABSENT, DatasetLocator.decide(Collections.<String>emptyList()), "no candidate: absent");
		eq(DatasetLocator.Verdict.ABSENT, DatasetLocator.decide(null), "null: absent");
		eq(DatasetLocator.Verdict.UNIQUE, DatasetLocator.decide(Arrays.asList("Vol100k_Master")), "one candidate: unique");
		eq(DatasetLocator.Verdict.AMBIGUOUS, DatasetLocator.decide(Arrays.asList("Personnes_Master", "Vol100k_Master")), "two candidates: ambiguous");
		eq(DatasetLocator.Verdict.AMBIGUOUS, DatasetLocator.decide(Arrays.asList("A", "B", "C")), "three candidates: ambiguous");
		eq(DatasetLocator.Verdict.UNIQUE, DatasetLocator.decide(Arrays.asList("A", "A")), "the same dataspace listed twice is one candidate");
	}

	/** A tree of dataspaces: name -> children; the hits are the named dataspaces that serve the dataset. */
	private static final class TreeFake implements DatasetLocator.Tree<String>
	{
		private final java.util.Map<String, List<String>> children = new java.util.HashMap<String, List<String>>();
		private final java.util.Set<String> hits = new java.util.HashSet<String>();

		TreeFake node(final String pName, final boolean pServes, final String... pChildren)
		{
			this.children.put(pName, Arrays.asList(pChildren));
			if (pServes)
			{
				this.hits.add(pName);
			}
			return this;
		}

		@Override
		public boolean isHit(final String pNode)
		{
			return this.hits.contains(pNode);
		}

		@Override
		public List<String> children(final String pNode)
		{
			final List<String> c = this.children.get(pNode);
			return c == null ? Collections.<String>emptyList() : c;
		}

		List<String> walk()
		{
			final List<String> out = new ArrayList<String>();
			DatasetLocator.collectPruned("Reference", this, 0, out);
			return out;
		}
	}

	private static void pruning()
	{
		// a dataspace and its workflow child hold the same dataset: one candidate, the parent
		List<String> hits = new TreeFake().node("Reference", false, "Master").node("Master", true, "wf1", "wf2").node("wf1", true).node("wf2", true).walk();
		eq(Arrays.asList("Master"), hits, "a parent and its child copies: only the parent is a candidate");
		eq(DatasetLocator.Verdict.UNIQUE, DatasetLocator.decide(hits), "... so the resolution is unique");
		// the child hangs deeper (branch -> version -> branch): still pruned
		hits = new TreeFake().node("Reference", false, "Master").node("Master", true, "v1").node("v1", false, "wf1").node("wf1", true).walk();
		eq(Arrays.asList("Master"), hits, "a child under a version of the parent is pruned too");
		// a child copy whose parent does not serve the dataset is a candidate of its own
		hits = new TreeFake().node("Reference", false, "Parent").node("Parent", false, "wf1").node("wf1", true).walk();
		eq(Arrays.asList("wf1"), hits, "a copy under a parent that does not serve it counts");
		// two distinct, non-nested dataspaces: ambiguous, each with its own children pruned
		hits = new TreeFake().node("Reference", false, "Personnes_Master", "Vol100k_Master").node("Personnes_Master", true, "a").node("a", true)
			.node("Vol100k_Master", true, "b").node("b", true).walk();
		eq(Arrays.asList("Personnes_Master", "Vol100k_Master"), hits, "distinct dataspaces: both candidates, no child");
		eq(DatasetLocator.Verdict.AMBIGUOUS, DatasetLocator.decide(hits), "... ambiguous");
		// nobody serves it
		eq(Collections.<String>emptyList(), new TreeFake().node("Reference", false, "A").node("A", false).walk(), "no hit: no candidate");
	}

	private static void messages()
	{
		final String ambiguous = DatasetLocator.failureText(DatasetLocator.Verdict.AMBIGUOUS, false, null, "Master", "/root/Personne",
			Arrays.asList("Personnes_Master", "Vol100k_Master"));
		yes(ambiguous.contains("Master") && ambiguous.contains("served by 2 dataspaces") && ambiguous.contains("Personnes_Master, Vol100k_Master")
			&& ambiguous.contains("name the dataspace"), "ambiguous message names the candidates and says what to do: " + ambiguous);
		final String absent = DatasetLocator.failureText(DatasetLocator.Verdict.ABSENT, false, null, "Master", "/root/Personne", Collections.<String>emptyList());
		yes(absent.contains("not found") && !absent.contains("served by"), "absent message says not found: " + absent);
		final String absentIn = DatasetLocator.failureText(DatasetLocator.Verdict.ABSENT, false, "Reference", "Master", null, Collections.<String>emptyList());
		yes(absentIn.endsWith("not found in Reference"), "absent message names the dataspace asked: " + absentIn);
		final String missing = DatasetLocator.failureText(DatasetLocator.Verdict.ABSENT, true, "Gone", "Master", null, Collections.<String>emptyList());
		yes(missing.contains("Dataspace 'Gone' does not exist"), "missing dataspace message: " + missing);
		yes(DatasetLocator.failureText(DatasetLocator.Verdict.UNIQUE, false, null, "Master", null, Arrays.asList("A")) == null, "a unique resolution has no failure");
		final List<String> many = new ArrayList<String>();
		for (int i = 0; i < DatasetLocator.MAX_CANDIDATES; i++)
		{
			many.add("D" + i);
		}
		yes(DatasetLocator.failureText(DatasetLocator.Verdict.AMBIGUOUS, false, null, "Master", null, many).contains("at least " + DatasetLocator.MAX_CANDIDATES),
			"a cut candidate list says 'at least'");
		final DatasetLocator.Location location = new DatasetLocator.Location(null, null, DatasetLocator.Verdict.AMBIGUOUS, Arrays.asList("A", "B"), false);
		yes(location.getDataset() == null && location.failure(null, "Master", "/root/T").contains("served by 2 dataspaces (A, B)"), "a Location carries the reason");
	}

	private static void upsertDecisions()
	{
		final List<String> one = Arrays.asList("Vol100k_Master");
		final List<String> two = Arrays.asList("Personnes_Master", "Vol100k_Master");
		final List<String> none = Collections.<String>emptyList();

		// the live defect: dataset Master, no dataspace, two dataspaces serve it
		ConfigTransfer.TargetDecision d = ConfigTransfer.decideTarget(null, DatasetLocator.Verdict.AMBIGUOUS, false, two, "Master", "/root/Personne");
		yes(d.error != null && d.dataspace == null && d.error.contains("Personnes_Master") && d.error.contains("Vol100k_Master") && d.error.contains("name the dataspace"),
			"blank dataspace + two candidates: refused, candidates named: " + d.error);
		d = ConfigTransfer.decideTarget("", DatasetLocator.Verdict.AMBIGUOUS, false, two, "Master", "/root/Personne");
		yes(d.error != null, "an empty string is blank too");

		d = ConfigTransfer.decideTarget(null, DatasetLocator.Verdict.UNIQUE, false, one, "Master", "/root/Personne");
		yes(d.error == null && d.dataspace == null && d.warning != null && d.warning.contains("table-scoped runs will use Vol100k_Master"),
			"blank dataspace + one candidate: stays blank (generic), and the note says what a table-scoped run uses: " + d.warning);

		d = ConfigTransfer.decideTarget("Vol100k_Master", DatasetLocator.Verdict.UNIQUE, false, one, "Master", "/root/Personne");
		yes(d.error == null && "Vol100k_Master".equals(d.dataspace) && d.warning == null, "a dataspace named and found: kept, nothing to say");

		d = ConfigTransfer.decideTarget("BVol100k_Master", DatasetLocator.Verdict.UNIQUE, false, one, "Master", "/root/Personne");
		yes(d.error == null && "Vol100k_Master".equals(d.dataspace) && d.warning != null && d.warning.contains("BVol100k_Master"),
			"a B-prefixed dataspace whose stripped key resolves is normalised, and said");

		d = ConfigTransfer.decideTarget("Deleted_Project", DatasetLocator.Verdict.ABSENT, true, none, "Master", "/root/Personne");
		yes(d.error != null && d.error.contains("Deleted_Project") && d.error.contains("does not exist"), "a dataspace that does not exist: refused: " + d.error);

		d = ConfigTransfer.decideTarget("Vol100k_Master", DatasetLocator.Verdict.ABSENT, false, none, "Master", "/root/Personne");
		yes(d.error == null && d.dataspace == null && d.warning != null && d.warning.contains("not (yet) in dataspace 'Vol100k_Master'"),
			"the dataspace exists but the dataset is not there yet: stored as given, with a warning: " + d.warning);

		d = ConfigTransfer.decideTarget(null, DatasetLocator.Verdict.ABSENT, false, none, "Master", "/root/Personne");
		yes(d.error == null && d.dataspace == null && d.warning != null && d.warning.contains("no open dataspace"),
			"blank dataspace + no candidate: stored as today, with a warning: " + d.warning);
	}

	/** A lookup that answers from a table dataspace -> candidates (null key = every open dataspace) and counts its questions. */
	private static final class Fake implements ConnectorsInstaller.DatasetLookup
	{
		private final java.util.Map<String, List<String>> answers = new java.util.HashMap<String, List<String>>();
		private final List<String> asked = new ArrayList<String>();

		Fake with(final String pDataspace, final String... pFound)
		{
			this.answers.put(pDataspace == null ? "*" : pDataspace, Arrays.asList(pFound));
			return this;
		}

		@Override
		public DatasetLocator.Location at(final String pDataspace)
		{
			this.asked.add(pDataspace);
			final List<String> found = this.answers.get(pDataspace == null ? "*" : pDataspace);
			final List<String> keys = found == null ? Collections.<String>emptyList() : found;
			return new DatasetLocator.Location(null, null, DatasetLocator.decide(keys), keys, pDataspace != null && found == null);
		}
	}

	private static void bootRepairDecisions()
	{
		// blank: left blank (a generic mapping), and nothing is even looked up
		final Fake blankLookup = new Fake().with(null, "Vol100k_Master");
		ConnectorsInstaller.MappingTarget t = ConnectorsInstaller.decideMappingTarget(null, "Master", "/root/T", blankLookup);
		yes(t.dataspace == null && t.warning == null && blankLookup.asked.isEmpty(), "boot: blank + one candidate: left blank, no lookup: " + blankLookup.asked);
		t = ConnectorsInstaller.decideMappingTarget("  ", "Master", "/root/T", new Fake().with(null, "Personnes_Master", "Vol100k_Master"));
		yes(t.dataspace == null && t.warning == null, "boot: blank + several candidates: left blank, silent");

		// stored and resolving: written back as the branch name
		final Fake resolving = new Fake().with("Vol100k_Master", "Vol100k_Master");
		t = ConnectorsInstaller.decideMappingTarget("Vol100k_Master", "Master", "/root/T", resolving);
		yes("Vol100k_Master".equals(t.dataspace) && t.warning == null, "boot: stored and resolving: kept");
		yes(resolving.asked.equals(Arrays.asList("Vol100k_Master")), "boot: a stored dataspace never starts the walk of the whole tree: " + resolving.asked);

		// B prefix: stripped only when the stripped key resolves
		t = ConnectorsInstaller.decideMappingTarget("BCompanies", "Companies", "/root/T", new Fake().with("Companies", "Companies"));
		yes("Companies".equals(t.dataspace) && t.warning == null, "boot: BCompanies -> Companies when Companies resolves");

		// THE DEFECT: a stored dataspace that no longer resolves must not be replaced by another project's data of the same name
		final Fake deleted = new Fake().with(null, "Personnes_Master");
		t = ConnectorsInstaller.decideMappingTarget("Vol100k_Master_Deleted", "Master", "/root/T", deleted);
		yes(t.dataspace == null && t.warning != null && t.warning.contains("Vol100k_Master_Deleted") && t.warning.contains("left as stored"),
			"boot: a stored dataspace that no longer resolves is left, with a warning: " + t.warning);
		yes(!deleted.asked.contains(null), "boot: ... and the whole tree is never searched to replace it: " + deleted.asked);
		t = ConnectorsInstaller.decideMappingTarget("BGone", "Master", "/root/T", new Fake().with(null, "Personnes_Master"));
		yes(t.dataspace == null && t.warning != null, "boot: B-prefixed and unresolvable: left, with a warning");
	}

	private static void eq(final Object pExpected, final Object pActual, final String pMessage)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pMessage + " (expected " + pExpected + ", got " + pActual + ")");
	}

	private static void yes(final boolean pCondition, final String pMessage)
	{
		checks++;
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
