package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.schema.types.dataspace.DataspaceSet;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.DataspaceEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.ui.toolbar.ToolbarLocation;
import com.orchestranetworks.userservice.declaration.ActionDisplaySpec;
import com.orchestranetworks.userservice.declaration.ActivationContextOnDataspace;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * "Manage connector conflicts" on a dataspace: perspective action, dataspace action and workflow
 * user task (inputs {@code dataset}, {@code table}, {@code recordKey} narrow the list; outputs
 * {@code resolvedCount}, {@code remainingCount}).
 */
public final class ConflictManagerServiceDeclaration implements UserServiceDeclaration.OnDataspace
{
	public static final String SERVICE_NAME = "ConflictManager";
	private final ServiceKey serviceKey;

	public ConflictManagerServiceDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, ConflictManagerServiceDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<DataspaceEntitySelection> createUserService()
	{
		return new ConflictManagerUserService<DataspaceEntitySelection>();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsPerspectiveAction(true);
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsWorkflowUserTask(true);
		pContext.addInputParameter(ConflictManagerUserService.PARAM_DATASET, Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.dataset"),
			Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.dataset.help"));
		pContext.addInputParameter(ConflictManagerUserService.PARAM_TABLE, Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.table"),
			Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.table.help"));
		pContext.addInputParameter(ConflictManagerUserService.PARAM_RECORD_KEY, Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.recordKey"),
			Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.param.recordKey.help"));
		pContext.addOutputParameter(ConflictManagerUserService.OUT_RESOLVED, Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.out.resolved"),
			Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.out.resolved.help"));
		pContext.addOutputParameter(ConflictManagerUserService.OUT_REMAINING, Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.out.remaining"),
			Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.out.remaining.help"));
	}

	@Override
	public void defineActivation(final ActivationContextOnDataspace pContext)
	{
		pContext.includeAllDataspaces(DataspaceSet.DataspaceType.BRANCH);
		// shown on the dataspace only: tables and records have their own, narrower entry
		pContext.setDisplayForAllLocations(ActionDisplaySpec.HIDDEN);
		pContext.setDisplayForLocations(ActionDisplaySpec.DISPLAYED, ToolbarLocation.DATASPACE_VIEW_WORKSPACE);
		// the conflicts carry record values and deciding one writes the record: an operator's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.label"));
		pContext.setDescription(Messages.getInfo(ConflictManagerServiceDeclaration.class, "conflicts.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
