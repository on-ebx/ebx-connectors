package com.on.ebx.connect.ebx.trigger;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.DeclaredLineage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.trigger.AfterModifyOccurrenceContext;
import com.orchestranetworks.schema.trigger.TableTrigger;
import com.orchestranetworks.schema.trigger.TriggerSetupContext;

/**
 * Declared on the Connector table of the Connectors model: when a connector's type, base URL or
 * activity changes, whatever the way (user interface, Data Services, OpenAPI import), the declared
 * lineage of its mappings is sent again ({@link DeclaredLineage#connectorChanged}) — their datasets
 * carry the connector's OpenLineage namespace, derived from those fields. Never fails the transaction.
 * The configuration import writes without triggers and calls the same method itself.
 */
public final class ConnectorLineageTrigger extends TableTrigger
{
	private static final Path[] WATCHED = { ConnectorsPaths.Connector.TYPE, ConnectorsPaths.Connector.BASE_URL, ConnectorsPaths.Connector.ACTIVE };
	/** Declared in the model ({@code <enabled>true</enabled>}): a trigger element must carry a property, EBX refuses a void one. */
	private String enabled = "true";

	public void setEnabled(final String pEnabled)
	{
		this.enabled = pEnabled;
	}

	@Override
	public void setup(final TriggerSetupContext pContext)
	{
		// nothing to configure
	}

	@Override
	public void handleAfterModify(final AfterModifyOccurrenceContext pContext)
	{
		if ("false".equalsIgnoreCase(this.enabled))
		{
			return;
		}
		boolean changed = false;
		for (final Path path : ConnectorLineageTrigger.WATCHED)
		{
			changed = changed || pContext.getChanges().getChange(path) != null;
		}
		if (!changed)
		{
			return;
		}
		try
		{
			DeclaredLineage.connectorChanged(pContext.getAdaptationHome().getRepository(), String.valueOf(pContext.getAdaptationOccurrence().get(ConnectorsPaths.Connector.ID)));
		}
		catch (final Throwable ex)
		{
			ConnectorService.log().warn("[connectors] declared lineage not scheduled after a connector change: " + ex);
		}
	}
}
