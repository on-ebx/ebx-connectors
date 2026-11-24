package com.on.ebx.connect.ebx;

import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.PreviewFeatures;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.Constraint;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.InvalidSchemaException;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/** On {@code MappingInput/expression}: every {@code ${./Group/field}} must be a field of the mapping's table (warnings); {@code matching} on a CDC mapping is an error while that preview function is closed. */
public final class MappingInputConstraint implements Constraint<String>
{
	@Override
	public void setup(final ConstraintContext pContext)
	{
		// reads the parent mapping and another dataset: nothing static to declare
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (pValue != null && "matching".equals(pContext.getValue(Path.parse("../name"))))
		{
			// a preview function, closed by default (audit C3-05): matching around a CDC batch is not accepted on a mapping
			final Adaptation cdc = MappingRulePathConstraint.mappingOf(pContext, (String) pContext.getValue(Path.parse("../mapping")));
			final java.util.Map<String, String> matching = new java.util.HashMap<String, String>();
			matching.put("matching", pValue);
			if (cdc != null)
			{
				try
				{
					PreviewFeatures.requireMappingAllowed(cdc.getString(ConnectorsPaths.Mapping.OPERATION), matching);
				}
				catch (final ConnectorException closed)
				{
					pContext.addError(closed.getMessage());
				}
			}
		}
		if (pValue == null || ModelCheck.fieldsOf(pValue).isEmpty())
		{
			return;
		}
		final Adaptation mapping = MappingRulePathConstraint.mappingOf(pContext, (String) pContext.getValue(Path.parse("../mapping")));
		if (mapping == null)
		{
			return;
		}
		final SchemaNode table = ModelCheck.table(mapping.getHome().getRepository(), mapping.getString(ConnectorsPaths.Mapping.DATASPACE),
			mapping.getString(ConnectorsPaths.Mapping.DATASET), mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH));
		if (table == null)
		{
			return;
		}
		final List<String> missing = ModelCheck.missingFields(table, pValue);
		if (!missing.isEmpty())
		{
			pContext.addWarning("Field(s) " + missing + " are not fields of " + mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH) + ": the input will be empty.");
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return "Every ${./Group/field} placeholder must be a field of the table of the mapping.";
	}
}
