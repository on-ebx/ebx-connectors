package com.on.ebx.connect.ebx.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConflictResolver;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.RunLogPage;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.ImportOutcome;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ListingPage;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.core.MappingRule;
import com.on.ebx.connect.core.OutboundScope;
import com.on.ebx.connect.core.ProductPublisher;
import com.on.ebx.connect.core.RunLimits;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.ebx.ConfigTransfer;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.ebx.ConnectorsAudit;
import com.on.ebx.connect.ebx.ConnectorsInstaller;
import com.on.ebx.connect.ebx.DatasetLocator;
import com.on.ebx.connect.ebx.OpenApiImport;
import com.on.ebx.connect.ebx.rest.Mappings;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * The MCP tools of the module, independent of any MCP runtime: one method per tool, JSON in,
 * JSON out, an EBX session for permissions. {@link ConnectorsMcpServer} exposes them through the
 * native MCP API of EBX 6.3 (annotations); {@link com.on.ebx.connect.ebx.rest.McpEndpoint} exposes
 * the same tools through the module's own JSON-RPC endpoint on any EBX version. {@link #CATALOG}
 * is the single description of the tools (names, parameters, behaviour).
 */
public final class ConnectorsTools
{
	public static final String SERVER_NAME = "ebx-connectors";
	public static final String SERVER_DESCRIPTION = "Connectors module: configured connectors and mappings, enrichment of records from external services, outbound "
			+ "synchronisation and deltas, replay of failed runs, conflict arbitration, synchronisation log, configuration export/import, OpenAPI import.";

	// tool descriptions (constants: they are also annotation values in ConnectorsMcpServer)
	public static final String T_LIST = "connectors_list";
	public static final String D_LIST = "Lists the connector types registered in the module and the configured connectors (id, label, type, base URL, authentication, trust, pricing, whether secrets are set). Start here.";
	public static final String T_DESCRIBE = "connectors_describe";
	public static final String D_DESCRIBE = "Describes a configured connector: its operations with their inputs, its properties (secrets masked) and, when an operation is given, the fields of the documents it returns or accepts (catalog).";
	public static final String T_CALL = "connectors_call";
	public static final String D_CALL = "Calls one operation of a connector with explicit inputs and returns the raw document, without touching EBX data. Read and search operations only, unless allowWrite=true. "
			+ "document is the document to send for a WRITE operation (create/update/upsert), as JSON object text, e.g. {\"Name\":\"Acme\"} - refused on a read/search operation.";
	public static final String T_MAPPINGS = "connectors_mappings_list";
	public static final String D_MAPPINGS = "Lists the mappings (a mapping binds a connector operation to an EBX table with rules); filters by table path, connector id or direction (INBOUND, OUTBOUND).";
	public static final String T_MAPPING = "connectors_mapping_get";
	public static final String D_MAPPING = "Returns one mapping in full: fields, inputs, rules (source, target, transformation pipeline, write policy) and sub-object mappings.";
	public static final String T_ENRICH = "connectors_enrich";
	public static final String D_ENRICH = "Enriches one EBX record (key or XPath predicate) of a table by running inbound mappings in order: mappings by id or label (recommended), or connectors, or every inbound mapping of the table. dryRun=true shows what would be written. The answer says whether conflicts were logged (TRUSTED policy).";
	public static final String T_SYNC = "connectors_sync";
	public static final String D_SYNC = "Runs one mapping (id or label): on one record (key or predicate); for an outbound mapping, on a scope: all, unsent (never sent), changed (changed since last sent), failed, dataspaceDelta (differences with the initial snapshot of the dataspace); for an inbound feed (a mapping with keyPredicate) without key, predicate or scope, on the items the connector returns (a Kafka topic, a file, a paged API), each located or created by its keyPredicate. dryRun=true builds the documents without sending (a feed: lists the values without writing, Kafka messages not committed). "
		+ "max (default " + RunLimits.API + ") bounds what one run takes: a run cut there says so (truncated=true, status PARTIAL, the sentence in message and warnings[]), never a silent cut. "
		+ "A run-time input the mapping does not read as ${param.name} is not used: the answer lists it in ignoredInputs, says so in message, and a plain OK then reads WARNING. "
		+ "A non-dry-run feed writes in batches of the mapping's batchSize (default 1000): resumeFrom restarts an interrupted feed at that 0-based item index instead of from the start - only meaningful when the connector returns items in the same order every call (a fixed document: a file, most REST list calls; not a paged/streamed source). "
		+ "While a large feed import is still running, its runId (and progress) can be read from connectors_run_get or connectors_log_search before this call returns; connectors_run_stop(runId) then asks it to stop at the next batch boundary. "
		+ "async=true (real run only) starts the run on a background thread and returns {runId, status:RUNNING, async:true} at once instead of waiting for it to finish - poll connectors_run_get(runId) for progress and the final status: for an inbound feed import, and (since wave 13) for an outbound mapping sent on a scope (scope=all|unsent|changed|failed|dataspaceDelta, optional predicate, no key; the answer adds direction:OUTBOUND and scope). "
		+ "An asynchronous scope send counts its records in execution{} (updated = sent, noChange = unchanged since their last send, errorCount, skipped = waiting for their retry delay) at each group of at most 250 records, ends DONE / PARTIAL (cut at max: truncated=true) / ERROR / STOPPED, and is stopped with connectors_run_stop; the records already sent keep their outbound state, so a later scope=changed send does not resend them. Without async the synchronous answer is unchanged. "
		+ "A feed's answer carries status, count, ok and errors (errors is a NUMBER: the count of failing items), counters (created, updated, noChange, skipped, rejected, conflicts) computed as the run goes rather than one entry per row, and results[] and errorSamples[] (the first failing results), both capped (settings resultsMax/errorsMax, default 1000/50) - resultsOmitted:true means results[] is not the whole run (the counters still are); read the full detail from connectors_log_search(runId=...) or the ExecutionRun table. "
		+ "An INCREMENTAL feed (loadMode INCREMENTAL, checkpointStrategy TIMESTAMP, MONOTONIC_ID or ORDERED_STRING, checkpointValuePath = the row field carrying the change value) reads only what changed at the source since its last successful run: the answer adds watermarkField, watermarkBefore (the checkpoint the run started from; null on a first run), watermarkAfter (the checkpoint now: it moves only when the run ends DONE - never on PARTIAL, ERROR, STOPPED or a truncation by max), skippedByWatermark (rows at or below the checkpoint, dropped before any lookup; not part of count), watermarkAdvanced, fullRefresh and, when needed, watermarkNote. fullRefresh=true ignores the checkpoint for this run and resets it at the end. Rows deleted at the source are never seen by a watermark read.";
	public static final String T_STOP = "connectors_run_stop";
	public static final String D_STOP = "Asks a currently-running feed import (connectors_sync on an inbound feed, batched, still in progress on this node) or asynchronous outbound scope send (connectors_sync(scope=..., async=true)) to stop at its next batch boundary - never mid-batch, never a partial write. The run then ends STOPPED with what it did so far in its counters; records an outbound send already sent keep their outbound state. "
		+ "Find the run id of an import still in progress from its batch SyncLog rows (connectors_log_search(runId=...)) or connectors_run_get, since connectors_sync only returns the run id once the whole call completes. "
		+ "In-JVM only: lost on a Tomcat restart, and only effective on the node the import is actually running on. Resume it later with connectors_sync(..., resumeFrom=<next item index>), read from the same log rows or from the ExecutionRun table's nextIndex field.";
	public static final String T_REPLAY = "connectors_replay";
	public static final String D_REPLAY = "Replays the failed calls of a run (runId returned by enrich, sync or the workflow bean).";
	public static final String T_LINEAGE = "connectors_lineage_declare";
	public static final String D_LINEAGE = "Sends the declared lineage of the mappings to the OpenLineage sinks (the data catalog) now, without running them: active mappings declared (EBX table -> connector object, column lineage per rule), inactive ones retired. Done automatically after each mapping change; use it after adding a sink or changing a connector's base URL.";
	public static final String T_SOURCE = "connectors_catalog_source";
	public static final String D_SOURCE = "How the system a connector reaches is known to the ON EBX data catalog, and with apply=true the change that makes its lineage land on the right Source. "
		+ "Only when the catalog is deployed here and verified (its dataset on the catalog's own data model); never deletes. Actions: LINKED (a Source names the connector with its OpenLineage namespace), "
		+ "CREATE (no Source yet), SET_CONNECTOR (the Source the catalog created from lineage events for that namespace), SET_NAMESPACE (the Source naming the connector), "
		+ "MANUAL (duplicate or ambiguous: nothing written). apply=false (default) only reports the plan; the catalog then creates the flows of the Source.";
	public static final String T_RUN = "connectors_run_get";
	public static final String D_RUN = "The state of a run, plus one page of its calls from the synchronisation log (calls[], newest first, with status and message; callsMax rows, default 50, at most 500, 0 skips the log; callsCut says when more exist and callsNextFrom is the callsFrom of the next page; replayable says whether a replay has something to resend). For a feed import (sync or async, batched or not) and for an asynchronous outbound scope send (since wave 13), its persisted execution{} state, with direction (INBOUND or OUTBOUND, also at the top of the answer with the mapping id) when the mapping still exists: status (RUNNING, DONE, PARTIAL, ERROR, STOPPED, INTERRUPTED - the last one meaning a module restart caught it mid-run), running counters (created, updated, noChange, errorCount, skipped, rejected, conflicts), nextIndex (for connectors_sync(resumeFrom=...)), truncated/available, and a bounded sample of the first failing keys/messages; for an incremental feed also watermarkBefore, watermarkAfter and skippedByWatermark. For an outbound send the counters read: updated = records sent, noChange = unchanged since their last send, errorCount = failures (firstErrors), skipped = waiting for their retry delay; created and rejected stay 0, nextIndex is the number of records handled, truncated says the send was cut at max. Poll it right after connectors_sync(async=true) to watch an import or a send progress.";
	public static final String T_OUTBOUND = "connectors_outbound_states";
	public static final String D_OUTBOUND = "Outbound state of the records of a mapping: SENT, FAILED (with the last error and attempts) or DELETED; default FAILED, ALL for everything. Paged: max (default 200, at most 2000) and from; count is the total, cut/nextFrom/note say when more exist.";
	public static final String T_CONFLICTS = "connectors_conflicts_list";
	public static final String D_CONFLICTS = "Lists the conflicts logged by the TRUSTED policy (a less trusted source proposed another value): status OPEN by default, narrowed by dataspace, dataset, table and record key. Newest first, at most 200 returned: count is the real total, truncated says when more exist.";
	public static final String T_DECIDE = "connectors_conflict_decide";
	public static final String D_DECIDE = "Arbitrates one conflict: accept writes the proposed value into the record (the proposing source becomes the provenance), reject keeps the current value; an optional comment is stored with the decision.";
	public static final String T_LOG = "connectors_log_search";
	public static final String D_LOG = "Searches the synchronisation log (most recent first): by mapping id, connector, record key, status (OK, NO_CHANGE, ERROR, SKIPPED, DRY_RUN...), run id. Paged: max (default 200, at most 2000) and from; count is the total, cut/nextFrom/note say when more exist.";
	public static final String T_EXPORT = "connectors_config_export";
	public static final String D_EXPORT = "Exports the configuration as one JSON document: connectors (secrets never exported, only whether they are set), their properties, and the mappings with rules, inputs and sub-object mappings. Optionally one connector only.";
	public static final String T_IMPORT = "connectors_config_import";
	public static final String D_IMPORT = "Imports a configuration document produced by connectors_config_export: connectors matched by id, mappings by connector, operation, direction, table and label; rules and inputs of an imported mapping replace the existing ones; existing secrets are never overwritten. dryRun=true reports without writing.";
	public static final String T_OPENAPI = "connectors_openapi_import";
	public static final String D_OPENAPI = "Creates (or replaces) a generic REST connector from an OpenAPI 3 specification (JSON or YAML), given by URL or as text: one operation per path, authentication from the security schemes.";
	public static final String T_UPSERT = "connectors_mapping_upsert";
	public static final String D_UPSERT = "Creates or updates one mapping from a compact JSON document: connector, operation, direction (INBOUND or OUTBOUND), dataset, tablePath, label (these six identify the mapping; the same label on the same connector/operation/direction/table updates it), optional description, responseRoot, active, batchSize (rows a real feed run writes per transaction: default 1000, at most 10000, 1 writes row by row), logEveryRecord (true: one log line per imported record instead of one per batch), inputs {name: expression}, rules [{sourcePath, targetPath, transformation, policy}] (replaced; policy IF_EMPTY writes a field only while it is empty, so a run after the source changed leaves a filled field as it is - the default for a rule sent without one, kept for compatibility; ALWAYS overwrites at every run, what a feed that must follow its source needs, its key fields kept IF_EMPTY; TRUSTED writes when this source is at least as trusted as the one that wrote the value. The answer says what was applied: rulePolicies {IF_EMPTY, ALWAYS, TRUSTED: count}, policyDefaulted (rules written IF_EMPTY because they had no policy) and a note when that touched an inbound mapping), children [sub-object mappings with tablePath, label, collectionPath, parentLinkField, childKeyFields, childFilter, deleteMissing, rules; connector, operation, direction, dataspace and dataset taken from the parent when omitted]. An inbound feed (items read from a topic, a file or a paged API, each located by a predicate) sets keyPredicate (XPath template, e.g. ./Code='${code}'), itemsPath when one document holds several items (e.g. messages[*].value) and createIfMissing (default true), or the same three under feed {}. When keyPredicate addresses the table's whole primary key with plain equality clauses on string fields (e.g. ./_sourceId='${SOURCE_ID}'), a feed looks the record up directly by primary key instead of an XPath query - no extra configuration needed. provenance (default true): set to false on a feed whose target table already carries its own lineage (a landing table with _loadId/_sourceSystem) so imports skip FieldProvenance bookkeeping entirely. unchangedIfEqual (a target field path, e.g. ./_rowHash): when set and the record already exists, a feed compares the mapped value for that field against the record's current value before writing anything; an exact match reports NO_CHANGE and skips the write, including ALWAYS-policy rules on technical fields that would otherwise always look changed on a reload of the same source row. Incremental feed (read only what changed at the source since the last successful run): loadMode INCREMENTAL, checkpointStrategy TIMESTAMP | MONOTONIC_ID (a number) | ORDERED_STRING (plain string order), checkpointValuePath (the field of the returned row that carries the change value, e.g. LAST_MODIFIED), optional checkpointInitialValue (where the very first run starts) and checkpointBoundary INCLUSIVE | EXCLUSIVE (whether a row equal to the checkpoint is read again; default INCLUSIVE when unchangedIfEqual is set, EXCLUSIVE otherwise). An input expression may use ${watermark} (the checkpoint, or the floor of the type on a first run) and ${watermark.isSet} (1 or 0): in the sql input of a JDBC query, ${watermark} is bound as a typed prepared-statement parameter, never written into the SQL text, e.g. select * from CONTACT s where s.LAST_MODIFIED >= ${watermark}; for a source that cannot filter (a file, a REST list) the engine skips the rows at or below the checkpoint itself, before any transformation. Writing or deleting a mapping sends its declared lineage to the OpenLineage sinks. Use connectors_describe for the document fields and connectors_mapping_get on an existing mapping as a model. dryRun=true validates without writing.";
	public static final String T_DELETE = "connectors_mapping_delete";
	public static final String D_DELETE = "Deletes a mapping with everything attached: rules, inputs, sub-object mappings, outbound states; provenance and conflict rows keep their history but lose the reference (nothing is left orphaned). The synchronisation log is kept.";
	public static final String T_PUBLISH = "connectors_publish";
	public static final String D_PUBLISH = "Publishes a data product as a whole to a TablePublisher connector (Snowflake): one or more root outbound mappings, all on the same connector, "
		+ "each becoming one target table (its input 'table' names it, its rules and active children become columns), replaced in one atomic commit against a contract (full reload, v1 scope). "
		+ "A mapping's input mergedRecords ('exclude', the default, or 'include') controls whether Match and Merge MERGED/DELETED records are left out of that table; crosswalk=true switches the "
		+ "default to 'include' for a key/cross-reference table meant to carry them, typically alongside the golden(tablePath) and mdmState(tablePath) transformations (golden(...) resolves a "
		+ "record's key to its golden record's key when merged; mdmState(...) reports its consolidation state). mappings is a comma-separated list of ids or labels. dryRun=true builds the plan "
		+ "and a 3-row sample per table without touching the connector or the schema; recreate=true replaces a target table whose columns differ from the contract instead of failing. The answer "
		+ "is the publication report: per-table action, columns, row counts, skipped merged/deleted counts and (for a real run) the Publication row and OpenLineage event already recorded. "
		+ "Rows are read and loaded in batches, so a table of any size is published; status OK means every row was published, PARTIAL means a table was cut at max (or at the operator ceiling) and the target holds the cut set.";
	public static final String T_HELP = "connectors_help";
	public static final String D_HELP = "How the module works and which tool to use for what: read this once before configuring or running anything.";

	/** Parameter of a tool: name, description, required, JSON type. */
	public static final class Param
	{
		public final String name;
		public final String description;
		public final boolean required;
		public final String type;

		Param(final String pName, final String pDescription, final boolean pRequired, final String pType)
		{
			this.name = pName;
			this.description = pDescription;
			this.required = pRequired;
			this.type = pType;
		}
	}

	/** A tool of the catalogue. */
	public static final class Tool
	{
		public final String name;
		public final String description;
		/** read, additive or destructive. */
		public final String behavior;
		public final List<Param> params;

		Tool(final String pName, final String pDescription, final String pBehavior, final Param... pParams)
		{
			this.name = pName;
			this.description = pDescription;
			this.behavior = pBehavior;
			this.params = java.util.Arrays.asList(pParams);
		}
	}

	private static Param p(final String pName, final String pDescription)
	{
		return new Param(pName, pDescription, false, "string");
	}

	private static Param req(final String pName, final String pDescription)
	{
		return new Param(pName, pDescription, true, "string");
	}

	private static Param flag(final String pName, final String pDescription)
	{
		return new Param(pName, pDescription, false, "boolean");
	}

	private static Param num(final String pName, final String pDescription)
	{
		return new Param(pName, pDescription, false, "integer");
	}

	public static final List<Tool> CATALOG = java.util.Arrays.asList(
		new Tool(ConnectorsTools.T_HELP, ConnectorsTools.D_HELP, "read"),
		new Tool(ConnectorsTools.T_LIST, ConnectorsTools.D_LIST, "read", p("type", "Only the connectors of this type (gleif, rest, kafka...)")),
		new Tool(ConnectorsTools.T_DESCRIBE, ConnectorsTools.D_DESCRIBE, "read", req("connector", "Connector id"), p("operation", "Operation whose document fields are described (calls the service's catalog or a sample)"),
			p("inputs", "Inputs of the operation for the sample, as JSON object text, e.g. {\"siren\":\"123456789\"}")),
		new Tool(ConnectorsTools.T_CALL, ConnectorsTools.D_CALL, "additive", req("connector", "Connector id"), req("operation", "Operation name"),
			p("inputs", "Inputs as JSON object text, e.g. {\"lei\":\"969500MMPQVHK671GT54\"}"), flag("allowWrite", "true to allow a write operation (default false)"),
			new Param("document", "The document to send for a WRITE operation (JSON object), e.g. {\"Name\":\"Acme\"}; ignored for a read/search operation, which must not pass it", false, "object")),
		new Tool(ConnectorsTools.T_MAPPINGS, ConnectorsTools.D_MAPPINGS, "read", p("table", "Table path, e.g. /root/Companies"), p("connector", "Connector id"), p("direction", "INBOUND or OUTBOUND")),
		new Tool(ConnectorsTools.T_MAPPING, ConnectorsTools.D_MAPPING, "read", req("mapping", "Mapping id or label")),
		new Tool(ConnectorsTools.T_UPSERT, ConnectorsTools.D_UPSERT, "additive", new Param("mapping", "The mapping document (JSON object): connector, operation, direction, dataset, tablePath, label, inputs, rules, children...", true, "object"),
			flag("dryRun", "true: validate without writing (default false)")),
		new Tool(ConnectorsTools.T_DELETE, ConnectorsTools.D_DELETE, "destructive", req("mapping", "Mapping id")),
		new Tool(ConnectorsTools.T_ENRICH, ConnectorsTools.D_ENRICH, "additive", req("dataset", "Dataset name, e.g. Companies"), req("table", "Table path, e.g. /root/Companies"),
			p("dataspace", "Dataspace name (default: the dataspace the selected mappings are bound to, else the first dataspace holding the dataset)"), p("key", "Primary key of the record, e.g. 740"),
			p("predicate", "XPath predicate selecting the record when no key, e.g. ./Identification/Name='Exemplar Industries'"),
			p("mappings", "Ordered, comma-separated mapping ids or labels, e.g. 30,25"), p("connectors", "Comma-separated connector ids or connector/operation, e.g. GLEIF/lei,VIES"),
			flag("dryRun", "true: compute without writing (default false)")),
		new Tool(ConnectorsTools.T_SYNC, ConnectorsTools.D_SYNC, "additive", req("mapping", "Mapping id or label"), p("key", "Primary key of one record"), p("predicate", "XPath predicate (one record, or narrows the table for a scope)"),
			p("scope", "Outbound scope: all, unsent, changed, failed, dataspaceDelta"), p("inputs", "Inbound feed only: runtime inputs as name=value pairs separated by ; e.g. tables=SALES.ORDERS;loadId=42. An input the mapping does not read as ${param.name} is ignored: the answer lists it in ignoredInputs, says so in message, and a plain OK becomes WARNING"),
			num("max", "Maximum records for a scope or a table, or items for a feed (default " + RunLimits.API + "). A run cut at max always says so: truncated=true, status PARTIAL, the sentence in message"), flag("dryRun", "true: build without sending, or list a feed's values without writing (default false)"),
			num("resumeFrom", "Inbound feed only: 0-based item index to restart a batched import from (default 0: from the start)"),
			flag("async", "Real run only: true starts the run in the background and returns {runId, status:RUNNING} immediately instead of waiting for it to finish (default false). For an inbound feed import with no key, predicate or scope, and (since wave 13) for an outbound mapping sent on a scope, no key"),
			flag("fullRefresh", "Incremental feed only (loadMode INCREMENTAL): true ignores the stored watermark for this run, reads everything, and resets the watermark to the highest value seen when the run ends DONE (default false)")),
		new Tool(ConnectorsTools.T_STOP, ConnectorsTools.D_STOP, "additive", req("runId", "Run id of the still-running import, e.g. R20260907-154128-0001")),
		new Tool(ConnectorsTools.T_REPLAY, ConnectorsTools.D_REPLAY, "additive", req("runId", "Run id, e.g. R20260907-154128-0001"), flag("dryRun", "true: show what would be replayed")),
		new Tool(ConnectorsTools.T_LINEAGE, ConnectorsTools.D_LINEAGE, "additive", p("mapping", "Mapping id or label (default: every mapping)")),
		new Tool(ConnectorsTools.T_SOURCE, ConnectorsTools.D_SOURCE, "additive", req("connector", "Connector id"),
			p("source", "Id of the Source to create (default: from the connector id)"), flag("apply", "true: write the change in the catalog (default false: plan only)")),
		new Tool(ConnectorsTools.T_RUN, ConnectorsTools.D_RUN, "read", req("runId", "Run id"), num("callsMax", "Log rows to list, newest first (default 50, at most 500, 0 = none and the log is not read)"), num("callsFrom", "Log rows to skip, to read the next page (default 0)")),
		new Tool(ConnectorsTools.T_OUTBOUND, ConnectorsTools.D_OUTBOUND, "read", req("mapping", "Mapping id or label"), p("status", "SENT, FAILED (default), DELETED or ALL"), num("max", "States per page (default 200, at most 2000: a larger value is lowered to 2000)"), num("from", "States to skip, to read the next page (default 0)")),
		new Tool(ConnectorsTools.T_CONFLICTS, ConnectorsTools.D_CONFLICTS, "read", p("status", "OPEN (default; also lists a conflict whose acceptance did not finish, status APPLYING), ACCEPTED or REJECTED"), p("dataspace", "Dataspace"), p("dataset", "Dataset"), p("table", "Table path"), p("recordKey", "Record key")),
		new Tool(ConnectorsTools.T_DECIDE, ConnectorsTools.D_DECIDE, "additive", req("id", "Conflict id"), req("decision", "accept or reject"), p("comment", "Comment stored with the decision")),
		new Tool(ConnectorsTools.T_LOG, ConnectorsTools.D_LOG, "read", p("mapping", "Mapping id"), p("connector", "Connector id"), p("recordKey", "Record key"), p("status", "Status"), p("runId", "Run id"), num("max", "Rows per page, newest first (default 200, at most 2000: a larger value is lowered to 2000)"), num("from", "Rows to skip, to read the next page (default 0)")),
		new Tool(ConnectorsTools.T_EXPORT, ConnectorsTools.D_EXPORT, "read", p("connector", "Only this connector and its mappings"), flag("includeMappings", "false to export connectors only (default true)")),
		new Tool(ConnectorsTools.T_IMPORT, ConnectorsTools.D_IMPORT, "destructive", req("config", "The configuration document as JSON text"), flag("dryRun", "true: report only (default false)")),
		new Tool(ConnectorsTools.T_OPENAPI, ConnectorsTools.D_OPENAPI, "additive", req("id", "Id of the connector to create"), p("label", "Label"), p("url", "URL of the specification"), p("spec", "The specification text when no URL"),
			flag("replace", "true to replace an existing connector of that id (its secrets are kept)"), flag("dryRun", "true: report without creating")),
		new Tool(ConnectorsTools.T_PUBLISH, ConnectorsTools.D_PUBLISH, "additive", req("mappings", "Comma-separated root outbound mapping ids or labels, all on the same connector, e.g. 210 or 210,211"),
			req("schema", "Target schema, e.g. SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS"), req("product", "Product code, e.g. COMPANY_360"),
			p("contractVersion", "Contract version (default 1)"), flag("dryRun", "true: plan and sample only, nothing executed (default false)"),
			flag("recreate", "true: replace a target table whose columns differ from the contract instead of failing (default false)"),
			num("max", "Maximum rows per table (default 0 = every row, read and loaded in batches; a positive value cuts each table there and the status is then PARTIAL, never OK)"), p("dataspace", "Dataspace (default: the mappings' own dataspace, error if ambiguous)")));

	public static Tool tool(final String pName)
	{
		for (final Tool tool : ConnectorsTools.CATALOG)
		{
			if (tool.name.equals(pName))
			{
				return tool;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ per-call state

	private final Session session;
	private final Repository repository;
	private final Locale locale;

	public ConnectorsTools(final Session pSession, final Repository pRepository, final Locale pLocale)
	{
		this.session = pSession;
		this.repository = pRepository;
		this.locale = pLocale == null ? Locale.ENGLISH : pLocale;
	}

	/** Runs a tool by name; errors come back as {@code {"error": "..."}} rather than exceptions. */
	public ObjectNode invoke(final String pTool, final JsonNode pArgs)
	{
		final JsonNode args = pArgs == null ? Json.object() : pArgs;
		// one gate for every transport (HTTP endpoint, in-JVM bridge, native MCP runtime), before anything is read
		// or installed with the caller's session
		final ConnectorsAccess.Right needed = ConnectorsAccess.required(pTool, args);
		// a call that runs or changes something leaves one line in the kernel log, whatever its outcome
		final boolean traced = ConnectorsAudit.traced(needed);
		if (!ConnectorsAccess.has(this.session, needed))
		{
			ConnectorService.log().warn("[connectors] access denied: user " + ConnectorsAccess.userOf(this.session) + ", tool " + pTool + " (needs " + needed + ")");
			if (traced)
			{
				ConnectorsAudit.tool(this.session, pTool, args, ConnectorsAudit.REFUSED, 0L);
			}
			return ConnectorsTools.error(ConnectorsAccess.refusal("'" + pTool + "'", needed, this.session == null ? null : this.session.getLocale()));
		}
		if (!traced)
		{
			return this.dispatch(pTool, args);
		}
		final long started = System.nanoTime();
		String outcome = ConnectorsAudit.ERROR;
		try
		{
			final ObjectNode answer = this.dispatch(pTool, args);
			outcome = answer == null || answer.has("error") ? ConnectorsAudit.ERROR : ConnectorsAudit.OK;
			return answer;
		}
		finally
		{
			ConnectorsAudit.tool(this.session, pTool, args, outcome, (System.nanoTime() - started) / 1000000L);
		}
	}

	/** The tool itself, once the caller's right is established; a failure is an answer carrying {@code error}. */
	private ObjectNode dispatch(final String pTool, final JsonNode pArguments)
	{
		final JsonNode args = pArguments;
		try
		{
			ConnectorsInstaller.ensure(this.repository, this.session);
			if (ConnectorsTools.T_HELP.equals(pTool))
			{
				return this.help();
			}
			if (ConnectorsTools.T_LIST.equals(pTool))
			{
				return this.list(str(args, "type"));
			}
			if (ConnectorsTools.T_DESCRIBE.equals(pTool))
			{
				return this.describe(str(args, "connector"), str(args, "operation"), objectArg(args, "inputs"));
			}
			if (ConnectorsTools.T_CALL.equals(pTool))
			{
				return this.call(str(args, "connector"), str(args, "operation"), objectArg(args, "inputs"), bool(args, "allowWrite", false), objectArg(args, "document"));
			}
			if (ConnectorsTools.T_MAPPINGS.equals(pTool))
			{
				return this.mappings(str(args, "table"), str(args, "connector"), str(args, "direction"));
			}
			if (ConnectorsTools.T_MAPPING.equals(pTool))
			{
				return this.mapping(str(args, "mapping"));
			}
			if (ConnectorsTools.T_UPSERT.equals(pTool))
			{
				final ObjectNode document = objectArg(args, "mapping");
				return ConfigTransfer.upsertMapping(this.session, this.repository, document, bool(args, "dryRun", false));
			}
			if (ConnectorsTools.T_DELETE.equals(pTool))
			{
				final MappingDefinition m = Mappings.mappingNamed(this.repo(), str(args, "mapping"));
				return ConfigTransfer.deleteMapping(this.session, this.repository, m == null ? str(args, "mapping") : String.valueOf(m.getId()));
			}
			if (ConnectorsTools.T_ENRICH.equals(pTool))
			{
				return this.enrich(str(args, "dataspace"), str(args, "dataset"), str(args, "table"), str(args, "key"), str(args, "predicate"), str(args, "mappings"),
					str(args, "connectors"), bool(args, "dryRun", false));
			}
			if (ConnectorsTools.T_SYNC.equals(pTool))
			{
				return this.sync(str(args, "mapping"), str(args, "key"), str(args, "predicate"), str(args, "scope"), str(args, "inputs"), integer(args, "max", RunLimits.API),
					bool(args, "dryRun", false), integer(args, "resumeFrom", 0), bool(args, "async", false), bool(args, "fullRefresh", false));
			}
			if (ConnectorsTools.T_STOP.equals(pTool))
			{
				return this.stop(str(args, "runId"));
			}
			if (ConnectorsTools.T_REPLAY.equals(pTool))
			{
				return this.replay(str(args, "runId"), bool(args, "dryRun", false));
			}
			if (ConnectorsTools.T_LINEAGE.equals(pTool))
			{
				return this.declareLineage(str(args, "mapping"));
			}
			if (ConnectorsTools.T_SOURCE.equals(pTool))
			{
				return com.on.ebx.connect.core.CatalogDetection.catalogSource(this.repo(), this.session, str(args, "connector"), str(args, "source"), bool(args, "apply", false));
			}
			if (ConnectorsTools.T_RUN.equals(pTool))
			{
				return this.run(str(args, "runId"), integer(args, "callsMax", -1), integer(args, "callsFrom", 0));
			}
			if (ConnectorsTools.T_OUTBOUND.equals(pTool))
			{
				return this.outbound(str(args, "mapping"), str(args, "status"), integer(args, "from", 0), integer(args, "max", ListingPage.DEFAULT_MAX));
			}
			if (ConnectorsTools.T_CONFLICTS.equals(pTool))
			{
				return this.conflicts(str(args, "status"), str(args, "dataspace"), str(args, "dataset"), str(args, "table"), str(args, "recordKey"));
			}
			if (ConnectorsTools.T_DECIDE.equals(pTool))
			{
				return this.decide(str(args, "id"), str(args, "decision"), str(args, "comment"));
			}
			if (ConnectorsTools.T_LOG.equals(pTool))
			{
				return this.log(str(args, "mapping"), str(args, "connector"), str(args, "recordKey"), str(args, "status"), str(args, "runId"), integer(args, "from", 0), integer(args, "max", ListingPage.DEFAULT_MAX));
			}
			if (ConnectorsTools.T_EXPORT.equals(pTool))
			{
				return ConfigTransfer.export(this.repository, str(args, "connector"), bool(args, "includeMappings", true));
			}
			if (ConnectorsTools.T_IMPORT.equals(pTool))
			{
				final String text = str(args, "config");
				final JsonNode document = text == null ? args.get("config") : Json.parse(text);
				return ConfigTransfer.importConfig(this.session, this.repository, document, bool(args, "dryRun", false));
			}
			if (ConnectorsTools.T_OPENAPI.equals(pTool))
			{
				return this.openapi(str(args, "id"), str(args, "label"), str(args, "url"), str(args, "spec"), bool(args, "replace", false), bool(args, "dryRun", false));
			}
			if (ConnectorsTools.T_PUBLISH.equals(pTool))
			{
				return this.publish(str(args, "mappings"), str(args, "schema"), str(args, "product"), str(args, "contractVersion"), bool(args, "dryRun", false),
					bool(args, "recreate", false), integer(args, "max", 0), str(args, "dataspace"));
			}
			return ConnectorsTools.error("Unknown tool '" + pTool + "'");
		}
		catch (final ConnectorException ex)
		{
			return ConnectorsTools.error(ex.getMessage());
		}
		catch (final IllegalArgumentException ex)
		{
			return ConnectorsTools.error(ex.getMessage());
		}
		catch (final com.on.ebx.connect.core.TooManyRunsException ex)
		{
			// a refusal by design, not a failure of the tool: answered, not logged as an error
			return ConnectorsTools.error(ex.getMessage());
		}
		catch (final com.on.ebx.connect.core.MappingAlreadyRunningException ex)
		{
			// same: the mapping is held by a run that is still going
			return ConnectorsTools.error(ex.getMessage());
		}
		catch (final Exception ex)
		{
			ConnectorService.log().error(com.on.ebx.connect.core.Redaction.redact("[connectors] MCP tool " + pTool + " failed: " + ex.getMessage()));
			return ConnectorsTools.error(com.on.ebx.connect.core.Redaction.failure(ex));
		}
	}

	// ------------------------------------------------------------------ tools

	private ObjectNode help()
	{
		final ObjectNode out = Json.object();
		// the build that answers, the same stamp as the About page and the status endpoint
		out.put("version", com.on.ebx.connect.core.ModuleVersion.version());
		out.put("commit", com.on.ebx.connect.core.ModuleVersion.commit());
		out.put("overview", "A connector is a configured access to an external service (row of the Connector table: type, base URL, authentication, trust level 0-100, "
				+ "pricing FREE/FREEMIUM/PAID/UNKNOWN - how the service is billed, UNKNOWN also meaning not applicable, e.g. a customer's own system). "
				+ "A mapping binds one connector operation to one EBX table, in one direction: INBOUND writes the service's document into records through rules "
				+ "(document path -> EBX field, transformation pipeline, write policy IF_EMPTY / ALWAYS / TRUSTED), OUTBOUND builds a document from the record and sends it. "
				+ "Sub-object mappings map child tables to collections. Every run has a run id; failed outbound calls are recorded and can be replayed.");
		final ArrayNode steps = out.putArray("typicalFlows");
		steps.add("Discover: connectors_list, then connectors_describe(connector, operation) to see the document fields, connectors_call to try an operation.");
		steps.add("Enrich a record: connectors_mappings_list(table) to pick inbound mappings, connectors_enrich(dataset, table, key, mappings, dryRun=true) then without dryRun; if hasConflicts, connectors_conflicts_list and connectors_conflict_decide.");
		steps.add("Publish / synchronise outbound: connectors_sync(mapping, scope=changed|unsent|failed|dataspaceDelta, dryRun=true) then without dryRun; connectors_outbound_states(mapping) and connectors_replay(runId) for failures.");
		steps.add("Create a mapping: connectors_describe(connector, operation) for the document fields, then connectors_mapping_upsert(mapping={connector, operation, direction, dataset, tablePath, label, inputs, rules}, dryRun=true) then without dryRun, then connectors_enrich(..., dryRun=true) to test it; connectors_mapping_delete removes it.");
		steps.add("Move a configuration: connectors_config_export on the source, connectors_config_import(config, dryRun=true) on the target, then without dryRun; secrets are entered in the EBX user interface afterwards.");
		steps.add("Create a REST connector from a specification: connectors_openapi_import(id, url or spec).");
		steps.add("Data catalog (ON EBX): mapping upserts and deletions answer a lineage block with advice; connectors_catalog_source(connector) shows how the connector's system is known "
			+ "to the catalog and apply=true creates or aligns its Source; connectors_lineage_declare sends the declared lineage again.");
		out.put("secrets", "Never pass secrets through these tools: API keys and passwords are entered in the EBX user interface (encrypted) or referenced from a vault (vault:, env:, file:, azurekv:, awssecrets:, gcpsecret:).");
		out.put("restEquivalent", "/ebx-connectors/rest/v1 offers the same operations (enrich, sync, replay, runs, outbound, conflicts, connectors/openapi, config).");
		return out;
	}

	private ObjectNode list(final String pType)
	{
		final ObjectNode out = Json.object();
		final ArrayNode types = out.putArray("types");
		for (final String type : ConnectorRegistry.get().getTypes())
		{
			if (pType != null && !pType.trim().isEmpty() && !pType.trim().equalsIgnoreCase(type))
			{
				continue;
			}
			final ObjectNode t = types.addObject();
			t.put("type", type);
			try
			{
				final Connector probe = ConnectorRegistry.get().describeType(type);
				t.put("label", probe.getLabel());
				final ArrayNode ops = t.putArray("operations");
				for (final Operation op : probe.getOperations())
				{
					ops.add(op.getName() + " (" + op.getKind().name().toLowerCase() + ")");
				}
			}
			catch (final Exception ex)
			{
				t.put("error", String.valueOf(ex.getMessage()));
			}
		}
		final ArrayNode configured = out.putArray("connectors");
		for (final ConnectorConfig config : this.repo().connectors())
		{
			if (pType != null && !pType.trim().isEmpty() && !pType.trim().equalsIgnoreCase(config.getType()))
			{
				continue;
			}
			final ObjectNode c = configured.addObject();
			c.put("id", config.getId());
			c.put("label", config.getLabel());
			c.put("type", config.getType());
			c.put("baseUrl", config.getDisplayBaseUrl());
			c.put("authType", config.getAuthType().name());
			c.put("active", config.isActive());
			c.put("trust", config.getTrust());
			c.put("pricing", config.getPricing().name());
			if (config.getPricingNote() != null && !config.getPricingNote().trim().isEmpty())
			{
				c.put("pricingNote", config.getPricingNote());
			}
			c.put("secretsSet", !config.isPlaceholder(config.getApiKey()) || !config.isPlaceholder(config.getPassword()));
			if (config.getProblem() != null)
			{
				c.put("problem", config.getProblem());
			}
		}
		out.put("count", configured.size());
		return out;
	}

	private ObjectNode describe(final String pConnector, final String pOperation, final ObjectNode pInputs) throws ConnectorException
	{
		final ConnectorConfig config = this.config(pConnector);
		final Connector connector = ConnectorRegistry.get().connector(config.getId(), this.repo());
		final ObjectNode out = Json.object();
		out.put("id", config.getId());
		out.put("label", config.getLabel());
		out.put("type", config.getType());
		out.put("typeLabel", connector.getLabel());
		out.put("baseUrl", config.getDisplayBaseUrl());
		out.put("authType", config.getAuthType().name());
		out.put("trust", config.getTrust());
		out.put("pricing", config.getPricing().name());
		if (config.getPricingNote() != null && !config.getPricingNote().trim().isEmpty())
		{
			out.put("pricingNote", config.getPricingNote());
		}
		out.put("active", config.isActive());
		final ObjectNode properties = out.putObject("properties");
		for (final Map.Entry<String, String> property : config.getProperties().entrySet())
		{
			properties.put(property.getKey(), com.on.ebx.connect.core.Redaction.propertyValue(property.getKey(), property.getValue()));
		}
		final com.on.ebx.connect.spi.ConfigurationValidationResult validation =
			com.on.ebx.connect.core.ConnectorStatusEvaluator.evaluate(config, ConnectorRegistry.get()).getValidation();
		if (validation != null && !validation.getWarnings().isEmpty())
		{
			final ArrayNode warnings = out.putArray("warnings");
			for (final com.on.ebx.connect.spi.ConfigurationValidationIssue warning : validation.getWarnings()) { warnings.add(warning.toString()); }
		}
		final ArrayNode ops = out.putArray("operations");
		for (final Operation op : connector.getOperations())
		{
			final ObjectNode o = ops.addObject();
			o.put("name", op.getName());
			o.put("kind", op.getKind().name());
			o.put("description", op.getDescription());
			// Read recipe + deduced scan (2026-09-24): true only when this operation cannot change anything at the
			// source (Operation.isReadOnly() is the one place this is derived) - the Factory's deduced scan refuses
			// to call any operation whose readOnly is not true, whatever the language model answered.
			o.put("readOnly", op.isReadOnly());
			final ArrayNode required = o.putArray("requiredInputs");
			for (final String input : op.getRequiredInputs())
			{
				required.add(input);
			}
			final ArrayNode optional = o.putArray("optionalInputs");
			for (final String input : op.getOptionalInputs())
			{
				optional.add(input);
			}
		}
		if (pOperation != null && !pOperation.trim().isEmpty())
		{
			final Map<String, String> inputs = ConnectorsTools.inputs(pInputs);
			final ConnectorContext context = new ConnectorContext(this.session, this.repository, null, null);
			final ArrayNode fields = out.putArray("fields");
			for (final FieldInfo field : connector.describeFields(context, pOperation.trim(), inputs))
			{
				final ObjectNode f = fields.addObject();
				f.put("path", field.getPath());
				f.put("label", field.getLabel());
				f.put("type", field.getType());
				if (field.getSample() != null)
				{
					f.put("sample", field.getSample());
				}
			}
			out.put("fieldsOf", pOperation.trim());
		}
		return out;
	}

	private ObjectNode call(final String pConnector, final String pOperation, final ObjectNode pInputs, final boolean pAllowWrite, final ObjectNode pDocument) throws ConnectorException
	{
		final ConnectorConfig config = this.config(pConnector);
		final Connector connector = ConnectorRegistry.get().connector(config.getId(), this.repo());
		return ConnectorsTools.executeCall(new ConnectorContext(this.session, this.repository, null, null), connector, config.getId(), pOperation, pInputs, pAllowWrite, pDocument);
	}

	/**
	 * The body of {@link #call}, independent of EBX session/repository/config lookup so it can run against a
	 * fake {@link Connector} in a plain test: resolves the operation, refuses a write without {@code allowWrite},
	 * refuses a document on a non-write operation (nothing accepts it), then calls {@link Connector#execute} with
	 * {@code document} as the payload - the argument {@code call} used to always pass {@code null}, so a WRITE
	 * operation (e.g. Salesforce create/update) always posted an empty body.
	 */
	static ObjectNode executeCall(final ConnectorContext pContext, final Connector pConnector, final String pConnectorId, final String pOperation, final ObjectNode pInputs,
		final boolean pAllowWrite, final ObjectNode pDocument) throws ConnectorException
	{
		Operation found = null;
		for (final Operation op : pConnector.getOperations())
		{
			if (op.getName().equals(pOperation))
			{
				found = op;
			}
		}
		if (found == null)
		{
			throw new IllegalArgumentException("Connector " + pConnectorId + " has no operation '" + pOperation + "'");
		}
		final boolean write = found.getKind() == Operation.Kind.WRITE;
		if (write && !pAllowWrite)
		{
			throw new IllegalArgumentException("'" + pOperation + "' is a write operation: pass allowWrite=true to call it");
		}
		if (!write && pDocument != null)
		{
			throw new IllegalArgumentException("'" + pOperation + "' is not a write operation: document is only for a WRITE operation");
		}
		final long start = System.currentTimeMillis();
		final JsonNode document = pConnector.execute(pContext, pOperation, ConnectorsTools.inputs(pInputs), pDocument);
		final ObjectNode out = Json.object();
		out.put("connector", pConnectorId);
		out.put("operation", pOperation);
		out.put("durationMs", System.currentTimeMillis() - start);
		out.set("document", document == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : document);
		return out;
	}

	private ObjectNode mappings(final String pTable, final String pConnector, final String pDirection)
	{
		final ObjectNode out = Json.object();
		final ArrayNode list = out.putArray("mappings");
		for (final MappingDefinition m : this.repo().mappings())
		{
			if (pTable != null && !pTable.trim().isEmpty() && !pTable.trim().equals(m.getTablePath()))
			{
				continue;
			}
			if (pConnector != null && !pConnector.trim().isEmpty() && !pConnector.trim().equalsIgnoreCase(m.getConnectorId()))
			{
				continue;
			}
			if (pDirection != null && !pDirection.trim().isEmpty() && !pDirection.trim().equalsIgnoreCase(m.getDirection().name()))
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
			n.put("children", m.getChildren().size());
		}
		out.put("count", list.size());
		return out;
	}

	private ObjectNode mapping(final String pMapping)
	{
		if (pMapping == null || pMapping.trim().isEmpty())
		{
			throw new IllegalArgumentException("mapping is required: a mapping id or label (the argument is named mapping, not id)");
		}
		final MappingDefinition m = Mappings.mappingNamed(this.repo(), pMapping);
		if (m == null)
		{
			throw new IllegalArgumentException("No mapping '" + pMapping + "'");
		}
		return ConnectorsTools.mappingJson(m);
	}

	static ObjectNode mappingJson(final MappingDefinition pMapping)
	{
		final ObjectNode n = Json.object();
		n.put("id", pMapping.getId());
		n.put("label", pMapping.getLabel());
		n.put("connector", pMapping.getConnectorId());
		n.put("operation", pMapping.getOperation());
		n.put("direction", pMapping.getDirection().name());
		n.put("dataspace", pMapping.getDataspace());
		n.put("dataset", pMapping.getDataset());
		n.put("table", pMapping.getTablePath());
		n.put("responseRoot", pMapping.getResponseRoot());
		n.put("active", pMapping.isActive());
		n.put("feed", pMapping.isFeed());
		n.put("createIfMissing", pMapping.isCreateIfMissing());
		n.put("provenance", pMapping.isProvenance());
		if (!pMapping.getUnchangedIfEqual().isEmpty())
		{
			n.put("unchangedIfEqual", pMapping.getUnchangedIfEqual());
		}
		if (pMapping.getLoadMode() == MappingDefinition.LoadMode.INCREMENTAL)
		{
			n.put("loadMode", "INCREMENTAL");
			n.put("checkpointStrategy", pMapping.getCheckpointStrategy() == null ? null : pMapping.getCheckpointStrategy().name());
			n.put("checkpointValuePath", pMapping.getCheckpointValuePath());
			n.put("checkpointInitialValue", pMapping.getCheckpointInitialValue());
			n.put("checkpointBoundary", pMapping.getCheckpointBoundary());
			n.put("watermarkFeed", pMapping.isWatermarkFeed());
			n.put("watermarkInclusive", pMapping.isWatermarkInclusive());
		}
		if (pMapping.isChild())
		{
			n.put("parent", pMapping.getParentId());
			n.put("collectionPath", pMapping.getCollectionPath());
			n.put("parentLinkField", pMapping.getParentLinkField());
			n.put("childFilter", pMapping.getChildFilter());
			n.put("deleteMissing", pMapping.isDeleteMissing());
		}
		final ObjectNode inputs = n.putObject("inputs");
		for (final Map.Entry<String, String> input : pMapping.getInputs().entrySet())
		{
			inputs.put(input.getKey(), input.getValue());
		}
		final ArrayNode rules = n.putArray("rules");
		for (final MappingRule rule : pMapping.getRules())
		{
			final ObjectNode r = rules.addObject();
			r.put("position", rule.getPosition());
			r.put("source", rule.getSourcePath());
			r.put("target", rule.getTargetPath());
			r.put("transformation", rule.getTransformation());
			r.put("policy", rule.getPolicy() == null ? null : rule.getPolicy().name());
			if (rule.getComment() != null)
			{
				r.put("comment", rule.getComment());
			}
		}
		final ArrayNode children = n.putArray("children");
		for (final MappingDefinition child : pMapping.getChildren())
		{
			children.add(ConnectorsTools.mappingJson(child));
		}
		return n;
	}

	private ObjectNode enrich(final String pDataspace, final String pDataset, final String pTable, final String pKey, final String pPredicate, final String pMappings,
		final String pConnectors, final boolean pDryRun)
	{
		if (pTable == null || pDataset == null)
		{
			throw new IllegalArgumentException("dataset and table are required");
		}
		final ConnectorRepository repo = this.repo();
		final List<MappingDefinition> all = repo.mappingsForAnyDataspace(pDataspace, pDataset, pTable);
		final List<MappingDefinition> mappings = new ArrayList<MappingDefinition>();
		if (pMappings != null && !pMappings.trim().isEmpty())
		{
			for (final String token : pMappings.split(","))
			{
				final MappingDefinition m = Mappings.mappingNamed(repo, token.trim());
				if (m == null)
				{
					throw new IllegalArgumentException("No mapping '" + token.trim() + "'");
				}
				if (!m.appliesToAnyDataspace(pDataspace, pDataset, pTable))
				{
					throw new IllegalArgumentException("Mapping " + m.getId() + " (" + m.getLabel() + ") is not a mapping of " + pTable);
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
		if (mappings.isEmpty())
		{
			throw new IllegalArgumentException("No inbound mapping selected for " + pTable);
		}
		final String runDataspace = MappingDefinition.runDataspace(pDataspace, mappings);
		final DatasetLocator.Location located = DatasetLocator.locate(this.repository, runDataspace, pDataset, pTable);
		final Adaptation container = located.getDataset();
		if (container == null)
		{
			throw new IllegalArgumentException(located.failure(runDataspace, pDataset, pTable));
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
			request.setSession(this.session);
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
		if (record != null && this.session.getPermissions().getAdaptationAccessPermission(record).isHidden())
		{
			record = null;
		}
		if (record == null)
		{
			throw new IllegalArgumentException("No record for key/predicate in " + pTable);
		}
		final String runId = ConnectorService.beginRun();
		try
		{
			final ObjectNode out = Json.object();
			out.put("runId", runId);
			out.put("record", record.getLabel(this.locale));
			out.put("dryRun", pDryRun);
			final ArrayNode items = out.putArray("results");
			int ok = 0;
			int errors = 0;
			int conflicts = 0;
			final List<SyncResult> outcomes = new java.util.ArrayList<SyncResult>();
			final String origin = "MCP " + this.session.getUserReference().getUserId();
			for (final MappingDefinition mapping : mappings)
			{
				final SyncResult result = ConnectorService.get().syncRecord(this.session, record, mapping, pDryRun, origin);
				outcomes.add(result);
				record = records.lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey());
				final ObjectNode item = items.addObject();
				item.put("mapping", mapping.getId());
				item.put("label", mapping.getLabel());
				item.put("connector", mapping.getConnectorId());
				item.put("status", result.getStatus() == null ? "" : result.getStatus().name());
				item.put("message", result.getMessage());
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
				if (result.hasFailure())
				{
					errors++;
				}
				else if (result.isSuccess())
				{
					ok++;
				}
			}
			ConnectorsTools.putOutcome(out, outcomes);
			out.put("conflicts", conflicts);
			out.put("hasConflicts", conflicts > 0);
			if (conflicts > 0)
			{
				out.put("nextStep", "Arbitrate with connectors_conflicts_list(recordKey=" + record.getOccurrencePrimaryKey().format() + ") and connectors_conflict_decide.");
			}
			return out;
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	private ObjectNode sync(final String pMapping, final String pKey, final String pPredicate, final String pScope, final String pInputs, final int pMax,
		final boolean pDryRun, final int pResumeFrom, final boolean pAsync, final boolean pFullRefresh) throws ConnectorException
	{
		final MappingDefinition mapping = Mappings.mappingNamed(this.repo(), pMapping);
		if (mapping == null)
		{
			throw new IllegalArgumentException("No mapping '" + pMapping + "'");
		}
		final boolean noRecord = (pKey == null || pKey.trim().isEmpty()) && (pPredicate == null || pPredicate.trim().isEmpty());
		if (mapping.isFeed() && noRecord && (pScope == null || pScope.trim().isEmpty()))
		{
			// an inbound feed reads its own items (Kafka topic, file, paged API): no record to start from
			if (pAsync)
			{
				if (pDryRun)
				{
					throw new IllegalArgumentException("async cannot be combined with dryRun");
				}
				final List<String> ignored = new ArrayList<String>();
				final Map<String, String> inputs = ConnectorService.runtimeInputs(mapping, pInputs, ignored);
				final String runId = ConnectorService.get().importFromAsync(this.session, this.repository, mapping, inputs, pMax,
					"MCP " + this.session.getUserReference().getUserId(), pResumeFrom, pFullRefresh);
				final ObjectNode out = Json.object();
				out.put("runId", runId);
				out.put("status", "RUNNING");
				out.put("mapping", mapping.getId());
				out.put("feed", true);
				out.put("async", true);
				out.put("nextStep", "Poll connectors_run_get(runId=\"" + runId + "\") for progress and the final status; connectors_run_stop(runId) stops it at the next batch boundary.");
				ConnectorsTools.putNotices(out, ignored, null);
				return out;
			}
			final String runId = ConnectorService.beginRun();
			try
			{
				final List<String> ignored = new ArrayList<String>();
				final Map<String, String> inputs = ConnectorService.runtimeInputs(mapping, pInputs, ignored);
				final ImportOutcome outcome = ConnectorService.get().importFrom(this.session, this.repository, mapping, inputs, pMax, pDryRun,
					"MCP " + this.session.getUserReference().getUserId(), pResumeFrom, pFullRefresh);
				final ObjectNode out = ConnectorsTools.feedResults(outcome, pDryRun);
				out.put("mapping", mapping.getId());
				out.put("feed", true);
				out.put("async", false);
				ConnectorsTools.putNotices(out, ignored, outcome.getLimitMessage());
				return out;
			}
			finally
			{
				ConnectorService.endRun();
			}
		}
		if (pAsync)
		{
			// wave 13: an outbound mapping sent on a scope runs in the background too (a feed import is handled above)
			final String refusal = ConnectorsTools.scopeAsyncRefusal(pScope != null && !pScope.trim().isEmpty(), pKey != null && !pKey.trim().isEmpty(),
				mapping.getDirection(), pDryRun);
			if (refusal != null)
			{
				throw new IllegalArgumentException(refusal);
			}
		}
		// a run on records or on a scope reads no run-time input: every one given is ignored, and said (audit C-35)
		final List<String> ignored = ConnectorService.inputNames(pInputs);
		if (pScope == null || pScope.trim().isEmpty())
		{
			final ObjectNode out = this.enrich(mapping.getDataspace(), mapping.getDataset(), mapping.getTablePath(), pKey, pPredicate, String.valueOf(mapping.getId()), null, pDryRun);
			ConnectorsTools.putNotices(out, ignored, null);
			return out;
		}
		final OutboundScope scope;
		try
		{
			scope = OutboundScope.parse(pScope);
		}
		catch (final IllegalArgumentException ex)
		{
			throw new IllegalArgumentException("Unknown scope '" + pScope + "': all, unsent, changed, failed, dataspaceDelta");
		}
		final DatasetLocator.Location located = DatasetLocator.locate(this.repository, mapping.getDataspace(), mapping.getDataset(), mapping.getTablePath());
		final Adaptation container = located.getDataset();
		if (container == null)
		{
			throw new IllegalArgumentException(located.failure(mapping.getDataspace(), mapping.getDataset(), mapping.getTablePath()));
		}
		final AdaptationTable table = container.getTable(com.orchestranetworks.schema.Path.parse(mapping.getTablePath()));
		Request request = null;
		if (!pAsync && pPredicate != null && !pPredicate.trim().isEmpty())
		{
			request = table.createRequest();
			request.setSession(this.session);
			request.setXPathFilter(pPredicate.trim());
		}
		if (pAsync)
		{
			final String runId = ConnectorService.get().sendScopeAsync(this.session, this.repository, mapping, table, pPredicate, scope, pMax,
				"MCP " + this.session.getUserReference().getUserId());
			final ObjectNode out = ConnectorsTools.asyncSendStart(runId, mapping.getId(), scope.name());
			ConnectorsTools.putNotices(out, ignored, null);
			return out;
		}
		final String runId = ConnectorService.beginRun();
		try
		{
			final List<SyncResult> results = ConnectorService.get().sendScope(this.session, this.repository, mapping, table, request, scope, pMax, pDryRun,
				"MCP " + this.session.getUserReference().getUserId());
			final ObjectNode out = ConnectorsTools.results(runId, pDryRun, results);
			out.put("mapping", mapping.getId());
			out.put("scope", scope.name());
			ConnectorsTools.putNotices(out, ignored, SyncResult.limitMessage(results));
			return out;
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	/** The mapping a run row names, null when it is gone or the row names none (a run of a deleted mapping is still readable). */
	private static MappingDefinition mappingOfRun(final ConnectorRepository pRepo, final String pMappingId)
	{
		if (pMappingId == null || !pMappingId.matches("[0-9]{1,9}"))
		{
			return null;
		}
		try
		{
			return pRepo.mapping(Integer.parseInt(pMappingId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Pure (wave 13): {@code direction} (INBOUND for a feed import, OUTBOUND for a scope send) and the mapping id, at the top of a
	 * {@code connectors_run_get} answer and in its {@code execution{}}, when the run's mapping still exists; nothing otherwise.
	 */
	static void putDirection(final ObjectNode pAnswer, final ObjectNode pExecution, final MappingDefinition pMapping)
	{
		if (pMapping == null || pMapping.getDirection() == null)
		{
			return;
		}
		pAnswer.put("direction", pMapping.getDirection().name());
		pAnswer.put("mapping", pMapping.getId());
		pExecution.put("direction", pMapping.getDirection().name());
	}

	/** What a start refused for {@code async} says; the MDM Factory recognises "async only applies" and asks again without {@code async}. */
	public static final String ASYNC_REFUSAL = "async only applies to an inbound feed with no key, predicate or scope, or to an outbound mapping sent on a scope"
		+ " (scope=all|unsent|changed|failed|dataspaceDelta, no key)";

	/**
	 * Pure (wave 13): why {@code async} is refused for a call that is not a feed import, null when it is accepted - an OUTBOUND mapping
	 * sent on a scope (optionally narrowed by a predicate), no key, a real run.
	 */
	public static String scopeAsyncRefusal(final boolean pScoped, final boolean pKeyGiven, final MappingDefinition.Direction pDirection, final boolean pDryRun)
	{
		if (!pScoped || pKeyGiven || pDirection != MappingDefinition.Direction.OUTBOUND)
		{
			return ConnectorsTools.ASYNC_REFUSAL;
		}
		return pDryRun ? "async cannot be combined with dryRun" : null;
	}

	/** The answer of an asynchronous scope send that was accepted: the run to poll with connectors_run_get and to stop with connectors_run_stop. */
	public static ObjectNode asyncSendStart(final String pRunId, final int pMappingId, final String pScope)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pRunId);
		out.put("status", "RUNNING");
		out.put("mapping", pMappingId);
		out.put("direction", "OUTBOUND");
		out.put("scope", pScope);
		out.put("async", true);
		out.put("nextStep", "Poll connectors_run_get(runId=\"" + pRunId + "\") for progress and the final status; connectors_run_stop(runId) stops it at the next batch boundary.");
		return out;
	}

	/** Volume lot 6, task 5: flags a still-running batched import for cancellation at its next batch boundary. */
	private ObjectNode stop(final String pRunId)
	{
		if (pRunId == null || pRunId.trim().isEmpty())
		{
			throw new IllegalArgumentException("runId is required");
		}
		ConnectorService.requestCancel(pRunId.trim());
		final ObjectNode out = Json.object();
		out.put("runId", pRunId.trim());
		out.put("requested", true);
		out.put("note", "Takes effect at the run's next batch boundary (a feed import's batch, an outbound send's group of records), only if it is still running on this node; check connectors_run_get(runId) to see whether it actually stopped (status STOPPED). Records an outbound send already sent keep their outbound state.");
		return out;
	}

	private ObjectNode declareLineage(final String pMapping)
	{
		Integer id = null;
		if (pMapping != null && !pMapping.trim().isEmpty())
		{
			final MappingDefinition one = Mappings.mappingNamed(this.repo(), pMapping);
			if (one == null)
			{
				throw new IllegalArgumentException("No mapping '" + pMapping + "'");
			}
			id = Integer.valueOf(one.getId());
		}
		return com.on.ebx.connect.core.DeclaredLineage.declareNow(this.repository, id);
	}

	private ObjectNode replay(final String pRunId, final boolean pDryRun) throws ConnectorException
	{
		if (pRunId == null || pRunId.trim().isEmpty())
		{
			throw new IllegalArgumentException("runId is required");
		}
		final String runId = ConnectorService.beginRun();
		try
		{
			final List<SyncResult> results = ConnectorService.get().replay(this.session, this.repository, pRunId.trim(), pDryRun,
				"replay " + pRunId.trim() + " by " + this.session.getUserReference().getUserId());
			final ObjectNode out = ConnectorsTools.results(runId, pDryRun, results);
			out.put("replayOf", pRunId.trim());
			return out;
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	private static ObjectNode results(final String pRunId, final boolean pDryRun, final List<SyncResult> pResults)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pRunId);
		out.put("dryRun", pDryRun);
		int ok = 0;
		int errors = 0;
		final ArrayNode items = out.putArray("results");
		for (final SyncResult result : pResults)
		{
			final ObjectNode item = items.addObject();
			item.put("mapping", result.getMappingId());
			item.put("record", result.getRecordLabel());
			item.put("key", result.getRecordKey());
			item.put("status", result.getStatus() == null ? "" : result.getStatus().name());
			item.put("message", result.getMessage());
			if (!result.getErrors().isEmpty())
			{
				final ArrayNode list = item.putArray("errors");
				for (final String error : result.getErrors())
				{
					list.add(error);
				}
			}
			if (result.hasFailure())
			{
				errors++;
			}
			else if (result.isSuccess())
			{
				ok++;
			}
		}
		// the records processed: a "limit reached" notice is said in truncated/message, not counted as a record
		out.put("count", SyncResult.countWithoutLimitNotices(pResults));
		ConnectorsTools.putOutcome(out, pResults);
		if (errors > 0 && !pDryRun)
		{
			out.put("nextStep", "Review failed and partially completed items before replay; some writes may already be committed.");
		}
		return out;
	}

	private static void putOutcome(final ObjectNode out, final List<SyncResult> results)
	{
		final SyncResult.Summary summary = SyncResult.summarize(results);
		out.put("ok", summary.ok);
		out.put("errors", summary.errors + summary.partial);
		out.put("failed", summary.errors);
		out.put("partial", summary.partial);
		out.put("skipped", summary.skipped);
		out.put("noData", summary.noData);
		out.put("status", summary.status());
		// A truncated feed read must never look like a clean run: surfaced here so a caller checking
		// only top-level fields (the MDM Factory's LandingFlow.loadOutcome) can still catch it.
		out.put("truncated", summary.truncated);
		if (summary.available >= 0)
		{
			out.put("available", summary.available);
		}
		ConnectorsTools.putBookkeeping(out, com.on.ebx.connect.core.Bookkeeping.lostIn(results));
	}

	/**
	 * Audit C-35: what a run has to say beyond its counters, in the same place for every answer of {@code connectors_sync}
	 * and {@code POST /sync}. {@code ignoredInputs} lists the run-time inputs the run did not use, and then a plain
	 * {@code OK} becomes {@code WARNING} ({@link RunLimits#answerStatus}; PARTIAL, ERROR, NO_DATA and RUNNING are left as
	 * they are). {@code warning: true}, {@code warnings[]} and {@code message} carry the sentences: the ignored inputs,
	 * and the "limit reached" notice of a run cut at {@code max} (whose {@code truncated} is already true). Absent on a
	 * run with nothing to say.
	 */
	public static void putNotices(final ObjectNode pOut, final List<String> pIgnored, final String pLimitMessage)
	{
		final List<String> warnings = new ArrayList<String>();
		if (pIgnored != null && !pIgnored.isEmpty())
		{
			final ArrayNode list = pOut.putArray("ignoredInputs");
			for (final String name : pIgnored)
			{
				list.add(name);
			}
			warnings.add(RunLimits.ignoredInputsMessage(pIgnored));
			if (pOut.has("status"))
			{
				pOut.put("status", RunLimits.answerStatus(pOut.path("status").asText(""), true));
			}
		}
		if (pLimitMessage != null && pOut.path("truncated").asBoolean(false))
		{
			warnings.add(pLimitMessage);
		}
		if (warnings.isEmpty())
		{
			return;
		}
		pOut.put("warning", true);
		final ArrayNode list = pOut.putArray("warnings");
		for (final String warning : warnings)
		{
			list.add(warning);
		}
		pOut.put("message", String.join(" ", warnings));
	}

	/**
	 * Rows about a run that could not be saved (sync log, outbound state, run state...): {@code
	 * bookkeepingLost} (count by kind) and {@code bookkeepingNotice} (what it means). Absent when
	 * nothing was lost, which is every run on a healthy repository; {@code status} is never changed by
	 * it, since the data itself is written.
	 */
	public static void putBookkeeping(final ObjectNode pOut, final java.util.Map<String, Integer> pLost)
	{
		if (pLost == null || pLost.isEmpty())
		{
			return;
		}
		final ObjectNode lost = pOut.putObject("bookkeepingLost");
		for (final java.util.Map.Entry<String, Integer> entry : pLost.entrySet())
		{
			lost.put(entry.getKey(), entry.getValue().intValue());
		}
		pOut.put("bookkeepingNotice", com.on.ebx.connect.core.Bookkeeping.describe(pLost));
	}

	/** One {@link SyncResult} as the item shape {@link #results} and {@link #feedResults} both use. */
	private static ObjectNode resultItem(final SyncResult result)
	{
		final ObjectNode item = Json.object();
		item.put("mapping", result.getMappingId());
		item.put("record", result.getRecordLabel());
		item.put("key", result.getRecordKey());
		item.put("status", result.getStatus() == null ? "" : result.getStatus().name());
		item.put("message", result.getMessage());
		if (!result.getErrors().isEmpty())
		{
			final ArrayNode list = item.putArray("errors");
			for (final String error : result.getErrors())
			{
				list.add(error);
			}
		}
		if (!result.getBookkeepingLost().isEmpty())
		{
			final ArrayNode lost = item.putArray("bookkeepingLost");
			for (final String kind : result.getBookkeepingLost())
			{
				lost.add(kind);
			}
		}
		return item;
	}

	/**
	 * Volume lot 7a: the {@code connectors_sync} answer for an inbound feed
	 * ({@code com.on.ebx.connect.core.ImportOutcome}) - counters computed incrementally during the run
	 * instead of {@link #putOutcome} walking a full {@code List<SyncResult>} at the end, and
	 * {@code results}/{@code errorSamples} bounded samples instead of one entry per row.
	 * <p>
	 * {@code status}, {@code count}, {@code ok}, {@code errors} (a NUMBER, not an array - the failing
	 * count), {@code truncated}, {@code available} and {@code results[].status}/{@code .message}/
	 * {@code .errors[]} keep the EXACT shape and meaning {@link #putOutcome}/{@link #results} already
	 * gave them: the MDM Factory's {@code LandingFlow.loadOutcome} reads precisely
	 * {@code status/count/ok/errors} plus {@code results[].errors[]/.status/.message} (verified against
	 * its source, 2026-09-28) and must keep working unchanged. The new, additive fields - created,
	 * updated, noChange, skipped, rejected, conflicts, resultsOmitted, errorSamples[] (the bounded
	 * first-K failing results, a NEW key so it never collides with the legacy numeric {@code errors}) -
	 * are for a caller that wants more than that, including a future Factory lot.
	 */
	private static ObjectNode feedResults(final ImportOutcome pOutcome, final boolean pDryRun)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pOutcome.getRunId());
		out.put("dryRun", pDryRun);
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
		ConnectorsTools.putWatermark(out, pOutcome);
		ConnectorsTools.putBookkeeping(out, pOutcome.getBookkeepingLost());
		final ArrayNode errorSamples = out.putArray("errorSamples");
		for (final SyncResult error : pOutcome.getErrors())
		{
			errorSamples.add(ConnectorsTools.resultItem(error));
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
				items.add(ConnectorsTools.resultItem(result));
			}
		}
		if (pOutcome.getErrorCount() > 0 && !pDryRun)
		{
			out.put("nextStep", "Review failed and partially completed items before replay; some writes may already be committed.");
		}
		return out;
	}

	/**
	 * Volume lot V2-D: the watermark of an incremental feed run - {@code watermarkBefore} (the checkpoint the
	 * run started from, null when none), {@code watermarkAfter} (the checkpoint now: different from before
	 * only when the run ended DONE and moved it), {@code skippedByWatermark} (rows at or below the
	 * checkpoint, dropped before any lookup - not part of {@code count}), {@code watermarkAdvanced},
	 * {@code fullRefresh} and, when something needs saying, {@code watermarkNote}. Absent for a run that is
	 * not an incremental feed, so an existing caller's answer is unchanged.
	 */
	public static void putWatermark(final ObjectNode pOut, final ImportOutcome pOutcome)
	{
		if (!pOutcome.isWatermarkActive())
		{
			return;
		}
		pOut.put("watermarkField", pOutcome.getWatermarkField());
		if (pOutcome.getWatermarkBefore() == null)
		{
			pOut.putNull("watermarkBefore");
		}
		else
		{
			pOut.put("watermarkBefore", pOutcome.getWatermarkBefore());
		}
		if (pOutcome.getWatermarkAfter() == null)
		{
			pOut.putNull("watermarkAfter");
		}
		else
		{
			pOut.put("watermarkAfter", pOutcome.getWatermarkAfter());
		}
		pOut.put("skippedByWatermark", pOutcome.getSkippedByWatermark());
		pOut.put("watermarkAdvanced", pOutcome.isWatermarkAdvanced());
		pOut.put("fullRefresh", pOutcome.isWatermarkFullRefresh());
		if (pOutcome.getWatermarkNote() != null && !pOutcome.getWatermarkNote().isEmpty())
		{
			pOut.put("watermarkNote", pOutcome.getWatermarkNote());
		}
	}

	/**
	 * Volume lot 7a: also reads the persisted {@code ExecutionRun} row when one exists (any feed
	 * import, synchronous or asynchronous, batched or not - see {@code ConnectorService.runImport}),
	 * under {@code execution} - status (RUNNING/DONE/PARTIAL/ERROR/STOPPED/INTERRUPTED), counters,
	 * resume position and a bounded sample of the first failing keys/messages. {@code calls} (the
	 * SyncLog rows of the run) is unchanged - lot 6's own answer shape for a run that predates this
	 * table's state fields, or one this table does not cover (an outbound sync, a replay).
	 */
	private ObjectNode run(final String pRunId, final int pCallsMax, final int pCallsFrom)
	{
		return ConnectorsTools.runStatusJson(this.repo(), pRunId, pCallsMax, pCallsFrom);
	}

	/**
	 * Volume lot 7b: extracted from the MCP {@code connectors_run_get} tool's own body so
	 * {@code ConnectorsResource}'s REST {@code GET /runs/{runId}} (a different package - {@code
	 * com.on.ebx.connect.ebx.rest}) builds the exact same answer instead of a separate, drifting
	 * reimplementation. See {@link #run(String)}'s original javadoc for the shape: {@code execution{}}
	 * (status/counters/resume position/truncation/first errors, when the run has a persisted
	 * {@code ExecutionRun} row - any feed import, synchronous or asynchronous, batched or not) plus the
	 * unchanged {@code calls[]} (the run's SyncLog rows) and their replayability.
	 */
	public static ObjectNode runStatusJson(final ConnectorRepository pRepo, final String pRunId)
	{
		return ConnectorsTools.runStatusJson(pRepo, pRunId, -1, 0);
	}

	/**
	 * The state of a run and one bounded page of its log rows (audit C-33). The state ({@code execution}) comes from the
	 * run's own row, so reading it costs one primary-key lookup whatever the size of the log; {@code calls} is a page of
	 * {@code pCallsMax} rows (unset = {@link RunLogPage#DEFAULT_MAX}, at most {@link RunLogPage#HARD_MAX}, 0 = the log is not
	 * read) starting {@code pCallsFrom} rows from the newest. {@code callsCut} and {@code callsNextFrom} say when rows remain;
	 * {@code count} and {@code errors} describe the page, not the run - the run's counters are in {@code execution}.
	 */
	public static ObjectNode runStatusJson(final ConnectorRepository pRepo, final String pRunId, final int pCallsMax, final int pCallsFrom)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pRunId);
		final Adaptation state = pRepo.getRun(pRunId);
		if (state != null && state.getSchemaNode().getNode(ConnectorsPaths.ExecutionRun.STATUS) != null)
		{
			final ObjectNode execution = out.putObject("execution");
			execution.put("status", state.getString(ConnectorsPaths.ExecutionRun.STATUS));
			execution.put("mapping", state.getString(ConnectorsPaths.ExecutionRun.MAPPING));
			ConnectorsTools.putDirection(out, execution, ConnectorsTools.mappingOfRun(pRepo, state.getString(ConnectorsPaths.ExecutionRun.MAPPING)));
			final Integer nextIndex = (Integer) state.get(ConnectorsPaths.ExecutionRun.NEXT_INDEX);
			if (nextIndex != null) execution.put("nextIndex", nextIndex.intValue());
			execution.put("startedAt", ConnectorsTools.date((java.util.Date) state.get(ConnectorsPaths.ExecutionRun.STARTED_AT)));
			execution.put("updatedAt", ConnectorsTools.date((java.util.Date) state.get(ConnectorsPaths.ExecutionRun.UPDATED_AT)));
			execution.put("created", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.CREATED_COUNT));
			execution.put("updated", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.UPDATED_COUNT));
			execution.put("noChange", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.NO_CHANGE_COUNT));
			execution.put("errorCount", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.ERROR_COUNT));
			execution.put("skipped", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.SKIPPED_COUNT));
			execution.put("rejected", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.REJECTED_COUNT));
			execution.put("conflicts", ConnectorsTools.intOrZero(state, ConnectorsPaths.ExecutionRun.CONFLICT_COUNT));
			final Boolean truncated = (Boolean) state.get(ConnectorsPaths.ExecutionRun.TRUNCATED);
			execution.put("truncated", truncated != null && truncated.booleanValue());
			final Integer available = (Integer) state.get(ConnectorsPaths.ExecutionRun.AVAILABLE);
			if (available != null) execution.put("available", available.intValue());
			final Boolean resultsOmitted = (Boolean) state.get(ConnectorsPaths.ExecutionRun.RESULTS_OMITTED);
			if (resultsOmitted != null && resultsOmitted.booleanValue()) execution.put("resultsOmitted", true);
			final String firstErrors = state.getString(ConnectorsPaths.ExecutionRun.FIRST_ERRORS);
			if (firstErrors != null && !firstErrors.isEmpty()) execution.put("firstErrors", firstErrors);
			final String message = state.getString(ConnectorsPaths.ExecutionRun.MESSAGE);
			if (message != null && !message.isEmpty()) execution.put("message", message);
			// volume lot V2-D: the watermark of an incremental feed run (fields absent on an older model)
			if (state.getSchemaNode().getNode(ConnectorsPaths.ExecutionRun.SKIPPED_BY_WATERMARK) != null)
			{
				final Integer skippedByWatermark = (Integer) state.get(ConnectorsPaths.ExecutionRun.SKIPPED_BY_WATERMARK);
				final String before = state.getString(ConnectorsPaths.ExecutionRun.WATERMARK_BEFORE);
				final String after = state.getString(ConnectorsPaths.ExecutionRun.WATERMARK_AFTER);
				if (skippedByWatermark != null || before != null || after != null)
				{
					if (before == null) execution.putNull("watermarkBefore"); else execution.put("watermarkBefore", before);
					if (after == null) execution.putNull("watermarkAfter"); else execution.put("watermarkAfter", after);
					execution.put("skippedByWatermark", skippedByWatermark == null ? 0 : skippedByWatermark.intValue());
				}
			}
		}
		// The row could not be given the final state of this run: what the run reached is still known to
		// this JVM, and saying it beats a row that reads RUNNING for a run that is over.
		final com.on.ebx.connect.core.Bookkeeping.LostRunState lost = com.on.ebx.connect.core.Bookkeeping.lostRunState(pRunId);
		if (lost != null)
		{
			final ObjectNode execution = out.has("execution") ? (ObjectNode) out.get("execution") : out.putObject("execution");
			execution.put("status", lost.getStatus());
			execution.put("stateNotSaved", true);
			execution.put("message", "The final state of this run could not be saved (see the kernel log): status and message come from memory until the next restart, the counters are those of the last saved batch"
				+ (lost.getMessage() == null || lost.getMessage().isEmpty() ? "" : " - " + lost.getMessage()));
		}
		final int callsMax = RunLogPage.max(pCallsMax);
		final int callsFrom = RunLogPage.from(pCallsFrom);
		final RunLogPage<Adaptation> page = pRepo.logOfRunPage(pRunId, callsFrom, callsMax);
		final ArrayNode rows = out.putArray("calls");
		int errors = 0;
		for (final Adaptation row : page.getRows())
		{
			rows.add(ConnectorsTools.logJson(row));
			if ("ERROR".equals(row.getString(ConnectorsPaths.SyncLog.STATUS)))
			{
				errors++;
			}
		}
		out.put("count", rows.size());
		out.put("errors", errors);
		out.put("callsMax", callsMax);
		out.put("callsFrom", callsFrom);
		if (callsMax == 0)
		{
			out.put("callsOmitted", true);
		}
		if (page.isCut())
		{
			out.put("callsCut", true);
			out.put("callsNextFrom", page.getNextFrom());
			out.put("callsNote", page.cutNote(callsMax));
		}
		// The page does not hold every row of the run: a run is also replayable when its own counters or the unread rows may hold a failure.
		final boolean executionErrors = out.has("execution") && out.path("execution").path("errorCount").asInt(0) > 0;
		out.put("replayable", errors > 0 || executionErrors || page.isCut() || callsMax == 0 && !out.has("execution"));
		return out;
	}

	private static int intOrZero(final Adaptation pRecord, final com.orchestranetworks.schema.Path pPath)
	{
		final Integer value = (Integer) pRecord.get(pPath);
		return value == null ? 0 : value.intValue();
	}

	/** One outbound state as JSON: the single shape of the MCP tool and of {@code GET /v1/outbound} (audit C2-07). */
	public static ObjectNode outboundJson(final Adaptation pState)
	{
		final ObjectNode r = Json.object();
		r.put("key", pState.getString(ConnectorsPaths.OutboundState.RECORD_KEY));
		r.put("record", pState.getString(ConnectorsPaths.OutboundState.RECORD_LABEL));
		r.put("status", pState.getString(ConnectorsPaths.OutboundState.STATUS));
		final Integer attempts = (Integer) pState.get(ConnectorsPaths.OutboundState.ATTEMPTS);
		r.put("attempts", attempts == null ? 0 : attempts.intValue());
		r.put("lastError", pState.getString(ConnectorsPaths.OutboundState.LAST_ERROR));
		r.put("lastRunId", pState.getString(ConnectorsPaths.OutboundState.LAST_RUN_ID));
		r.put("lastSentAt", ConnectorsTools.date((java.util.Date) pState.get(ConnectorsPaths.OutboundState.LAST_SENT_AT)));
		return r;
	}

	/**
	 * The paging fields of an answer: {@code count} = every matching row, {@code max} and {@code from} as applied, and when the page is cut
	 * (or {@code max} was lowered to the ceiling) {@code cut}, {@code nextFrom} and {@code note} (audit C2-07).
	 */
	public static void pageFields(final ObjectNode pOut, final ListingPage<?> pPage, final int pRequestedMax, final String pWhat)
	{
		pOut.put("count", pPage.getTotal());
		pOut.put("max", pPage.getMax());
		pOut.put("from", pPage.getFrom());
		if (pPage.isCut())
		{
			pOut.put("cut", true);
			pOut.put("nextFrom", pPage.getNextFrom());
		}
		final String note = pPage.note(pWhat, pRequestedMax);
		if (note != null)
		{
			pOut.put("note", note);
		}
	}

	/** The answer of {@code connectors_outbound_states} and of {@code GET /v1/outbound}: one page of the states the session may read. */
	public static ObjectNode outboundPage(final ConnectorRepository pRepo, final Session pSession, final int pMappingId, final String pStatus, final int pFrom, final int pMax)
	{
		final String status = pStatus == null || pStatus.trim().isEmpty() ? "FAILED" : pStatus.trim().toUpperCase();
		final ObjectNode out = Json.object();
		out.put("mapping", pMappingId);
		out.put("status", status);
		final ListingPage<Adaptation> page = pRepo.pageOutboundStates(pSession, pMappingId, "ALL".equals(status) ? null : status, pFrom, pMax);
		final ArrayNode rows = out.putArray("records");
		for (final Adaptation state : page.getRows())
		{
			rows.add(ConnectorsTools.outboundJson(state));
		}
		ConnectorsTools.pageFields(out, page, pMax, "outbound states");
		return out;
	}

	private ObjectNode outbound(final String pMapping, final String pStatus, final int pFrom, final int pMax)
	{
		final MappingDefinition mapping = pMapping == null ? null : Mappings.mappingNamed(this.repo(), pMapping);
		if (mapping == null)
		{
			throw new IllegalArgumentException("No mapping '" + pMapping + "' (mapping is required)");
		}
		return ConnectorsTools.outboundPage(this.repo(), this.session, mapping.getId(), pStatus, pFrom, pMax);
	}

	private ObjectNode conflicts(final String pStatus, final String pDataspace, final String pDataset, final String pTable, final String pKey)
	{
		final ConflictResolver resolver = new ConflictResolver(this.repo());
		final String status = pStatus == null || pStatus.trim().isEmpty() ? ConflictResolver.OPEN : pStatus.trim().toUpperCase();
		final ConflictResolver.Page page = resolver.page(this.session, status, pDataspace, pDataset, pTable, pKey, ConflictResolver.pageSize());
		final List<Adaptation> rows = page.rows;
		final ObjectNode out = Json.object();
		out.put("status", status);
		// count is every conflict that matches; conflicts[] holds the newest ones up to the page size (audit C-39)
		out.put("count", page.total);
		out.put("hasConflicts", page.total > 0);
		if (page.truncated())
		{
			out.put("truncated", true);
			out.put("returned", rows.size());
			out.put("nextStep", "Only the newest " + rows.size() + " of " + page.total + " conflicts are listed: narrow by dataspace, dataset, table or recordKey, or decide these and list again.");
		}
		final ArrayNode list = out.putArray("conflicts");
		for (final Adaptation row : rows)
		{
			list.add(ConnectorsTools.conflictJson(row));
		}
		return out;
	}

	private ObjectNode decide(final String pId, final String pDecision, final String pComment)
	{
		final ConflictResolver resolver = new ConflictResolver(this.repo());
		final Adaptation conflict = pId == null ? null : resolver.conflict(pId.trim());
		if (conflict == null)
		{
			throw new IllegalArgumentException("No conflict " + pId);
		}
		final ConflictResolver.Outcome outcome;
		if ("accept".equalsIgnoreCase(pDecision))
		{
			outcome = resolver.accept(this.session, conflict, pComment);
		}
		else if ("reject".equalsIgnoreCase(pDecision))
		{
			outcome = resolver.reject(this.session, conflict, pComment);
		}
		else
		{
			throw new IllegalArgumentException("decision must be accept or reject");
		}
		final ObjectNode out = Json.object();
		out.put("id", pId.trim());
		out.put("ok", outcome.ok);
		out.put("message", outcome.message);
		final Adaptation after = resolver.conflict(pId.trim());
		if (after != null)
		{
			out.set("conflict", ConnectorsTools.conflictJson(after));
		}
		return out;
	}

	private ObjectNode log(final String pMapping, final String pConnector, final String pKey, final String pStatus, final String pRunId, final int pFrom, final int pMax)
	{
		final Adaptation dataset = this.repo().dataset();
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		if (dataset == null)
		{
			out.put("count", 0);
			return out;
		}
		final StringBuilder filter = new StringBuilder();
		ConnectorsTools.and(filter, "./mapping", pMapping);
		ConnectorsTools.and(filter, "./connector", pConnector);
		ConnectorsTools.and(filter, "./recordKey", pKey);
		ConnectorsTools.and(filter, "./status", pStatus == null ? null : pStatus.toUpperCase());
		ConnectorsTools.and(filter, "./runId", pRunId);
		final Request request = dataset.getTable(ConnectorsPaths.SYNC_LOG).createRequest();
		request.setSession(this.session);
		if (filter.length() > 0)
		{
			request.setXPathFilter(filter.toString());
		}
		request.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(ConnectorsPaths.SyncLog.ID, false));
		final RequestResult result = request.execute();
		try
		{
			final RequestResult source = result;
			final ListingPage<Adaptation> page = ListingPage.take(new java.util.Iterator<Adaptation>()
			{
				private Adaptation next = source.nextAdaptation();

				@Override
				public boolean hasNext()
				{
					return this.next != null;
				}

				@Override
				public Adaptation next()
				{
					final Adaptation current = this.next;
					this.next = source.nextAdaptation();
					return current;
				}

				@Override
				public void remove()
				{
					throw new UnsupportedOperationException();
				}
			}, pFrom, pMax, result.getSize());
			for (final Adaptation row : page.getRows())
			{
				rows.add(ConnectorsTools.logJson(row));
			}
			ConnectorsTools.pageFields(out, page, pMax, "log rows, newest first,");
		}
		finally
		{
			result.close();
		}
		return out;
	}

	private ObjectNode openapi(final String pId, final String pLabel, final String pUrl, final String pSpec, final boolean pReplace, final boolean pDryRun) throws ConnectorException
	{
		if (pId == null || pId.trim().isEmpty())
		{
			throw new IllegalArgumentException("id is required");
		}
		final String text = pUrl != null && !pUrl.trim().isEmpty() ? OpenApiImport.fetch(pUrl.trim()) : pSpec;
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("url or spec is required");
		}
		final OpenApiImport.Report report = OpenApiImport.run(this.session, this.repository, pId.trim(), pLabel, text, pReplace, pDryRun);
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
		return out;
	}

	private ObjectNode publish(final String pMappings, final String pSchema, final String pProduct, final String pContractVersion, final boolean pDryRun,
		final boolean pRecreate, final int pMax, final String pDataspace)
	{
		if (pMappings == null || pMappings.trim().isEmpty())
		{
			throw new IllegalArgumentException("mappings is required");
		}
		final ConnectorRepository repo = this.repo();
		final List<Integer> ids = new ArrayList<Integer>();
		for (final String token : pMappings.split(","))
		{
			final String trimmed = token.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final MappingDefinition m = Mappings.mappingNamed(repo, trimmed);
			if (m == null)
			{
				throw new IllegalArgumentException("No mapping '" + trimmed + "'");
			}
			ids.add(Integer.valueOf(m.getId()));
		}
		if (ids.isEmpty())
		{
			throw new IllegalArgumentException("mappings is required");
		}
		final ProductPublisher.Request request = new ProductPublisher.Request();
		request.setMappings(ids);
		request.setSchema(pSchema);
		request.setProduct(pProduct);
		request.setContractVersion(pContractVersion);
		request.setDryRun(pDryRun);
		request.setRecreate(pRecreate);
		request.setMax(pMax);
		request.setDataspace(pDataspace);
		return new ProductPublisher(this.session, repo).publish(request).toJson();
	}

	// ------------------------------------------------------------------ helpers

	private ConnectorRepository repo()
	{
		return ConnectorService.get().repository(this.repository);
	}

	private ConnectorConfig config(final String pConnector)
	{
		final ConnectorConfig config = pConnector == null ? null : this.repo().connector(pConnector.trim());
		if (config == null)
		{
			throw new IllegalArgumentException("No connector '" + pConnector + "' (connectors_list shows the configured ids)");
		}
		return config;
	}

	private static Map<String, String> inputs(final ObjectNode pInputs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		if (pInputs != null)
		{
			for (final Iterator<Map.Entry<String, JsonNode>> it = pInputs.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> e = it.next();
				inputs.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText());
			}
		}
		return inputs;
	}

	static ObjectNode logJson(final Adaptation pRow)
	{
		final ObjectNode r = Json.object();
		r.put("id", pRow.getOccurrencePrimaryKey().format());
		r.put("timestamp", ConnectorsTools.date((java.util.Date) pRow.get(ConnectorsPaths.SyncLog.TIMESTAMP)));
		r.put("mapping", pRow.getString(ConnectorsPaths.SyncLog.MAPPING));
		r.put("connector", pRow.getString(ConnectorsPaths.SyncLog.CONNECTOR));
		r.put("operation", pRow.getString(ConnectorsPaths.SyncLog.OPERATION));
		r.put("direction", pRow.getString(ConnectorsPaths.SyncLog.DIRECTION));
		r.put("record", pRow.getString(ConnectorsPaths.SyncLog.RECORD_LABEL));
		r.put("key", pRow.getString(ConnectorsPaths.SyncLog.RECORD_KEY));
		r.put("status", pRow.getString(ConnectorsPaths.SyncLog.STATUS));
		r.put("message", pRow.getString(ConnectorsPaths.SyncLog.MESSAGE));
		r.put("runId", pRow.getString(ConnectorsPaths.SyncLog.RUN_ID));
		r.put("origin", pRow.getString(ConnectorsPaths.SyncLog.ORIGIN));
		return r;
	}

	static ObjectNode conflictJson(final Adaptation pConflict)
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
		c.put("detectedAt", ConnectorsTools.date((java.util.Date) pConflict.get(ConnectorsPaths.Conflict.DETECTED_AT)));
		c.put("comment", pConflict.getString(ConnectorsPaths.Conflict.COMMENT));
		return c;
	}

	private static String date(final java.util.Date pDate)
	{
		return pDate == null ? null : new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(pDate);
	}

	private static void and(final StringBuilder pFilter, final String pField, final String pValue)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			pFilter.append(pFilter.length() > 0 ? " and " : "").append(pField).append("='").append(pValue.trim().replace("'", "''")).append("'");
		}
	}

	private static ObjectNode error(final String pMessage)
	{
		final ObjectNode out = Json.object();
		out.put("error", com.on.ebx.connect.core.Redaction.redact(pMessage));
		return out;
	}

	static String str(final JsonNode pArgs, final String pName)
	{
		final JsonNode v = pArgs.get(pName);
		if (v == null || v.isNull())
		{
			return null;
		}
		return v.isContainerNode() ? Json.write(v) : v.asText();
	}

	static boolean bool(final JsonNode pArgs, final String pName, final boolean pDefault)
	{
		final JsonNode v = pArgs.get(pName);
		return v == null || v.isNull() ? pDefault : v.isBoolean() ? v.asBoolean() : "true".equalsIgnoreCase(v.asText());
	}

	static int integer(final JsonNode pArgs, final String pName, final int pDefault)
	{
		final JsonNode v = pArgs.get(pName);
		try
		{
			return v == null || v.isNull() ? pDefault : v.isNumber() ? v.asInt() : Integer.parseInt(v.asText().trim());
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	static ObjectNode objectArg(final JsonNode pArgs, final String pName) throws java.io.IOException
	{
		final JsonNode v = pArgs.get(pName);
		if (v == null || v.isNull())
		{
			return null;
		}
		if (v.isObject())
		{
			return (ObjectNode) v;
		}
		final JsonNode parsed = Json.parse(v.asText());
		return parsed != null && parsed.isObject() ? (ObjectNode) parsed : null;
	}
}
