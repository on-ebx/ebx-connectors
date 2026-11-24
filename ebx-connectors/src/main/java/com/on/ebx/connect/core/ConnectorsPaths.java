package com.on.ebx.connect.core;

import com.orchestranetworks.schema.Path;

/** Paths of the {@code Connectors.xsd} configuration model. */
public final class ConnectorsPaths
{
	public static final String MODEL_PATH = "/WEB-INF/ebx/schemas/Connectors.xsd";
	/** The dedicated EBX module that packages Connectors.xsd (module.xml/name); the startup hook passes the real name. */
	public static final String MODULE_NAME = "ebx-connectors";
	public static final String DATASPACE = "Connectors";
	public static final String DATASET = "Connectors";

	public static final Path CONNECTOR = Path.parse("/root/Connector");
	public static final Path CONNECTOR_CATEGORY = Path.parse("/root/ConnectorCategory");
	public static final Path CONNECTOR_PROPERTY = Path.parse("/root/ConnectorProperty");
	public static final Path SCHEMA_REFRESH_STATE = Path.parse("/root/SchemaRefreshState");
	public static final Path CONNECTOR_TEMPLATE = Path.parse("/root/ConnectorTemplate");
	public static final Path RELEASE = Path.parse("/root/Release");
	public static final Path RELEASE_ITEM = Path.parse("/root/ReleaseItem");
	public static final Path RELEASE_METRIC = Path.parse("/root/ReleaseMetric");
	public static final Path RELEASE_PASS_STATUS = Path.parse("/root/ReleasePassStatus");
	public static final Path RELEASE_PASS_STEP = Path.parse("/root/ReleasePassStep");
	public static final Path MAPPING = Path.parse("/root/Mapping");
	public static final Path MAPPING_INPUT = Path.parse("/root/MappingInput");
	public static final Path MAPPING_RULE = Path.parse("/root/MappingRule");
	public static final Path SYNCHRONIZATION_CHECKPOINT = Path.parse("/root/SynchronizationCheckpoint");
	public static final Path CONNECTOR_OPERATIONAL_METRICS = Path.parse("/root/ConnectorOperationalMetrics");
	public static final Path CONNECTOR_GOVERNANCE = Path.parse("/root/ConnectorGovernance");
	public static final Path CONNECTOR_GOVERNANCE_AUDIT = Path.parse("/root/ConnectorGovernanceAudit");
	public static final Path EXECUTION_RUN = Path.parse("/root/ExecutionRun");
	public static final Path OUTBOUND_STATE = Path.parse("/root/OutboundState");
	public static final Path SYNC_LOG = Path.parse("/root/SyncLog");
	public static final Path FIELD_PROVENANCE = Path.parse("/root/FieldProvenance");
	public static final Path CONFLICT = Path.parse("/root/Conflict");
	public static final Path PUBLICATION = Path.parse("/root/Publication");
	public static final Path FAVORITE = Path.parse("/root/Favorite");
	public static final Path SEED_MARKER = Path.parse("/root/SeedMarker");
	/** What a table trigger asked to send, waiting for the background sender (audit C-07, lot L8). */
	public static final Path OUTBOUND_QUEUE = Path.parse("/root/OutboundQueue");
	public static final Path ACCESS_RIGHT = Path.parse("/root/AccessRight");
	/** The module's own settings: a group of dataset-level fields, edited by an administrator (wave 12). */
	public static final Path SETTINGS = Path.parse("/root/Settings");
	/** The {@code llm} connector the in-JVM contract uses when a caller names none (wave 12). */
	public static final Path SETTINGS_DEFAULT_LLM = Path.parse("/root/Settings/defaultLlmConnector");

	public static final class Connector
	{
		public static final Path ID = Path.parse("./id");
		public static final Path LABEL = Path.parse("./label");
		public static final Path DESCRIPTION = Path.parse("./description");
		public static final Path CATEGORY = Path.parse("./category");
		public static final Path TYPE = Path.parse("./type");
		public static final Path BASE_URL = Path.parse("./baseUrl");
		public static final Path AUTH_TYPE = Path.parse("./authType");
		public static final Path USERNAME = Path.parse("./username");
		public static final Path PASSWORD = Path.parse("./password");
		public static final Path API_KEY_HEADER = Path.parse("./apiKeyHeader");
		public static final Path API_KEY = Path.parse("./apiKey");
		public static final Path SAMPLE_DATA = Path.parse("./sampleData");
		public static final Path CONNECT_TIMEOUT = Path.parse("./connectTimeoutMs");
		public static final Path READ_TIMEOUT = Path.parse("./readTimeoutMs");
		public static final Path ACTIVE = Path.parse("./active");
		public static final Path CONNECTION_STATUS = Path.parse("./connectionStatus");
		public static final Path CONNECTION_VERIFIED_AT = Path.parse("./connectionVerifiedAt");
		public static final Path CONNECTION_FINGERPRINT = Path.parse("./connectionFingerprint");
		public static final Path CONNECTION_MESSAGE = Path.parse("./connectionMessage");
		public static final Path TRUST = Path.parse("./trust");
		public static final Path PRICING = Path.parse("./pricing");
		public static final Path PRICING_NOTE = Path.parse("./pricingNote");
		public static final Path LOGO = Path.parse("./logo");
		public static final Path UPLOADED_LOGO = Path.parse("./uploadedLogo");
		public static final Path LOGO_URL = Path.parse("./logoUrl");
	}

	public static final class ConnectorCategory
	{
		public static final Path CODE = Path.parse("./code");
		public static final Path LABEL = Path.parse("./label");
		public static final Path DESCRIPTION = Path.parse("./description");
		public static final Path DISPLAY_ORDER = Path.parse("./displayOrder");
	}

	public static final class FieldProvenance
	{
		// Volume lot 5: the primary key is now (dataspace, dataset, tablePath, recordKey, fieldPath) -
		// the auto-increment id is gone from the schema (see tools/build_xsd.py and docs/REFERENCE.md).
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path FIELD_PATH = Path.parse("./fieldPath");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path TRUST = Path.parse("./trust");
		public static final Path VALUE = Path.parse("./value");
		public static final Path WRITTEN_AT = Path.parse("./writtenAt");
		/** Gartner lot A: where the value was read (document, page, snippet); optional column. */
		public static final Path SOURCE = Path.parse("./source");
	}

	public static final class Conflict
	{
		public static final Path ID = Path.parse("./id");
		public static final Path STATUS = Path.parse("./status");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path RECORD_LABEL = Path.parse("./recordLabel");
		public static final Path FIELD_PATH = Path.parse("./fieldPath");
		public static final Path CURRENT_VALUE = Path.parse("./currentValue");
		public static final Path CURRENT_CONNECTOR = Path.parse("./currentConnector");
		public static final Path CURRENT_TRUST = Path.parse("./currentTrust");
		public static final Path PROPOSED_VALUE = Path.parse("./proposedValue");
		public static final Path PROPOSED_CONNECTOR = Path.parse("./proposedConnector");
		public static final Path PROPOSED_TRUST = Path.parse("./proposedTrust");
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path DETECTED_AT = Path.parse("./detectedAt");
		public static final Path COMMENT = Path.parse("./comment");
		public static final Path RESOLVED_BY = Path.parse("./resolvedBy");
		public static final Path RESOLVED_AT = Path.parse("./resolvedAt");
	}

	public static final class ConnectorProperty
	{
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path KEY = Path.parse("./key");
		public static final Path VALUE = Path.parse("./value");
	}

	public static final class SchemaRefreshState
	{
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path KEY = Path.parse("./key");
		public static final Path URL = Path.parse("./url");
		public static final Path STATE = Path.parse("./state");
		public static final Path UPDATED_AT = Path.parse("./updatedAt");
	}

	public static final class ConnectorTemplate
	{
		public static final Path ID = Path.parse("./id");
		public static final Path VERSION = Path.parse("./version");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path BASED_ON = Path.parse("./basedOn");
		public static final Path BASED_ON_VERSION = Path.parse("./basedOnVersion");
		public static final Path CONFIGURATION = Path.parse("./configuration");
		public static final Path CREATED_BY = Path.parse("./createdBy");
		public static final Path CREATED_AT = Path.parse("./createdAt");
		public static final Path UPDATED_BY = Path.parse("./updatedBy");
		public static final Path UPDATED_AT = Path.parse("./updatedAt");
	}

	public static final class Release
	{
		public static final Path VERSION = Path.parse("./version");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path RELEASE_DATE = Path.parse("./releaseDate");
		public static final Path STATUS = Path.parse("./status");
		public static final Path TITLE = Path.parse("./title");
		public static final Path SUMMARY = Path.parse("./summary");
		public static final Path BUILD_VERSION = Path.parse("./buildVersion");
		public static final Path COMMIT_ID = Path.parse("./commitId");
	}

	public static final class ReleaseItem
	{
		public static final Path RELEASE = Path.parse("./release");
		public static final Path ID = Path.parse("./id");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path TYPE = Path.parse("./type");
		public static final Path DISPLAY_ORDER = Path.parse("./displayOrder");
		public static final Path TITLE = Path.parse("./title");
		public static final Path DETAIL = Path.parse("./detail");
	}

	public static final class ReleaseMetric
	{
		public static final Path RELEASE = Path.parse("./release");
		public static final Path CODE = Path.parse("./code");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path LABEL = Path.parse("./label");
		public static final Path VALUE = Path.parse("./value");
		public static final Path DISPLAY_ORDER = Path.parse("./displayOrder");
	}

	public static final class ReleasePassStatus
	{
		public static final Path RELEASE = Path.parse("./release");
		public static final Path CODE = Path.parse("./code");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path LABEL = Path.parse("./label");
		public static final Path STATUS = Path.parse("./status");
		public static final Path PROGRESS = Path.parse("./progress");
		public static final Path REMAINING_GATES = Path.parse("./remainingGates");
		public static final Path DISPLAY_ORDER = Path.parse("./displayOrder");
	}

	public static final class ReleasePassStep
	{
		public static final Path RELEASE = Path.parse("./release");
		public static final Path PASS_CODE = Path.parse("./passCode");
		public static final Path CODE = Path.parse("./code");
		public static final Path OWNERSHIP = Path.parse("./ownership");
		public static final Path LABEL = Path.parse("./label");
		public static final Path STATUS = Path.parse("./status");
		public static final Path OCCURRED_ON = Path.parse("./occurredOn");
		public static final Path DETAIL = Path.parse("./detail");
		public static final Path DISPLAY_ORDER = Path.parse("./displayOrder");
	}

	public static final class Mapping
	{
		public static final Path ID = Path.parse("./id");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path OPERATION = Path.parse("./operation");
		public static final Path DIRECTION = Path.parse("./direction");
		public static final Path LABEL = Path.parse("./label");
		public static final Path DESCRIPTION = Path.parse("./description");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RESPONSE_ROOT = Path.parse("./responseRoot");
		public static final Path ACTIVE = Path.parse("./active");
		public static final Path PARENT = Path.parse("./parent");
		public static final Path COLLECTION_PATH = Path.parse("./collectionPath");
		public static final Path PARENT_LINK_FIELD = Path.parse("./parentLinkField");
		public static final Path CHILD_KEY_FIELDS = Path.parse("./childKeyFields");
		public static final Path CHILD_FILTER = Path.parse("./childFilter");
		public static final Path DELETE_MISSING = Path.parse("./deleteMissing");
		public static final Path ITEMS_PATH = Path.parse("./itemsPath");
		public static final Path KEY_PREDICATE = Path.parse("./keyPredicate");
		public static final Path CREATE_IF_MISSING = Path.parse("./createIfMissing");
		public static final Path LOAD_MODE = Path.parse("./loadMode");
		public static final Path CHECKPOINT_STRATEGY = Path.parse("./checkpointStrategy");
		public static final Path CHECKPOINT_INPUT = Path.parse("./checkpointInput");
		public static final Path CHECKPOINT_VALUE_PATH = Path.parse("./checkpointValuePath");
		public static final Path CHECKPOINT_INITIAL_VALUE = Path.parse("./checkpointInitialValue");
		/** Volume lot V2-D: INCLUSIVE / EXCLUSIVE - whether a row equal to the watermark is read again; empty = automatic. */
		public static final Path CHECKPOINT_BOUNDARY = Path.parse("./checkpointBoundary");
		public static final Path DELETE_OPERATION = Path.parse("./deleteOperation");
		public static final Path MAX_ATTEMPTS = Path.parse("./maxAttempts");
		public static final Path RETRY_DELAY_MINUTES = Path.parse("./retryDelayMinutes");
		/** Volume lot 5: whether a sync through this mapping records FieldProvenance rows (default true). */
		public static final Path PROVENANCE = Path.parse("./provenance");
		/** Volume lot 5: target field whose unchanged value skips the write entirely; empty = off. */
		public static final Path UNCHANGED_IF_EQUAL = Path.parse("./unchangedIfEqual");
		/** Volume lot 6: rows per import-feed write procedure; null/absent -> default (1000). */
		public static final Path BATCH_SIZE = Path.parse("./batchSize");
		/** Volume lot 6: true restores one SyncLog row per imported record instead of one per batch. */
		public static final Path LOG_EVERY_RECORD = Path.parse("./logEveryRecord");
	}

	public static final class MappingInput
	{
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path NAME = Path.parse("./name");
		public static final Path EXPRESSION = Path.parse("./expression");
	}

	public static final class SynchronizationCheckpoint
	{
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path STRATEGY = Path.parse("./strategy");
		public static final Path VALUE = Path.parse("./value");
		public static final Path UPDATED_AT = Path.parse("./updatedAt");
		public static final Path VERSION = Path.parse("./version");
	}

	/** What the installer has already created (audit C-26): one row per seeded connector, mapping group or migration. */
	public static final class OutboundQueue
	{
		public static final Path ID = Path.parse("./id");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path OPERATION = Path.parse("./operation");
		public static final Path DIRECTION = Path.parse("./direction");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path PUBLISH = Path.parse("./publish");
		public static final Path ENQUEUED_AT = Path.parse("./enqueuedAt");
		public static final Path NOT_BEFORE = Path.parse("./notBefore");
		public static final Path ATTEMPTS = Path.parse("./attempts");
		public static final Path STATUS = Path.parse("./status");
		public static final Path LAST_ATTEMPT_AT = Path.parse("./lastAttemptAt");
		public static final Path LAST_ERROR = Path.parse("./lastError");
	}

	public static final class SeedMarker
	{
		public static final Path ITEM = Path.parse("./item");
		public static final Path SEEDED_AT = Path.parse("./seededAt");
	}

	public static final class ExecutionRun
	{
		public static final Path RUN_ID = Path.parse("./runId");
		public static final Path NEXT_INDEX = Path.parse("./nextIndex");
		public static final Path ATTEMPTS = Path.parse("./attempts");
		public static final Path COMPLETED = Path.parse("./completed");
		public static final Path DEAD_LETTERS = Path.parse("./deadLetters");
		public static final Path LEASE_OWNER = Path.parse("./leaseOwner");
		public static final Path LEASE_EXPIRES_AT = Path.parse("./leaseExpiresAt");
		public static final Path UPDATED_AT = Path.parse("./updatedAt");
		// Volume lot 7a: status/counters/timestamps for connectors_run_get and the asynchronous form of
		// connectors_sync - a different, additive use of the same table (see saveNextItemIndex's javadoc
		// on ConnectorRepository for why this table, not a new one).
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path STATUS = Path.parse("./status");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		public static final Path CREATED_COUNT = Path.parse("./createdCount");
		public static final Path UPDATED_COUNT = Path.parse("./updatedCount");
		public static final Path NO_CHANGE_COUNT = Path.parse("./noChangeCount");
		public static final Path ERROR_COUNT = Path.parse("./errorCount");
		public static final Path SKIPPED_COUNT = Path.parse("./skippedCount");
		public static final Path REJECTED_COUNT = Path.parse("./rejectedCount");
		public static final Path CONFLICT_COUNT = Path.parse("./conflictCount");
		public static final Path TRUNCATED = Path.parse("./truncated");
		public static final Path AVAILABLE = Path.parse("./available");
		public static final Path RESULTS_OMITTED = Path.parse("./resultsOmitted");
		public static final Path FIRST_ERRORS = Path.parse("./firstErrors");
		public static final Path MESSAGE = Path.parse("./message");
		/** Volume lot V2-D: the feed watermark the run started from / ended on, and the rows it skipped at or below it. */
		public static final Path WATERMARK_BEFORE = Path.parse("./watermarkBefore");
		public static final Path WATERMARK_AFTER = Path.parse("./watermarkAfter");
		public static final Path SKIPPED_BY_WATERMARK = Path.parse("./skippedByWatermark");
	}

	public static final class ConnectorOperationalMetrics
	{
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path READ_COUNT = Path.parse("./readCount");
		public static final Path WRITE_COUNT = Path.parse("./writeCount");
		public static final Path SKIP_COUNT = Path.parse("./skipCount");
		public static final Path CONFLICT_COUNT = Path.parse("./conflictCount");
		public static final Path REQUEST_COUNT = Path.parse("./requestCount");
		public static final Path RETRY_COUNT = Path.parse("./retryCount");
		public static final Path THROTTLE_COUNT = Path.parse("./throttleCount");
		public static final Path DURATION_SAMPLES = Path.parse("./durationSamples");
		public static final Path LAST_SUCCESS_AT = Path.parse("./lastSuccessAt");
		public static final Path LAST_FAILURE_AT = Path.parse("./lastFailureAt");
		public static final Path LAST_FAILURE = Path.parse("./lastFailure");
		public static final Path CONSECUTIVE_FAILURES = Path.parse("./consecutiveFailures");
		public static final Path CHECKPOINT_AT = Path.parse("./checkpointAt");
		public static final Path SCHEMA_DRIFT_AT = Path.parse("./schemaDriftAt");
		public static final Path SCHEMA_DRIFT_COUNT = Path.parse("./schemaDriftCount");
		public static final Path UPDATED_AT = Path.parse("./updatedAt");
	}

	public static final class ConnectorGovernance
	{
		public static final Path RESOURCE_ID = Path.parse("./resourceId");
		public static final Path OWNER = Path.parse("./owner");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path SOURCE_PRECEDENCE = Path.parse("./sourcePrecedence");
		public static final Path APPROVAL = Path.parse("./approval");
		public static final Path EFFECTIVE_FROM = Path.parse("./effectiveFrom");
		public static final Path EFFECTIVE_UNTIL = Path.parse("./effectiveUntil");
		public static final Path PUBLICATION_VERSION = Path.parse("./publicationVersion");
		public static final Path DEPENDENCIES = Path.parse("./dependencies");
		public static final Path SECRET_REFERENCES = Path.parse("./secretReferences");
	}

	public static final class ConnectorGovernanceAudit
	{
		public static final Path ID = Path.parse("./id");
		public static final Path RESOURCE_ID = Path.parse("./resourceId");
		public static final Path ACTION = Path.parse("./action");
		public static final Path ACTOR = Path.parse("./actor");
		public static final Path DETAIL = Path.parse("./detail");
		public static final Path OCCURRED_AT = Path.parse("./occurredAt");
	}

	public static final class MappingRule
	{
		public static final Path ID = Path.parse("./id");
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path POSITION = Path.parse("./position");
		public static final Path SOURCE_PATH = Path.parse("./sourcePath");
		public static final Path TARGET_PATH = Path.parse("./targetPath");
		public static final Path TRANSFORMATION = Path.parse("./transformation");
		public static final Path POLICY = Path.parse("./policy");
		public static final Path ACTIVE = Path.parse("./active");
		public static final Path COMMENT = Path.parse("./comment");
	}

	public static final class SyncLog
	{
		public static final Path ID = Path.parse("./id");
		public static final Path TIMESTAMP = Path.parse("./timestamp");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path OPERATION = Path.parse("./operation");
		public static final Path DIRECTION = Path.parse("./direction");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path RECORD_LABEL = Path.parse("./recordLabel");
		public static final Path STATUS = Path.parse("./status");
		public static final Path MESSAGE = Path.parse("./message");
		public static final Path CHANGES = Path.parse("./changes");
		public static final Path DURATION_MS = Path.parse("./durationMs");
		public static final Path USER = Path.parse("./user");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path RUN_ID = Path.parse("./runId");
		public static final Path MAPPING = Path.parse("./mapping");
	}

	private ConnectorsPaths()
	{
	}

	public static final class AccessRight
	{
		public static final Path PROFILE = Path.parse("./profile");
		public static final Path RIGHT = Path.parse("./right");
	}

	public static final class OutboundState
	{
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path RECORD_LABEL = Path.parse("./recordLabel");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path STATUS = Path.parse("./status");
		public static final Path LAST_SENT_AT = Path.parse("./lastSentAt");
		public static final Path LAST_ATTEMPT_AT = Path.parse("./lastAttemptAt");
		public static final Path ATTEMPTS = Path.parse("./attempts");
		public static final Path HASH = Path.parse("./hash");
		public static final Path LAST_DOCUMENT = Path.parse("./lastDocument");
		public static final Path LAST_ERROR = Path.parse("./lastError");
		public static final Path LAST_RUN_ID = Path.parse("./lastRunId");
	}

	/** Per-user shortcuts of the connector explorer. */
	public static final class Favorite
	{
		public static final Path USER_LOGIN = Path.parse("./userLogin");
		public static final Path TARGET_TYPE = Path.parse("./targetType");
		public static final Path TARGET_KEY = Path.parse("./targetKey");
		public static final Path CREATED_AT = Path.parse("./createdAt");
	}

	/** One row per {@code ProductPublisher.publish(...)} call, dry runs included (§2.3 bookkeeping). */
	public static final class Publication
	{
		public static final Path RUN_ID = Path.parse("./runId");
		public static final Path PRODUCT = Path.parse("./product");
		public static final Path CONTRACT_VERSION = Path.parse("./contractVersion");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path SCHEMA = Path.parse("./schema");
		public static final Path STATUS = Path.parse("./status");
		public static final Path MESSAGE = Path.parse("./message");
		public static final Path CUTOFF = Path.parse("./cutoff");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		public static final Path ENDED_AT = Path.parse("./endedAt");
		public static final Path ROW_COUNT = Path.parse("./rowCount");
		public static final Path MAPPINGS = Path.parse("./mappings");
		public static final Path TABLES = Path.parse("./tables");
		public static final Path REPORT = Path.parse("./report");
	}
}
