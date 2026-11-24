package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable, transport-neutral change event. The source position is its idempotency key. */
public final class CdcEvent
{
	public enum Operation { SNAPSHOT, CREATE, UPDATE, DELETE, TOMBSTONE }
	public enum SnapshotBoundary { NONE, IN_PROGRESS, LAST }

	private final String id;
	private final String topic;
	private final int partition;
	private final long offset;
	private final Operation operation;
	private final SnapshotBoundary snapshotBoundary;
	private final JsonNode key;
	private final JsonNode before;
	private final JsonNode after;
	private final String schemaFingerprint;

	CdcEvent(final String pId, final String pTopic, final int pPartition, final long pOffset,
		final Operation pOperation, final SnapshotBoundary pSnapshotBoundary, final JsonNode pKey,
		final JsonNode pBefore, final JsonNode pAfter, final String pSchemaFingerprint)
	{
		this.id = pId;
		this.topic = pTopic;
		this.partition = pPartition;
		this.offset = pOffset;
		this.operation = pOperation;
		this.snapshotBoundary = pSnapshotBoundary;
		this.key = copy(pKey);
		this.before = copy(pBefore);
		this.after = copy(pAfter);
		this.schemaFingerprint = pSchemaFingerprint;
	}
	private static JsonNode copy(final JsonNode pNode) { return pNode == null ? null : pNode.deepCopy(); }

	public String getId() { return this.id; }
	public String getTopic() { return this.topic; }
	public int getPartition() { return this.partition; }
	public long getOffset() { return this.offset; }
	public Operation getOperation() { return this.operation; }
	public SnapshotBoundary getSnapshotBoundary() { return this.snapshotBoundary; }
	public JsonNode getKey() { return copy(this.key); }
	public JsonNode getBefore() { return copy(this.before); }
	public JsonNode getAfter() { return copy(this.after); }
	public String getSchemaFingerprint() { return this.schemaFingerprint; }
}
