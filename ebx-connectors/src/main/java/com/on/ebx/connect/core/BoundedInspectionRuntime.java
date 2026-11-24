package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.ObjectDescription;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;

/** Read-only, bounded execution boundary for connector inspection SPI calls. */
public final class BoundedInspectionRuntime
{
	private final MetadataCache<Object> cache;
	/**
	 * The threads the bounded calls run on, shared by every instance: created on first use and stopped
	 * with the module ({@link BackgroundWork}), where a pool owned by each instance could not be.
	 */
	private static final BackgroundWork.Managed<ExecutorService> EXECUTOR = BackgroundWork.managed("connector inspection",
		new BackgroundWork.Factory<ExecutorService>()
		{
			@Override public ExecutorService create()
			{
				return Executors.newCachedThreadPool(BackgroundWork.daemonThreads("ebx-connector-inspection", false));
			}
		});
	private final PreviewRequest limits;

	public BoundedInspectionRuntime() { this(PreviewRequest.defaults(), new MetadataCache<Object>()); }

	public BoundedInspectionRuntime(final PreviewRequest pLimits, final MetadataCache<Object> pCache)
	{
		if (pLimits == null || pCache == null) throw new IllegalArgumentException("limits and cache are required");
		this.limits = pLimits;
		this.cache = pCache;
	}

	public ObjectDiscoveryResult discover(final Connector pConnector, final ConnectorContext pContext,
		final ConnectorConfig pConfig, final ObjectDiscoveryRequest pRequest) throws ConnectorException
	{
		require(pConnector, pConfig);
		final ObjectDiscoveryRequest request = pRequest == null
			? ObjectDiscoveryRequest.firstPage("", this.limits.getRecordLimit())
			: new ObjectDiscoveryRequest(pRequest.getQuery(), Math.min(pRequest.getLimit(), this.limits.getRecordLimit()), pRequest.getContinuationToken());
		final MetadataCacheKey key = key(pConnector, "discover", pConfig, request.getQuery() + "\n" + value(request.getContinuationToken()));
		final Object cached = this.cache.get(key);
		if (cached instanceof ObjectDiscoveryResult) return (ObjectDiscoveryResult) cached;
		final SensitiveDataMasker masker = masker(pConfig);
		final ObjectDiscoveryResult raw = timed(new Callable<ObjectDiscoveryResult>()
		{
			@Override public ObjectDiscoveryResult call() throws Exception { return pConnector.discoverObjects(pContext, request); }
		}, masker);
		final ObjectDiscoveryResult safe = safeDiscovery(raw, request.getLimit(), masker);
		this.cache.put(key, safe);
		return safe;
	}

	public ObjectDescription describe(final Connector pConnector, final ConnectorContext pContext,
		final ConnectorConfig pConfig, final String pObjectId) throws ConnectorException
	{
		require(pConnector, pConfig);
		final String objectId = value(pObjectId).trim();
		final MetadataCacheKey key = key(pConnector, "describe", pConfig, objectId);
		final Object cached = this.cache.get(key);
		if (cached instanceof ObjectDescription) return (ObjectDescription) cached;
		final SensitiveDataMasker masker = masker(pConfig);
		final ObjectDescription raw = timed(new Callable<ObjectDescription>()
		{
			@Override public ObjectDescription call() throws Exception { return pConnector.describeObject(pContext, objectId); }
		}, masker);
		final ObjectDescription safe = safeDescription(raw, masker);
		this.cache.put(key, safe);
		return safe;
	}

	public InspectionPreview preview(final Connector pConnector, final ConnectorContext pContext,
		final ConnectorConfig pConfig, final com.on.ebx.connect.spi.PreviewRequest pRequest) throws ConnectorException
	{
		require(pConnector, pConfig);
		if (pRequest == null) throw new IllegalArgumentException("preview request is required");
		final int limit = Math.min(pRequest.getLimit(), this.limits.getRecordLimit());
		final com.on.ebx.connect.spi.PreviewRequest request = new com.on.ebx.connect.spi.PreviewRequest(
			pRequest.getObjectId(), pRequest.getOperation(), pRequest.getInputs(), limit);
		final SensitiveDataMasker masker = masker(pConfig);
		final com.on.ebx.connect.spi.PreviewResult raw = timed(new Callable<com.on.ebx.connect.spi.PreviewResult>()
		{
			@Override public com.on.ebx.connect.spi.PreviewResult call() throws Exception { return pConnector.preview(pContext, request); }
		}, masker);
		return safePreview(raw, limit, masker);
	}

	private <T> T timed(final Callable<T> pCall, final SensitiveDataMasker pMasker) throws ConnectorException
	{
		final Future<T> future;
		try
		{
			future = BoundedInspectionRuntime.EXECUTOR.get().submit(pCall);
		}
		catch (final java.util.concurrent.RejectedExecutionException stopping)
		{
			throw new ConnectorException("The connectors module is stopping: inspection not started");
		}
		try { return future.get(this.limits.getTimeoutMillis(), TimeUnit.MILLISECONDS); }
		catch (final TimeoutException ex)
		{
			future.cancel(true);
			throw new ConnectorException("Inspection exceeded the " + this.limits.getTimeoutMillis() + " ms time limit", ex);
		}
		catch (final InterruptedException ex)
		{
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new ConnectorException("Inspection was interrupted", ex);
		}
		catch (final ExecutionException ex)
		{
			final Throwable cause = ex.getCause();
			if (cause instanceof ConnectorException)
			{
				final ConnectorException connectorError = (ConnectorException) cause;
				throw new ConnectorException(pMasker.mask(safeMessage(connectorError)), connectorError.getStatus(), connectorError);
			}
			throw new ConnectorException(cause == null ? "Inspection failed" : pMasker.mask(safeMessage(cause)), cause);
		}
	}

	private ObjectDiscoveryResult safeDiscovery(final ObjectDiscoveryResult pRaw, final int pLimit, final SensitiveDataMasker pMasker)
	{
		if (pRaw == null) return ObjectDiscoveryResult.unsupported("Connector returned no discovery result");
		if (!pRaw.isSupported()) return ObjectDiscoveryResult.unsupported(pMasker.mask(pRaw.getMessage()));
		final List<DiscoveredObject> safe = new ArrayList<DiscoveredObject>();
		int bytes = 0;
		for (final DiscoveredObject object : pRaw.getObjects())
		{
			if (object == null || safe.size() >= pLimit) break;
			final DiscoveredObject masked = new DiscoveredObject(pMasker.mask(object.getId()), pMasker.mask(object.getLabel()), pMasker.mask(object.getType()), object.isReadable());
			final int size = bytes(masked.getId()) + bytes(masked.getLabel()) + bytes(masked.getType());
			if (size > this.limits.getPayloadByteLimit() - bytes) break;
			safe.add(masked);
			bytes += size;
		}
		return ObjectDiscoveryResult.page(safe, pMasker.mask(pRaw.getContinuationToken()),
			pRaw.isTruncated() || safe.size() != pRaw.getObjects().size());
	}

	private ObjectDescription safeDescription(final ObjectDescription pRaw, final SensitiveDataMasker pMasker)
	{
		if (pRaw == null) return ObjectDescription.unsupported("", "Connector returned no object description");
		if (!pRaw.isSupported()) return ObjectDescription.unsupported(pMasker.mask(pRaw.getObjectId()), pMasker.mask(pRaw.getMessage()));
		final List<FieldInfo> safe = new ArrayList<FieldInfo>();
		int bytes = 0;
		for (final FieldInfo field : pRaw.getFields())
		{
			if (field == null || safe.size() >= this.limits.getRecordLimit()) break;
			final FieldInfo masked = new FieldInfo(pMasker.mask(field.getPath()), pMasker.mask(field.getLabel()), pMasker.mask(field.getType()), pMasker.mask(field.getSample()));
			final int size = bytes(masked.getPath()) + bytes(masked.getLabel()) + bytes(masked.getType()) + bytes(masked.getSample());
			if (size > this.limits.getPayloadByteLimit() - bytes) break;
			safe.add(masked);
			bytes += size;
		}
		return ObjectDescription.of(pMasker.mask(pRaw.getObjectId()), pMasker.mask(pRaw.getLabel()), safe);
	}

	private InspectionPreview safePreview(final com.on.ebx.connect.spi.PreviewResult pRaw, final int pLimit,
		final SensitiveDataMasker pMasker) throws ConnectorException
	{
		if (pRaw == null) return InspectionPreview.unsupported("Connector returned no preview result");
		if (!pRaw.isSupported()) return InspectionPreview.unsupported(pMasker.mask(pRaw.getMessage()));
		final List<JsonNode> safe = new ArrayList<JsonNode>();
		int bytes = 0;
		boolean truncated = pRaw.isTruncated();
		for (final JsonNode record : pRaw.getRecords())
		{
			if (safe.size() >= pLimit)
			{
				truncated = true;
				break;
			}
			try
			{
				final JsonNode masked = Json.parse(pMasker.mask(Json.write(record)));
				final int size = bytes(Json.write(masked));
				if (size > this.limits.getPayloadByteLimit() - bytes)
				{
					truncated = true;
					break;
				}
				safe.add(masked);
				bytes += size;
			}
			catch (final java.io.IOException ex) { throw new ConnectorException("Could not sanitize preview data", ex); }
		}
		return InspectionPreview.of(safe, truncated, bytes);
	}

	private static SensitiveDataMasker masker(final ConnectorConfig pConfig)
	{
		final Map<String, String> values = new LinkedHashMap<String, String>(pConfig.getProperties());
		values.put("password", pConfig.getPassword());
		values.put("apiKey", pConfig.getApiKey());
		return SensitiveDataMasker.fromConfiguration(values);
	}

	private static MetadataCacheKey key(final Connector pConnector, final String pOperation, final ConnectorConfig pConfig, final String pScope)
	{
		return MetadataCacheKey.of(pConnector.getType(), pOperation, value(pConfig.getId()) + "\n" + value(pConfig.getBaseUrl()) + "\n" + value(pScope));
	}

	private static void require(final Connector pConnector, final ConnectorConfig pConfig)
	{
		if (pConnector == null || pConfig == null) throw new IllegalArgumentException("connector and configuration are required");
	}
	private static int bytes(final String pValue) { return pValue == null ? 0 : pValue.getBytes(StandardCharsets.UTF_8).length; }
	private static String value(final String pValue) { return pValue == null ? "" : pValue; }
	private static String safeMessage(final Throwable pError)
	{
		return pError.getMessage() == null || pError.getMessage().trim().isEmpty() ? pError.getClass().getSimpleName() : Redaction.redact(pError.getMessage());
	}
}
