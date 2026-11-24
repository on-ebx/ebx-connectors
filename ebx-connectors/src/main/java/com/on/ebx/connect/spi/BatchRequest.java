package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A bounded group of ordinary connector operations. The hard ceilings protect connectors even
 * when a caller supplies overly generous limits; implementations may enforce smaller limits.
 */
public final class BatchRequest
{
	public static final int HARD_MAX_ITEMS = 100;
	public static final int HARD_MAX_WRITE_ITEMS = 50;
	public static final int HARD_MAX_BYTES = 2 * 1024 * 1024;

	private final List<Item> items;
	private final int maximumBytes;

	public BatchRequest(final List<Item> pItems, final int pMaximumBytes)
	{
		if (pItems == null || pItems.isEmpty()) throw new IllegalArgumentException("A batch must contain at least one item");
		if (pItems.size() > HARD_MAX_ITEMS) throw new IllegalArgumentException("A batch cannot exceed " + HARD_MAX_ITEMS + " items");
		if (pMaximumBytes < 1 || pMaximumBytes > HARD_MAX_BYTES) throw new IllegalArgumentException("Batch byte limit must be between 1 and " + HARD_MAX_BYTES);
		final List<Item> copy = new ArrayList<Item>(pItems.size());
		final java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
		for (final Item item : pItems)
		{
			if (item == null) throw new IllegalArgumentException("Batch items cannot be null");
			if (!ids.add(item.getId())) throw new IllegalArgumentException("Duplicate batch item id '" + item.getId() + "'");
			copy.add(item);
		}
		this.items = Collections.unmodifiableList(copy);
		this.maximumBytes = pMaximumBytes;
	}

	public List<Item> getItems() { return this.items; }
	public int getMaximumBytes() { return this.maximumBytes; }

	public static final class Item
	{
		private final String id, operation;
		private final Map<String, String> inputs;
		private final JsonNode payload;

		public Item(final String pId, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		{
			if (!safe(pId)) throw new IllegalArgumentException("Batch item id must contain only letters, numbers, '.', '_' or '-'");
			if (pOperation == null || pOperation.trim().isEmpty()) throw new IllegalArgumentException("Batch item operation is required");
			this.id = pId;
			this.operation = pOperation.trim();
			this.inputs = Collections.unmodifiableMap(pInputs == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(pInputs));
			this.payload = pPayload;
		}

		public String getId() { return this.id; }
		public String getOperation() { return this.operation; }
		public Map<String, String> getInputs() { return this.inputs; }
		public JsonNode getPayload() { return this.payload; }

		private static boolean safe(final String pValue)
		{
			return pValue != null && pValue.matches("[A-Za-z0-9._-]{1,80}");
		}
	}
}
