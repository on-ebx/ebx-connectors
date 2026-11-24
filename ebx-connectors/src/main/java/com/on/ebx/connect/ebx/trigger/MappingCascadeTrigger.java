package com.on.ebx.connect.ebx.trigger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.DeclaredLineage;
import com.on.ebx.connect.ebx.ConfigTransfer;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.trigger.AfterCreateOccurrenceContext;
import com.orchestranetworks.schema.trigger.AfterDeleteOccurrenceContext;
import com.orchestranetworks.schema.trigger.AfterModifyOccurrenceContext;
import com.orchestranetworks.schema.trigger.TableTrigger;
import com.orchestranetworks.schema.trigger.TriggerSetupContext;
import com.orchestranetworks.schema.trigger.ValueChange;
import com.orchestranetworks.service.OperationException;

/**
 * Declared on the Mapping table of the Connectors model: when a mapping is deleted, whatever the
 * way (user interface, Data Services, import, service), its rules, inputs, sub-object mappings and
 * outbound states go with it, and the provenance and conflict rows that pointed at it lose their
 * reference instead of becoming invalid foreign keys. Runs after the deletion, in the same
 * transaction; the deleted record is identified from its XPath.
 * <p>
 * Every creation, change and deletion also schedules the mapping's declared lineage
 * ({@link DeclaredLineage}): described to the OpenLineage sinks once the transaction is over, its old
 * job retired when it is renamed, deactivated or deleted; a sub-object mapping describes its root.
 */
public final class MappingCascadeTrigger extends TableTrigger
{
	private static final Pattern ID = Pattern.compile("\\[\\s*\\./id\\s*=\\s*'?(\\d+)'?\\s*\\]");
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
		final Adaptation mapping = pContext.getAdaptationOccurrence();
		this.lineage(pContext.getAdaptationHome().getRepository(), mapping.get(ConnectorsPaths.Mapping.ID), null);
		this.lineage(pContext.getAdaptationHome().getRepository(), mapping.get(ConnectorsPaths.Mapping.PARENT), null);
	}

	@Override
	public void handleAfterModify(final AfterModifyOccurrenceContext pContext)
	{
		final Repository repository = pContext.getAdaptationHome().getRepository();
		final Adaptation mapping = pContext.getAdaptationOccurrence();
		final ValueChange label = pContext.getChanges().getChange(ConnectorsPaths.Mapping.LABEL);
		final Object id = mapping.get(ConnectorsPaths.Mapping.ID);
		this.lineage(repository, id, label == null ? null : com.on.ebx.connect.core.OpenLineageEmitter.jobName(MappingCascadeTrigger.number(id), MappingCascadeTrigger.text(label.getValueBefore())));
		final ValueChange parent = pContext.getChanges().getChange(ConnectorsPaths.Mapping.PARENT);
		if (parent != null)
		{
			this.lineage(repository, parent.getValueBefore(), null); // the root it left sends one collection less
		}
		this.lineage(repository, mapping.get(ConnectorsPaths.Mapping.PARENT), null);
	}

	@Override
	public void handleAfterDelete(final AfterDeleteOccurrenceContext pContext) throws OperationException
	{
		final Matcher m = MappingCascadeTrigger.ID.matcher(String.valueOf(pContext.getDeletedRecordXPathExpression()));
		if ("false".equalsIgnoreCase(this.enabled) || !m.find())
		{
			return;
		}
		final Repository repository = pContext.getAdaptationHome().getRepository();
		try
		{
			final Object parent = pContext.getOccurrenceContext().getValue(ConnectorsPaths.Mapping.PARENT);
			this.lineage(repository, m.group(1), com.on.ebx.connect.core.OpenLineageEmitter.jobName(Integer.parseInt(m.group(1)),
				MappingCascadeTrigger.text(pContext.getOccurrenceContext().getValue(ConnectorsPaths.Mapping.LABEL))));
			this.lineage(repository, parent, null);
		}
		catch (final RuntimeException ex)
		{
			com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] declared lineage not scheduled for deleted mapping " + m.group(1) + ": " + ex);
		}
		final Adaptation dataset = com.on.ebx.connect.core.ConnectorService.get().repository(pContext.getProcedureContext().getAdaptationHome().getRepository()).dataset();
		if (dataset == null)
		{
			return;
		}
		try
		{
			ConfigTransfer.cascade(pContext.getProcedureContext(), dataset, m.group(1));
		}
		catch (final Exception ex)
		{
			throw OperationException.createError("Deleting the elements attached to mapping " + m.group(1) + " failed: " + ex.getMessage(), ex);
		}
	}

	/** Schedules the declared lineage of a mapping (id or foreign key value); never fails the transaction. */
	private void lineage(final Repository pRepository, final Object pMapping, final String pRetiredJobName)
	{
		if ("false".equalsIgnoreCase(this.enabled) || pMapping == null || String.valueOf(pMapping).trim().isEmpty())
		{
			return;
		}
		try
		{
			DeclaredLineage.mappingChanged(pRepository, MappingCascadeTrigger.number(pMapping), pRetiredJobName);
		}
		catch (final Throwable ex)
		{
			com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] declared lineage not scheduled for mapping " + pMapping + ": " + ex);
		}
	}

	private static int number(final Object pValue)
	{
		try
		{
			return Integer.parseInt(String.valueOf(pValue).trim());
		}
		catch (final RuntimeException ex)
		{
			return -1;
		}
	}

	private static String text(final Object pValue)
	{
		return pValue == null ? null : String.valueOf(pValue);
	}
}
