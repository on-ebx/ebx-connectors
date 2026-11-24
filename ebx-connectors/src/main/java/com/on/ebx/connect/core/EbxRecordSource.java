package com.on.ebx.connect.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.ebx.DatasetLocator;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.TablePublisher.Kind;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.schema.info.SchemaFacetMaxLength;
import com.orchestranetworks.service.Session;

/**
 * The real {@link ProductPublisher.RecordSource}: resolves each mapping's table by schema (for the
 * column kinds and the primary key), and iterates its records to build outbound documents through
 * {@link MappingEngine#buildOutbound}. {@code ProductPublisherTest} uses a fake instead, so none of
 * this class is exercised offline.
 */
final class EbxRecordSource implements ProductPublisher.RecordSource
{
	private final Session session;
	private final Repository repository;
	private final String dataspace;
	private final Map<Integer, Adaptation> datasets = new HashMap<Integer, Adaptation>();
	private final ColumnMeaning meaning;

	EbxRecordSource(final Session pSession, final Repository pRepository, final String pDataspace)
	{
		this.session = pSession;
		this.repository = pRepository;
		this.dataspace = pDataspace;
		this.meaning = new ColumnMeaning(pRepository);
	}

	@Override
	public com.on.ebx.connect.spi.TablePublisher.ColumnContext contextOf(final MappingDefinition pMapping, final MappingRule pRule, final String pEbxTable)
	{
		SchemaNode node = null;
		try
		{
			node = this.sourceNode(pMapping, pRule);
		}
		catch (final RuntimeException ex)
		{
			node = null; // a path the model does not have: the catalog may still know the column
		}
		try
		{
			return this.meaning.of(node, pRule.getSourcePath(), pEbxTable);
		}
		catch (final RuntimeException ex)
		{
			return null; // meaning is a bonus: it never fails a publication
		}
	}

	private Adaptation datasetOf(final MappingDefinition pMapping)
	{
		final Integer key = Integer.valueOf(pMapping.getId());
		Adaptation dataset = this.datasets.get(key);
		if (dataset == null)
		{
			final DatasetLocator.Location located = DatasetLocator.locate(this.repository, this.dataspace, pMapping.getDataset(), pMapping.getTablePath());
			dataset = located.getDataset();
			if (dataset == null)
			{
				throw new IllegalStateException("mapping " + pMapping.getId() + ": no dataset serves table " + pMapping.getTablePath() + " - "
					+ located.failure(this.dataspace, pMapping.getDataset(), pMapping.getTablePath()));
			}
			this.datasets.put(key, dataset);
		}
		return dataset;
	}

	private SchemaNode tableNode(final MappingDefinition pMapping)
	{
		return this.datasetOf(pMapping).getTable(Path.parse(pMapping.getTablePath())).getTableOccurrenceRootNode();
	}

	private SchemaNode sourceNode(final MappingDefinition pMapping, final MappingRule pRule)
	{
		return this.tableNode(pMapping).getNode(Path.parse(MappingEngine.normalise(pRule.getSourcePath())));
	}

	@Override
	public Kind kindOf(final MappingDefinition pMapping, final MappingRule pRule)
	{
		final SchemaNode node = this.sourceNode(pMapping, pRule);
		if (node == null)
		{
			return Kind.STRING;
		}
		if (node.getFacetOnTableReference() != null)
		{
			return Kind.STRING; // a foreign key is a record: publish its formatted key as text
		}
		if (node.getMaxOccurs() != 1)
		{
			return Kind.JSON; // a multi-valued scalar
		}
		final SchemaTypeName type = node.getXsTypeName();
		if (SchemaTypeName.XS_INT.equals(type) || SchemaTypeName.XS_INTEGER.equals(type))
		{
			return Kind.INTEGER;
		}
		if (SchemaTypeName.XS_DECIMAL.equals(type))
		{
			return Kind.DECIMAL;
		}
		if (SchemaTypeName.XS_BOOLEAN.equals(type))
		{
			return Kind.BOOLEAN;
		}
		if (SchemaTypeName.XS_DATE.equals(type))
		{
			return Kind.DATE;
		}
		if (SchemaTypeName.XS_DATETIME.equals(type))
		{
			return Kind.DATETIME;
		}
		if (SchemaTypeName.XS_TIME.equals(type))
		{
			return Kind.TIME;
		}
		return Kind.STRING;
	}

	@Override
	public Integer maxLengthOf(final MappingDefinition pMapping, final MappingRule pRule)
	{
		final SchemaNode node = this.sourceNode(pMapping, pRule);
		if (node == null)
		{
			return null;
		}
		final SchemaFacetMaxLength facet = node.getFacetMaxLength();
		return facet == null ? null : facet.getValue();
	}

	@Override
	public boolean isKeyPath(final MappingDefinition pMapping, final MappingRule pRule)
	{
		final SchemaNode node = this.sourceNode(pMapping, pRule);
		if (node == null)
		{
			return false;
		}
		// The primary key is declared on the table node, not on the occurrence root (which answers null).
		final SchemaNode[] keys = this.datasetOf(pMapping).getTable(Path.parse(pMapping.getTablePath())).getTableNode().getTablePrimaryKeyNodes();
		if (keys == null)
		{
			return false;
		}
		for (final SchemaNode pk : keys)
		{
			if (pk.getPathInAdaptation().equals(node.getPathInAdaptation()))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public ProductPublisher.StreamSummary stream(final MappingDefinition pMapping, final int pMax, final List<String> pErrors,
		final ProductPublisher.RowSink pSink) throws ConnectorException
	{
		final Adaptation dataset = this.datasetOf(pMapping);
		final AdaptationTable table = dataset.getTable(Path.parse(pMapping.getTablePath()));
		final Request request = table.createRequest();
		request.setSession(this.session);
		final RequestResult results = request.execute();
		// mergedRecords=exclude (the default) leaves MERGED/DELETED records out; the mode and the way to read
		// the state are resolved once per mapping, not per row, and the Match and Merge lookup itself is skipped
		// entirely when the mode is "include" (the common crosswalk case) so a table not under Match and Merge
		// pays nothing extra.
		final String mode = ProductPublisher.mergedRecordsMode(pMapping);
		final boolean excludes = "exclude".equalsIgnoreCase(mode);
		final GoldenRecords.StateReader states = excludes ? GoldenRecords.get().stateReaderFor(table) : null;
		int rows = 0;
		int skippedMerged = 0;
		int skippedDeleted = 0;
		int unlistedErrors = 0;
		boolean truncated = false;
		try
		{
			for (Adaptation record = results.nextAdaptation(); record != null; record = results.nextAdaptation())
			{
				if (excludes)
				{
					final String state = states.state(record);
					if (!ProductPublisher.publishes(state, mode))
					{
						if ("MERGED".equals(state))
						{
							skippedMerged++;
						}
						else if ("DELETED".equals(state))
						{
							skippedDeleted++;
						}
						continue; // skipped records do not count towards pMax
					}
				}
				if (rows >= pMax)
				{
					truncated = true;
					break;
				}
				final MappingResult built = new MappingResult();
				final TransformationContext context = new TransformationContext(record.getContainer(), record, this.session);
				final ObjectNode document = MappingEngine.buildOutbound(pMapping, record, context, built);
				for (final String error : built.getErrors())
				{
					if (pErrors.size() < ProductPublisher.RecordSource.MAX_LISTED_ERRORS)
					{
						pErrors.add("mapping " + pMapping.getId() + ": " + error);
					}
					else
					{
						unlistedErrors++;
					}
				}
				pSink.accept(new ProductPublisher.Row(document, record.getOccurrencePrimaryKey().format()));
				rows++;
			}
		}
		finally
		{
			results.close();
		}
		return new ProductPublisher.StreamSummary(rows, truncated, skippedMerged, skippedDeleted, unlistedErrors);
	}
}
