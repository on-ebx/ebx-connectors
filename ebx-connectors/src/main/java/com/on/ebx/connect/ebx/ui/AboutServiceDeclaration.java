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

/**
 * The "About" page of the module: the version that runs, and the documentation of that version. A dataset service of the
 * Connectors model, registered by {@link com.on.ebx.connect.ebx.ConnectorsSchemaExtensions} like the release notes, and
 * offered as perspective action and toolbar action (the "Data connector" perspective carries an About entry).
 */
public final class AboutServiceDeclaration implements UserServiceDeclaration.OnDataset
{
	public static final String SERVICE_NAME = "About";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override public UserService<DatasetEntitySelection> createUserService() { return new AboutUserService(); }

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnDataset pContext)
	{
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
		return ConnectorsPaths.MODEL_PATH.equals(pDataset.getSchemaLocation().getPathInModule());
	}

	@Override public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(AboutServiceDeclaration.class, "about.label"));
		pContext.setDescription(Messages.getInfo(AboutServiceDeclaration.class, "about.description"));
	}

	@Override public ServiceKey getServiceKey() { return SERVICE_KEY; }
}
