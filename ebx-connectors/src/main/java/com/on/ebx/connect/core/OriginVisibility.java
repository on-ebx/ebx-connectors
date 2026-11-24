package com.on.ebx.connect.core;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Audit C2-03: a value the module stored about a record (SyncLog.changes, FieldProvenance.value, a conflict's values, the
 * document of an outbound state) is only shown to a session that may read the record it came from, under the EBX permissions
 * of the origin table. Otherwise a reader of the Connectors tables would see salaries or IBANs of tables closed to them.
 * The decision is {@link #canRead}; what cannot be established (record gone, dataset gone, a lookup that fails) is hidden.
 * An EBX administrator, the system user and the platform itself (no session) always see.
 */
public final class OriginVisibility
{
	/** What a hidden value reads as. */
	public static final String MASK = "…";

	/** Whether a session may read the record with this origin. Replaceable so that a test needs no repository. */
	public interface Probe
	{
		boolean canRead(Session pSession, String pDataspace, String pDataset, String pTablePath, String pRecordKey);
	}

	private static volatile Probe probe = new EbxProbe();

	private OriginVisibility()
	{
	}

	/** Test hook: the probe in use (null restores the EBX one). Returns the previous probe. */
	public static Probe use(final Probe pProbe)
	{
		final Probe previous = OriginVisibility.probe;
		OriginVisibility.probe = pProbe == null ? new EbxProbe() : pProbe;
		return previous;
	}

	/** True when the session may see values that came from this record. The dataset may be blank (a SyncLog line names dataspace, table and key only): every dataset of the dataspace holding that record must then be readable. A row without an origin record is not ours to judge: visible. */
	public static boolean canRead(final Session pSession, final String pDataspace, final String pDataset, final String pTablePath, final String pRecordKey)
	{
		if (pSession == null || OriginVisibility.privileged(pSession))
		{
			return true;
		}
		if (blank(pDataspace) || blank(pTablePath) || blank(pRecordKey))
		{
			return true; // no origin record: nothing of a table to protect
		}
		try
		{
			return OriginVisibility.probe.canRead(pSession, pDataspace, pDataset, pTablePath, pRecordKey);
		}
		catch (final RuntimeException ex)
		{
			return false; // cannot be shown to be readable: hidden
		}
	}

	/** The value, or {@link #MASK} when the session may not read the record it came from (null stays null). */
	public static String mask(final Session pSession, final String pDataspace, final String pDataset, final String pTablePath, final String pRecordKey,
		final String pValue)
	{
		return pValue == null || OriginVisibility.canRead(pSession, pDataspace, pDataset, pTablePath, pRecordKey) ? pValue : OriginVisibility.MASK;
	}

	private static boolean privileged(final Session pSession)
	{
		try
		{
			return pSession.isSystemUser() || pSession.isUserInRole(Profile.ADMINISTRATOR);
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/** The EBX decision: the dataspace, then the record, under the session's own permissions. */
	private static final class EbxProbe implements Probe
	{
		@Override
		public boolean canRead(final Session pSession, final String pDataspace, final String pDataset, final String pTablePath, final String pRecordKey)
		{
			final Repository repository = Repository.getDefault();
			final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pDataspace));
			if (home == null || pSession.getPermissions().getHomeAccessPermission(home).isHidden())
			{
				return false;
			}
			final Path tablePath = Path.parse(pTablePath);
			final PrimaryKey key = PrimaryKey.parseString(pRecordKey);
			if (pDataset != null && !pDataset.trim().isEmpty())
			{
				final Adaptation dataset = home.findAdaptationOrNull(AdaptationName.forName(pDataset));
				return dataset != null && this.readable(pSession, dataset, tablePath, key);
			}
			boolean found = false;
			for (final Adaptation dataset : home.findAllRoots())
			{
				if (dataset.getSchemaNode().getNode(tablePath) == null)
				{
					continue;
				}
				final AdaptationTable table = dataset.getTable(tablePath);
				if (table.lookupAdaptationByPrimaryKey(key) == null)
				{
					continue;
				}
				found = true;
				if (!this.readable(pSession, dataset, tablePath, key))
				{
					return false;
				}
			}
			return found;
		}

		private boolean readable(final Session pSession, final Adaptation pDataset, final Path pTablePath, final PrimaryKey pKey)
		{
			if (pDataset.getSchemaNode().getNode(pTablePath) == null)
			{
				return false;
			}
			final Adaptation record = pDataset.getTable(pTablePath).lookupAdaptationByPrimaryKey(pKey);
			return record != null && !pSession.getPermissions().getAdaptationAccessPermission(record).isHidden();
		}
	}
}
