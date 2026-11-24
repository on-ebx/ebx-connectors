package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** "Import an OpenAPI specification", on the table view of the Connector table. */
public final class OpenApiImportServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "OpenApiImport";
	private final ServiceKey serviceKey;

	public OpenApiImportServiceDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, OpenApiImportServiceDeclaration.SERVICE_NAME);
	}

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new OpenApiImportUserService();
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
		pContext.includeSchemaNodesMatching(ConnectorsPaths.CONNECTOR);
		// creates a connection and fetches the URL it is given: a designer's action
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.MANAGE));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.connect.ebx.ConnectorsModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(OpenApiImportServiceDeclaration.class, "openapi.label"));
		pContext.setDescription(Messages.getInfo(OpenApiImportServiceDeclaration.class, "openapi.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
