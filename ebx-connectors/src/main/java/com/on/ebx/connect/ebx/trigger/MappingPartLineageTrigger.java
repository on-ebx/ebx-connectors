package com.on.ebx.connect.ebx.trigger;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.DeclaredLineage;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.trigger.AfterCreateOccurrenceContext;
import com.orchestranetworks.schema.trigger.AfterDeleteOccurrenceContext;
import com.orchestranetworks.schema.trigger.AfterModifyOccurrenceContext;
import com.orchestranetworks.schema.trigger.TableTrigger;
import com.orchestranetworks.schema.trigger.TriggerSetupContext;
import com.orchestranetworks.schema.trigger.ValueChange;

/**
 * Declared on the {@code MappingInput} and {@code MappingRule} tables of the Connectors model: an
 * input or a rule added, changed or removed changes what its mapping sends, so the mapping's
 * declared lineage is described again once the transaction is over ({@link DeclaredLineage}). Never
 * fails the transaction.
 */
public final class MappingPartLineageTrigger extends TableTrigger
{
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
	public void handleAfterCreate(final AfterCreateOccurrenceContext pContext)
	{
		this.changed(pContext.getAdaptationHome().getRepository(), pContext.getAdaptationOccurrence().get(ConnectorsPaths.MappingRule.MAPPING));
	}

	@Override
	public void handleAfterModify(final AfterModifyOccurrenceContext pContext)
	{
		final Repository repository = pContext.getAdaptationHome().getRepository();
		final ValueChange moved = pContext.getChanges().getChange(ConnectorsPaths.MappingRule.MAPPING);
		if (moved != null)
		{
			this.changed(repository, moved.getValueBefore());
		}
		this.changed(repository, pContext.getAdaptationOccurrence().get(ConnectorsPaths.MappingRule.MAPPING));
	}

	@Override
	public void handleAfterDelete(final AfterDeleteOccurrenceContext pContext)
	{
		this.changed(pContext.getAdaptationHome().getRepository(), pContext.getOccurrenceContext().getValue(ConnectorsPaths.MappingRule.MAPPING));
	}

	private void changed(final Repository pRepository, final Object pMapping)
	{
		if ("false".equalsIgnoreCase(this.enabled) || pMapping == null)
		{
			return;
		}
		try
		{
			DeclaredLineage.mappingChanged(pRepository, Integer.parseInt(String.valueOf(pMapping).trim()), null);
		}
		catch (final Throwable ex)
		{
			ConnectorService.log().warn("[connectors] declared lineage not scheduled for mapping " + pMapping + ": " + ex);
		}
	}
}
