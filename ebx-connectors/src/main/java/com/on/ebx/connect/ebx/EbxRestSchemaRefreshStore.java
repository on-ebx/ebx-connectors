package com.on.ebx.connect.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.RestSchemaRefresh;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** EBX-table persistence for conditional REST schema refresh state. */
public final class EbxRestSchemaRefreshStore implements RestSchemaRefresh.StateStore
{
	static final int MAX_STATE_CHARS = 1000000;
	private final Backend backend;
	private final String connectorId;
	private final String url;

	interface Backend
	{
		String load(String pConnectorId, String pKey) throws IOException;
		void save(String pConnectorId, String pKey, String pUrl, String pState) throws IOException;
	}

	public EbxRestSchemaRefreshStore(final Repository pRepository, final Session pSession,
		final String pConnectorId, final String pUrl)
	{
		this(new EbxBackend(pRepository, pSession), pConnectorId, pUrl);
	}

	EbxRestSchemaRefreshStore(final Backend pBackend, final String pConnectorId, final String pUrl)
	{
		if (pBackend == null) throw new IllegalArgumentException("backend is required");
		if (blank(pConnectorId)) throw new IllegalArgumentException("connector is required");
		if (blank(pUrl) || pUrl.length() > 1000) throw new IllegalArgumentException("schema URL is required and must not exceed 1000 characters");
		this.backend = pBackend;
		this.connectorId = pConnectorId.trim();
		this.url = pUrl.trim();
	}

	public String stateKey() { return keyFor(this.url); }

	@Override public String load(final String pKey) throws IOException
	{
		requireOwnKey(pKey);
		return this.backend.load(this.connectorId, pKey);
	}

	@Override public void save(final String pKey, final String pValue) throws IOException
	{
		requireOwnKey(pKey);
		if (pValue == null || pValue.length() > MAX_STATE_CHARS)
			throw new IOException("Schema refresh state must be present and at most " + MAX_STATE_CHARS + " characters");
		this.backend.save(this.connectorId, pKey, this.url, pValue);
	}

	private void requireOwnKey(final String pKey) throws IOException
	{
		if (!stateKey().equals(pKey)) throw new IOException("Schema refresh state key does not match its URL");
	}

	public static String keyFor(final String pUrl)
	{
		if (blank(pUrl)) throw new IllegalArgumentException("schema URL is required");
		try
		{
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(pUrl.trim().getBytes(StandardCharsets.UTF_8));
			final StringBuilder out = new StringBuilder("schema-");
			for (final byte value : digest) out.append(String.format("%02x", Integer.valueOf(value & 0xff)));
			return out.toString();
		}
		catch (final Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
	}

	private static boolean blank(final String pValue) { return pValue == null || pValue.trim().isEmpty(); }

	private static final class EbxBackend implements Backend
	{
		private final Repository repository;
		private final Session session;
		EbxBackend(final Repository pRepository, final Session pSession)
		{
			if (pRepository == null || pSession == null) throw new IllegalArgumentException("repository and session are required");
			this.repository = pRepository;
			this.session = pSession;
		}

		private AdaptationTable table() throws IOException
		{
			final Adaptation dataset = new com.on.ebx.connect.core.ConnectorRepository(this.repository).dataset();
			if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.SCHEMA_REFRESH_STATE) == null)
				throw new IOException("SchemaRefreshState table is unavailable; reload the Connectors data model");
			return dataset.getTable(ConnectorsPaths.SCHEMA_REFRESH_STATE);
		}

		@Override public String load(final String pConnectorId, final String pKey) throws IOException
		{
			final AdaptationTable table = table();
			final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(PrimaryKey.parseObjects(
				new Object[] { pConnectorId, pKey }, new com.orchestranetworks.schema.SchemaNode[] {
					table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.SchemaRefreshState.CONNECTOR),
					table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.SchemaRefreshState.KEY) })));
			return row == null ? null : row.getString(ConnectorsPaths.SchemaRefreshState.STATE);
		}

		@Override public void save(final String pConnectorId, final String pKey, final String pUrl, final String pState) throws IOException
		{
			final AdaptationTable table = table();
			final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(PrimaryKey.parseObjects(
				new Object[] { pConnectorId, pKey }, new com.orchestranetworks.schema.SchemaNode[] {
					table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.SchemaRefreshState.CONNECTOR),
					table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.SchemaRefreshState.KEY) })));
			final ProcedureResult result = ProgrammaticService.createForSession(this.session, table.getContainerAdaptation().getHome()).execute(new Procedure()
			{
				@Override public void execute(final ProcedureContext pContext) throws Exception
				{
					// Deliberately no setAllPrivileges: the scheduler/user session must have write access.
					final ValueContextForUpdate values = existing == null ? pContext.getContextForNewOccurrence(table)
						: pContext.getContext(existing.getAdaptationName());
					values.setValue(pConnectorId, ConnectorsPaths.SchemaRefreshState.CONNECTOR);
					values.setValue(pKey, ConnectorsPaths.SchemaRefreshState.KEY);
					values.setValue(pUrl, ConnectorsPaths.SchemaRefreshState.URL);
					values.setValue(pState, ConnectorsPaths.SchemaRefreshState.STATE);
					values.setValue(new Date(), ConnectorsPaths.SchemaRefreshState.UPDATED_AT);
					if (existing == null) pContext.doCreateOccurrence(values, table);
					else pContext.doModifyContent(existing, values);
				}
			});
			if (result.hasFailed()) throw new IOException("EBX rejected schema refresh state: " + result.getException().getMessage());
		}
	}
}
