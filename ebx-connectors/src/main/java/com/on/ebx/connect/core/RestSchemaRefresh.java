package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Conditional GET state for safely refreshing remote OpenAPI or JSON-schema documents. */
public final class RestSchemaRefresh
{
	/** Persistence seam: an EBX adapter can store this value in a connector property or a technical table. */
	public interface StateStore
	{
		String load(String pKey) throws IOException;
		void save(String pKey, String pValue) throws IOException;
	}

	/** Injectable time source for deterministic scheduling and tests. */
	public interface Clock { long now(); }

	private String etag;
	private String lastModified;
	private JsonNode schema;
	private long checkedAt;
	private long nextRefreshAt;
	private final StateStore store;
	private final String stateKey;
	private final Clock clock;

	public RestSchemaRefresh()
	{
		this(null, null, new Clock()
		{
			public long now()
			{
				return System.currentTimeMillis();
			}
		});
	}

	public RestSchemaRefresh(final StateStore pStore, final String pStateKey) throws IOException
	{
		this.store = pStore;
		this.stateKey = pStateKey;
		this.clock = new Clock()
		{
			public long now()
			{
				return System.currentTimeMillis();
			}
		};
		if (pStore != null && pStateKey != null) this.restore(pStore.load(pStateKey));
	}

	RestSchemaRefresh(final StateStore pStore, final String pStateKey, final Clock pClock)
	{
		this.store = pStore;
		this.stateKey = pStateKey;
		this.clock = pClock;
		if (pStore != null && pStateKey != null)
		{
			try { this.restore(pStore.load(pStateKey)); }
			catch (final IOException ex) { throw new IllegalStateException("Cannot load schema refresh state '" + pStateKey + "'", ex); }
		}
	}

	public synchronized Result refresh(final HttpJsonClient pClient, final String pUrl,
		final Map<String, String> pHeaders) throws IOException
	{
		return this.refresh(pClient, pUrl, pHeaders, 0L);
	}

	/**
	 * Refreshes when due and persists validators, the last usable schema, and schedule timestamps.
	 * A non-positive interval preserves the original always-check behavior.
	 */
	public synchronized Result refresh(final HttpJsonClient pClient, final String pUrl,
		final Map<String, String> pHeaders, final long pMinimumIntervalMillis) throws IOException
	{
		final long now = this.clock.now();
		if (pMinimumIntervalMillis > 0L && this.schema != null && now < this.nextRefreshAt)
		{
			return new Result(this.schema, false, 0, false, this.checkedAt, this.nextRefreshAt);
		}
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		if (pHeaders != null) headers.putAll(pHeaders);
		if (this.etag != null) headers.put("If-None-Match", this.etag);
		if (this.lastModified != null) headers.put("If-Modified-Since", this.lastModified);
		final HttpJsonClient.Response response = pClient.send("GET", pUrl, headers, null);
		this.checkedAt = now;
		this.nextRefreshAt = safeAdd(now, Math.max(0L, pMinimumIntervalMillis));
		if (response.getStatus() == 304)
		{
			this.persist();
			return new Result(this.schema, false, response.getStatus(), true, this.checkedAt, this.nextRefreshAt);
		}
		if (!response.isSuccess())
		{
			this.persist();
			return new Result(this.schema, false, response.getStatus(), true, this.checkedAt, this.nextRefreshAt);
		}
		this.schema = response.getBody();
		this.etag = response.getHeader("ETag");
		this.lastModified = response.getHeader("Last-Modified");
		this.persist();
		return new Result(this.schema, true, response.getStatus(), true, this.checkedAt, this.nextRefreshAt);
	}

	/** Portable state payload; it contains no credentials and can be persisted by EBX transaction code. */
	public synchronized String exportState()
	{
		final com.fasterxml.jackson.databind.node.ObjectNode state = Json.object();
		if (this.etag != null) state.put("etag", this.etag);
		if (this.lastModified != null) state.put("lastModified", this.lastModified);
		if (this.schema != null) state.set("schema", this.schema);
		state.put("checkedAt", this.checkedAt);
		state.put("nextRefreshAt", this.nextRefreshAt);
		return Json.write(state);
	}

	private void restore(final String pState) throws IOException
	{
		if (pState == null || pState.trim().isEmpty()) return;
		final JsonNode state = Json.parse(pState);
		this.etag = text(state, "etag");
		this.lastModified = text(state, "lastModified");
		this.schema = state.get("schema");
		this.checkedAt = number(state, "checkedAt");
		this.nextRefreshAt = number(state, "nextRefreshAt");
	}

	private void persist() throws IOException
	{
		if (this.store != null && this.stateKey != null) this.store.save(this.stateKey, this.exportState());
	}

	private static String text(final JsonNode pNode, final String pName)
	{
		final JsonNode value = pNode.get(pName);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static long number(final JsonNode pNode, final String pName)
	{
		final JsonNode value = pNode.get(pName);
		return value == null || !value.isNumber() ? 0L : value.asLong();
	}

	private static long safeAdd(final long pLeft, final long pRight)
	{
		return pRight > Long.MAX_VALUE - pLeft ? Long.MAX_VALUE : pLeft + pRight;
	}

	public static final class Result
	{
		private final JsonNode schema;
		private final boolean changed;
		private final int status;
		private final boolean checked;
		private final long checkedAt;
		private final long nextRefreshAt;
		Result(final JsonNode pSchema, final boolean pChanged, final int pStatus, final boolean pChecked,
			final long pCheckedAt, final long pNextRefreshAt)
		{
			this.schema = pSchema;
			this.changed = pChanged;
			this.status = pStatus;
			this.checked = pChecked;
			this.checkedAt = pCheckedAt;
			this.nextRefreshAt = pNextRefreshAt;
		}
		public JsonNode getSchema() { return this.schema; }
		public boolean isChanged() { return this.changed; }
		public int getStatus() { return this.status; }
		public boolean wasChecked() { return this.checked; }
		public long getCheckedAt() { return this.checkedAt; }
		public long getNextRefreshAt() { return this.nextRefreshAt; }
	}
}
