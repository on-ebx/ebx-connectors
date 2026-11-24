package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Delete the mapping and everything attached", on the Mapping table only (table). */
public final class DeleteMappingOnTableDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "DeleteMappingTable";
	private final ServiceKey serviceKey;

	public DeleteMappingOnTableDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, DeleteMappingOnTableDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new DeleteMappingUserService<TableViewEntitySelection>();
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
		pContext.includeSchemaNodesMatching(ConnectorsPaths.MAPPING);
		// deleting a mapping changes the configuration: a designer's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.MANAGE));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(DeleteMappingOnTableDeclaration.class, "deleteMapping.label.table"));
		pContext.setDescription(Messages.getInfo(DeleteMappingOnTableDeclaration.class, "deleteMapping.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
