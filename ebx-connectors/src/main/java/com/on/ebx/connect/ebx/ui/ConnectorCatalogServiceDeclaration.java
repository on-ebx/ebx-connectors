package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * Polished, searchable and category-grouped catalog for the Connector table. Each row of that table is a
 * configured connection (a connector type plus its properties and credentials), hence the "Connection
 * catalog" label; the connector <em>types</em> are listed in the reference guide, not here.
 */
public final class ConnectorCatalogServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ConnectorCatalog";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ConnectorCatalogUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(ConnectorsPaths.CONNECTOR);
		// the catalog lists every connection: at least the viewer role
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.READ));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ConnectorCatalogServiceDeclaration.class, "catalog.label"));
		pContext.setDescription(Messages.getInfo(ConnectorCatalogServiceDeclaration.class, "catalog.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return SERVICE_KEY;
	}
}
