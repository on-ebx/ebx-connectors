package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.OpenApiImporter;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Creates or updates a connector of type {@code rest} from an OpenAPI specification (text or URL),
 * in the Connectors dataset. Secrets already entered on an existing connector are kept.
 */
public final class OpenApiImport
{
	public static final class Report
	{
		public String connectorId;
		public boolean created;
		public boolean dryRun;
		public String baseUrl;
		public String authType;
		public int propertyCount;
		public final List<String> operations = new ArrayList<String>();
		public final List<String> notes = new ArrayList<String>();
	}

	private OpenApiImport()
	{
	}

	/** Ceiling of a downloaded specification: the largest public ones weigh a few megabytes. */
	static final int MAX_SPECIFICATION_BYTES = 16 * 1024 * 1024;

	/**
	 * Downloads a specification (JSON or YAML text). The URL is typed by a user, so it is treated
	 * like any connector call: http or https only, the outbound allow-list and address rules of
	 * {@link com.on.ebx.connect.core.Egress} on the URL and on every redirect hop, and a bounded
	 * answer, so that the import cannot be used to read an internal address or to fill the heap.
	 */
	public static String fetch(final String pUrl) throws ConnectorException
	{
		return OpenApiImport.fetch(pUrl, OpenApiImport.MAX_SPECIFICATION_BYTES);
	}

	static String fetch(final String pUrl, final int pMaximumBytes) throws ConnectorException
	{
		final String url = pUrl == null ? "" : pUrl.trim();
		final String lower = url.toLowerCase();
		if (!lower.startsWith("http://") && !lower.startsWith("https://"))
		{
			throw new ConnectorException("OpenAPI: the specification URL must start with http:// or https://");
		}
		// the URL may carry a key in its query string: it is only ever shown redacted
		final String shown = com.on.ebx.connect.core.Redaction.redact(url);
		try
		{
			final HttpJsonClient client = new HttpJsonClient(10000, 30000, "EBX-Connectors/1.0 (openapi)");
			final java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
			headers.put("Accept", "application/json, application/yaml, text/yaml, */*");
			final HttpJsonClient.Response response = client.sendBytes("GET", url, headers, null, pMaximumBytes);
			if (!response.isSuccess())
			{
				throw new ConnectorException("OpenAPI: GET " + shown + " answered HTTP " + response.getStatus());
			}
			return response.getRawBody();
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("OpenAPI: cannot download " + shown + ": " + com.on.ebx.connect.core.Redaction.redact(ex.getMessage()), ex);
		}
	}

	public static Report run(final Session pSession, final Repository pRepository, final String pId, final String pLabel, final String pSpecText,
		final boolean pReplace, final boolean pDryRun) throws ConnectorException
	{
		final JsonNode spec = OpenApiImporter.parse(pSpecText);
		final OpenApiImporter.Draft draft = OpenApiImporter.build(spec, pId, pLabel);
		final Report report = new Report();
		report.connectorId = draft.id;
		report.dryRun = pDryRun;
		report.baseUrl = draft.baseUrl;
		report.authType = draft.authType.name();
		report.propertyCount = draft.properties.size();
		report.operations.addAll(draft.operations);
		report.notes.addAll(draft.notes);
		if (pDryRun)
		{
			return report;
		}
		ConnectorsInstaller.ensure(pRepository, pSession);
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET));
		if (dataset == null)
		{
			throw new ConnectorException("The Connectors dataset is not available");
		}
		final AdaptationTable connectors = dataset.getTable(ConnectorsPaths.CONNECTOR);
		final Adaptation existing = connectors.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(draft.id));
		report.created = existing == null;
		if (existing != null && !pReplace)
		{
			throw new ConnectorException("Connector '" + draft.id + "' already exists: choose another id or allow replacement");
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, dataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate row = existing == null ? pContext.getContextForNewOccurrence(connectors) : pContext.getContext(existing.getAdaptationName());
				row.setValue(draft.id, ConnectorsPaths.Connector.ID);
				row.setValue(draft.label, ConnectorsPaths.Connector.LABEL);
				row.setValue("rest", ConnectorsPaths.Connector.TYPE);
				row.setValue(draft.baseUrl, ConnectorsPaths.Connector.BASE_URL);
				row.setValue(draft.authType.name(), ConnectorsPaths.Connector.AUTH_TYPE);
				row.setValue(draft.apiKeyHeader, ConnectorsPaths.Connector.API_KEY_HEADER);
				row.setValue(draft.description, ConnectorsPaths.Connector.DESCRIPTION);
				row.setValue(Boolean.TRUE, ConnectorsPaths.Connector.ACTIVE);
				if (existing == null)
				{
					row.setValue("TO_BE_SET", ConnectorsPaths.Connector.API_KEY);
					pContext.doCreateOccurrence(row, connectors);
				}
				else
				{
					pContext.doModifyContent(existing, row);
					// the imported properties replace the previous op.* and auth.* ones, other properties stay
					final AdaptationTable properties = dataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY);
					final com.onwbp.adaptation.Request request = properties.createRequest();
					request.setXPathFilter("./connector='" + draft.id.replace("'", "''") + "'");
					final RequestResult rows = request.execute();
					final List<Adaptation> old = new ArrayList<Adaptation>();
					try
					{
						for (Adaptation p = rows.nextAdaptation(); p != null; p = rows.nextAdaptation())
						{
							final String key = p.getString(ConnectorsPaths.ConnectorProperty.KEY);
							if (key.startsWith("op.") || key.startsWith("auth."))
							{
								old.add(p);
							}
						}
					}
					finally
					{
						rows.close();
					}
					for (final Adaptation p : old)
					{
						pContext.doDelete(p.getAdaptationName(), false);
					}
				}
				final AdaptationTable properties = dataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY);
				for (final Map.Entry<String, String> property : draft.properties.entrySet())
				{
					final ValueContextForUpdate p = pContext.getContextForNewOccurrence(properties);
					p.setValue(draft.id, ConnectorsPaths.ConnectorProperty.CONNECTOR);
					p.setValue(property.getKey(), ConnectorsPaths.ConnectorProperty.KEY);
					p.setValue(property.getValue(), ConnectorsPaths.ConnectorProperty.VALUE);
					pContext.doCreateOccurrence(p, properties);
				}
			}
		});
		if (result.hasFailed())
		{
			throw new ConnectorException("Import failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		ConnectorRegistry.get().invalidate();
		return report;
	}
}
