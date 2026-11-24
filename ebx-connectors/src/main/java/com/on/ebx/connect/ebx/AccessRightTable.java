package com.on.ebx.connect.ebx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Profile;

/**
 * The rights an administrator gave in the table {@code AccessRight} of the Connectors dataset, as
 * {@link ConnectorsAccess} reads them: role of the directory -> right. The table is small and is asked on every
 * call and every menu, so its content is kept for {@link #TTL_MILLIS}: a change applies within that delay.
 * <p>
 * Anything that prevents the read (dataset not created yet, model loaded before the table existed, a row that
 * cannot be understood) gives no right: the administrator and the default roles still pass, nobody else does.
 */
final class AccessRightTable implements ConnectorsAccess.Grants
{
	static final long TTL_MILLIS = 5000L;
	private volatile Map<Profile, ConnectorsAccess.Right> cached = Collections.<Profile, ConnectorsAccess.Right> emptyMap();
	private volatile long readAt;

	@Override
	public Map<Profile, ConnectorsAccess.Right> byProfile()
	{
		final long now = System.currentTimeMillis();
		if (this.readAt != 0L && now - this.readAt < AccessRightTable.TTL_MILLIS)
		{
			return this.cached;
		}
		Map<Profile, ConnectorsAccess.Right> read;
		try
		{
			read = AccessRightTable.read(Repository.getDefault());
		}
		catch (final RuntimeException ex)
		{
			read = Collections.<Profile, ConnectorsAccess.Right> emptyMap();
		}
		catch (final LinkageError ex)
		{
			read = Collections.<Profile, ConnectorsAccess.Right> emptyMap();
		}
		this.cached = read;
		this.readAt = now;
		return read;
	}

	private static Map<Profile, ConnectorsAccess.Right> read(final Repository pRepository)
	{
		final AdaptationHome home = pRepository == null ? null : pRepository.lookupHome(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET));
		if (dataset == null)
		{
			return Collections.<Profile, ConnectorsAccess.Right> emptyMap();
		}
		final AdaptationTable table = dataset.getTable(ConnectorsPaths.ACCESS_RIGHT);
		final Map<Profile, ConnectorsAccess.Right> rights = new LinkedHashMap<Profile, ConnectorsAccess.Right>();
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Profile profile = AccessRightTable.profile(row.getString(ConnectorsPaths.AccessRight.PROFILE));
				final ConnectorsAccess.Right right = ConnectorsAccess.Right.named(row.getString(ConnectorsPaths.AccessRight.RIGHT));
				if (profile != null && right != null)
				{
					rights.put(profile, right);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return Collections.unmodifiableMap(rights);
	}

	/** The profile a row names when it may receive a right (a role or "Everyone"), null otherwise. */
	private static Profile profile(final String pFormatted)
	{
		if (pFormatted == null || pFormatted.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final Profile profile = Profile.parse(pFormatted.trim());
			return ConnectorsRoleEnumeration.eligible(profile) ? profile : null;
		}
		catch (final IllegalArgumentException ex)
		{
			return null;
		}
	}
}
