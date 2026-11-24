package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Manage connector conflicts" on a record: the conflicts of this record only. */
public final class ConflictManagerOnRecordDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "ConflictManagerRecord";
	private final ServiceKey serviceKey;

	public ConflictManagerOnRecordDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, ConflictManagerOnRecordDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new ConflictManagerUserService<RecordEntitySelection>();
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
		pContext.includeAllSchemaNodes();
		// the conflicts carry record values and deciding one writes the record: an operator's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(ConflictManagerOnRecordDeclaration.class, "conflicts.label.record"));
		pContext.setDescription(Messages.getInfo(ConflictManagerOnRecordDeclaration.class, "conflicts.description.record"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
