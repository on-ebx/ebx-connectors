package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/**
 * Where "Synchronise with an external application" is offered (audit C2-19): to a session that holds the RUN right, on a table
 * for which at least one active mapping exists in that dataspace and dataset. On any other table the action opened an empty
 * screen. The mappings are read once per {@link #TTL_MILLIS} and shared by every table view, not once per toolbar rendering.
 */
final class SyncActivation implements ServicePermissionRule<TableViewEntitySelection>
{
	/** How long the list of mappings read for the toolbars is kept: a new mapping shows its action within this delay. */
	static final long TTL_MILLIS = 15000L;

	private static final Object LOCK = new Object();
	private static List<MappingDefinition> cached;
	private static long cachedAt;

	@Override
	public UserServicePermission getPermission(final ServicePermissionRuleContext<TableViewEntitySelection> pContext)
	{
		final Session session = pContext.getSession();
		if (!ConnectorsAccess.has(session, ConnectorsAccess.Right.RUN))
		{
			return UserServicePermission.getDisabled();
		}
		final TableViewEntitySelection selection = pContext.getEntitySelection();
		final List<MappingDefinition> mappings;
		try
		{
			mappings = SyncActivation.mappings(selection.getDataset().getHome().getRepository());
		}
		catch (final RuntimeException ex)
		{
			// the mappings cannot be read: the action stays offered, and the screen it opens says why it cannot list them
			ConnectorService.logFailure("synchronisation action could not list the mappings", null, ex);
			return UserServicePermission.getEnabled();
		}
		final Path table = selection.getTable().getTablePath();
		return SyncActivation.hasMapping(mappings, selection.getDataset().getHome().getKey().getName(),
			selection.getDataset().getAdaptationName().getStringName(), table.format()) ? UserServicePermission.getEnabled() : UserServicePermission.getDisabled();
	}

	/** True when an active, top-level mapping applies to the table of that dataspace and dataset (a child mapping runs with its parent). */
	static boolean hasMapping(final List<MappingDefinition> pAll, final String pDataspace, final String pDataset, final String pTablePath)
	{
		for (final MappingDefinition mapping : pAll)
		{
			if (mapping.isActive() && !mapping.isChild() && mapping.appliesTo(pDataspace, pDataset, pTablePath))
			{
				return true;
			}
		}
		return false;
	}

	private static List<MappingDefinition> mappings(final Repository pRepository)
	{
		synchronized (SyncActivation.LOCK)
		{
			final long now = System.currentTimeMillis();
			if (SyncActivation.cached == null || now - SyncActivation.cachedAt > SyncActivation.TTL_MILLIS || now < SyncActivation.cachedAt)
			{
				SyncActivation.cached = new ArrayList<MappingDefinition>(ConnectorService.get().repository(pRepository).mappings());
				SyncActivation.cachedAt = now;
			}
			return SyncActivation.cached;
		}
	}
}
