package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/** Read-only operational dashboard over the caller's permission-filtered synchronization log view. */
public final class ConnectorOperationsServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName("ConnectorOperations");
	@Override public UserService<TableViewEntitySelection> createUserService() { return new ConnectorOperationsUserService(); }
	@Override public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}
	@Override public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(ConnectorsPaths.SYNC_LOG);
		// the operations screen reads the log of every mapping: at least the viewer role
		pContext.setPermissionRule(com.on.ebx.connect.ebx.ConnectorsAccess.rule(com.on.ebx.connect.ebx.ConnectorsAccess.Right.READ));
	}
	@Override public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ConnectorOperationsServiceDeclaration.class, "operations.label"));
		pContext.setDescription(Messages.getInfo(ConnectorOperationsServiceDeclaration.class, "operations.description"));
	}
	@Override public ServiceKey getServiceKey() { return SERVICE_KEY; }
}
