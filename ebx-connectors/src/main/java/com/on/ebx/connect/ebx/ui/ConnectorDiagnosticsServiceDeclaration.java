package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/** Non-destructive diagnostics and capability preview for a configured connector. */
public final class ConnectorDiagnosticsServiceDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName("ConnectorDiagnostics");

	@Override public UserService<RecordEntitySelection> createUserService() { return new ConnectorDiagnosticsUserService(); }

	@Override public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
	}

	@Override public void defineActivation(final ActivationContextOnRecord pContext)
	{
		pContext.includeSchemaNodesMatching(ConnectorsPaths.CONNECTOR);
		pContext.setPermissionRule(new ServicePermissionRule<RecordEntitySelection>()
		{
			@Override public UserServicePermission getPermission(
				final ServicePermissionRuleContext<RecordEntitySelection> pPermissionContext)
			{
				final RecordEntitySelection selection = pPermissionContext.getEntitySelection();
				if (selection == null || selection.getRecord() == null) { return UserServicePermission.getDisabled(); }
				// tests, connects and previews with the stored credentials: a designer's action
				if (!com.on.ebx.connect.ebx.ConnectorsAccess.has(pPermissionContext.getSession(), com.on.ebx.connect.ebx.ConnectorsAccess.Right.MANAGE))
				{
					return UserServicePermission.getDisabled();
				}
				return ConnectorDiagnosticsUserService.isReadable(
					pPermissionContext.getSession(), selection.getRecord())
					? UserServicePermission.getEnabled() : UserServicePermission.getDisabled();
			}
		});
	}

	@Override public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ConnectorDiagnosticsServiceDeclaration.class, "diagnostics.label"));
		pContext.setDescription(Messages.getInfo(ConnectorDiagnosticsServiceDeclaration.class, "diagnostics.description"));
	}

	@Override public ServiceKey getServiceKey() { return SERVICE_KEY; }
}
