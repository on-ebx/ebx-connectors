package com.on.ebx.connect.ebx.rest;

import jakarta.ws.rs.ApplicationPath;

import com.orchestranetworks.rest.ApplicationConfigurator;
import com.orchestranetworks.rest.RESTApplicationAbstract;

/**
 * REST API of the connectors module, on the EBX REST Toolkit: authentication is the standard
 * EBX one (HTTP Basic, token, SSO) and the caller's session runs the mappings. Registered by
 * {@code ConnectorsModuleRegistration.handleContextInitialized} through
 * {@code ModuleInitializedContext.registerRESTApplication}; the
 * toolkit maps the application on the path below, under the module context:
 * {@code /ebx-connectors/rest/v1/...}.
 */
@ApplicationPath("/rest")
public final class ConnectorsRestApplication extends RESTApplicationAbstract
{
	public ConnectorsRestApplication()
	{
		super(new java.util.function.Consumer<ApplicationConfigurator>()
		{
			@Override
			public void accept(final ApplicationConfigurator pConfigurator)
			{
				pConfigurator.register(ConnectorsResource.class);
				pConfigurator.register(McpEndpoint.class);
				// refuses a state-changing request that could be riding the browser's session cookie from another site
				pConfigurator.register(CsrfGuard.class);
				// one line in the kernel log for every route that runs or changes something
				pConfigurator.register(RestAudit.class);
			}
		});
	}
}
