package com.on.ebx.connect.ebx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.ConnectorTemplate;
import com.on.ebx.connect.core.ConnectorTemplateStore;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Json;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** EBX table-backed connector-template store; writes retain the caller's EBX permissions. */
public final class EbxConnectorTemplateStore implements ConnectorTemplateStore
{
	private final Repository repository;
	private final Session session;

	public EbxConnectorTemplateStore(final Repository pRepository, final Session pSession)
	{
		if (pRepository == null || pSession == null) throw new IllegalArgumentException("repository and session are required");
		this.repository = pRepository;
		this.session = pSession;
	}

	private AdaptationTable table() throws IOException
	{
		final Adaptation dataset = new com.on.ebx.connect.core.ConnectorRepository(this.repository).dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR_TEMPLATE) == null)
			throw new IOException("ConnectorTemplate table is unavailable; reload the Connectors data model");
		return dataset.getTable(ConnectorsPaths.CONNECTOR_TEMPLATE);
	}

	@Override public ConnectorTemplate find(final String pId) throws IOException
	{
		if (pId == null) return null;
		final Adaptation row = table().lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId));
		return row == null ? null : fromRow(row);
	}

	@Override public List<ConnectorTemplate> list() throws IOException
	{
		final List<ConnectorTemplate> result = new ArrayList<ConnectorTemplate>();
		final RequestResult rows = table().createRequest().execute();
		try { for (Adaptation row; (row = rows.nextAdaptation()) != null;) result.add(fromRow(row)); }
		finally { rows.close(); }
		Collections.sort(result, new Comparator<ConnectorTemplate>()
		{
			@Override public int compare(final ConnectorTemplate pLeft, final ConnectorTemplate pRight) { return pLeft.getId().compareTo(pRight.getId()); }
		});
		return Collections.unmodifiableList(result);
	}

	@Override public void create(final ConnectorTemplate pTemplate, final String pActor) throws IOException { write(pTemplate, pActor, false); }

	@Override public void replacePackaged(final ConnectorTemplate pTemplate, final String pActor) throws IOException
	{
		if (pTemplate.getOwnership() != ConnectorTemplate.Ownership.PACKAGED) throw new IllegalArgumentException("Only packaged templates may be replaced");
		write(pTemplate, pActor, true);
	}

	private void write(final ConnectorTemplate pTemplate, final String pActor, final boolean pReplace) throws IOException
	{
		final AdaptationTable table = table();
		final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTemplate.getId()));
		if (!pReplace && existing != null) throw new IllegalStateException("Template id already exists: " + pTemplate.getId());
		if (pReplace && (existing == null || !ConnectorTemplate.Ownership.PACKAGED.name().equals(existing.getString(ConnectorsPaths.ConnectorTemplate.OWNERSHIP))))
			throw new IllegalStateException("Packaged upgrade cannot replace customer template " + pTemplate.getId());
		final Date now = new Date();
		final ProcedureResult result = ProgrammaticService.createForSession(this.session, table.getContainerAdaptation().getHome()).execute(new Procedure()
		{
			@Override public void execute(final ProcedureContext pContext) throws Exception
			{
				// No setAllPrivileges: table/record permissions are authoritative.
				final ValueContextForUpdate values = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
				values.setValue(pTemplate.getId(), ConnectorsPaths.ConnectorTemplate.ID);
				values.setValue(Integer.valueOf(pTemplate.getVersion()), ConnectorsPaths.ConnectorTemplate.VERSION);
				values.setValue(pTemplate.getOwnership().name(), ConnectorsPaths.ConnectorTemplate.OWNERSHIP);
				values.setValue(pTemplate.getBasedOn(), ConnectorsPaths.ConnectorTemplate.BASED_ON);
				values.setValue(pTemplate.getBasedOnVersion() < 1 ? null : Integer.valueOf(pTemplate.getBasedOnVersion()), ConnectorsPaths.ConnectorTemplate.BASED_ON_VERSION);
				values.setValue(Json.write(pTemplate.getConfiguration()), ConnectorsPaths.ConnectorTemplate.CONFIGURATION);
				if (existing == null)
				{
					values.setValue(pActor, ConnectorsPaths.ConnectorTemplate.CREATED_BY);
					values.setValue(now, ConnectorsPaths.ConnectorTemplate.CREATED_AT);
				}
				values.setValue(pActor, ConnectorsPaths.ConnectorTemplate.UPDATED_BY);
				values.setValue(now, ConnectorsPaths.ConnectorTemplate.UPDATED_AT);
				if (existing == null) pContext.doCreateOccurrence(values, table);
				else pContext.doModifyContent(existing, values);
			}
		});
		if (result.hasFailed()) throw new IOException("EBX rejected connector template: " + result.getException().getMessage());
	}

	private static ConnectorTemplate fromRow(final Adaptation pRow) throws IOException
	{
		final JsonNode configuration = Json.parse(pRow.getString(ConnectorsPaths.ConnectorTemplate.CONFIGURATION));
		if (configuration == null || !configuration.isObject()) throw new IOException("Template " + pRow.getString(ConnectorsPaths.ConnectorTemplate.ID) + " has invalid configuration");
		final Integer basedOnVersion = (Integer) pRow.get(ConnectorsPaths.ConnectorTemplate.BASED_ON_VERSION);
		return new ConnectorTemplate(pRow.getString(ConnectorsPaths.ConnectorTemplate.ID), ((Integer) pRow.get(ConnectorsPaths.ConnectorTemplate.VERSION)).intValue(),
			ConnectorTemplate.Ownership.valueOf(pRow.getString(ConnectorsPaths.ConnectorTemplate.OWNERSHIP)), pRow.getString(ConnectorsPaths.ConnectorTemplate.BASED_ON),
			basedOnVersion == null ? 0 : basedOnVersion.intValue(), (com.fasterxml.jackson.databind.node.ObjectNode) configuration);
	}
}
