package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure rules of applying a Debezium change batch to an EBX table (Gartner lot B): which rows a feed
 * mapping upserts, which records are deleted or flagged, and how several events of one source key inside
 * one batch collapse to the last one. No EBX type: tested offline ({@code CdcApplyPlanTest}).
 * <p>
 * Event to action: {@code r} (snapshot), {@code c} and {@code u} upsert the {@code after} image;
 * {@code d} deletes, flags or ignores (option {@code deleteMode}) the record its {@code before} image
 * (merged over the key) addresses; a tombstone (Kafka null value after a delete) does nothing. Every
 * upsert row carries {@code __op} (c, u, r), {@code __position} (topic:partition:offset) and
 * {@code __deleted = false}; every delete row {@code __deleted = true}: a rule may map them.
 * <p>
 * <b>Idempotent:</b> an upsert by key, a delete of a missing record, a flag already set all leave the
 * table as they find it, so replaying a batch after a crash (the checkpoint is saved only after the
 * apply) converges to the same state.
 */
public final class CdcApplyPlan
{
	/** What a {@code d} event does to its record. */
	public enum DeleteMode
	{
		/** Delete the EBX record (default). */
		DELETE,
		/** Set {@code deleteFlagField} to {@code deleteFlagValue}; keep the record. */
		FLAG,
		/** Do nothing (an EBX that keeps everything the source ever had). */
		IGNORE
	}

	/** Mapping inputs that drive the plan, read from {@code MappingDefinition#getInputs}. */
	public static final class Options
	{
		private final DeleteMode deleteMode;
		private final String flagField;
		private final String flagValue;

		public Options(final DeleteMode pMode, final String pFlagField, final String pFlagValue)
		{
			this.deleteMode = pMode == null ? DeleteMode.DELETE : pMode;
			this.flagField = pFlagField == null || pFlagField.trim().isEmpty() ? "" : pFlagField.trim();
			this.flagValue = pFlagValue == null ? "true" : pFlagValue;
		}

		/** {@code deleteMode} (delete | flag | ignore), {@code deleteFlagField} (./deleted), {@code deleteFlagValue} (true). */
		public static Options from(final Map<String, String> pInputs)
		{
			final String mode = pInputs == null ? null : pInputs.get("deleteMode");
			DeleteMode parsed = DeleteMode.DELETE;
			if (mode != null && !mode.trim().isEmpty())
			{
				try
				{
					parsed = DeleteMode.valueOf(mode.trim().toUpperCase(Locale.ENGLISH));
				}
				catch (final IllegalArgumentException ex)
				{
					throw new IllegalArgumentException("deleteMode must be delete, flag or ignore, not '" + mode.trim() + "'");
				}
			}
			final Options options = new Options(parsed, pInputs == null ? null : pInputs.get("deleteFlagField"),
				pInputs == null ? null : pInputs.get("deleteFlagValue"));
			if (parsed == DeleteMode.FLAG && !options.flagField.startsWith("./"))
				throw new IllegalArgumentException("deleteMode flag needs input deleteFlagField, the field to set, e.g. ./deleted");
			return options;
		}

		public DeleteMode getDeleteMode()
		{
			return this.deleteMode;
		}

		public String getFlagField()
		{
			return this.flagField;
		}

		public String getFlagValue()
		{
			return this.flagValue;
		}
	}

	/** The actions of one batch, in source order of each key's last event. */
	public static final class Plan
	{
		private final List<ObjectNode> upserts = new ArrayList<ObjectNode>();
		private final List<ObjectNode> deletes = new ArrayList<ObjectNode>();
		private int events;
		private int collapsed;
		private int tombstones;
		private int ignoredDeletes;

		public List<ObjectNode> getUpserts()
		{
			return Collections.unmodifiableList(this.upserts);
		}

		/** Rows addressing a record to delete or flag (empty in IGNORE mode). */
		public List<ObjectNode> getDeletes()
		{
			return Collections.unmodifiableList(this.deletes);
		}

		public int getEvents()
		{
			return this.events;
		}

		/** Events superseded by a later event of the same key in the batch. */
		public int getCollapsed()
		{
			return this.collapsed;
		}

		public int getTombstones()
		{
			return this.tombstones;
		}

		public int getIgnoredDeletes()
		{
			return this.ignoredDeletes;
		}

		/** One line for the run message: {@code CDC: 12 events -> 9 upserts, 2 deletes (1 collapsed, 1 tombstone)}. */
		public String summary(final DeleteMode pMode)
		{
			final StringBuilder out = new StringBuilder("CDC: ").append(this.events).append(" event(s) -> ").append(this.upserts.size()).append(" upsert(s), ")
				.append(this.deletes.size()).append(pMode == DeleteMode.FLAG ? " flagged" : " delete(s)");
			final List<String> extra = new ArrayList<String>();
			if (this.collapsed > 0) extra.add(this.collapsed + " collapsed");
			if (this.tombstones > 0) extra.add(this.tombstones + " tombstone(s)");
			if (this.ignoredDeletes > 0) extra.add(this.ignoredDeletes + " delete(s) ignored");
			if (!extra.isEmpty()) out.append(" (").append(String.join(", ", extra)).append(')');
			return out.toString();
		}
	}

	private CdcApplyPlan()
	{
	}

	public static Plan plan(final CdcBatch pBatch, final Options pOptions)
	{
		final Options options = pOptions == null ? new Options(DeleteMode.DELETE, null, null) : pOptions;
		final Plan plan = new Plan();
		// last event per source key wins; re-inserting moves the key to the position of its last event
		final Map<String, ObjectNode> last = new LinkedHashMap<String, ObjectNode>();
		final Map<String, Boolean> isDelete = new LinkedHashMap<String, Boolean>();
		if (pBatch == null) return plan;
		for (final CdcEvent event : pBatch.getEvents())
		{
			plan.events++;
			if (event.getOperation() == CdcEvent.Operation.TOMBSTONE)
			{
				plan.tombstones++;
				continue;
			}
			final String key = event.getKey() == null || event.getKey().isNull() ? "position:" + event.getId() : Json.write(CdcApplyPlan.canonical(event.getKey()));
			final ObjectNode row;
			final boolean delete = event.getOperation() == CdcEvent.Operation.DELETE;
			if (delete)
			{
				row = CdcApplyPlan.merge(event.getKey(), event.getBefore());
				row.put("__op", "d");
				row.put("__deleted", true);
			}
			else
			{
				if (event.getAfter() == null || !event.getAfter().isObject()) continue;
				row = (ObjectNode) event.getAfter();
				row.put("__op", event.getOperation() == CdcEvent.Operation.SNAPSHOT ? "r" : event.getOperation() == CdcEvent.Operation.CREATE ? "c" : "u");
				row.put("__deleted", false);
			}
			row.put("__position", event.getId());
			if (last.remove(key) != null) plan.collapsed++;
			isDelete.remove(key);
			last.put(key, row);
			isDelete.put(key, Boolean.valueOf(delete));
		}
		for (final Map.Entry<String, ObjectNode> entry : last.entrySet())
		{
			if (!isDelete.get(entry.getKey()).booleanValue()) plan.upserts.add(entry.getValue());
			else if (options.getDeleteMode() == DeleteMode.IGNORE) plan.ignoredDeletes++;
			else plan.deletes.add(entry.getValue());
		}
		return plan;
	}

	/** The key fields, overlaid by the before image (which may be only the key columns, e.g. PostgreSQL's default replica identity). */
	private static ObjectNode merge(final JsonNode pKey, final JsonNode pBefore)
	{
		final ObjectNode row = Json.object();
		final JsonNode key = pKey != null && pKey.has("payload") && pKey.get("payload").isObject() ? pKey.get("payload") : pKey;
		if (key != null && key.isObject()) row.setAll((ObjectNode) key.deepCopy());
		if (pBefore != null && pBefore.isObject()) row.setAll((ObjectNode) pBefore.deepCopy());
		return row;
	}

	/** Field order does not make two keys different. */
	private static JsonNode canonical(final JsonNode pNode)
	{
		if (pNode == null || !pNode.isObject()) return pNode;
		final JsonNode source = pNode.has("payload") && pNode.has("schema") ? pNode.get("payload") : pNode;
		if (source == null || !source.isObject()) return source;
		final java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<String, JsonNode>();
		for (final Iterator<Map.Entry<String, JsonNode>> it = source.fields(); it.hasNext();)
		{
			final Map.Entry<String, JsonNode> e = it.next();
			sorted.put(e.getKey(), CdcApplyPlan.canonical(e.getValue()));
		}
		final ObjectNode out = Json.object();
		for (final Map.Entry<String, JsonNode> e : sorted.entrySet()) out.set(e.getKey(), e.getValue());
		return out;
	}
}
