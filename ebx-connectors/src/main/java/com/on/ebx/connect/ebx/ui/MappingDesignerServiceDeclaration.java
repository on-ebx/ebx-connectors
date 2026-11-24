package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Design the mapping", on a record of /root/Mapping in the Connectors dataset. */
public final class MappingDesignerServiceDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "MappingDesigner";
	private final ServiceKey serviceKey;

	/**
	 * The service is registered by {@code ConnectorsSchemaExtensions}, so it belongs to the data
	 * model and not to the module: its key carries no module part. A {@code module@service} key
	 * would be rejected here anyway, because EBX checks keys against
	 * {@code ([-\d\p{L}:_.]*)@?(%?[-\d\p{L}:_. /]+)} and the module of this project is named
	 * "EBX FastTrack - TIBCO EBX Presales environment", with spaces.
	 */
	public MappingDesignerServiceDeclaration()
	{
		this.serviceKey = ServiceKey.forName(MappingDesignerServiceDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new MappingDesignerUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnRecord pContext)
	{
		pContext.includeSchemaNodesMatching(ConnectorsPaths.MAPPING);
		// the designer edits a mapping and samples the remote system: a designer's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.MANAGE));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(MappingDesignerServiceDeclaration.class, "designer.label"));
		pContext.setDescription(Messages.getInfo(MappingDesignerServiceDeclaration.class, "designer.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
