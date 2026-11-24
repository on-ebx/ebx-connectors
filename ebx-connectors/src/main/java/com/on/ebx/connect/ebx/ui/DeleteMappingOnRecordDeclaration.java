package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Delete the mapping and everything attached", on the Mapping table only (record). */
public final class DeleteMappingOnRecordDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "DeleteMappingRecord";
	private final ServiceKey serviceKey;

	public DeleteMappingOnRecordDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, DeleteMappingOnRecordDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new DeleteMappingUserService<RecordEntitySelection>();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnRecord pContext)
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
		pContext.setLabel(Messages.getInfo(DeleteMappingOnRecordDeclaration.class, "deleteMapping.label.record"));
		pContext.setDescription(Messages.getInfo(DeleteMappingOnRecordDeclaration.class, "deleteMapping.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
