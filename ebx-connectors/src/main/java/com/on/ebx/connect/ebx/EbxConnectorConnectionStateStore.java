package com.on.ebx.connect.ebx;

import java.util.Date;

import com.on.ebx.connect.core.ConnectorConnectionStateService;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** Stores explicit connector verification state on its EBX Connector row. */
public final class EbxConnectorConnectionStateStore implements ConnectorConnectionStateService.Store
{
	private final ConnectorRepository repository;
	private final Session session;
	public EbxConnectorConnectionStateStore(final ConnectorRepository pRepository, final Session pSession)
	{
		if (pRepository == null || pSession == null) throw new IllegalArgumentException("repository and session are required");
		this.repository = pRepository;
		this.session = pSession;
	}
	private AdaptationTable table()
	{
		final Adaptation dataset = this.repository.dataset();
		if (dataset == null) throw new IllegalStateException("Connectors dataset is unavailable");
		return dataset.getTable(ConnectorsPaths.CONNECTOR);
	}
	private Adaptation row(final String pConnectorId) { return table().lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConnectorId)); }

	@Override public ConnectorConnectionStateService.State load(final String pConnectorId)
	{
		final Adaptation row = row(pConnectorId);
		if (row == null) return null;
		final String status = row.getString(ConnectorsPaths.Connector.CONNECTION_STATUS);
		ConnectorConnectionStateService.Status parsed = ConnectorConnectionStateService.Status.NOT_CONNECTED;
		if (status != null) try { parsed = ConnectorConnectionStateService.Status.valueOf(status); } catch (final IllegalArgumentException ignored) { }
		return new ConnectorConnectionStateService.State(parsed,
			(Date) row.get(ConnectorsPaths.Connector.CONNECTION_VERIFIED_AT),
			row.getString(ConnectorsPaths.Connector.CONNECTION_FINGERPRINT),
			row.getString(ConnectorsPaths.Connector.CONNECTION_MESSAGE));
	}

	@Override public void save(final String pConnectorId, final ConnectorConnectionStateService.State pState)
	{
		final AdaptationTable table = table();
		final Adaptation existing = row(pConnectorId);
		if (existing == null) throw new IllegalArgumentException("Connector '" + pConnectorId + "' does not exist");
		final ProcedureResult result = ProgrammaticService.createForSession(this.session, table.getContainerAdaptation().getHome()).execute(new Procedure() {
			@Override public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate values = pContext.getContext(existing.getAdaptationName());
				values.setValue(pState.getStatus().name(), ConnectorsPaths.Connector.CONNECTION_STATUS);
				values.setValue(pState.getVerifiedAt(), ConnectorsPaths.Connector.CONNECTION_VERIFIED_AT);
				values.setValue(pState.getFingerprint(), ConnectorsPaths.Connector.CONNECTION_FINGERPRINT);
				values.setValue(pState.getMessage(), ConnectorsPaths.Connector.CONNECTION_MESSAGE);
				pContext.doModifyContent(existing, values);
			}
		});
		if (result.hasFailed()) throw new IllegalStateException("EBX rejected connector connection state", result.getException());
	}
}
