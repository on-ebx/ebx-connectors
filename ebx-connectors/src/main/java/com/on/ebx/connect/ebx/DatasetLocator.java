package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;

/**
 * Finds a dataset by name, in a given dataspace or anywhere under the reference dataspace.
 * Several dataspaces may hold a dataset of the same name (Companies exists in the reference
 * dataspace with a broken model and in the Companies dataspace with the real data): when a
 * table path is given, only a dataset whose model actually serves that table qualifies.
 * <p>
 * Without a dataspace the name is only a guess, and a guess must be unique: when two or more open
 * dataspaces hold the dataset (and serve the table) the answer is "ambiguous", never "the first one
 * found" - a mapping stored with no dataspace once published the rows of another project's master
 * because an orphan dataspace of the same name came first in the walk. Only datasets in distinct,
 * NON-NESTED dataspaces count: the walk does not descend under a dataspace that serves the dataset, so a
 * child dataspace (a workflow workspace) holding a copy of it is the same dataset, not a second candidate,
 * and a table-scoped run on it uses the parent. {@link #locate} gives the
 * whole verdict (the dataset, or the candidates and the reason); {@link #find} gives the dataset only
 * when the resolution is unique, and null otherwise.
 */
public final class DatasetLocator
{
	/** Without a dataspace, collecting stops after that many candidates (the verdict is "ambiguous" long before; the list is only for the message). */
	static final int MAX_CANDIDATES = 10;
	/** Depth bound of the walk of the dataspace tree. */
	static final int MAX_DEPTH = 12;

	private DatasetLocator()
	{
	}

	/** What the candidates found say. */
	public enum Verdict
	{
		/** Exactly one dataspace holds the dataset: it is the answer. */
		UNIQUE,
		/** Two or more dataspaces hold it: the caller must name the dataspace. */
		AMBIGUOUS,
		/** None holds it (or the named dataspace does not exist). */
		ABSENT
	}

	/**
	 * The result of {@link DatasetLocator#locate}: the dataset when the resolution is unique, otherwise why not.
	 * {@code dataspaceMissing} is true when a dataspace was named and does not exist (as opposed to existing
	 * without the dataset). {@code candidates} are the keys of the dataspaces that hold the dataset, in the
	 * order of the walk (at most {@link DatasetLocator#MAX_CANDIDATES}).
	 */
	public static final class Location
	{
		private final Adaptation dataset;
		private final Adaptation first;
		private final Verdict verdict;
		private final List<String> candidates;
		private final boolean dataspaceMissing;

		Location(final Adaptation pDataset, final Adaptation pFirst, final Verdict pVerdict, final List<String> pCandidates, final boolean pDataspaceMissing)
		{
			this.dataset = pDataset;
			this.first = pFirst;
			this.verdict = pVerdict;
			this.candidates = Collections.unmodifiableList(new ArrayList<String>(pCandidates));
			this.dataspaceMissing = pDataspaceMissing;
		}

		/** The dataset when {@link #getVerdict()} is UNIQUE, null otherwise. */
		public Adaptation getDataset()
		{
			return this.dataset;
		}

		/**
		 * The first dataset found, whatever the verdict (null when none). For checks that only read the MODEL (does the table exist,
		 * do the fields resolve): copies of a dataset in several dataspaces share it. Never to read or write data.
		 */
		public Adaptation getFirst()
		{
			return this.first;
		}

		public Verdict getVerdict()
		{
			return this.verdict;
		}

		public List<String> getCandidates()
		{
			return this.candidates;
		}

		public boolean isDataspaceMissing()
		{
			return this.dataspaceMissing;
		}

		/** Null when the dataset was found; otherwise the reason, in the words an operator needs ("not found", "served by several dataspaces (...): name the dataspace"). */
		public String failure(final String pDataspace, final String pDataset, final String pTablePath)
		{
			return DatasetLocator.failureText(this.verdict, this.dataspaceMissing, pDataspace, pDataset, pTablePath, this.candidates);
		}
	}

	public static Adaptation find(final Repository pRepository, final String pDataspace, final String pDataset)
	{
		return DatasetLocator.find(pRepository, pDataspace, pDataset, null);
	}

	/** The dataset, only when the resolution is unique (a named dataspace, or exactly one dataspace holding it); null when absent or ambiguous. */
	public static Adaptation find(final Repository pRepository, final String pDataspace, final String pDataset, final String pTablePath)
	{
		return DatasetLocator.locate(pRepository, pDataspace, pDataset, pTablePath).getDataset();
	}

	/**
	 * Resolves a dataset. With a dataspace: that dataspace only. Without one: EVERY open dataspace under the
	 * reference dataspace that holds the dataset and serves the table is collected (depth-first over branches
	 * and versions, depth {@value #MAX_DEPTH}, stopping at {@value #MAX_CANDIDATES} candidates), without
	 * descending under a hit (see the class comment). The cost of a blank-dataspace resolution is the rest of
	 * the dataspace tree, no longer "up to the first hit": name the dataspace on anything that runs often. The verdict comes from {@link #decide(List)}.
	 */
	public static Location locate(final Repository pRepository, final String pDataspace, final String pDataset, final String pTablePath)
	{
		if (pDataset == null || pDataset.trim().isEmpty())
		{
			return new Location(null, null, Verdict.ABSENT, Collections.<String>emptyList(), false);
		}
		final AdaptationName name = AdaptationName.forName(pDataset.trim());
		if (pDataspace != null && !pDataspace.trim().isEmpty())
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pDataspace.trim()));
			if (home == null)
			{
				return new Location(null, null, Verdict.ABSENT, Collections.<String>emptyList(), true);
			}
			final Adaptation found = home.findAdaptationOrNull(name);
			if (found == null || !DatasetLocator.serves(found, pTablePath))
			{
				return new Location(null, null, Verdict.ABSENT, Collections.<String>emptyList(), false);
			}
			return new Location(found, found, Verdict.UNIQUE, Collections.singletonList(home.getKey().getName()), false);
		}
		final List<Adaptation> hits = new ArrayList<Adaptation>();
		DatasetLocator.collect(pRepository.getReferenceBranch(), name, pTablePath, hits);
		final List<String> keys = new ArrayList<String>();
		for (final Adaptation hit : hits)
		{
			keys.add(hit.getHome().getKey().getName());
		}
		final Verdict verdict = DatasetLocator.decide(keys);
		return new Location(verdict == Verdict.UNIQUE ? hits.get(0) : null, hits.isEmpty() ? null : hits.get(0), verdict, keys, false);
	}

	/** The verdict of a list of candidate dataspace keys: none, one, or several (the same key listed twice counts once). */
	public static Verdict decide(final List<String> pCandidateDataspaces)
	{
		if (pCandidateDataspaces == null)
		{
			return Verdict.ABSENT;
		}
		final java.util.Set<String> distinct = new java.util.LinkedHashSet<String>(pCandidateDataspaces);
		return distinct.isEmpty() ? Verdict.ABSENT : distinct.size() == 1 ? Verdict.UNIQUE : Verdict.AMBIGUOUS;
	}

	/**
	 * The message for a resolution that did not give a dataset. Pure. {@code pDataspace} is what the mapping names
	 * (blank: none); {@code pCandidates} are only read for AMBIGUOUS.
	 */
	public static String failureText(final Verdict pVerdict, final boolean pDataspaceMissing, final String pDataspace, final String pDataset,
		final String pTablePath, final List<String> pCandidates)
	{
		final String table = pTablePath == null || pTablePath.trim().isEmpty() ? "" : " (table " + pTablePath.trim() + ")";
		final String dataspace = pDataspace == null ? "" : pDataspace.trim();
		if (pVerdict == Verdict.UNIQUE)
		{
			return null;
		}
		if (pVerdict == Verdict.AMBIGUOUS)
		{
			final int n = pCandidates == null ? 0 : new java.util.LinkedHashSet<String>(pCandidates).size();
			final StringBuilder keys = new StringBuilder();
			if (pCandidates != null)
			{
				for (final String key : new java.util.LinkedHashSet<String>(pCandidates))
				{
					keys.append(keys.length() == 0 ? "" : ", ").append(key);
				}
			}
			return "Dataset '" + pDataset + "'" + table + " is served by " + (n >= MAX_CANDIDATES ? "at least " : "") + n + " dataspaces (" + keys
				+ "): name the dataspace on the mapping";
		}
		if (pDataspaceMissing)
		{
			return "Dataspace '" + dataspace + "' does not exist";
		}
		return "Dataset '" + pDataset + "'" + table + " not found" + (dataspace.isEmpty() ? " in any open dataspace" : " in " + dataspace);
	}

	/** True when the dataset can hand out the table (null path: any dataset whose model loads). */
	static boolean serves(final Adaptation pDataset, final String pTablePath)
	{
		try
		{
			if (pTablePath == null || pTablePath.trim().isEmpty())
			{
				return pDataset.getSchemaNode() != null;
			}
			return pDataset.getTable(Path.parse(pTablePath.trim())) != null;
		}
		catch (final RuntimeException ex)
		{
			return false; // model in error in this dataspace, or table absent
		}
	}

	/** The tree the walk goes through; a seam so that the pruning can be tested without a repository. */
	interface Tree<T>
	{
		/** True when that node is an open dataspace serving the dataset and table. */
		boolean isHit(T pNode);

		/** The branch children, then the version children (in EBX a child dataspace hangs under a version of its parent). */
		List<T> children(T pNode);
	}

	/**
	 * Depth-first, PRUNED under a hit: a hit is a candidate and its children are not visited (a copy of the same dataset in a
	 * child dataspace is not another candidate). Collects at most {@value #MAX_CANDIDATES} hits, to depth {@value #MAX_DEPTH}.
	 */
	static <T> void collectPruned(final T pNode, final Tree<T> pTree, final int pDepth, final List<T> pHits)
	{
		if (pNode == null || pDepth > MAX_DEPTH || pHits.size() >= MAX_CANDIDATES)
		{
			return;
		}
		if (pTree.isHit(pNode))
		{
			pHits.add(pNode);
			return;
		}
		for (final T child : pTree.children(pNode))
		{
			DatasetLocator.collectPruned(child, pTree, pDepth + 1, pHits);
		}
	}

	private static void collect(final AdaptationHome pRoot, final AdaptationName pName, final String pTablePath, final List<Adaptation> pHits)
	{
		final java.util.Map<AdaptationHome, Adaptation> found = new java.util.IdentityHashMap<AdaptationHome, Adaptation>();
		final List<AdaptationHome> homes = new ArrayList<AdaptationHome>();
		DatasetLocator.collectPruned(pRoot, new Tree<AdaptationHome>()
		{
			@Override
			public boolean isHit(final AdaptationHome pHome)
			{
				if (!pHome.isBranch() || !pHome.isOpen())
				{
					return false;
				}
				final Adaptation dataset = pHome.findAdaptationOrNull(pName);
				if (dataset != null && DatasetLocator.serves(dataset, pTablePath))
				{
					found.put(pHome, dataset);
					return true;
				}
				return false;
			}

			@Override
			public List<AdaptationHome> children(final AdaptationHome pHome)
			{
				final List<AdaptationHome> out = new ArrayList<AdaptationHome>(pHome.getBranchChildren());
				out.addAll(pHome.getVersionChildren());
				return out;
			}
		}, 0, homes);
		for (final AdaptationHome home : homes)
		{
			pHits.add(found.get(home));
		}
	}
}
