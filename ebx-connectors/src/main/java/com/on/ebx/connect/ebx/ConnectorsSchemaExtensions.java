package com.on.ebx.connect.ebx;

import com.on.ebx.connect.ebx.ui.MappingDesignerServiceDeclaration;
import com.orchestranetworks.schema.SchemaExtensions;
import com.orchestranetworks.schema.SchemaExtensionsContext;

/**
 * Registered by Connectors.xsd: the mapping designer lives on the Mapping table of the
 * configuration dataset. Declared on the model rather than on the module so that a change to the
 * XSD (development mode) picks it up without a Tomcat restart.
 */
public final class ConnectorsSchemaExtensions implements SchemaExtensions
{
	@Override
	public void defineExtensions(final SchemaExtensionsContext pContext)
	{
		pContext.registerUserService(new MappingDesignerServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.ConnectorCatalogServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.ConnectorDiagnosticsServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.ConnectorOperationsServiceDeclaration());
		// EBX normally inherits dataset services into every table's Actions menu. Release notes are
		// intentionally a dataset-level application, so explicitly suppress that inherited exposure
		// on every table while keeping the service enabled on the Connectors dataset itself. Declare
		// the node permission overrides before registering the service: EBX snapshots inherited
		// permissions as the declaration is registered.
		disableReleaseNotesOnTables(pContext, pContext.getSchemaNode());
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.ReleaseNotesServiceDeclaration());
		// the About page (version and documentation) is a dataset service too: same suppression on tables, declared before registering
		disableServiceOnTables(pContext, pContext.getSchemaNode(), com.on.ebx.connect.ebx.ui.AboutServiceDeclaration.SERVICE_KEY);
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.AboutServiceDeclaration());
		// favorites of the connector explorer: each user sees their own, nobody edits them by hand
		pContext.setAccessRuleOnOccurrence(com.on.ebx.connect.core.ConnectorsPaths.FAVORITE, new FavoriteAccessRule());
		// who may use the connectors is decided by the EBX administrator alone: a designer, who may change every
		// other configuration table, must not be able to give rights (to others or back to a removed colleague)
		final com.orchestranetworks.service.AccessRule administratorOnly = new com.orchestranetworks.service.AccessRule()
		{
			@Override
			public com.orchestranetworks.service.AccessPermission getPermission(final com.onwbp.adaptation.Adaptation pAdaptation,
				final com.orchestranetworks.service.Session pSession, final com.orchestranetworks.schema.SchemaNode pNode)
			{
				return pSession.isUserInRole(com.orchestranetworks.service.Profile.ADMINISTRATOR) ? com.orchestranetworks.service.AccessPermission.getReadWrite()
						: com.orchestranetworks.service.AccessPermission.getReadOnly(
							com.on.ebx.connect.ebx.ui.Messages.getInfo(com.on.ebx.connect.ebx.ui.Messages.class, "access.table.adminOnly"));
			}
		};
		pContext.setAccessRuleOnNodeAndAllDescendants(com.on.ebx.connect.core.ConnectorsPaths.ACCESS_RIGHT, true, administratorOnly);
		pContext.setAccessRuleOnOccurrence(com.on.ebx.connect.core.ConnectorsPaths.ACCESS_RIGHT, administratorOnly);
		// the module's settings (wave 12, a group of dataset-level fields): hidden without a Connectors right, read with one,
		// changed by an EBX administrator only - the default language-model connector decides where every sibling module's
		// prompts go, a designer must not move them
		pContext.setAccessRuleOnNodeAndAllDescendants(com.on.ebx.connect.core.ConnectorsPaths.SETTINGS, true,
			new ConnectorsTableAccessRule(ConnectorsTableAccessRule.SETTINGS));
		// audit C-01 (tables): every table of the dataset follows the Connectors rights, whatever the EBX permissions say -
		// hidden without a right, read only below the right that writes the table, creation closed field by field (see
		// ConnectorsTableAccessRule for the decision of each table). Declared AFTER the Favorite and AccessRight rules above:
		// those two tables keep their own record rule and only receive the creation rule from here.
		ConnectorsTableAccessRule.register(pContext);
		// the value of a credential property is hidden from whoever does not manage connectors (audit C-05). A node rule,
		// combined with the record rule of the table: EBX applies the most restrictive of the two. Since audit C-01 (tables)
		// only MANAGE edits the configuration tables, whether a designer role property is set or not.
		pContext.setAccessRuleOnNode(ConnectorPropertyValueAccessRule.VALUE, new ConnectorPropertyValueAccessRule(true));
	}

	private static void disableReleaseNotesOnTables(final SchemaExtensionsContext pContext,
		final com.orchestranetworks.schema.SchemaNode pNode)
	{
		if (pNode.isTableNode())
		{
			pContext.setServicePermissionRuleOnNodeAndAllDescendants(pNode.getPathInSchema(),
				// Schema-registered services use the data-model ServiceKey returned by the
				// declaration. A module-qualified key identifies a different service and leaves
				// EBX's inherited dataset action enabled on table and record contexts.
				com.on.ebx.connect.ebx.ui.ReleaseNotesServiceDeclaration.SERVICE_KEY,
				com.orchestranetworks.userservice.permission.ServicePermissionRule.ALWAYS_DISABLED);
			return;
		}
		for (final com.orchestranetworks.schema.SchemaNode child : pNode.getNodeChildren())
		{
			disableReleaseNotesOnTables(pContext, child);
		}
	}

	private static void disableServiceOnTables(final SchemaExtensionsContext pContext, final com.orchestranetworks.schema.SchemaNode pNode,
		final com.orchestranetworks.service.ServiceKey pKey)
	{
		if (pNode.isTableNode())
		{
			pContext.setServicePermissionRuleOnNodeAndAllDescendants(pNode.getPathInSchema(), pKey,
				com.orchestranetworks.userservice.permission.ServicePermissionRule.ALWAYS_DISABLED);
			return;
		}
		for (final com.orchestranetworks.schema.SchemaNode child : pNode.getNodeChildren())
		{
			disableServiceOnTables(pContext, child, pKey);
		}
	}
}
