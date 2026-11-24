package com.on.ebx.connect.ebx.mcp;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.orchestranetworks.mcp.annotations.McpServer;
import com.orchestranetworks.mcp.annotations.McpTool;
import com.orchestranetworks.mcp.annotations.McpToolParam;
import com.orchestranetworks.mcp.annotations.ToolBehavior;
import com.orchestranetworks.mcp.core.McpContext;

/**
 * The module's MCP server for the native MCP runtime of EBX 6.3 (public API: annotations
 * {@code @McpServer}, {@code @McpTool}, {@code @McpToolParam}). Every tool delegates to
 * {@link ConnectorsTools}; this class is only loaded and registered when that runtime exists (see
 * {@link com.on.ebx.connect.ebx.ConnectorsModuleRegistration}), so the module still runs on older
 * EBX versions, where the same tools are served by {@link com.on.ebx.connect.ebx.rest.McpEndpoint}.
 * Served at {@code /ebx-assistant/mcp/v1/ebx-connectors} once EBX accepts the registration.
 */
@McpServer(ConnectorsTools.SERVER_NAME)
public final class ConnectorsMcpServer
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public ConnectorsMcpServer()
	{
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> invoke(final McpContext pContext, final String pTool, final Object... pArgs)
	{
		final ObjectNode args = Json.object();
		for (int i = 0; i + 1 < pArgs.length; i += 2)
		{
			if (pArgs[i + 1] != null)
			{
				args.set(String.valueOf(pArgs[i]), ConnectorsMcpServer.MAPPER.valueToTree(pArgs[i + 1]));
			}
		}
		final ConnectorsTools tools = new ConnectorsTools(pContext.getSessionContext().getSession(), pContext.getSessionContext().getRepository(), pContext.getLocale());
		return ConnectorsMcpServer.MAPPER.convertValue(tools.invoke(pTool, args), Map.class);
	}

	@McpTool(name = ConnectorsTools.T_HELP, description = ConnectorsTools.D_HELP, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> help(final McpContext pContext)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_HELP);
	}

	@McpTool(name = ConnectorsTools.T_LIST, description = ConnectorsTools.D_LIST, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> list(final McpContext pContext,
		@McpToolParam(name = "type", description = "Only the connectors of this type (gleif, rest, kafka...)", required = false) final String pType)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_LIST, "type", pType);
	}

	@McpTool(name = ConnectorsTools.T_DESCRIBE, description = ConnectorsTools.D_DESCRIBE, behavior = ToolBehavior.READ_ONLY, openWorldHint = true)
	public Map<String, Object> describe(final McpContext pContext, @McpToolParam(name = "connector", description = "Connector id") final String pConnector,
		@McpToolParam(name = "operation", description = "Operation whose document fields are described", required = false) final String pOperation,
		@McpToolParam(name = "inputs", description = "Inputs of the operation for the sample, as a JSON object", required = false) final Map<String, Object> pInputs)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_DESCRIBE, "connector", pConnector, "operation", pOperation, "inputs", pInputs);
	}

	@McpTool(name = ConnectorsTools.T_CALL, description = ConnectorsTools.D_CALL, behavior = ToolBehavior.ADDITIVE, openWorldHint = true)
	public Map<String, Object> call(final McpContext pContext, @McpToolParam(name = "connector", description = "Connector id") final String pConnector,
		@McpToolParam(name = "operation", description = "Operation name") final String pOperation,
		@McpToolParam(name = "inputs", description = "Inputs as a JSON object, e.g. {\"lei\":\"969500MMPQVHK671GT54\"}", required = false) final Map<String, Object> pInputs,
		@McpToolParam(name = "allowWrite", description = "true to allow a write operation (default false)", required = false) final Boolean pAllowWrite,
		@McpToolParam(name = "document", description = "The document to send for a WRITE operation (JSON object), e.g. {\"Name\":\"Acme\"}; refused for a read/search operation", required = false) final Map<String, Object> pDocument)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_CALL, "connector", pConnector, "operation", pOperation, "inputs", pInputs, "allowWrite", pAllowWrite, "document", pDocument);
	}

	@McpTool(name = ConnectorsTools.T_MAPPINGS, description = ConnectorsTools.D_MAPPINGS, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> mappings(final McpContext pContext, @McpToolParam(name = "table", description = "Table path, e.g. /root/Companies", required = false) final String pTable,
		@McpToolParam(name = "connector", description = "Connector id", required = false) final String pConnector,
		@McpToolParam(name = "direction", description = "INBOUND or OUTBOUND", required = false) final String pDirection)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_MAPPINGS, "table", pTable, "connector", pConnector, "direction", pDirection);
	}

	@McpTool(name = ConnectorsTools.T_MAPPING, description = ConnectorsTools.D_MAPPING, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> mapping(final McpContext pContext, @McpToolParam(name = "mapping", description = "Mapping id or label") final String pMapping)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_MAPPING, "mapping", pMapping);
	}

	@McpTool(name = ConnectorsTools.T_UPSERT, description = ConnectorsTools.D_UPSERT, behavior = ToolBehavior.ADDITIVE)
	public Map<String, Object> mappingUpsert(final McpContext pContext,
		@McpToolParam(name = "mapping", description = "The mapping document (JSON object): connector, operation, direction, dataset, tablePath, label, inputs, rules, children...") final Map<String, Object> pMapping,
		@McpToolParam(name = "dryRun", description = "true: validate without writing (default false)", required = false) final Boolean pDryRun)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_UPSERT, "mapping", pMapping, "dryRun", pDryRun);
	}

	@McpTool(name = ConnectorsTools.T_DELETE, description = ConnectorsTools.D_DELETE, behavior = ToolBehavior.DESTRUCTIVE)
	public Map<String, Object> mappingDelete(final McpContext pContext, @McpToolParam(name = "mapping", description = "Mapping id") final String pMapping)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_DELETE, "mapping", pMapping);
	}

	@McpTool(name = ConnectorsTools.T_ENRICH, description = ConnectorsTools.D_ENRICH, behavior = ToolBehavior.ADDITIVE, openWorldHint = true)
	public Map<String, Object> enrich(final McpContext pContext, @McpToolParam(name = "dataset", description = "Dataset name, e.g. Companies") final String pDataset,
		@McpToolParam(name = "table", description = "Table path, e.g. /root/Companies") final String pTable,
		@McpToolParam(name = "dataspace", description = "Dataspace name (default: the dataspace the selected mappings are bound to, else the first dataspace holding the dataset)", required = false) final String pDataspace,
		@McpToolParam(name = "key", description = "Primary key of the record, e.g. 740", required = false) final String pKey,
		@McpToolParam(name = "predicate", description = "XPath predicate selecting the record when no key", required = false) final String pPredicate,
		@McpToolParam(name = "mappings", description = "Ordered, comma-separated mapping ids or labels, e.g. 30,25", required = false) final String pMappings,
		@McpToolParam(name = "connectors", description = "Comma-separated connector ids or connector/operation", required = false) final String pConnectors,
		@McpToolParam(name = "dryRun", description = "true: compute without writing (default false)", required = false) final Boolean pDryRun)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_ENRICH, "dataset", pDataset, "table", pTable, "dataspace", pDataspace, "key", pKey, "predicate", pPredicate,
			"mappings", pMappings, "connectors", pConnectors, "dryRun", pDryRun);
	}

	@McpTool(name = ConnectorsTools.T_SYNC, description = ConnectorsTools.D_SYNC, behavior = ToolBehavior.ADDITIVE, openWorldHint = true)
	public Map<String, Object> sync(final McpContext pContext, @McpToolParam(name = "mapping", description = "Mapping id or label") final String pMapping,
		@McpToolParam(name = "key", description = "Primary key of one record", required = false) final String pKey,
		@McpToolParam(name = "predicate", description = "XPath predicate (one record, or narrows the table for a scope)", required = false) final String pPredicate,
		@McpToolParam(name = "scope", description = "Outbound scope: all, unsent, changed, failed, dataspaceDelta", required = false) final String pScope,
		@McpToolParam(name = "max", description = "Maximum records for a scope or a table, or items for a feed (default " + com.on.ebx.connect.core.RunLimits.API + "); a run cut there says truncated=true", required = false) final Integer pMax,
		@McpToolParam(name = "dryRun", description = "true: build without sending (default false)", required = false) final Boolean pDryRun,
		@McpToolParam(name = "resumeFrom", description = "Inbound feed only: 0-based item index to restart a batched import from (default 0)", required = false) final Integer pResumeFrom,
		@McpToolParam(name = "async", description = "Real run only: true starts the run in the background and returns {runId, status:RUNNING} immediately (default false). Inbound feed with no key, predicate or scope, or an outbound mapping sent on a scope (no key)", required = false) final Boolean pAsync)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_SYNC, "mapping", pMapping, "key", pKey, "predicate", pPredicate, "scope", pScope, "max", pMax, "dryRun", pDryRun,
			"resumeFrom", pResumeFrom, "async", pAsync);
	}

	@McpTool(name = ConnectorsTools.T_STOP, description = ConnectorsTools.D_STOP, behavior = ToolBehavior.ADDITIVE)
	public Map<String, Object> stop(final McpContext pContext, @McpToolParam(name = "runId", description = "Run id of the still-running import") final String pRunId)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_STOP, "runId", pRunId);
	}

	@McpTool(name = ConnectorsTools.T_REPLAY, description = ConnectorsTools.D_REPLAY, behavior = ToolBehavior.ADDITIVE_RETRYABLE, openWorldHint = true)
	public Map<String, Object> replay(final McpContext pContext, @McpToolParam(name = "runId", description = "Run id, e.g. R20260907-154128-0001") final String pRunId,
		@McpToolParam(name = "dryRun", description = "true: show what would be replayed", required = false) final Boolean pDryRun)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_REPLAY, "runId", pRunId, "dryRun", pDryRun);
	}

	@McpTool(name = ConnectorsTools.T_LINEAGE, description = ConnectorsTools.D_LINEAGE, behavior = ToolBehavior.ADDITIVE_RETRYABLE, openWorldHint = true)
	public Map<String, Object> declareLineage(final McpContext pContext,
		@McpToolParam(name = "mapping", description = "Mapping id or label (default: every mapping)", required = false) final String pMapping)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_LINEAGE, "mapping", pMapping);
	}

	@McpTool(name = ConnectorsTools.T_SOURCE, description = ConnectorsTools.D_SOURCE, behavior = ToolBehavior.ADDITIVE)
	public Map<String, Object> catalogSource(final McpContext pContext, @McpToolParam(name = "connector", description = "Connector id") final String pConnector,
		@McpToolParam(name = "source", description = "Id of the Source to create (default: from the connector id)", required = false) final String pSource,
		@McpToolParam(name = "apply", description = "true: write the change in the catalog (default false: plan only)", required = false) final Boolean pApply)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_SOURCE, "connector", pConnector, "source", pSource, "apply", pApply);
	}

	@McpTool(name = ConnectorsTools.T_RUN, description = ConnectorsTools.D_RUN, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> run(final McpContext pContext, @McpToolParam(name = "runId", description = "Run id") final String pRunId)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_RUN, "runId", pRunId);
	}

	@McpTool(name = ConnectorsTools.T_OUTBOUND, description = ConnectorsTools.D_OUTBOUND, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> outbound(final McpContext pContext, @McpToolParam(name = "mapping", description = "Mapping id or label") final String pMapping,
		@McpToolParam(name = "status", description = "SENT, FAILED (default), DELETED or ALL", required = false) final String pStatus,
		@McpToolParam(name = "max", description = "States per page (default 200, at most 2000: a larger value is lowered to 2000)", required = false) final Integer pMax,
		@McpToolParam(name = "from", description = "States to skip, to read the next page (default 0)", required = false) final Integer pFrom)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_OUTBOUND, "mapping", pMapping, "status", pStatus, "max", pMax, "from", pFrom);
	}

	@McpTool(name = ConnectorsTools.T_CONFLICTS, description = ConnectorsTools.D_CONFLICTS, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> conflicts(final McpContext pContext, @McpToolParam(name = "status", description = "OPEN (default), ACCEPTED or REJECTED", required = false) final String pStatus,
		@McpToolParam(name = "dataspace", description = "Dataspace", required = false) final String pDataspace,
		@McpToolParam(name = "dataset", description = "Dataset", required = false) final String pDataset,
		@McpToolParam(name = "table", description = "Table path", required = false) final String pTable,
		@McpToolParam(name = "recordKey", description = "Record key", required = false) final String pRecordKey)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_CONFLICTS, "status", pStatus, "dataspace", pDataspace, "dataset", pDataset, "table", pTable, "recordKey", pRecordKey);
	}

	@McpTool(name = ConnectorsTools.T_DECIDE, description = ConnectorsTools.D_DECIDE, behavior = ToolBehavior.ADDITIVE)
	public Map<String, Object> decide(final McpContext pContext, @McpToolParam(name = "id", description = "Conflict id") final String pId,
		@McpToolParam(name = "decision", description = "accept or reject") final String pDecision,
		@McpToolParam(name = "comment", description = "Comment stored with the decision", required = false) final String pComment)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_DECIDE, "id", pId, "decision", pDecision, "comment", pComment);
	}

	@McpTool(name = ConnectorsTools.T_LOG, description = ConnectorsTools.D_LOG, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> log(final McpContext pContext, @McpToolParam(name = "mapping", description = "Mapping id", required = false) final String pMapping,
		@McpToolParam(name = "connector", description = "Connector id", required = false) final String pConnector,
		@McpToolParam(name = "recordKey", description = "Record key", required = false) final String pRecordKey,
		@McpToolParam(name = "status", description = "Status", required = false) final String pStatus,
		@McpToolParam(name = "runId", description = "Run id", required = false) final String pRunId,
		@McpToolParam(name = "max", description = "Rows per page, newest first (default 200, at most 2000: a larger value is lowered to 2000)", required = false) final Integer pMax,
		@McpToolParam(name = "from", description = "Rows to skip, to read the next page (default 0)", required = false) final Integer pFrom)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_LOG, "mapping", pMapping, "connector", pConnector, "recordKey", pRecordKey, "status", pStatus, "runId", pRunId, "max", pMax, "from", pFrom);
	}

	@McpTool(name = ConnectorsTools.T_EXPORT, description = ConnectorsTools.D_EXPORT, behavior = ToolBehavior.READ_ONLY)
	public Map<String, Object> configExport(final McpContext pContext, @McpToolParam(name = "connector", description = "Only this connector and its mappings", required = false) final String pConnector,
		@McpToolParam(name = "includeMappings", description = "false to export connectors only (default true)", required = false) final Boolean pIncludeMappings)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_EXPORT, "connector", pConnector, "includeMappings", pIncludeMappings);
	}

	@McpTool(name = ConnectorsTools.T_IMPORT, description = ConnectorsTools.D_IMPORT, behavior = ToolBehavior.DESTRUCTIVE)
	public Map<String, Object> configImport(final McpContext pContext, @McpToolParam(name = "config", description = "The configuration document (JSON object, or its text)") final Object pConfig,
		@McpToolParam(name = "dryRun", description = "true: report only (default false)", required = false) final Boolean pDryRun)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_IMPORT, "config", pConfig, "dryRun", pDryRun);
	}

	@McpTool(name = ConnectorsTools.T_OPENAPI, description = ConnectorsTools.D_OPENAPI, behavior = ToolBehavior.ADDITIVE, openWorldHint = true)
	public Map<String, Object> openapiImport(final McpContext pContext, @McpToolParam(name = "id", description = "Id of the connector to create") final String pId,
		@McpToolParam(name = "label", description = "Label", required = false) final String pLabel,
		@McpToolParam(name = "url", description = "URL of the specification", required = false) final String pUrl,
		@McpToolParam(name = "spec", description = "The specification text when no URL", required = false) final String pSpec,
		@McpToolParam(name = "replace", description = "true to replace an existing connector of that id (its secrets are kept)", required = false) final Boolean pReplace,
		@McpToolParam(name = "dryRun", description = "true: report without creating", required = false) final Boolean pDryRun)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_OPENAPI, "id", pId, "label", pLabel, "url", pUrl, "spec", pSpec, "replace", pReplace, "dryRun", pDryRun);
	}

	@McpTool(name = ConnectorsTools.T_PUBLISH, description = ConnectorsTools.D_PUBLISH, behavior = ToolBehavior.ADDITIVE, openWorldHint = true)
	public Map<String, Object> publish(final McpContext pContext,
		@McpToolParam(name = "mappings", description = "Comma-separated root outbound mapping ids or labels, all on the same connector, e.g. 210 or 210,211") final String pMappings,
		@McpToolParam(name = "schema", description = "Target schema, e.g. SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS") final String pSchema,
		@McpToolParam(name = "product", description = "Product code, e.g. COMPANY_360") final String pProduct,
		@McpToolParam(name = "contractVersion", description = "Contract version (default 1)", required = false) final String pContractVersion,
		@McpToolParam(name = "dryRun", description = "true: plan and sample only, nothing executed (default false)", required = false) final Boolean pDryRun,
		@McpToolParam(name = "recreate", description = "true: replace a target table whose columns differ from the contract instead of failing (default false)", required = false) final Boolean pRecreate,
		@McpToolParam(name = "max", description = "Maximum rows per table (default 0 = every row; a positive value cuts each table there and the status is then PARTIAL, never OK)", required = false) final Integer pMax,
		@McpToolParam(name = "dataspace", description = "Dataspace (default: the mappings' own dataspace, error if ambiguous)", required = false) final String pDataspace)
	{
		return ConnectorsMcpServer.invoke(pContext, ConnectorsTools.T_PUBLISH, "mappings", pMappings, "schema", pSchema, "product", pProduct, "contractVersion", pContractVersion,
			"dryRun", pDryRun, "recreate", pRecreate, "max", pMax, "dataspace", pDataspace);
	}
}
