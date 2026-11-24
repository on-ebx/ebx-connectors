package com.on.ebx.connect.ebx;

import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.Constraint;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.InvalidSchemaException;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/**
 * On {@code Mapping/tablePath}: warns when the dataset or the table does not exist in the repository,
 * and, for a sub-object mapping, when the link field or the key fields are not fields of the child table.
 * Warnings only: a mapping written for another environment stays editable.
 */
public final class MappingTableConstraint implements Constraint<String>
{
	@Override
	public void setup(final ConstraintContext pContext)
	{
		// the checks read other fields of the record and other datasets: no static dependency to declare
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return;
		}
		// audit C2-04: a placeholder outside apostrophes in the key predicate is refused (an error, whatever the environment)
		final String refusal = com.on.ebx.connect.core.ValueTemplates.xpathRefusal((String) pContext.getValue(Path.parse("../keyPredicate")));
		if (refusal != null)
		{
			pContext.addError(refusal);
		}
		final String dataspace = (String) pContext.getValue(Path.parse("../dataspace"));
		final String dataset = (String) pContext.getValue(Path.parse("../dataset"));
		final com.orchestranetworks.instance.Repository repository = pContext.getAdaptationInstance().getHome().getRepository();
		if (dataset == null || dataset.trim().isEmpty())
		{
			pContext.addWarning("No dataset: the mapping cannot run until the dataset holding " + pValue + " is set.");
			return;
		}
		if (!ModelCheck.datasetExists(repository, dataspace, dataset))
		{
			pContext.addWarning("Dataset '" + dataset + "' not found" + (dataspace == null || dataspace.trim().isEmpty() ? "" : " in dataspace '" + dataspace + "'")
					+ ": the mapping cannot run in this environment.");
			return;
		}
		final SchemaNode table = ModelCheck.table(repository, dataspace, dataset, pValue);
		if (table == null)
		{
			pContext.addWarning("Table " + pValue + " not found in dataset '" + dataset + "': the mapping cannot run in this environment.");
			return;
		}
		final String link = (String) pContext.getValue(Path.parse("../parentLinkField"));
		if (link != null && !link.trim().isEmpty() && !ModelCheck.hasField(table, link))
		{
			pContext.addWarning("Link field " + link + " is not a field of " + pValue + ".");
		}
		final List<String> missingKeys = ModelCheck.missingFieldList(table, (String) pContext.getValue(Path.parse("../childKeyFields")));
		if (!missingKeys.isEmpty())
		{
			pContext.addWarning("Key field(s) " + missingKeys + " are not fields of " + pValue + ".");
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return "The dataset and the table must exist; for a sub-object mapping, the link and key fields must be fields of the child table.";
	}
}
