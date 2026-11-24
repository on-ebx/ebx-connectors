package com.on.ebx.connect.connectors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.DebeziumKafkaCdcAdapter;
import com.on.ebx.connect.core.Json;

/** One bounded Kafka poll that deliberately has no broker-offset commit path. */
final class KafkaCdcSource implements KafkaCdcExecution.Source
{
	private final Properties properties;
	private final String topic;
	private final int maxEvents;
	private final long pollMs;

	KafkaCdcSource(final Properties pProperties, final String pTopic, final int pMaxEvents, final long pPollMs)
	{
		if (pProperties == null || pTopic == null || pTopic.trim().isEmpty())
			throw new IllegalArgumentException("Kafka properties and CDC topic are required");
		if (pMaxEvents < 1 || pMaxEvents > 10000 || pPollMs < 1L)
			throw new IllegalArgumentException("Kafka CDC poll bounds are invalid");
		this.properties = new Properties();
		this.properties.putAll(pProperties);
		this.properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		this.properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		this.properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		this.properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		this.properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(pMaxEvents));
		this.topic = pTopic;
		this.maxEvents = pMaxEvents;
		this.pollMs = pPollMs;
	}

	@Override
	public List<DebeziumKafkaCdcAdapter.Record> poll(final String pCheckpoint)
	{
		final List<DebeziumKafkaCdcAdapter.Record> out = new ArrayList<DebeziumKafkaCdcAdapter.Record>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(this.properties))
		{
			final List<PartitionInfo> metadata = consumer.partitionsFor(this.topic, Duration.ofMillis(this.pollMs));
			final List<TopicPartition> partitions = new ArrayList<TopicPartition>();
			if (metadata != null) for (final PartitionInfo info : metadata) partitions.add(new TopicPartition(info.topic(), info.partition()));
			if (partitions.isEmpty()) return out;
			consumer.assign(partitions);
			final Map<String, Long> positions = DebeziumKafkaCdcAdapter.checkpointPositions(pCheckpoint);
			final Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions, Duration.ofMillis(this.pollMs));
			for (final TopicPartition partition : partitions)
			{
				final Long durable = positions.get(partition.topic() + ":" + partition.partition());
				final long beginning = beginnings.get(partition).longValue();
				final long resume = durable == null ? beginning : (durable.longValue() == Long.MAX_VALUE
					? Long.MAX_VALUE : durable.longValue() + 1L);
				consumer.seek(partition, Math.max(beginning, resume));
			}
			final ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(this.pollMs));
			for (final ConsumerRecord<String, String> record : records)
			{
				if (out.size() >= this.maxEvents) break;
				final JsonNode key = parseNullable(record.key(), "key", record);
				final JsonNode value = parseNullable(record.value(), "value", record);
				out.add(new DebeziumKafkaCdcAdapter.Record(record.topic(), record.partition(), record.offset(), key, value));
			}
		}
		return out;
	}

	private static JsonNode parseNullable(final String pJson, final String pPart, final ConsumerRecord<String, String> pRecord)
	{
		if (pJson == null) return null;
		try { return Json.parse(pJson); }
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("Invalid Debezium " + pPart + " JSON at " + pRecord.topic() + ":"
				+ pRecord.partition() + ":" + pRecord.offset(), ex);
		}
	}
}
