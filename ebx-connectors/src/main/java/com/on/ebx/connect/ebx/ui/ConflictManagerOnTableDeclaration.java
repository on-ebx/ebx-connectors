package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Manage connector conflicts" on a table view: the conflicts of this table. */
public final class ConflictManagerOnTableDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ConflictManagerTable";
	private final ServiceKey serviceKey;

	public ConflictManagerOnTableDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, ConflictManagerOnTableDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ConflictManagerUserService<TableViewEntitySelection>();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeAllDatasets();
		pContext.includeAllSchemaNodes();
		// the conflicts carry record values and deciding one writes the record: an operator's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(ConflictManagerOnTableDeclaration.class, "conflicts.label.table"));
		pContext.setDescription(Messages.getInfo(ConflictManagerOnTableDeclaration.class, "conflicts.description.table"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
