package com.on.ebx.connect.spi;

	import java.util.Collections;
	import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A connector to an external application. Implementations are stateless apart from their
 * {@link ConnectorConfig}; one instance serves one configured connector id.
 * <p>
 * Register a new connector type either programmatically
 * ({@code ConnectorRegistry.registerType}) or through {@code META-INF/services/com.on.ebx.connect.spi.Connector}.
 * The external representation is JSON ({@link JsonNode}) in both directions: an inbound mapping
 * reads paths from the document the connector returns, an outbound mapping builds the document
 * the connector sends.
 */
public interface Connector
{
	/** Registry key, e.g. {@code gleif}; the {@code type} column of the Connector table. */
	String getType();

	/** Human readable name of the connector type. */
	String getLabel();

	/** Operations this connector supports. */
	List<Operation> getOperations();

	/**
	 * Type-level metadata for catalogs and configuration tooling.
	 *
	 * <p>The default preserves source and binary compatibility for existing connectors. Implementations
	 * can override it incrementally to advertise richer capabilities and typed configuration.</p>
	 */
	default ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(
			getType(), getLabel(), "GENERIC_INTEGRATION");
		final List<Operation> operations = getOperations();
		if (operations != null)
		{
			for (final Operation operation : operations)
			{
				if (operation == null)
				{
					continue;
				}
				final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
					? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
				descriptor.direction(direction).operation(new ConnectorOperationDescriptor(
					operation.getName(), operation.getKind(), operation.getDescription(),
					Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
			}
		}
		return descriptor.build();
	}

	/** Called once, before any execution, with the configuration stored in EBX. */
	void configure(ConnectorConfig pConfig);

	/**
	 * Releases what this instance keeps open between two calls: a client, a connection, a thread.
	 * <p>
	 * Called when the module lets go of the instance - its configuration changed or was removed, the
	 * instance cache was invalidated, the module is stopping - and at no other moment. A connector
	 * that opens and closes what it needs inside each call has nothing to do here, which is what the
	 * default does.
	 * <p>
	 * A run started before may still hold the instance and call it during or after {@code close()}:
	 * an implementation must then either fail that call cleanly or open again what it needs; it must
	 * not leave the instance in a state where the call hangs. Must not throw.
	 */
	default void close()
	{
	}

	/**
	 * Executes one operation.
	 *
	 * @param pInputs resolved inputs (see {@link Operation#getRequiredInputs()}).
	 * @param pPayload document to send for {@link Operation.Kind#WRITE} operations, else null.
	 * @return the document received, or null when the external system has nothing for these
	 *         inputs (HTTP 404, empty search...).
	 */
	JsonNode execute(ConnectorContext pContext, String pOperation, Map<String, String> pInputs,
		JsonNode pPayload) throws ConnectorException;

	/**
	 * Executes a bounded group of ordinary operations. Existing connectors remain compatible and
	 * use ordered sequential execution; protocol-aware connectors can override with a native batch.
	 */
	default BatchResult executeBatch(final ConnectorContext pContext, final BatchRequest pRequest)
		throws ConnectorException
	{
		return ConnectorBatchExecutor.sequential(this, pContext, pRequest);
	}

	/**
	 * Fields of the document an operation returns (READ/SEARCH) or expects (WRITE), for the
	 * mapping designer. Paths are relative to the document root, e.g. {@code data.attributes.entity.legalName.name}.
	 * Default: none; the designer then relies on a sample call.
	 */
	default List<FieldInfo> describeFields(ConnectorContext pContext, String pOperation, Map<String, String> pInputs)
		throws ConnectorException
	{
		return java.util.Collections.emptyList();
	}

	/** Validates an instance without contacting or modifying the external system. */
	default ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		return ConnectorConfigurationValidator.validate(getDescriptor(), pConfig);
	}

	/** Performs a read-only connectivity check. Existing connectors opt in by overriding this method. */
	default ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		return ConnectionTestResult.unsupported("Connection testing is not implemented by this connector");
	}

	/** Discovers external objects without reading their records. */
	default ObjectDiscoveryResult discoverObjects(final ConnectorContext pContext,
		final ObjectDiscoveryRequest pRequest) throws ConnectorException
	{
		return ConnectorInspectionAdapters.discoverOperations(getOperations(), pRequest);
	}

	/** Describes the fields of one discovered external object. */
	default ObjectDescription describeObject(final ConnectorContext pContext, final String pObjectId)
		throws ConnectorException
	{
		final List<FieldInfo> fields = describeFields(pContext, pObjectId,
			Collections.<String, String>emptyMap());
		return fields == null || fields.isEmpty()
			? ObjectDescription.unsupported(pObjectId,
				"Object description is not implemented by this connector")
			: ObjectDescription.of(pObjectId, ConnectorInspectionAdapters.operationLabel(
				getOperations(), pObjectId), fields);
	}

	/** Reads a bounded sample. Implementations must not invoke a write operation. */
	default PreviewResult preview(final ConnectorContext pContext, final PreviewRequest pRequest)
		throws ConnectorException
	{
		return PreviewResult.unsupported("Preview is not implemented by this connector");
	}

	// ------------------------------------------------------------------ volume lot 7a: streamed feed reads

	/**
	 * One row or item read by {@link #readRows}, delivered as soon as it is available instead of
	 * being appended to an in-memory array. Return {@code false} to stop the read early (the caller's
	 * batch/circuit-breaker/cancellation logic is done with the source); the connector must then stop
	 * pulling further rows as soon as it reasonably can (for a JDBC cursor: close the
	 * {@code ResultSet}/{@code Statement} without fetching more).
	 */
	interface RowSink
	{
		boolean accept(JsonNode pRow);

		/**
		 * Offers the connector a way to learn which of the rows it delivered have been written, for a
		 * source that must not forget a row before EBX holds it (a message broker's offsets). Rows are
		 * numbered from 0 in the order {@link #accept} received them. Returns {@code false} when this
		 * sink cannot say - the default - and the connector must then not treat any row as written.
		 * A connector that does not care never calls this.
		 */
		default boolean onHandled(final HandledListener pListener)
		{
			return false;
		}

		/**
		 * The connector has no more rows to deliver for this read: the sink commits what it still holds
		 * (and reports it to its {@link HandledListener}) now, because the connector is about to release
		 * the source - a consumer it can still commit on. Optional: the engine finishes the sink itself
		 * once {@code readRows} returns, which is too late for a connector that needs the report.
		 */
		default void endOfRows()
		{
		}

		/**
		 * A line for the run about something the connector decided on its own (a message it could not
		 * read, rows it moved aside). Shown in the run's results and written to the sync log; the text
		 * must carry no data value.
		 */
		default void notice(final String pMessage)
		{
		}
	}

	/**
	 * What {@link RowSink#onHandled} reports: the rows numbered {@code [pFrom, pTo)} are settled, and
	 * the ones listed in {@code pNotWritten} are the settled rows that were NOT written (they failed).
	 * A row is settled once the engine is finished with it; a row never reported - after a stop, a
	 * failure of the engine, rows skipped to resume - is not settled and must be delivered again.
	 */
	interface HandledListener
	{
		void handled(int pFrom, int pTo, java.util.Set<Integer> pNotWritten);
	}

	/**
	 * Outcome of a call to {@link #readRows}: how many items were actually delivered to the sink,
	 * whether the source held more than that, and the source's real total when it is known ({@code -1}
	 * otherwise) - the same shape {@code ConnectorService.truncationOf} already reported from a fully
	 * materialised document, now produced directly by the read itself.
	 */
	final class RowStreamResult
	{
		public final int count;
		public final boolean truncated;
		public final long available;
		/**
		 * The connector's raw top-level document, when the read materialised one (the default
		 * implementation always does, since it calls {@link #execute}; a genuinely streaming override
		 * such as {@code JdbcConnector}'s never builds one and leaves this {@code null}). Used by
		 * {@code ConnectorService} to keep its catalog-scan bookkeeping (which needs the whole
		 * document, e.g. a top-level {@code errors} array) working for a connector on the default path,
		 * without requiring every {@link #readRows} implementation to produce one.
		 */
		public final JsonNode document;

		public RowStreamResult(final int pCount, final boolean pTruncated, final long pAvailable)
		{
			this(pCount, pTruncated, pAvailable, null);
		}

		public RowStreamResult(final int pCount, final boolean pTruncated, final long pAvailable, final JsonNode pDocument)
		{
			this.count = pCount;
			this.truncated = pTruncated;
			this.available = pAvailable;
			this.document = pDocument;
		}
	}

	/**
	 * Streams the items of a READ/SEARCH operation to {@code pSink} one at a time instead of building
	 * one {@link JsonNode} array holding every row in memory (volume lot 7a) - a 5-million-row feed no
	 * longer needs 5 million {@code JsonNode} objects alive at once for a connector that overrides this
	 * with a real cursor. {@code pItemsPath} narrows the connector's raw document exactly like
	 * {@code ConnectorService.importFrom}/{@code importDocument} already did before this lot (the
	 * document itself when blank); {@code pMax} bounds how many items are delivered - the sink is never
	 * called more than {@code pMax} times by the default implementation, but a native override may stop
	 * even earlier when {@code pSink.accept} returns {@code false}.
	 * <p>
	 * Default implementation: unchanged behaviour for a connector that does not override it - calls
	 * {@link #execute}, narrows the result with {@link #replay}, and delivers each element to
	 * {@code pSink}. A connector with no natural server-side cursor (Snowflake, a file, a REST list -
	 * volume lot 7b) can keep relying on this default, which still fully materialises the connector's
	 * own document (that part is unavoidable without changing the connector itself) but stops the
	 * caller from keeping a SECOND full copy of it around; {@code JdbcConnector} (volume lot 7a)
	 * overrides it with a real JDBC cursor instead of ever building the wrapping document.
	 */
	default RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final RowSink pSink) throws ConnectorException
	{
		final JsonNode document = this.execute(pContext, pOperation, pInputs, null);
		return Connector.replay(document, pItemsPath, pMax, pSink);
	}

	/**
	 * Shared default-replay logic behind {@link #readRows}'s default implementation, also usable by a
	 * caller that already has a document in hand (a webhook body, a file already read) and wants the
	 * exact same narrowing/streaming behaviour without a second connector call - see
	 * {@code ConnectorService.importDocument}. {@code pItemsPath} blank means "the document itself";
	 * an array at that path is replayed element by element (null elements skipped, as before this
	 * lot), any other non-null node is delivered once. {@code pMax} caps how many elements reach
	 * {@code pSink}; items beyond it are still counted towards {@code available} when the whole array
	 * is already in hand (cheap - it is already fully materialised), but never delivered. The
	 * document's own top-level {@code truncated}/{@code available} fields (set by a connector that
	 * already cut its own array to its {@code max}, e.g. a file feed's {@code read}) are folded in
	 * exactly like {@code ConnectorService.truncationOf} used to.
	 */
	static RowStreamResult replay(final JsonNode pDocument, final String pItemsPath, final int pMax, final RowSink pSink)
	{
		if (pDocument == null)
		{
			return new RowStreamResult(0, false, -1, null);
		}
		final JsonNode narrowed = pItemsPath == null || pItemsPath.trim().isEmpty() ? pDocument
			: com.on.ebx.connect.core.Json.at(pDocument, pItemsPath);
		int delivered = 0;
		int available = 0;
		if (narrowed != null && narrowed.isArray())
		{
			for (final JsonNode item : narrowed)
			{
				if (item == null || item.isNull())
				{
					continue;
				}
				available++;
				if (delivered < pMax)
				{
					delivered++;
					if (!pSink.accept(item))
					{
						break;
					}
				}
			}
		}
		else if (narrowed != null && !narrowed.isNull())
		{
			available = 1;
			if (pMax > 0)
			{
				delivered = 1;
				pSink.accept(narrowed);
			}
		}
		final boolean docTruncated = pDocument.path("truncated").asBoolean(false);
		final long docAvailable = pDocument.hasNonNull("available") ? pDocument.path("available").asLong(-1) : -1;
		final boolean truncated = available > delivered || docTruncated;
		final long finalAvailable = docAvailable >= 0 ? docAvailable : available > delivered ? available : -1;
		return new RowStreamResult(delivered, truncated, finalAvailable, pDocument);
	}

	/**
	 * Volume lot V2-D (feed watermark): true when the input {@code pInputName} of {@code pOperation} is a
	 * text (a SQL statement) in which the engine may write the named parameter {@code :watermark} instead
	 * of the checkpoint value itself. The engine then passes the checkpoint as two more inputs -
	 * {@code watermark} (the value) and {@code watermarkType} ({@code TIMESTAMP}, {@code NUMBER} or
	 * {@code STRING}) - and the connector binds {@code :watermark} as a typed prepared-statement parameter.
	 * Default false: {@code ${watermark}} is then written into the input as text, which is right for a REST
	 * query parameter and never acceptable for SQL.
	 */
	default boolean bindsWatermark(final String pOperation, final String pInputName)
	{
		return false;
	}

	/** Suggests mapping rules without persisting or executing a mapping. */
	default DraftMapping generateDraftMapping(final ConnectorContext pContext,
		final DraftMappingRequest pRequest) throws ConnectorException
	{
		return ConnectorInspectionAdapters.suggestMapping(describeObject(pContext,
			pRequest.getObjectId()), pRequest);
	}
}
