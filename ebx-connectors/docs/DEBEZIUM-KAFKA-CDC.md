# Debezium / Kafka CDC foundation

This adapter converts Debezium JSON envelopes into a transport-neutral change batch. The isolated Kafka CDC execution path uses a no-commit consumer and advances the EBX durable checkpoint only after the downstream apply returns successfully.

## Delivery contract

- A source position binds the configured source identity, topic, partition and offset; it is also the stable idempotency key.
- Snapshot records (`op=r`) retain `source.snapshot=true` or `last`, including the snapshot/live boundary.
- Create, update and delete retain `before` and `after`. A Kafka null value or a Debezium null payload is a tombstone.
- The canonical schema fingerprint changes when the supplied Debezium schema changes but remains stable when JSON object fields are merely reordered; consumers must make an explicit compatibility decision.
- Reads are bounded by both event count and serialized bytes. A candidate checkpoint includes only records returned in the batch.
- A restart suppresses positions at or below the durable per-partition offset.

## Critical checkpoint rule

Normalization never advances durable progress. `KafkaConnector.executeCdc` polls without broker commits, applies the bounded batch, and calls `CdcCheckpointCoordinator.confirmApplied` only after the complete downstream EBX transaction succeeds. Failed and dry-run applications preserve the old checkpoint, and optimistic versioning prevents a concurrent worker from silently overwriting progress.

## Applying the batch to EBX

A feed mapping on the Kafka operation `cdc` applies each batch to its table (upserts through the mapping, deletes as delete, flag or ignore) and saves the checkpoint only afterwards: see [CDC.md](CDC.md).

## Current boundary

The CDC path expects decoded Kafka key/value JSON and deliberately leaves the generic Kafka `consume` operation unchanged. Delivery is at-least-once: if EBX apply succeeds but checkpoint persistence fails, the batch can replay, so apply logic must remain idempotent by event ID. Avro/Protobuf converters, schema-registry compatibility enforcement, transaction metadata, and live Debezium acceptance remain separate work.

[Return to the connector user guide](USER-GUIDE.html)
