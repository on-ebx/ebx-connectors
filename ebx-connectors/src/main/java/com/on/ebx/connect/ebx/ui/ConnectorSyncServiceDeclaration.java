package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * "Synchronise with an external application", on the table view of every table: the service
 * itself decides whether a mapping exists for the current table. Registered at module level
 * (see ModuleRegistration), nothing to declare in the data models.
 */
public final class ConnectorSyncServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ConnectorSync";
	/** Optional mapping selected by a connector flow card. */
	public static final String PARAM_MAPPING = "mapping";
	/** Optional run-time inputs of a feed, {@code name=value;name=value}, read by the mapping as {@code ${param.name}}. */
	public static final String PARAM_INPUTS = "inputs";
	/** Optional initial value of the simulation box ({@code false} to run for real); a click on Run is still needed. */
	public static final String PARAM_DRY_RUN = "dryRun";
	private final ServiceKey serviceKey;

	public ConnectorSyncServiceDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, ConnectorSyncServiceDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ConnectorSyncUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsPerspectiveAction(true);
		pContext.setAvailableAsToolbarAction(true);
		// EBX retains URL values only when the service declares them.  Registering
		// this optional input keeps a card's selected mapping through the native
		// service route while the service still validates it against the table.
		pContext.addInputParameter(PARAM_MAPPING, Messages.getInfo(ConnectorSyncServiceDeclaration.class, "sync.param.mapping"),
			Messages.getInfo(ConnectorSyncServiceDeclaration.class, "sync.param.mapping.help"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeAllDatasets();
		pContext.includeAllSchemaNodes();
		// running a mapping is an operator's action, offered only where a mapping exists for the table (audit C2-19)
		pContext.setPermissionRule(new SyncActivation());
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(ConnectorSyncServiceDeclaration.class, "service.label"));
		pContext.setDescription(Messages.getInfo(ConnectorSyncServiceDeclaration.class, "service.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
