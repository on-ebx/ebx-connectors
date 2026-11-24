package com.on.ebx.connect.ebx;

import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.Constraint;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.InvalidSchemaException;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/**
 * On {@code MappingRule/sourcePath} and {@code MappingRule/targetPath}: the EBX side of the rule
 * (target for an inbound mapping, source for an outbound one) must be a field of the mapping's table.
 * Document paths are not checked. Warnings only.
 */
public final class MappingRulePathConstraint implements Constraint<String>
{
	private boolean onTarget;

	@Override
	public void setup(final ConstraintContext pContext)
	{
		this.onTarget = pContext.getSchemaNode().getPathInAdaptation().format().endsWith("targetPath");
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (!ModelCheck.isRecordPath(pValue))
		{
			return;
		}
		final Adaptation mapping = MappingRulePathConstraint.mappingOf(pContext, (String) pContext.getValue(Path.parse("../mapping")));
		if (mapping == null)
		{
			return;
		}
		final boolean outbound = "OUTBOUND".equals(mapping.getString(ConnectorsPaths.Mapping.DIRECTION));
		if (outbound == this.onTarget)
		{
			return; // this side is the document, not EBX
		}
		final SchemaNode table = ModelCheck.table(mapping.getHome().getRepository(), mapping.getString(ConnectorsPaths.Mapping.DATASPACE),
			mapping.getString(ConnectorsPaths.Mapping.DATASET), mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH));
		if (table == null)
		{
			return; // reported on the mapping itself
		}
		if (!ModelCheck.hasField(table, pValue))
		{
			pContext.addWarning(pValue.trim() + " is not a field of " + mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH) + ": this rule will fail.");
		}
	}

	static Adaptation mappingOf(final ValueContextForValidation pContext, final String pMappingId)
	{
		if (pMappingId == null || pMappingId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return pContext.getAdaptationInstance().getTable(ConnectorsPaths.MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMappingId.trim()));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return "An EBX path (./Group/field) must be a field of the table of the mapping.";
	}
}
