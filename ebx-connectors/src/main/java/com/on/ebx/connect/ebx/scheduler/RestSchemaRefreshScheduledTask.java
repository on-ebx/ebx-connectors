package com.on.ebx.connect.ebx.scheduler;

import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.RestSchemaRefresh;
import com.on.ebx.connect.ebx.EbxRestSchemaRefreshStore;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.scheduler.ScheduledExecutionContext;
import com.orchestranetworks.scheduler.ScheduledTask;
import com.orchestranetworks.scheduler.ScheduledTaskInterruption;
import com.orchestranetworks.service.OperationException;

/**
 * Refreshes one REST connector's OpenAPI/JSON schema with persisted conditional-GET state.
 * Register this class in EBX Administration &gt; Task scheduler and run it as a profile allowed to
 * read the connector and write the SchemaRefreshState technical table: since audit C-01 (tables), a profile
 * holding the Connectors MANAGE right (an administrator does), whatever the EBX permissions say.
 */
public final class RestSchemaRefreshScheduledTask extends ScheduledTask
{
	private static final long MIN_INTERVAL_MINUTES = 1L;
	private static final long MAX_INTERVAL_MINUTES = 10080L; // seven days
	private String connector;
	private String schemaUrl;
	private String minimumIntervalMinutes = "60";

	@Override public void execute(final ScheduledExecutionContext pContext)
		throws OperationException, ScheduledTaskInterruption
	{
		if (blank(this.connector)) throw OperationException.createError("connector is required");
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), pContext.getSession());
		final ConnectorRepository repository = new ConnectorRepository(pContext.getRepository());
		final Adaptation dataset = repository.dataset();
		final Adaptation record = dataset == null ? null : dataset.getTable(ConnectorsPaths.CONNECTOR)
			.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(this.connector.trim()));
		if (record == null) throw OperationException.createError("Connector '" + this.connector + "' does not exist");
		if (pContext.getSession().getPermissions().getAdaptationAccessPermission(record).isHidden())
			throw OperationException.createError("The scheduled-task profile cannot read connector '" + this.connector + "'");

		try
		{
			final ConnectorConfig config = repository.connector(this.connector.trim());
			String url = blank(this.schemaUrl) ? config.getProperty("schema.url", null) : this.schemaUrl.trim();
			if (blank(url)) url = config.getProperty("openapi.url", null);
			if (blank(url)) throw new IllegalArgumentException("schemaUrl or connector property schema.url/openapi.url is required");
			if (url.length() > 1000) throw new IllegalArgumentException("schema URL must not exceed 1000 characters");
			Egress.check(url);
			final long minutes = boundedMinutes(this.minimumIntervalMinutes);
			final Connector implementation = ConnectorRegistry.get().connector(this.connector.trim(), repository);
			if (!(implementation instanceof AbstractRestConnector))
				throw new IllegalArgumentException("Connector '" + this.connector + "' is not REST-based");
			final EbxRestSchemaRefreshStore store = new EbxRestSchemaRefreshStore(pContext.getRepository(),
				pContext.getSession(), this.connector.trim(), url);
			final RestSchemaRefresh.Result result = ((AbstractRestConnector) implementation).refreshPersistedSchema(
				url, store, store.stateKey(), minutes * 60000L);
			pContext.addExecutionInformation("Schema " + (result.wasChecked() ? "checked" : "not due")
				+ (result.isChanged() ? "; updated" : "; unchanged") + "; HTTP " + result.getStatus()
				+ "; next refresh at " + new java.util.Date(result.getNextRefreshAt()));
		}
		catch (final Exception ex)
		{
			throw OperationException.createError("Schema refresh failed for '" + this.connector + "': " + ex.getMessage());
		}
	}

	static long boundedMinutes(final String pValue)
	{
		final long value;
		try { value = Long.parseLong(blank(pValue) ? "60" : pValue.trim()); }
		catch (final NumberFormatException ex) { throw new IllegalArgumentException("minimumIntervalMinutes must be an integer"); }
		if (value < MIN_INTERVAL_MINUTES || value > MAX_INTERVAL_MINUTES)
			throw new IllegalArgumentException("minimumIntervalMinutes must be between 1 and 10080");
		return value;
	}

	private static boolean blank(final String pValue) { return pValue == null || pValue.trim().isEmpty(); }
	public String getConnector() { return this.connector; }
	public void setConnector(final String pConnector) { this.connector = pConnector; }
	public String getSchemaUrl() { return this.schemaUrl; }
	public void setSchemaUrl(final String pSchemaUrl) { this.schemaUrl = pSchemaUrl; }
	public String getMinimumIntervalMinutes() { return this.minimumIntervalMinutes; }
	public void setMinimumIntervalMinutes(final String pMinutes) { this.minimumIntervalMinutes = pMinutes; }
}
