package com.on.ebx.connect.ebx.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.core.ImportOutcome;
import com.on.ebx.connect.core.OutboundScope;
import com.on.ebx.connect.core.ProductPublisher;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.ConflictResolver;
import com.on.ebx.connect.core.Csv;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ListingPage;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.ebx.ConnectorsInstaller;
import com.on.ebx.connect.ebx.ConnectorsStatus;
import com.on.ebx.connect.ebx.DatasetLocator;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.Operation;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.rest.inject.SessionContext;
import com.orchestranetworks.service.Session;

/**
 * {@code /ebx-connectors/rest/v1}:
 * <ul>
 * <li>{@code GET /connectors} — connector types and configured connectors;</li>
 * <li>{@code GET /mappings[?table=/root/Companies]} — mappings;</li>
 * <li>{@code POST /sync/{mapping}?key=} — one mapping on one record ({@code mapping} = id, label or
 * connector/operation);</li>
 * <li>{@code POST /enrich?dataset=&table=&key=&mappings=2,1,30 | &connectors=GLEIF/lei,VIES} — a
 * sequence of inbound mappings on one record, in the given order;</li>
 * <li>{@code POST /webhook/{feed}} — inbound documents (JSON, ndjson, csv) imported through a feed
 * mapping; {@code GET /webhook/{feed}} is its health check.</li>
 * </ul>
 * Everything takes {@code ?dryRun=true}. Authentication: the EBX one (Basic, token, session), the webhook
 * included: the sender is an EBX user with the RUN right, and the import runs under that user (C2-10).
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public final class ConnectorsResource
{
	private final SessionContext sessionContext;
	private final HttpHeaders headers;

	public ConnectorsResource(@Context final SessionContext pSessionContext, @Context final HttpHeaders pHeaders)
	{
		this.sessionContext = pSessionContext;
		this.headers = pHeaders;
	}

	// ------------------------------------------------------------------ catalogue
	/** Explicit on-demand, catalog-authorized, masked preview; raw rows never enter this response. */
	@POST
	@Path("/catalog/preview")
	public Response catalogPreview(@QueryParam("asset") final String asset)
	{
		final Response refused = this.refused("catalogPreview", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		try
		{
			final JsonNode result = com.on.ebx.connect.core.CatalogPreview.preview(this.sessionContext.getSession(), this.sessionContext.getRepository(), asset);
			return Response.ok(Json.write(result)).header("Cache-Control", "no-store, private").header("Pragma", "no-cache").build();
		}
		catch (final SecurityException denied)
		{
			return Response.status(403).entity("{\"code\":\"PREVIEW_ACCESS_DENIED\",\"error\":\"Preview requires an administrator session and readable catalog metadata. Refresh your session or check permissions.\"}").header("Cache-Control", "no-store").build();
		}
		catch (final Exception error)
		{
			// Driver/API errors can contain source values. Never echo exception text into a preview.
			return Response.status(400).entity("{\"error\":\"Preview unavailable. Check source configuration, supported connector and scanned columns.\"}").header("Cache-Control", "no-store").build();
		}
	}

	@GET
	@Path("/connectors")
	public Response connectors()
	{
		final Response refused = this.refused("connectors", ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ObjectNode out = Json.object();
		final ArrayNode types = out.putArray("types");
		for (final String type : ConnectorRegistry.get().getTypes())
		{
			final ObjectNode t = types.addObject();
			t.put("type", type);
			try
			{
				final Connector probe = ConnectorRegistry.get().describeType(type);
				t.put("label", probe.getLabel());
				final ArrayNode ops = t.putArray("operations");
				for (final Operation op : probe.getOperations())
				{
					final ObjectNode o = ops.addObject();
					o.put("name", op.getName());
					o.put("kind", op.getKind().name());
					o.put("description", op.getDescription());
				}
			}
			catch (final Exception ex)
			{
				t.put("error", safeMessage(ex));
			}
		}
		final ArrayNode configured = out.putArray("connectors");
		for (final ConnectorConfig config : ConnectorService.get().repository(repository).connectors())
		{
			final ObjectNode c = configured.addObject();
			c.put("id", config.getId());
			c.put("label", config.getLabel());
			c.put("type", config.getType());
			c.put("baseUrl", config.getDisplayBaseUrl());
			c.put("active", config.isActive());
			c.put("logo", com.on.ebx.connect.ebx.ui.Logos.url(config.getLogo() == null ? com.on.ebx.connect.ebx.ui.Logos.resource("default.svg") : config.getLogo()));
			c.put("configured", !config.isPlaceholder(config.getApiKey()) || !config.isPlaceholder(config.getPassword())
					|| config.getAuthType() == ConnectorConfig.AuthType.NONE);
		}
		return Response.ok(Json.write(out)).build();
	}

	// ------------------------------------------------------------------ templates

	/** Lists persisted templates without credential material. */
	@GET
	@Path("/templates")
	public Response templates()
	{
		final Response refused = this.refused("templates", ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		try
		{
			final com.on.ebx.connect.core.ConnectorTemplateService service = templateService();
			return Response.ok(service.exportTemplates()).build();
		}
		catch (final Exception ex) { return ConnectorsResource.fail(400, safeMessage(ex)); }
	}

	/** Imports templates. Existing customer-owned templates are never overwritten. */
	@POST
	@Path("/templates/import")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response importTemplates(@HeaderParam("Content-Length") final String pContentLength, final java.io.InputStream pStream)
	{
		final Response refused = this.refused("importTemplates", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		final String[] body = new String[1];
		final Response tooBig = this.readBody(pContentLength, pStream, body);
		if (tooBig != null)
		{
			return tooBig;
		}
		try
		{
			final int changed = templateService().importTemplates(body[0], actor());
			final ObjectNode out = Json.object();
			out.put("changed", changed);
			return Response.ok(Json.write(out)).build();
		}
		catch (final Exception ex) { return ConnectorsResource.fail(400, safeMessage(ex)); }
	}

	/** Creates a customer-owned copy of a template. */
	@POST
	@Path("/templates/{template}/copy")
	public Response copyTemplate(@PathParam("template") final String pTemplate, @QueryParam("id") final String pId)
	{
		final Response refused = this.refused("copyTemplate", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		try
		{
			final com.on.ebx.connect.core.ConnectorTemplate copy = templateService().copy(pTemplate, pId, actor());
			final ObjectNode out = Json.object();
			out.put("id", copy.getId()); out.put("version", copy.getVersion()); out.put("basedOn", copy.getBasedOn()); out.put("basedOnVersion", copy.getBasedOnVersion());
			return Response.status(201).entity(Json.write(out)).build();
		}
		catch (final Exception ex) { return ConnectorsResource.fail(400, safeMessage(ex)); }
	}

	/** Creates an upgraded customer copy and leaves the original customer copy untouched. */
	@POST
	@Path("/templates/{template}/upgrade")
	public Response upgradeTemplate(@PathParam("template") final String pTemplate, @QueryParam("id") final String pId)
	{
		final Response refused = this.refused("upgradeTemplate", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		try
		{
			final com.on.ebx.connect.core.ConnectorTemplate copy = templateService().upgradeCopy(pTemplate, pId, actor());
			final ObjectNode out = Json.object();
			out.put("id", copy.getId()); out.put("version", copy.getVersion()); out.put("basedOn", copy.getBasedOn()); out.put("basedOnVersion", copy.getBasedOnVersion());
			return Response.status(201).entity(Json.write(out)).build();
		}
		catch (final Exception ex) { return ConnectorsResource.fail(400, safeMessage(ex)); }
	}

	private com.on.ebx.connect.core.ConnectorTemplateService templateService()
	{
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		return new com.on.ebx.connect.core.ConnectorTemplateService(new com.on.ebx.connect.ebx.EbxConnectorTemplateStore(repository, session));
	}

	private String actor()
	{
		return this.sessionContext.getSession().getUserReference() == null ? null : this.sessionContext.getSession().getUserReference().getUserId();
	}

	private static String safeMessage(final Exception pException)
	{
		return com.on.ebx.connect.core.Redaction.failure(pException);
	}

	/**
	 * {@code POST /connectors/openapi?id=MyApi[&label=][&url=][&replace=true][&dryRun=true]}: creates (or
	 * replaces) a {@code rest} connector from an OpenAPI 3 specification sent as the body (JSON or
	 * YAML) or downloaded from {@code url}.
	 */
	@POST
	@Path("/connectors/openapi")
	@Consumes({ MediaType.APPLICATION_JSON, "application/yaml", "application/x-yaml", "text/yaml", MediaType.TEXT_PLAIN, MediaType.WILDCARD })
	public Response importOpenApi(@QueryParam("id") final String pId, @QueryParam("label") final String pLabel, @QueryParam("url") final String pUrl,
		@QueryParam("replace") @DefaultValue("false") final boolean pReplace, @QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun,
		@HeaderParam("Content-Length") final String pContentLength, final java.io.InputStream pStream)
	{
		final Response refused = this.refused("importOpenApi", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		final String[] bodyText = new String[1];
		final Response tooBig = this.readBody(pContentLength, pStream, bodyText);
		if (tooBig != null)
		{
			return tooBig;
		}
		final String pBody = bodyText[0];
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		try
		{
			final String text = pUrl != null && !pUrl.trim().isEmpty() ? com.on.ebx.connect.ebx.OpenApiImport.fetch(pUrl.trim()) : pBody;
			final com.on.ebx.connect.ebx.OpenApiImport.Report report = com.on.ebx.connect.ebx.OpenApiImport.run(session, repository, pId, pLabel, text, pReplace, pDryRun);
			final ObjectNode out = Json.object();
			out.put("connector", report.connectorId);
			out.put("created", report.created);
			out.put("dryRun", report.dryRun);
			out.put("baseUrl", report.baseUrl);
			out.put("authType", report.authType);
			out.put("properties", report.propertyCount);
			final ArrayNode ops = out.putArray("operations");
			for (final String op : report.operations)
			{
				ops.add(op);
			}
			final ArrayNode notes = out.putArray("notes");
			for (final String note : report.notes)
			{
				notes.add(note);
			}
			return Response.status(report.created && !report.dryRun ? 201 : 200).entity(Json.write(out)).build();
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			return ConnectorsResource.fail(400, ex.getMessage());
		}
		catch (final Exception ex)
		{
			ConnectorService.log().error("[connectors] OpenAPI import failed: " + safeMessage(ex));
			return ConnectorsResource.fail(500, com.on.ebx.connect.core.Redaction.failure(ex));
		}
	}

	@GET
	@Path("/mappings")
	public Response mappings(@QueryParam("table") final String pTable)
	{
		final Response refused = this.refused("mappings", ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ObjectNode out = Json.object();
		final ArrayNode list = out.putArray("mappings");
		for (final MappingDefinition m : ConnectorService.get().repository(repository).mappings())
		{
			if (pTable != null && !pTable.isEmpty() && !pTable.equals(m.getTablePath()))
			{
				continue;
			}
			final ObjectNode n = list.addObject();
			n.put("id", m.getId());
			n.put("label", m.getLabel());
			n.put("connector", m.getConnectorId());
			n.put("operation", m.getOperation());
			n.put("direction", m.getDirection().name());
			n.put("dataset", m.getDataset());
			n.put("table", m.getTablePath());
			n.put("active", m.isActive());
			n.put("feed", m.isFeed());
			n.put("child", m.isChild());
			n.put("rules", m.getRules().size());
		}
		return Response.ok(Json.write(out)).build();
	}

	/** {@code DELETE /mappings/{id}}: the mapping with its rules, inputs, sub-object mappings and outbound states; provenance and conflicts unlinked. */
	@jakarta.ws.rs.DELETE
	@Path("/mappings/{mapping}")
	public Response deleteMapping(@PathParam("mapping") final String pMapping)
	{
		final Response refused = this.refused("deleteMapping", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final MappingDefinition one = Mappings.mappingNamed(ConnectorService.get().repository(repository), pMapping);
		if (one == null)
		{
			return ConnectorsResource.fail(404, "No mapping '" + pMapping + "'");
		}
		final ObjectNode report = com.on.ebx.connect.ebx.ConfigTransfer.deleteMapping(session, repository, String.valueOf(one.getId()));
		return Response.status(report.has("error") ? 400 : 200).entity(Json.write(report)).build();
	}

	/**
	 * {@code POST /lineage/declare[?mapping=<id or label>]}: sends the declared lineage of every mapping
	 * (or of one, a sub-object mapping resolving to its root) to the OpenLineage sinks now — active
	 * mappings declared, inactive ones retired. The triggers do it by themselves after each change; this
	 * is the catch-up after a sink was added or a connector's base URL changed.
	 */
	@POST
	@Path("/lineage/declare")
	public Response declareLineage(@QueryParam("mapping") final String pMapping)
	{
		final Response refused = this.refused("declareLineage", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		Integer id = null;
		if (pMapping != null && !pMapping.trim().isEmpty())
		{
			final MappingDefinition one = Mappings.mappingNamed(ConnectorService.get().repository(repository), pMapping);
			if (one == null)
			{
				return ConnectorsResource.fail(404, "No mapping '" + pMapping + "'");
			}
			id = Integer.valueOf(one.getId());
		}
		final ObjectNode report = com.on.ebx.connect.core.DeclaredLineage.declareNow(repository, id);
		return Response.status(report.has("error") ? 400 : 200).entity(Json.write(report)).build();
	}

	/**
	 * {@code POST /lineage/catalog-source?connector=<id>[&source=<id>][&apply=true]}: how the system a
	 * connector reaches is known to the ON EBX data catalog, and with {@code apply} the change that makes
	 * its lineage land on the right Source (MCP {@code connectors_catalog_source}). Verified catalog only;
	 * never deletes.
	 */
	@POST
	@Path("/lineage/catalog-source")
	public Response catalogSource(@QueryParam("connector") final String pConnector, @QueryParam("source") final String pSource,
		@QueryParam("apply") @DefaultValue("false") final boolean pApply)
	{
		final Response refused = this.refused("catalogSource", pApply ? ConnectorsAccess.Right.MANAGE : ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ObjectNode report = com.on.ebx.connect.core.CatalogDetection.catalogSource(ConnectorService.get().repository(repository), session, pConnector, pSource, pApply);
		return Response.status(report.has("error") ? 400 : 200).entity(Json.write(report)).build();
	}

	// ------------------------------------------------------------------ sync / enrich

	@POST
	@Path("/sync/{mapping}")
	public Response sync(@PathParam("mapping") final String pMapping, @QueryParam("key") final String pKey,
		@QueryParam("predicate") final String pPredicate, @QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun,
		@QueryParam("scope") final String pScope, @QueryParam("max") @DefaultValue("" + com.on.ebx.connect.core.RunLimits.API) final int pMax,
		@QueryParam("inputs") final String pInputs, @QueryParam("resumeFrom") @DefaultValue("0") final int pResumeFrom,
		@QueryParam("async") @DefaultValue("false") final boolean pAsync,
		@QueryParam("fullRefresh") @DefaultValue("false") final boolean pFullRefresh)
	{
		final Response refused = this.refused("sync", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ConnectorRepository repo = ConnectorService.get().repository(repository);
		final MappingDefinition one = Mappings.mappingNamed(repo, pMapping);
		if (one == null)
		{
			return ConnectorsResource.fail(404, "No mapping '" + pMapping + "'");
		}
		if (pScope != null && !pScope.trim().isEmpty())
		{
			final String asyncRefusal = pAsync ? com.on.ebx.connect.ebx.mcp.ConnectorsTools.scopeAsyncRefusal(true, pKey != null && !pKey.trim().isEmpty(),
				one.getDirection(), pDryRun) : null;
			if (asyncRefusal != null)
			{
				return ConnectorsResource.fail(400, asyncRefusal);
			}
			return this.runScope(session, repository, one, pScope, pPredicate, pMax, pDryRun, ConnectorService.inputNames(pInputs), pAsync);
		}
		if (one.isFeed() && (pKey == null || pKey.trim().isEmpty()) && (pPredicate == null || pPredicate.trim().isEmpty()))
		{
			// An import feed has no record to start from: run the connector operation and import its items
			// (up to max), exactly like the scheduled task and the workflow bean do.
			// Volume lot 7b: resumeFrom/async, at REST parity with the MCP connectors_sync tool - see
			// ConnectorsTools.sync's own async branch, mirrored here.
			if (pAsync)
			{
				if (pDryRun)
				{
					return ConnectorsResource.fail(400, "async cannot be combined with dryRun");
				}
				final List<String> ignored = new ArrayList<String>();
				final java.util.Map<String, String> inputs = ConnectorService.runtimeInputs(one, pInputs, ignored);
				final String runId;
				try
				{
					runId = ConnectorService.get().importFromAsync(session, repository, one, inputs, pMax,
						"REST " + session.getUserReference().getUserId(), pResumeFrom, pFullRefresh);
				}
				catch (final com.on.ebx.connect.core.TooManyRunsException full)
				{
					// nothing was started or written: the caller may simply try again
					return ConnectorsResource.fail(429, full.getMessage());
				}
				catch (final com.on.ebx.connect.core.MappingAlreadyRunningException running)
				{
					// the mapping is held by a run that is still going; nothing was started or written
					return ConnectorsResource.fail(409, running.getMessage());
				}
				final ObjectNode out = Json.object();
				out.put("runId", runId);
				out.put("status", "RUNNING");
				out.put("mapping", one.getId());
				out.put("feed", true);
				out.put("async", true);
				out.put("nextStep", "GET /runs/" + runId + " for progress and the final status; POST /runs/" + runId + "/stop stops it at the next batch boundary.");
				com.on.ebx.connect.ebx.mcp.ConnectorsTools.putNotices(out, ignored, null);
				return Response.ok(Json.write(out)).build();
			}
			final String runId = ConnectorService.beginRun();
			try
			{
				final List<String> ignored = new ArrayList<String>();
				final java.util.Map<String, String> inputs = ConnectorService.runtimeInputs(one, pInputs, ignored);
				// Volume lot 7a: importFrom now reports counters + a bounded results sample
				// (com.on.ebx.connect.core.ImportOutcome), not one SyncResult per row held for the whole
				// run - see feedResultsResponse.
				final ImportOutcome outcome = ConnectorService.get().importFrom(session, repository, one, inputs, pMax, pDryRun,
					"REST " + session.getUserReference().getUserId(), pResumeFrom, pFullRefresh);
				final ObjectNode extra = Json.object();
				extra.put("mapping", one.getId());
				extra.put("feed", true);
				extra.put("async", false);
				if (inputs != null)
				{
					final ObjectNode used = extra.putObject("inputs");
					for (final java.util.Map.Entry<String, String> input : inputs.entrySet()) used.put(input.getKey(), input.getValue());
				}
				if (!ignored.isEmpty())
				{
					// kept for the callers of the first version; ignoredInputs (as in the MCP answer) is added with the warning
					final com.fasterxml.jackson.databind.node.ArrayNode names = extra.putArray("inputsIgnored");
					for (final String name : ignored) names.add(name);
				}
				return ConnectorsResource.feedResultsResponse(runId, pDryRun, outcome, extra, ignored);
			}
			finally
			{
				ConnectorService.endRun();
			}
		}
		if (pAsync)
		{
			return ConnectorsResource.fail(400, com.on.ebx.connect.ebx.mcp.ConnectorsTools.ASYNC_REFUSAL);
		}
		final List<MappingDefinition> mappings = new ArrayList<MappingDefinition>();
		mappings.add(one);
		// one record reads no run-time input: every one given is ignored, and said (audit C-35)
		return this.run(session, repository, one.getDataspace(), one.getDataset(), one.getTablePath(), pKey, pPredicate, mappings, pDryRun,
			ConnectorService.inputNames(pInputs));
	}

	@POST
	@Path("/enrich")
	public Response enrich(@QueryParam("dataspace") final String pDataspace, @QueryParam("dataset") final String pDataset,
		@QueryParam("table") final String pTable, @QueryParam("key") final String pKey, @QueryParam("predicate") final String pPredicate,
		@QueryParam("mappings") final String pMappings, @QueryParam("connectors") final String pConnectors,
		@QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun)
	{
		final Response refused = this.refused("enrich", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		if (pTable == null || pDataset == null)
		{
			return ConnectorsResource.fail(400, "Parameters dataset and table are required");
		}
		final ConnectorRepository repo = ConnectorService.get().repository(repository);
		final List<MappingDefinition> all = repo.mappingsForAnyDataspace(pDataspace, pDataset, pTable);
		final List<MappingDefinition> mappings = new ArrayList<MappingDefinition>();
		if (pMappings != null && !pMappings.trim().isEmpty())
		{
			for (final String token : pMappings.split(","))
			{
				final MappingDefinition m = Mappings.mappingNamed(repo, token.trim());
				if (m == null)
				{
					return ConnectorsResource.fail(404, "No mapping '" + token.trim() + "'");
				}
				if (!m.appliesToAnyDataspace(pDataspace, pDataset, pTable))
				{
					return ConnectorsResource.fail(400, "Mapping " + m.getId() + " (" + m.getLabel() + ") is not a mapping of " + pTable);
				}
				mappings.add(m);
			}
		}
		else if (pConnectors == null || pConnectors.trim().isEmpty())
		{
			for (final MappingDefinition m : all)
			{
				if (m.getDirection() == MappingDefinition.Direction.INBOUND && !m.isFeed())
				{
					mappings.add(m);
				}
			}
		}
		else
		{
			for (final String wanted : pConnectors.split(","))
			{
				final String token = wanted.trim();
				final int slash = token.indexOf('/');
				final String connectorId = slash < 0 ? token : token.substring(0, slash);
				final String operation = slash < 0 ? null : token.substring(slash + 1);
				for (final MappingDefinition m : all)
				{
					if (m.getDirection() == MappingDefinition.Direction.INBOUND && !m.isFeed() && m.getConnectorId().equalsIgnoreCase(connectorId)
						&& (operation == null || m.getOperation().equalsIgnoreCase(operation)))
					{
						mappings.add(m);
					}
				}
			}
		}
		final String dataspace;
		try
		{
			dataspace = MappingDefinition.runDataspace(pDataspace, mappings);
		}
		catch (final IllegalArgumentException ex)
		{
			return ConnectorsResource.fail(400, ex.getMessage());
		}
		return this.run(session, repository, dataspace, pDataset, pTable, pKey, pPredicate, mappings, pDryRun);
	}

	private Response run(final Session pSession, final Repository pRepository, final String pDataspace, final String pDataset, final String pTable,
		final String pKey, final String pPredicate, final List<MappingDefinition> pMappings, final boolean pDryRun)
	{
		return this.run(pSession, pRepository, pDataspace, pDataset, pTable, pKey, pPredicate, pMappings, pDryRun, null);
	}

	private Response run(final Session pSession, final Repository pRepository, final String pDataspace, final String pDataset, final String pTable,
		final String pKey, final String pPredicate, final List<MappingDefinition> pMappings, final boolean pDryRun, final List<String> pIgnoredInputs)
	{
		final DatasetLocator.Location located = DatasetLocator.locate(pRepository, pDataspace, pDataset, pTable);
		final Adaptation container = located.getDataset();
		if (container == null)
		{
			return ConnectorsResource.fail(404, located.failure(pDataspace, pDataset, pTable));
		}
		final AdaptationTable records = container.getTable(com.orchestranetworks.schema.Path.parse(pTable));
		Adaptation record = null;
		if (pKey != null && !pKey.isEmpty())
		{
			record = records.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKey));
		}
		else if (pPredicate != null && !pPredicate.isEmpty())
		{
			final Request request = records.createRequest();
			request.setSession(pSession);
			request.setXPathFilter(pPredicate);
			final RequestResult rows = request.execute();
			try
			{
				record = rows.nextAdaptation();
			}
			finally
			{
				rows.close();
			}
		}
		if (record != null && pSession.getPermissions().getAdaptationAccessPermission(record).isHidden())
		{
			record = null;
		}
		if (record == null)
		{
			return ConnectorsResource.fail(404, "No record for key/predicate in " + pTable);
		}
		final String runId = ConnectorService.beginRun();
		try
		{
		final ObjectNode out = Json.object();
		out.put("runId", runId);
		out.put("record", record.getLabel(this.sessionContext.getLocale()));
		out.put("dryRun", pDryRun);
		final ArrayNode items = out.putArray("results");
		int ok = 0;
		int errors = 0;
		int conflicts = 0;
		final List<SyncResult> runResults = new java.util.ArrayList<SyncResult>();
		final String origin = "REST " + pSession.getUserReference().getUserId();
		for (final MappingDefinition mapping : pMappings)
		{
			final SyncResult result = ConnectorService.get().syncRecord(pSession, record, mapping, pDryRun, origin);
			runResults.add(result);
			record = records.lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey()); // the next mapping may need what this one wrote
			final ObjectNode item = items.addObject();
			item.put("mapping", mapping.getId());
			item.put("label", mapping.getLabel());
			item.put("connector", mapping.getConnectorId());
			item.put("operation", mapping.getOperation());
			item.put("status", result.getStatus() == null ? "" : result.getStatus().name());
			item.put("message", result.getMessage());
			item.put("durationMs", result.getDurationMs());
			final ArrayNode changes = item.putArray("changes");
			for (final MappingResult.Change change : result.getChanges())
			{
				changes.add(change.toString());
			}
			if (!result.getConflicts().isEmpty())
			{
				conflicts += result.getConflicts().size();
				final ArrayNode list = item.putArray("conflicts");
				for (final MappingResult.Conflict conflict : result.getConflicts())
				{
					list.add(conflict.toString());
				}
			}
			if (!result.getErrors().isEmpty())
			{
				final ArrayNode list = item.putArray("errors");
				for (final String error : result.getErrors())
				{
					list.add(error);
				}
			}
			if (result.getStatus() == SyncResult.Status.ERROR)
			{
				errors++;
			}
			else if (result.isSuccess())
			{
				ok++;
			}
		}
		out.put("ok", ok);
		out.put("errors", errors);
		out.put("conflicts", conflicts);
		out.put("hasConflicts", conflicts > 0);
		putOutcome(out, runResults);
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putNotices(out, pIgnoredInputs, null);
		return Response.ok(Json.write(out)).build();
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	// ------------------------------------------------------------------ outbound scopes, runs, replay

	private static ObjectNode resultJson(final SyncResult pResult)
	{
		final ObjectNode item = Json.object();
		item.put("mapping", pResult.getMappingId());
		item.put("connector", pResult.getConnectorId());
		item.put("operation", pResult.getOperation());
		item.put("record", pResult.getRecordLabel());
		item.put("key", pResult.getRecordKey());
		item.put("status", pResult.getStatus() == null ? "" : pResult.getStatus().name());
		item.put("message", pResult.getMessage());
		item.put("durationMs", pResult.getDurationMs());
		final ArrayNode changes = item.putArray("changes");
		for (final MappingResult.Change change : pResult.getChanges())
		{
			changes.add(change.toString());
		}
		if (!pResult.getErrors().isEmpty())
		{
			final ArrayNode list = item.putArray("errors");
			for (final String error : pResult.getErrors())
			{
				list.add(error);
			}
		}
		if (!pResult.getBookkeepingLost().isEmpty())
		{
			final ArrayNode lost = item.putArray("bookkeepingLost");
			for (final String kind : pResult.getBookkeepingLost())
			{
				lost.add(kind);
			}
		}
		return item;
	}

	private static Response resultsResponse(final String pRunId, final boolean pDryRun, final List<SyncResult> pResults, final ObjectNode pExtra)
	{
		return ConnectorsResource.resultsResponse(pRunId, pDryRun, pResults, pExtra, null);
	}

	private static Response resultsResponse(final String pRunId, final boolean pDryRun, final List<SyncResult> pResults, final ObjectNode pExtra,
		final List<String> pIgnoredInputs)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pRunId);
		out.put("dryRun", pDryRun);
		if (pExtra != null)
		{
			out.setAll(pExtra);
		}
		int ok = 0;
		int errors = 0;
		int deleted = 0;
		final ArrayNode items = out.putArray("results");
		for (final SyncResult result : pResults)
		{
			items.add(ConnectorsResource.resultJson(result));
			if (result.getStatus() == SyncResult.Status.ERROR)
			{
				errors++;
			}
			else if (result.isSuccess())
			{
				ok++;
			}
			if ("deleted".equals(result.getMessage() == null ? "" : result.getMessage().startsWith("deleted") ? "deleted" : ""))
			{
				deleted++;
			}
		}
		// the records processed: a "limit reached" notice is said in truncated/message, not counted as a record
		out.put("count", SyncResult.countWithoutLimitNotices(pResults));
		out.put("ok", ok);
		out.put("errors", errors);
		out.put("deleted", deleted);
		putOutcome(out, pResults);
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putNotices(out, pIgnoredInputs, SyncResult.limitMessage(pResults));
		return Response.status("ERROR".equals(out.path("status").asText()) ? 422 : 200).entity(Json.write(out)).build();
	}

	/** PARTIAL means some work may already be committed; do not advertise atomic failure. */
	private static void putOutcome(final ObjectNode out, final List<SyncResult> results)
	{
		final SyncResult.Summary summary = SyncResult.summarize(results);
		out.put("status", summary.status());
		out.put("ok", summary.ok);
		out.put("errors", summary.errors + summary.partial);
		out.put("failed", summary.errors);
		out.put("partial", summary.partial);
		out.put("skipped", summary.skipped);
		out.put("noData", summary.noData);
		// A truncated feed read must never look like a clean run: surfaced here so a caller checking
		// only top-level fields (the MDM Factory's LandingFlow.loadOutcome) can still catch it.
		out.put("truncated", summary.truncated);
		if (summary.available >= 0)
		{
			out.put("available", summary.available);
		}
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putBookkeeping(out, com.on.ebx.connect.core.Bookkeeping.lostIn(results));
	}

	/**
	 * Volume lot 7a: the {@code POST /sync/{mapping}} answer for an inbound feed
	 * ({@code com.on.ebx.connect.core.ImportOutcome}) - the same counters-plus-bounded-samples shape
	 * {@code ConnectorsTools.feedResults} builds for the MCP tool, so both surfaces agree.
	 * <p>
	 * {@code status}, {@code count}, {@code ok}, {@code errors} (a NUMBER, not an array - the failing
	 * count), {@code truncated}, {@code available} and {@code results[].status}/{@code .message}/
	 * {@code .errors[]} keep the EXACT shape and meaning {@link #putOutcome}/{@link #resultsResponse}
	 * already gave them: the MDM Factory's {@code LandingFlow.loadOutcome} reads precisely
	 * {@code status/count/ok/errors} plus {@code results[].errors[]/.status/.message} (verified against
	 * its source, 2026-09-28) and must keep working unchanged. The new, additive fields - created,
	 * updated, noChange, skipped, rejected, conflicts, resultsOmitted, errorSamples[] (the bounded
	 * first-K failing results, a NEW key so it never collides with the legacy numeric {@code errors}) -
	 * are for a caller that wants more than that.
	 */
	private static Response feedResultsResponse(final String pRunId, final boolean pDryRun, final ImportOutcome pOutcome, final ObjectNode pExtra,
		final List<String> pIgnoredInputs)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pRunId);
		out.put("dryRun", pDryRun);
		if (pExtra != null)
		{
			out.setAll(pExtra);
		}
		out.put("status", pOutcome.status());
		out.put("count", pOutcome.getCount());
		out.put("ok", pOutcome.getCreated() + pOutcome.getUpdated() + pOutcome.getNoChange());
		out.put("errors", pOutcome.getErrorCount());
		out.put("created", pOutcome.getCreated());
		out.put("updated", pOutcome.getUpdated());
		out.put("noChange", pOutcome.getNoChange());
		out.put("skipped", pOutcome.getSkipped());
		out.put("rejected", pOutcome.getRejected());
		out.put("conflicts", pOutcome.getConflicts());
		out.put("truncated", pOutcome.isTruncated());
		if (pOutcome.getAvailable() >= 0)
		{
			out.put("available", pOutcome.getAvailable());
		}
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putWatermark(out, pOutcome);
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putBookkeeping(out, pOutcome.getBookkeepingLost());
		final ArrayNode errorSamples = out.putArray("errorSamples");
		for (final SyncResult error : pOutcome.getErrors())
		{
			errorSamples.add(ConnectorsResource.resultJson(error));
		}
		if (pOutcome.isResultsOmitted())
		{
			out.put("resultsOmitted", true);
		}
		else
		{
			final ArrayNode items = out.putArray("results");
			for (final SyncResult result : pOutcome.getResults())
			{
				items.add(ConnectorsResource.resultJson(result));
			}
		}
		com.on.ebx.connect.ebx.mcp.ConnectorsTools.putNotices(out, pIgnoredInputs, pOutcome.getLimitMessage());
		return Response.status("ERROR".equals(out.path("status").asText()) ? 422 : 200).entity(Json.write(out)).build();
	}

	/** Outbound run on a scope: all, unsent, changed, failed, dataspaceDelta (optional predicate narrows the table). */
	private Response runScope(final Session pSession, final Repository pRepository, final MappingDefinition pMapping, final String pScope,
		final String pPredicate, final int pMax, final boolean pDryRun, final List<String> pIgnoredInputs, final boolean pAsync)
	{
		final OutboundScope scope;
		try
		{
			scope = OutboundScope.parse(pScope);
		}
		catch (final IllegalArgumentException ex)
		{
			return ConnectorsResource.fail(400, "Unknown scope '" + pScope + "': all, unsent, changed, failed, dataspaceDelta");
		}
		final DatasetLocator.Location located = DatasetLocator.locate(pRepository, pMapping.getDataspace(), pMapping.getDataset(), pMapping.getTablePath());
		final Adaptation container = located.getDataset();
		if (container == null)
		{
			return ConnectorsResource.fail(404, located.failure(pMapping.getDataspace(), pMapping.getDataset(), pMapping.getTablePath()));
		}
		final AdaptationTable table = container.getTable(com.orchestranetworks.schema.Path.parse(pMapping.getTablePath()));
		if (pAsync)
		{
			final String started;
			try
			{
				started = ConnectorService.get().sendScopeAsync(pSession, pRepository, pMapping, table, pPredicate, scope, pMax,
					"REST " + pSession.getUserReference().getUserId());
			}
			catch (final com.on.ebx.connect.core.TooManyRunsException full)
			{
				return ConnectorsResource.fail(429, full.getMessage());
			}
			catch (final com.on.ebx.connect.core.MappingAlreadyRunningException running)
			{
				return ConnectorsResource.fail(409, running.getMessage());
			}
			final ObjectNode out = com.on.ebx.connect.ebx.mcp.ConnectorsTools.asyncSendStart(started, pMapping.getId(), scope.name());
			out.put("nextStep", "GET /runs/" + started + " for progress and the final status; POST /runs/" + started + "/stop stops it at the next batch boundary.");
			com.on.ebx.connect.ebx.mcp.ConnectorsTools.putNotices(out, pIgnoredInputs, null);
			return Response.ok(Json.write(out)).build();
		}
		Request request = null;
		if (pPredicate != null && !pPredicate.trim().isEmpty())
		{
			request = table.createRequest();
			request.setSession(pSession);
			request.setXPathFilter(pPredicate.trim());
		}
		final String runId = ConnectorService.beginRun();
		try
		{
			final List<SyncResult> results = ConnectorService.get().sendScope(pSession, pRepository, pMapping, table, request, scope, pMax, pDryRun,
				"REST " + pSession.getUserReference().getUserId());
			final ObjectNode extra = Json.object();
			extra.put("mapping", pMapping.getId());
			extra.put("scope", scope.name());
			return ConnectorsResource.resultsResponse(runId, pDryRun, results, extra, pIgnoredInputs);
		}
		catch (final ConnectorException ex)
		{
			return ConnectorsResource.fail(400, ex.getMessage());
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	/** Replays the failed calls of a run. */
	@POST
	@Path("/replay/{runId}")
	public Response replay(@PathParam("runId") final String pRunId, @QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun)
	{
		final Response refused = this.refused("replay", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final String runId = ConnectorService.beginRun();
		try
		{
			final List<SyncResult> results = ConnectorService.get().replay(session, repository, pRunId, pDryRun, "replay " + pRunId + " by " + session.getUserReference().getUserId());
			final ObjectNode extra = Json.object();
			extra.put("replayOf", pRunId);
			return ConnectorsResource.resultsResponse(runId, pDryRun, results, extra);
		}
		catch (final ConnectorException ex)
		{
			return ConnectorsResource.fail(400, ex.getMessage());
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	/**
	 * The log rows of a run, plus - volume lot 7b - the same {@code execution{}} state
	 * {@code connectors_run_get} (MCP) reports for a feed import (sync or async, batched or not):
	 * status (RUNNING/DONE/PARTIAL/ERROR/STOPPED/INTERRUPTED), running counters, {@code nextIndex}
	 * (for a resumed import), truncation/availability and a bounded sample of the first failing
	 * keys/messages. {@code calls[]} is one page, newest first ({@code callsMax} default 50, at most 500, 0 = the log is not
	 * read; {@code callsFrom} skips rows; {@code callsCut} says when more exist). Built by the exact same {@code ConnectorsTools.runStatusJson} the MCP tool uses,
	 * not a separate, drifting reimplementation - additive only ({@code calls[]} rows now also carry
	 * {@code id}/{@code timestamp}/{@code direction}/{@code runId}/{@code origin}; every field this
	 * endpoint answered before keeps the same name and meaning).
	 */
	@GET
	@Path("/runs/{runId}")
	public Response run(@PathParam("runId") final String pRunId, @QueryParam("callsMax") @DefaultValue("-1") final int pCallsMax,
		@QueryParam("callsFrom") @DefaultValue("0") final int pCallsFrom)
	{
		final Response refused = this.refused("run", ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ObjectNode out = ConnectorsTools.runStatusJson(ConnectorService.get().repository(repository), pRunId, pCallsMax, pCallsFrom);
		return Response.ok(Json.write(out)).build();
	}

	/**
	 * Volume lot 7b: REST parity with the MCP {@code connectors_run_stop} tool - flags a still-running
	 * batched (or streamed) import for cancellation at its next batch boundary. Takes effect only if
	 * the run is still active on this node; {@code GET /runs/{runId}} shows whether it actually
	 * stopped. No EBX installer step is needed (a pure in-JVM flag), unlike every other endpoint here.
	 */
	@POST
	@Path("/runs/{runId}/stop")
	public Response stopRun(@PathParam("runId") final String pRunId)
	{
		final Response refused = this.refused("stopRun", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		if (pRunId == null || pRunId.trim().isEmpty())
		{
			return ConnectorsResource.fail(400, "runId is required");
		}
		ConnectorService.requestCancel(pRunId.trim());
		final ObjectNode out = Json.object();
		out.put("runId", pRunId.trim());
		out.put("requested", true);
		out.put("note", "Takes effect at the run's next batch boundary, only if it is still running on this node; GET /runs/{runId} shows whether it actually stopped.");
		return Response.ok(Json.write(out)).build();
	}

	/** Outbound state of a mapping: what was sent, what failed (status SENT, FAILED, DELETED; default: FAILED). */
	@GET
	@Path("/outbound")
	public Response outbound(@QueryParam("mapping") final String pMapping, @QueryParam("status") @DefaultValue("FAILED") final String pStatus,
		@QueryParam("max") final Integer pMax, @QueryParam("from") final Integer pFrom)
	{
		final Response refused = this.refused("outbound", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final ConnectorRepository repo = ConnectorService.get().repository(repository);
		final MappingDefinition mapping = pMapping == null ? null : Mappings.mappingNamed(repo, pMapping);
		if (mapping == null)
		{
			return ConnectorsResource.fail(404, "No mapping '" + pMapping + "' (parameter mapping is required)");
		}
		// page: max (default 200, at most 2000), from (0-based); same answer as the MCP tool connectors_outbound_states (audit C2-07)
		final ObjectNode out = ConnectorsTools.outboundPage(repo, session, mapping.getId(), pStatus, pFrom == null ? 0 : pFrom.intValue(), pMax == null ? ListingPage.DEFAULT_MAX : pMax.intValue());
		return Response.ok(Json.write(out)).build();
	}

	// ------------------------------------------------------------------ conflicts

	private static ObjectNode conflictJson(final Adaptation pConflict, final java.util.Locale pLocale)
	{
		final ObjectNode c = Json.object();
		c.put("id", pConflict.getOccurrencePrimaryKey().format());
		c.put("status", pConflict.getString(ConnectorsPaths.Conflict.STATUS));
		c.put("dataspace", pConflict.getString(ConnectorsPaths.Conflict.DATASPACE));
		c.put("dataset", pConflict.getString(ConnectorsPaths.Conflict.DATASET));
		c.put("table", pConflict.getString(ConnectorsPaths.Conflict.TABLE_PATH));
		c.put("recordKey", pConflict.getString(ConnectorsPaths.Conflict.RECORD_KEY));
		c.put("record", pConflict.getString(ConnectorsPaths.Conflict.RECORD_LABEL));
		c.put("field", pConflict.getString(ConnectorsPaths.Conflict.FIELD_PATH));
		c.put("currentValue", pConflict.getString(ConnectorsPaths.Conflict.CURRENT_VALUE));
		c.put("currentConnector", pConflict.getString(ConnectorsPaths.Conflict.CURRENT_CONNECTOR));
		final Integer currentTrust = (Integer) pConflict.get(ConnectorsPaths.Conflict.CURRENT_TRUST);
		c.put("currentTrust", currentTrust == null ? 100 : currentTrust.intValue());
		c.put("proposedValue", pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE));
		c.put("proposedConnector", pConflict.getString(ConnectorsPaths.Conflict.PROPOSED_CONNECTOR));
		final Integer proposedTrust = (Integer) pConflict.get(ConnectorsPaths.Conflict.PROPOSED_TRUST);
		c.put("proposedTrust", proposedTrust == null ? 0 : proposedTrust.intValue());
		c.put("mapping", pConflict.getString(ConnectorsPaths.Conflict.MAPPING));
		final java.util.Date detected = (java.util.Date) pConflict.get(ConnectorsPaths.Conflict.DETECTED_AT);
		c.put("detectedAt", detected == null ? null : new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(detected));
		c.put("comment", pConflict.getString(ConnectorsPaths.Conflict.COMMENT));
		if (pConflict.getSchemaNode().getNode(ConnectorsPaths.Conflict.RESOLVED_BY) != null)
		{
			c.put("resolvedBy", pConflict.getString(ConnectorsPaths.Conflict.RESOLVED_BY));
		}
		return c;
	}

	/** Conflicts to arbitrate (status OPEN by default), narrowed by dataspace, dataset, table and record key. */
	@GET
	@Path("/conflicts")
	public Response conflicts(@QueryParam("status") @DefaultValue("OPEN") final String pStatus, @QueryParam("dataspace") final String pDataspace,
		@QueryParam("dataset") final String pDataset, @QueryParam("table") final String pTable, @QueryParam("key") final String pKey)
	{
		final Response refused = this.refused("conflicts", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(repository, session);
		final ConflictResolver resolver = new ConflictResolver(ConnectorService.get().repository(repository));
		final ConflictResolver.Page page = resolver.page(session, pStatus, pDataspace, pDataset, pTable, pKey, ConflictResolver.pageSize());
		final java.util.List<Adaptation> rows = page.rows;
		final ObjectNode out = Json.object();
		out.put("status", pStatus);
		// count is every conflict that matches; conflicts[] holds the newest ones up to the page size (audit C-39)
		out.put("count", page.total);
		out.put("hasConflicts", page.total > 0);
		if (page.truncated())
		{
			out.put("truncated", true);
			out.put("returned", rows.size());
			out.put("nextStep", "Only the newest " + rows.size() + " of " + page.total + " conflicts are listed: narrow by dataspace, dataset, table or key, or decide these and list again.");
		}
		final ArrayNode list = out.putArray("conflicts");
		for (final Adaptation row : rows)
		{
			list.add(ConnectorsResource.conflictJson(row, this.sessionContext.getLocale()));
		}
		return Response.ok(Json.write(out)).build();
	}

	/** Steward decision: {@code accept} writes the proposed value into the record, {@code reject} keeps the current one. */
	@POST
	@Path("/conflicts/{id}/{decision}")
	public Response decide(@PathParam("id") final String pId, @PathParam("decision") final String pDecision, @QueryParam("comment") final String pComment)
	{
		final Response refused = this.refused("decide", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(repository, session);
		final ConflictResolver resolver = new ConflictResolver(ConnectorService.get().repository(repository));
		final Adaptation conflict = resolver.conflict(pId);
		if (conflict == null)
		{
			return ConnectorsResource.fail(404, "No conflict " + pId);
		}
		final ConflictResolver.Outcome outcome;
		if ("accept".equalsIgnoreCase(pDecision))
		{
			outcome = resolver.accept(session, conflict, pComment);
		}
		else if ("reject".equalsIgnoreCase(pDecision))
		{
			outcome = resolver.reject(session, conflict, pComment);
		}
		else
		{
			return ConnectorsResource.fail(400, "Decision must be accept or reject");
		}
		final ObjectNode out = Json.object();
		out.put("id", pId);
		out.put("ok", outcome.ok);
		out.put("message", outcome.message);
		final Adaptation after = resolver.conflict(pId);
		if (after != null)
		{
			out.set("conflict", ConnectorsResource.conflictJson(after, this.sessionContext.getLocale()));
		}
		return Response.status(outcome.ok ? 200 : 409).entity(Json.write(out)).build();
	}

	// ------------------------------------------------------------------ configuration transfer

	/** {@code GET /config?connector=&mappings=true}: the configuration as one JSON document (no secret). */
	@GET
	@Path("/config")
	public Response configExport(@QueryParam("connector") final String pConnector, @QueryParam("mappings") @DefaultValue("true") final boolean pMappings)
	{
		final Response refused = this.refused("configExport", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		return Response.ok(Json.write(com.on.ebx.connect.ebx.ConfigTransfer.export(repository, pConnector, pMappings))).build();
	}

	/** {@code POST /config?dryRun=true} with an exported document as body: creates or updates connectors and mappings, never secrets. */
	@POST
	@Path("/config")
	@Consumes({ MediaType.APPLICATION_JSON, MediaType.WILDCARD })
	public Response configImport(@QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun,
		@HeaderParam("Content-Length") final String pContentLength, final java.io.InputStream pStream)
	{
		final Response refused = this.refused("configImport", ConnectorsAccess.Right.MANAGE);
		if (refused != null)
		{
			return refused;
		}
		final String[] bodyText = new String[1];
		final Response tooBig = this.readBody(pContentLength, pStream, bodyText);
		if (tooBig != null)
		{
			return tooBig;
		}
		final String pBody = bodyText[0];
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		try
		{
			final ObjectNode report = com.on.ebx.connect.ebx.ConfigTransfer.importConfig(session, repository, Json.parse(pBody == null ? "" : pBody), pDryRun);
			return Response.status(report.has("error") ? 400 : 200).entity(Json.write(report)).build();
		}
		catch (final java.io.IOException ex)
		{
			return ConnectorsResource.fail(400, "Invalid JSON: " + ex.getMessage());
		}
	}

	/**
	 * {@code POST /publish}: publishes a data product as a whole to a {@code TablePublisher} connector
	 * (Snowflake, v1 scope) — one atomic commit for every table of one or more root outbound mappings
	 * (MCP {@code connectors_publish}). Body: {@code {"mappings":[210,211],"schema":"DB.SCHEMA",
	 * "product":"COMPANY_360","contractVersion":"1","dryRun":false,"recreate":false,"max":0,
	 * "dataspace":"Companies"}}; {@code mappings} entries are ids or labels; {@code max} 0 or absent reads every
	 * row, a positive value cuts each table there and the status is then PARTIAL (audit C-17). 200 for
	 * OK/PARTIAL/DRY_RUN, 422 for ERROR; the report (the {@code Publication.report} contract) is the body either way.
	 */
	@POST
	@Path("/publish")
	@Consumes({ MediaType.APPLICATION_JSON, MediaType.WILDCARD })
	public Response publish(@HeaderParam("Content-Length") final String pContentLength, final java.io.InputStream pStream)
	{
		final Response refused = this.refused("publish", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final String[] bodyText = new String[1];
		final Response tooBig = this.readBody(pContentLength, pStream, bodyText);
		if (tooBig != null)
		{
			return tooBig;
		}
		final String pBody = bodyText[0];
		final Session session = this.sessionContext.getSession();
		final Repository repository = this.sessionContext.getRepository();
		ConnectorsInstaller.ensure(repository, session);
		final JsonNode document;
		try
		{
			document = Json.parse(pBody == null ? "" : pBody);
		}
		catch (final java.io.IOException ex)
		{
			return ConnectorsResource.fail(400, "Invalid JSON: " + ex.getMessage());
		}
		if (document == null || !document.isObject())
		{
			return ConnectorsResource.fail(400, "A JSON object body is required");
		}
		final ConnectorRepository repo = ConnectorService.get().repository(repository);
		final List<Integer> ids = new ArrayList<Integer>();
		final JsonNode mappingsNode = document.get("mappings");
		if (mappingsNode != null && mappingsNode.isArray())
		{
			for (final JsonNode item : mappingsNode)
			{
				final String token = item.isTextual() ? item.asText() : item.asText(String.valueOf(item.asInt()));
				final MappingDefinition m = Mappings.mappingNamed(repo, token);
				if (m == null)
				{
					return ConnectorsResource.fail(404, "No mapping '" + token + "'");
				}
				ids.add(Integer.valueOf(m.getId()));
			}
		}
		else if (mappingsNode != null && mappingsNode.isTextual())
		{
			for (final String token : mappingsNode.asText().split(","))
			{
				final String trimmed = token.trim();
				if (trimmed.isEmpty())
				{
					continue;
				}
				final MappingDefinition m = Mappings.mappingNamed(repo, trimmed);
				if (m == null)
				{
					return ConnectorsResource.fail(404, "No mapping '" + trimmed + "'");
				}
				ids.add(Integer.valueOf(m.getId()));
			}
		}
		if (ids.isEmpty())
		{
			return ConnectorsResource.fail(400, "mappings is required");
		}
		final ProductPublisher.Request request = new ProductPublisher.Request();
		request.setMappings(ids);
		request.setSchema(document.path("schema").asText(null));
		request.setProduct(document.path("product").asText(null));
		if (document.hasNonNull("contractVersion"))
		{
			request.setContractVersion(document.path("contractVersion").asText());
		}
		request.setDescription(document.path("description").asText(null));
		request.setOwner(document.path("owner").asText(null));
		request.setDomain(document.path("domain").asText(null));
		request.setTitle(document.path("title").asText(null));
		request.setDryRun(document.path("dryRun").asBoolean(false));
		request.setRecreate(document.path("recreate").asBoolean(false));
		if (document.hasNonNull("max"))
		{
			request.setMax(document.path("max").asInt(0));
		}
		if (document.hasNonNull("dataspace"))
		{
			request.setDataspace(document.path("dataspace").asText());
		}
		final ProductPublisher.Report report = new ProductPublisher(session, repo).publish(request);
		return Response.status(report.isError() ? 422 : 200).entity(Json.write(report.toJson())).build();
	}

	// ------------------------------------------------------------------ webhook

	@GET
	@Path("/webhook/{feed}")
	public Response webhookInfo(@PathParam("feed") final String pFeed)
	{
		// C2-16: the feed's table and connection are the module's configuration, read at the RUN right like POST /webhook
		final Response refused = this.refused("webhookInfo", ConnectorsAccess.Right.RUN);
		if (refused != null)
		{
			return refused;
		}
		final Repository repository = this.sessionContext.getRepository();
		final MappingDefinition feed = Mappings.feed(ConnectorService.get().repository(repository), pFeed);
		if (feed == null)
		{
			return ConnectorsResource.fail(404, "No feed mapping " + pFeed);
		}
		final ObjectNode out = Json.object();
		out.put("mapping", feed.getId());
		out.put("label", feed.getLabel());
		out.put("connector", feed.getConnectorId());
		out.put("table", feed.getTablePath());
		out.put("itemsPath", feed.getItemsPath());
		out.put("ready", true);
		return Response.ok(Json.write(out)).build();
	}

	@POST
	@Path("/webhook/{feed}")
	@Consumes({ MediaType.APPLICATION_JSON, "application/x-ndjson", "text/csv", MediaType.TEXT_PLAIN, MediaType.WILDCARD })
	public Response webhook(@PathParam("feed") final String pFeed, @QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun,
		@QueryParam("max") @DefaultValue("" + com.on.ebx.connect.core.RunLimits.WEBHOOK) final int pMax, @QueryParam("separator") final String pSeparator,
		@HeaderParam("Content-Length") final String pContentLength, final java.io.InputStream pStream)
	{
		final Repository repository = this.sessionContext.getRepository();
		try
		{
			if (pMax < 1 || pMax > com.on.ebx.connect.core.RunLimits.WEBHOOK)
			{
				return ConnectorsResource.fail(400, "max must be between 1 and " + com.on.ebx.connect.core.RunLimits.WEBHOOK);
			}
			final ConnectorRepository repo = ConnectorService.get().repository(repository);
			final MappingDefinition feed = Mappings.feed(repo, pFeed);
			if (feed == null)
			{
				return ConnectorsResource.fail(404, "No feed mapping " + pFeed);
			}
			final ConnectorConfig connector = repo.connector(feed.getConnectorId());
			if (connector == null || !"webhook".equals(connector.getType()))
			{
				return ConnectorsResource.fail(400, "Mapping " + feed.getId() + " is not bound to a webhook connector");
			}
			if (!connector.isActive())
			{
				return ConnectorsResource.fail(403, "Connector " + connector.getId() + " is inactive");
			}
			// C2-10: an EBX user with the RUN right, like POST /sync. The shared-secret and signature modes are gone (the REST
			// Toolkit never let them through, and they fell back to the login and password stored on the connection).
			final Session session = this.sessionContext.getSessionOrNull();
			final int access = com.on.ebx.connect.core.Webhooks.access(session != null,
				session != null && ConnectorsAccess.has(session, ConnectorsAccess.Right.RUN));
			if (access == com.on.ebx.connect.core.Webhooks.UNAUTHENTICATED)
			{
				return Response.status(401).header("WWW-Authenticate", "Basic realm=\"EBX\"").type(MediaType.APPLICATION_JSON)
						.entity(Json.write(ConnectorsResource.errorBody(com.on.ebx.connect.core.Webhooks.refusal(access)))).build();
			}
			final Response refused = this.refused("webhook", ConnectorsAccess.Right.RUN);
			if (refused != null)
			{
				return refused;
			}
			// audit C2-06: the body is read only now, once the caller is known to hold the RUN right
			final String[] bodyText = new String[1];
			final Response tooBig = this.readBody(pContentLength, pStream, bodyText);
			if (tooBig != null)
			{
				return tooBig;
			}
			final String pBody = bodyText[0];
			ConnectorsInstaller.ensure(repository, session);
			final JsonNode document = ConnectorsResource.parseBody(pBody, this.headers.getMediaType() == null ? "application/json" : this.headers.getMediaType().toString(), pSeparator);
			if (document == null)
			{
				return ConnectorsResource.fail(400, "Empty or invalid body (JSON, ndjson or csv expected)");
			}
			// Volume lot 7a: importDocument now reports counters + a bounded results sample
			// (com.on.ebx.connect.core.ImportOutcome), not one SyncResult per row held for the whole run
			// - see feedResultsResponse (the same shape connectors_sync/POST /sync/{mapping} use).
			final ImportOutcome outcome = ConnectorService.get().importDocument(session, repository, feed, document, pMax, pDryRun,
				"webhook " + connector.getId());
			final ObjectNode extra = Json.object();
			extra.put("mapping", feed.getId());
			extra.put("hasConflicts", outcome.getConflicts() > 0);
			return ConnectorsResource.feedResultsResponse(outcome.getRunId(), pDryRun, outcome, extra, null);
		}
		catch (final Exception ex)
		{
			ConnectorService.log().error("[connectors] webhook failed: " + safeMessage(ex));
			return ConnectorsResource.fail(500, safeMessage(ex));
		}
	}

	static JsonNode parseBody(final String pBody, final String pContentType, final String pSeparator) throws java.io.IOException
	{
		final String content = pBody == null ? "" : pBody.trim();
		if (content.isEmpty())
		{
			return null;
		}
		final String type = pContentType.toLowerCase();
		if (type.contains("csv") || type.startsWith("text/plain"))
		{
			return Csv.parse(content, pSeparator == null || pSeparator.isEmpty() ? ';' : "tab".equals(pSeparator) ? '\t' : pSeparator.charAt(0), 100000);
		}
		if (type.contains("ndjson") || type.contains("jsonl"))
		{
			final ObjectNode out = Json.object();
			final ArrayNode rows = out.putArray("rows");
			for (final String line : content.split("\r?\n"))
			{
				if (!line.trim().isEmpty())
				{
					rows.add(Json.parse(line));
				}
			}
			out.put("count", rows.size());
			return out;
		}
		return Json.parse(content);
	}

	/**
	 * Health of the module for an operator or a probe: installation, outbound posture, retention, runs and the
	 * asynchronous pool, see {@link ConnectorsStatus}. Reads only, installs nothing, carries no secret and no
	 * connector.
	 */
	@GET
	@Path("/status")
	public Response status()
	{
		final Response refused = this.refused("status", ConnectorsAccess.Right.READ);
		if (refused != null)
		{
			return refused;
		}
		return Response.ok(Json.write(ConnectorsStatus.build(this.sessionContext.getRepository()))).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * Null when the caller holds the Connectors right this route asks ({@link ConnectorsAccess}); the 403 to
	 * return otherwise. Asked first, before the route reads or installs anything.
	 */
	/**
	 * Audit C2-06: the body of a route that takes a document, read AFTER the caller's right was checked, at most one byte past
	 * {@link BodyLimit#PROP_REST_BODY}, and refused when it nests deeper than {@link BodyLimit#PROP_REST_DEPTH}. Returns the
	 * refusal (413 or 400, coded, naming the property to raise) or null with the text in {@code pOut[0]} ("" when empty).
	 */
	private Response readBody(final String pContentLength, final java.io.InputStream pStream, final String[] pOut)
	{
		final java.util.Locale locale = ConnectorsAccess.localeOf(this.headers == null ? null : this.headers.getHeaderString("Accept-Language"));
		return ConnectorsResource.readBounded(pContentLength, pStream, pOut, locale);
	}

	static Response readBounded(final String pContentLength, final java.io.InputStream pStream, final String[] pOut, final java.util.Locale pLocale)
	{
		try
		{
			pOut[0] = BodyLimit.readText(pStream, pContentLength, BodyLimit.maxRestBodyBytes(), BodyLimit.PROP_REST_BODY);
		}
		catch (final BodyLimit.TooLarge ex)
		{
			return ConnectorsResource.violation(ex.violation, pLocale);
		}
		catch (final java.io.IOException ex)
		{
			return ConnectorsResource.fail(400, "The request body could not be read: " + ex.getMessage());
		}
		final BodyLimit.Violation deep = BodyLimit.checkDepth(pOut[0], BodyLimit.maxRestDepth(), BodyLimit.PROP_REST_DEPTH);
		return deep == null ? null : ConnectorsResource.violation(deep, pLocale);
	}

	private static Response violation(final BodyLimit.Violation pViolation, final java.util.Locale pLocale)
	{
		final ObjectNode out = Json.object();
		out.put("code", pViolation.code);
		out.put("error", pViolation.message(pLocale));
		out.put("property", pViolation.property);
		out.put("limit", pViolation.limit);
		return Response.status(pViolation.status).type(MediaType.APPLICATION_JSON).entity(Json.write(out)).build();
	}

	private Response refused(final String pRoute, final ConnectorsAccess.Right pNeeded)
	{
		final Session session = this.sessionContext.getSessionOrNull();
		final boolean allowed = ConnectorsAccess.has(session, pNeeded);
		if (com.on.ebx.connect.ebx.ConnectorsAudit.traced(pNeeded))
		{
			// a route that runs or changes something leaves one line in the kernel log when its answer leaves
			RestAudit.begin(pRoute, ConnectorsAccess.userOf(session), !allowed);
		}
		if (allowed)
		{
			return null;
		}
		ConnectorService.log().warn("[connectors] access denied: user " + ConnectorsAccess.userOf(session) + ", REST " + pRoute + " (needs " + pNeeded + ")");
		return ConnectorsResource.fail(403, ConnectorsAccess.refusal("this route", pNeeded,
			ConnectorsAccess.localeOf(this.headers == null ? null : this.headers.getHeaderString("Accept-Language"))));
	}

	private static ObjectNode errorBody(final String pMessage)
	{
		final ObjectNode out = Json.object();
		out.put("error", pMessage);
		return out;
	}

	static Response fail(final int pStatus, final String pMessage)
	{
		final ObjectNode out = Json.object();
		out.put("error", com.on.ebx.connect.core.Redaction.redact(pMessage));
		return Response.status(pStatus).type(MediaType.APPLICATION_JSON).entity(Json.write(out)).build();
	}
}
