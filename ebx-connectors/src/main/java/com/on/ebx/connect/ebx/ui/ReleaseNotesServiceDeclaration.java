package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.DatasetEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnDataset;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;
import com.orchestranetworks.userservice.permission.ServiceActivationRuleForDataset;
import com.orchestranetworks.userservice.permission.ServiceActivationRuleForDatasetContext;

/** Dataset action exposing published release notes for the Connectors data model. */
public final class ReleaseNotesServiceDeclaration implements UserServiceDeclaration.OnDataset
{
	public static final String SERVICE_NAME = "ReleaseNotes";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override public UserService<DatasetEntitySelection> createUserService() { return new ReleaseNotesUserService(); }

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnDataset pContext)
	{
		// Schema extensions are already model-local. This explicit rule is a second boundary for
		// web-component/perspective launches and makes it impossible to surface as a table action.
		pContext.includeAllDatasets();
		pContext.setActivationRule(new ServiceActivationRuleForDataset()
		{
			@Override public boolean isAvailableFor(final ServiceActivationRuleForDatasetContext pRuleContext)
			{
				return isConnectorsDataset(pRuleContext.getDataset());
			}
		});
	}

	static boolean isConnectorsDataset(final Adaptation pDataset)
	{
		if (pDataset == null || pDataset.getSchemaLocation() == null) { return false; }
		final String path = pDataset.getSchemaLocation().getPathInModule();
		return ConnectorsPaths.MODEL_PATH.equals(path);
	}

	@Override public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ReleaseNotesServiceDeclaration.class, "releaseNotes.label"));
		pContext.setDescription(Messages.getInfo(ReleaseNotesServiceDeclaration.class, "releaseNotes.description"));
	}

	@Override public ServiceKey getServiceKey() { return SERVICE_KEY; }
}
