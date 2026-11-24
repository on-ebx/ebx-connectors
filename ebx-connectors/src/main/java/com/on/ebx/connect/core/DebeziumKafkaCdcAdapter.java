package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Normalizes Kafka records containing Debezium JSON envelopes without committing Kafka offsets. */
public final class DebeziumKafkaCdcAdapter
{
	private static final int MAX_CHECKPOINT_BYTES = 1024 * 1024;
	private static final int MAX_CHECKPOINT_PARTITIONS = 10000;
	private static final int MAX_TOPIC_CHARS = 249;
	public static final class Record
	{
		public final String topic;
		public final int partition;
		public final long offset;
		public final JsonNode key;
		public final JsonNode value;
		public Record(final String pTopic, final int pPartition, final long pOffset, final JsonNode pKey, final JsonNode pValue)
		{
			topic = pTopic;
			partition = pPartition;
			offset = pOffset;
			key = copy(pKey);
			value = copy(pValue);
		}
	}

	private DebeziumKafkaCdcAdapter() { }

	/** Validated durable offsets keyed by {@code topic:partition}; callers cannot mutate the result. */
	public static Map<String, Long> checkpointPositions(final String pCheckpoint)
	{
		return Collections.unmodifiableMap(new LinkedHashMap<String, Long>(parseCheckpoint(pCheckpoint)));
	}

	public static CdcBatch normalize(final List<Record> pRecords, final String pCheckpoint,
		final int pMaxEvents, final int pMaxBytes)
	{
		if (pMaxEvents < 1 || pMaxEvents > 10000) throw new IllegalArgumentException("maxEvents must be 1..10000");
		if (pMaxBytes < 1 || pMaxBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("maxBytes must be 1..16777216");
		final Map<String, Long> positions = parseCheckpoint(pCheckpoint);
		final Map<String, Long> durablePositions = new LinkedHashMap<String, Long>(positions);
		final List<CdcEvent> events = new ArrayList<CdcEvent>();
		final Set<String> seen = new HashSet<String>();
		long bytes = 0L;
		if (pRecords != null) for (final Record record : pRecords)
		{
			validate(record);
			final String partitionKey = record.topic + ":" + record.partition;
			final Long durable = durablePositions.get(partitionKey);
			if (durable != null && record.offset <= durable.longValue()) continue; // restart replay
			final String id = partitionKey + ":" + record.offset;
			if (!seen.add(id)) throw new IllegalArgumentException("Duplicate source position in CDC batch: " + id);
			final Long previous = positions.get(partitionKey);
			if (previous != null && record.offset <= previous.longValue()) throw new IllegalArgumentException("Out-of-order source position in CDC batch: " + id);
			final long recordBytes = (long) serializedBytes(record.key) + (long) serializedBytes(record.value);
			if (events.size() >= pMaxEvents || bytes + recordBytes > pMaxBytes) break;
			bytes += recordBytes;
			events.add(event(record, id));
			positions.put(partitionKey, Long.valueOf(record.offset));
		}
		final ObjectNode checkpoint = Json.object();
		for (final Map.Entry<String, Long> position : positions.entrySet()) checkpoint.put(position.getKey(), position.getValue().longValue());
		return new CdcBatch(events, Json.write(checkpoint));
	}

	private static CdcEvent event(final Record pRecord, final String pId)
	{
		if (pRecord.value == null || pRecord.value.isNull())
			return new CdcEvent(pId, pRecord.topic, pRecord.partition, pRecord.offset, CdcEvent.Operation.TOMBSTONE,
				CdcEvent.SnapshotBoundary.NONE, pRecord.key, null, null, "");
		final JsonNode payload = pRecord.value.has("payload") ? pRecord.value.get("payload") : pRecord.value;
		if (payload == null || payload.isNull())
			return new CdcEvent(pId, pRecord.topic, pRecord.partition, pRecord.offset, CdcEvent.Operation.TOMBSTONE,
				CdcEvent.SnapshotBoundary.NONE, pRecord.key, null, null, fingerprint(pRecord.value.get("schema")));
		final String op = text(payload.get("op"));
		final CdcEvent.Operation operation;
		if ("r".equals(op)) operation = CdcEvent.Operation.SNAPSHOT;
		else if ("c".equals(op)) operation = CdcEvent.Operation.CREATE;
		else if ("u".equals(op)) operation = CdcEvent.Operation.UPDATE;
		else if ("d".equals(op)) operation = CdcEvent.Operation.DELETE;
		else throw new IllegalArgumentException("Unsupported Debezium operation");
		final JsonNode source = payload.get("source");
		final String snapshot = source == null ? "" : text(source.get("snapshot"));
		final CdcEvent.SnapshotBoundary boundary = "last".equalsIgnoreCase(snapshot) ? CdcEvent.SnapshotBoundary.LAST
			: ("true".equalsIgnoreCase(snapshot) ? CdcEvent.SnapshotBoundary.IN_PROGRESS : CdcEvent.SnapshotBoundary.NONE);
		return new CdcEvent(pId, pRecord.topic, pRecord.partition, pRecord.offset, operation, boundary,
			pRecord.key, nullable(payload.get("before")), nullable(payload.get("after")), fingerprint(pRecord.value.get("schema")));
	}

	private static Map<String, Long> parseCheckpoint(final String pCheckpoint)
	{
		final Map<String, Long> result = new LinkedHashMap<String, Long>();
		if (pCheckpoint == null || pCheckpoint.trim().isEmpty()) return result;
		if (pCheckpoint.getBytes(StandardCharsets.UTF_8).length > MAX_CHECKPOINT_BYTES)
			throw new IllegalArgumentException("CDC checkpoint exceeds 1048576 bytes");
		try
		{
			final JsonNode node = Json.parse(pCheckpoint);
			if (node == null || !node.isObject()) throw new IllegalArgumentException("CDC checkpoint must be a JSON object");
			final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> f = fields.next();
				if (result.size() >= MAX_CHECKPOINT_PARTITIONS) throw new IllegalArgumentException("CDC checkpoint has too many partitions");
				validatePartitionKey(f.getKey());
				if (!f.getValue().isIntegralNumber() || !f.getValue().canConvertToLong() || f.getValue().asLong() < 0)
					throw new IllegalArgumentException("Invalid CDC checkpoint offset");
				result.put(f.getKey(), Long.valueOf(f.getValue().asLong()));
			}
			return result;
		}
		catch (final IllegalArgumentException ex) { throw ex; }
		catch (final Exception ex) { throw new IllegalArgumentException("Invalid CDC checkpoint", ex); }
	}

	private static void validate(final Record pRecord)
	{
		if (pRecord == null || pRecord.topic == null || pRecord.topic.trim().isEmpty()) throw new IllegalArgumentException("CDC topic is required");
		if (!pRecord.topic.equals(pRecord.topic.trim()) || pRecord.topic.length() > MAX_TOPIC_CHARS || pRecord.topic.indexOf('\u0000') >= 0)
			throw new IllegalArgumentException("Invalid CDC topic identity");
		if (pRecord.partition < 0 || pRecord.offset < 0) throw new IllegalArgumentException("CDC source position must be non-negative");
	}
	private static void validatePartitionKey(final String pKey)
	{
		if (pKey == null) throw new IllegalArgumentException("Invalid CDC checkpoint partition");
		final int separator = pKey.lastIndexOf(':');
		if (separator < 1 || separator == pKey.length() - 1) throw new IllegalArgumentException("Invalid CDC checkpoint partition");
		final String topic = pKey.substring(0, separator);
		try { validate(new Record(topic, Integer.parseInt(pKey.substring(separator + 1)), 0L, null, null)); }
		catch (final RuntimeException ex) { throw new IllegalArgumentException("Invalid CDC checkpoint partition", ex); }
	}
	private static int serializedBytes(final JsonNode pNode)
	{ return Json.write(pNode).getBytes(StandardCharsets.UTF_8).length; }
	private static JsonNode copy(final JsonNode pNode) { return pNode == null ? null : pNode.deepCopy(); }
	private static String text(final JsonNode pNode) { return pNode == null || pNode.isNull() ? "" : pNode.asText(); }
	private static JsonNode nullable(final JsonNode pNode) { return pNode == null || pNode.isNull() ? null : pNode; }
	private static String fingerprint(final JsonNode pSchema)
	{
		if (pSchema == null || pSchema.isNull()) return "";
		try
		{
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(pSchema)).getBytes(StandardCharsets.UTF_8));
			final StringBuilder out = new StringBuilder();
			for (final byte b : hash) out.append(String.format("%02x", Integer.valueOf(b & 255)));
			return out.toString();
		}
		catch (final Exception ex) { throw new IllegalStateException("Cannot fingerprint CDC schema", ex); }
	}
	private static JsonNode canonical(final JsonNode pNode)
	{
		if (pNode == null || pNode.isNull() || pNode.isValueNode()) return copy(pNode);
		if (pNode.isArray())
		{
			final com.fasterxml.jackson.databind.node.ArrayNode out = Json.mapper().createArrayNode();
			for (final JsonNode child : pNode) out.add(canonical(child));
			return out;
		}
		final ObjectNode out = Json.object();
		final Map<String, JsonNode> fields = new TreeMap<String, JsonNode>();
		final Iterator<Map.Entry<String, JsonNode>> iterator = pNode.fields();
		while (iterator.hasNext())
		{
			final Map.Entry<String, JsonNode> field = iterator.next();
			fields.put(field.getKey(), field.getValue());
		}
		for (final Map.Entry<String, JsonNode> field : fields.entrySet()) out.set(field.getKey(), canonical(field.getValue()));
		return out;
	}
}
