package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/**
 * Checks a mapping configuration against the real data model: does the target table exist, do the
 * EBX field paths of the rules and of the input expressions resolve in it. Shared by the installer
 * (which drops what does not fit the customer's model) and by the validation constraints of the
 * Mapping, MappingRule and MappingInput tables (which warn the user).
 */
public final class ModelCheck
{
	/** {@code ${./Group/field|...}} placeholders, nested or not: the EBX paths they reference. */
	private static final Pattern FIELD_PLACEHOLDER = Pattern.compile("\\$\\{\\s*(\\./[^|}\\s]+)");

	private ModelCheck()
	{
	}

	/** The table node of {@code pTablePath} in the dataset named {@code pDataset}, or null (dataset or table missing). */
	public static SchemaNode table(final Repository pRepository, final String pDataspace, final String pDataset, final String pTablePath)
	{
		if (pRepository == null || pTablePath == null || pTablePath.trim().isEmpty())
		{
			return null;
		}
		try
		{
			// the MODEL is read, not the data: the copies of a dataset in several dataspaces share it, so the first one found is enough
			final Adaptation dataset = DatasetLocator.locate(pRepository, pDataspace, pDataset, pTablePath.trim()).getFirst();
			if (dataset == null)
			{
				return null;
			}
			final SchemaNode node = dataset.getSchemaNode().getNode(Path.parse(pTablePath.trim()));
			return node != null && node.isTableNode() ? node : null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** True when the dataset exists somewhere in the repository, whatever its tables. */
	public static boolean datasetExists(final Repository pRepository, final String pDataspace, final String pDataset)
	{
		try
		{
			return DatasetLocator.locate(pRepository, pDataspace, pDataset, null).getFirst() != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/** True when {@code pRecordPath} ("./Group/field") is a field of the table; document paths (no "./") are not checked. */
	public static boolean hasField(final SchemaNode pTable, final String pRecordPath)
	{
		if (pTable == null || !ModelCheck.isRecordPath(pRecordPath))
		{
			return true;
		}
		try
		{
			return pTable.getTableOccurrenceRootNode().getNode(Path.parse(pRecordPath.trim())) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	public static boolean isRecordPath(final String pPath)
	{
		return pPath != null && pPath.trim().startsWith("./");
	}

	/** EBX field paths referenced by an input expression. */
	public static List<String> fieldsOf(final String pExpression)
	{
		final List<String> out = new ArrayList<String>();
		if (pExpression == null)
		{
			return out;
		}
		final Matcher m = ModelCheck.FIELD_PLACEHOLDER.matcher(pExpression);
		while (m.find())
		{
			final String path = m.group(1).trim();
			if (!out.contains(path))
			{
				out.add(path);
			}
		}
		return out;
	}

	/** The referenced fields that the table does not have. */
	public static List<String> missingFields(final SchemaNode pTable, final String pExpression)
	{
		final List<String> missing = new ArrayList<String>();
		for (final String path : ModelCheck.fieldsOf(pExpression))
		{
			if (!ModelCheck.hasField(pTable, path))
			{
				missing.add(path);
			}
		}
		return missing;
	}

	/** Comma-separated record paths ("./a,./b"): those the table does not have. */
	public static List<String> missingFieldList(final SchemaNode pTable, final String pPaths)
	{
		final List<String> missing = new ArrayList<String>();
		if (pPaths == null)
		{
			return missing;
		}
		for (final String raw : pPaths.split(","))
		{
			final String path = raw.trim();
			if (!path.isEmpty() && !ModelCheck.hasField(pTable, path))
			{
				missing.add(path);
			}
		}
		return missing;
	}
}
