package com.on.ebx.connect.connectors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;

/**
 * One read of a Kafka topic by a feed: the messages polled (up to {@code max}), kept with their offsets
 * in a {@link KafkaConsumeLedger}, delivered to the engine's row sink, and committed only for the ones the
 * engine reports as written.
 * <p>
 * The consumer stays open from the poll to {@link #deliver}: the engine reports each batch it commits while
 * the rows are being delivered, and each report commits the offsets it covers. Nothing is committed for a
 * message the engine did not settle, for a message beyond {@code max}, or when the sink cannot confirm
 * writes (a simulation).
 */
final class KafkaConsumption
{
	private final Consumer<String, String> consumer;
	private final String topic;
	private final KafkaConsumeLedger ledger;
	private final ArrayNode messages = Json.mapper().createArrayNode();
	private final ArrayNode invalidMessages = Json.mapper().createArrayNode();
	/** The ledger entry of each element of {@link #messages}, same order. */
	private final List<KafkaConsumeLedger.Entry> messageEntries = new ArrayList<KafkaConsumeLedger.Entry>();
	private final List<KafkaConsumeLedger.Entry> invalidEntries = new ArrayList<KafkaConsumeLedger.Entry>();
	private boolean consumed;
	private boolean moreLeft;
	private int tombstones;

	private KafkaConsumption(final Consumer<String, String> pConsumer, final String pTopic, final KafkaConsumeLedger pLedger)
	{
		this.consumer = pConsumer;
		this.topic = pTopic;
		this.ledger = pLedger;
	}

	/**
	 * Polls the subscribed consumer until {@code pMax} messages were taken, the deadline passed or the topic was
	 * idle twice. A message is never taken beyond {@code pMax}: what a poll returned past it is left for the
	 * next read, since nothing is committed for it.
	 */
	static KafkaConsumption poll(final Consumer<String, String> pConsumer, final String pTopic, final int pMax, final long pPollMs,
		final long pDeadlineMs, final KafkaConsumeLedger.DeadLetters pDeadLetters, final String pDeadLetterTopic)
	{
		final KafkaConsumption read = new KafkaConsumption(pConsumer, pTopic, new KafkaConsumeLedger(pConsumer, pDeadLetters, pDeadLetterTopic));
		int idle = 0;
		while (read.messages.size() + read.invalidMessages.size() < pMax && System.currentTimeMillis() < pDeadlineMs && idle < 2)
		{
			final ConsumerRecords<String, String> polled = pConsumer.poll(Duration.ofMillis(pPollMs));
			if (polled.isEmpty())
			{
				idle++;
				continue;
			}
			idle = 0;
			for (final ConsumerRecord<String, String> record : polled)
			{
				if (read.messages.size() + read.invalidMessages.size() >= pMax)
				{
					read.moreLeft = true;
					break;
				}
				read.take(record);
			}
		}
		return read;
	}

	private void take(final ConsumerRecord<String, String> pRecord)
	{
		this.consumed = true;
		final KafkaConsumeLedger.Entry entry = this.ledger.add(pRecord);
		if (pRecord.value() == null)
		{
			// A tombstone says "forget this key"; a feed has nothing to import from it, and it must not block a compacted topic.
			this.tombstones++;
			this.ledger.skip(entry);
			return;
		}
		final JsonNode value;
		try
		{
			value = Json.parse(pRecord.value());
		}
		catch (final Exception ex)
		{
			// Never expose a non-JSON Kafka value as a mapping payload: a mapped empty document could
			// otherwise create a blank EBX record.
			final ObjectNode invalid = this.invalidMessages.addObject();
			invalid.put("topic", pRecord.topic());
			invalid.put("partition", pRecord.partition());
			invalid.put("offset", pRecord.offset());
			invalid.put("reason", "Payload is not valid JSON");
			this.invalidEntries.add(entry);
			return;
		}
		final ObjectNode m = this.messages.addObject();
		m.put("topic", pRecord.topic());
		m.put("partition", pRecord.partition());
		m.put("offset", pRecord.offset());
		m.put("timestamp", pRecord.timestamp());
		m.put("key", pRecord.key());
		m.set("value", value);
		this.messageEntries.add(entry);
	}

	/** True when the poll returned at least one message of any kind. */
	boolean consumed()
	{
		return this.consumed;
	}

	/** The document {@code consume} has always returned: the topic, the valid messages, the ones that were not JSON. */
	ObjectNode document()
	{
		final ObjectNode result = Json.object();
		result.put("topic", this.topic);
		result.put("count", this.messages.size());
		result.set("messages", this.messages);
		if (this.invalidMessages.size() > 0)
		{
			result.put("invalidCount", this.invalidMessages.size());
			result.set("invalidMessages", this.invalidMessages);
		}
		if (this.tombstones > 0)
		{
			result.put("tombstonesSkipped", this.tombstones);
		}
		if (this.moreLeft)
		{
			result.put("moreAvailable", true);
		}
		return result;
	}

	KafkaConsumeLedger ledger()
	{
		return this.ledger;
	}

	/**
	 * An interactive {@code consume}: the caller receives the messages in the answer, so they are committed (up to
	 * the ones returned, not to the consumer's position) and the invalid ones are moved aside or held. Adds the
	 * notices to the document.
	 */
	void settleForCaller(final ObjectNode pDocument)
	{
		for (final KafkaConsumeLedger.Entry entry : this.invalidEntries)
		{
			this.ledger.invalid(entry, true);
		}
		this.ledger.handleAll();
		KafkaConsumption.addNotices(pDocument, this.ledger.closingNotices());
	}

	private static void addNotices(final ObjectNode pDocument, final List<String> pNotices)
	{
		if (pNotices.isEmpty())
		{
			return;
		}
		final ArrayNode list = pDocument.putArray("notices");
		for (final String notice : pNotices)
		{
			list.add(notice);
		}
	}

	/**
	 * Feeds the messages to the engine's sink and commits as it reports them written. Returns the rows delivered and
	 * the document. The consumer is not closed here.
	 *
	 * @param pItemsPath how the feed narrows the document to its items; blank = the document itself
	 */
	Connector.RowStreamResult deliver(final String pItemsPath, final Connector.RowSink pSink)
	{
		final ObjectNode document = this.document();
		// A sink that cannot say what it wrote (a simulation, a filter that cannot map rows) gets the rows and
		// nothing is committed or moved aside for it.
		final boolean live = pSink.onHandled(new Connector.HandledListener()
		{
			@Override
			public void handled(final int pFrom, final int pTo, final Set<Integer> pNotWritten)
			{
				KafkaConsumption.this.ledger.handled(pFrom, pTo, pNotWritten, true);
				KafkaConsumption.this.keepAlive();
			}
		});
		for (final KafkaConsumeLedger.Entry entry : this.invalidEntries)
		{
			this.ledger.invalid(entry, live);
		}
		final JsonNode narrowed = pItemsPath == null || pItemsPath.trim().isEmpty() ? document : Json.at(document, pItemsPath);
		final boolean oneToOne = narrowed != null && narrowed.isArray() && narrowed.size() == this.messageEntries.size();
		boolean allDelivered = true;
		final int[] wholeCount = new int[1];
		if (oneToOne)
		{
			for (int i = 0; i < this.messageEntries.size(); i++)
			{
				final JsonNode item = narrowed.get(i);
				final KafkaConsumeLedger.Entry entry = this.messageEntries.get(i);
				if (item == null || item.isNull())
				{
					this.ledger.skip(entry);
					continue;
				}
				this.ledger.delivered(entry);
				if (!pSink.accept(item))
				{
					allDelivered = false;
					break;
				}
			}
		}
		else
		{
			this.ledger.wholeRunOnly();
			final boolean[] stopped = new boolean[1];
			Connector.replay(document, pItemsPath, Integer.MAX_VALUE, new Connector.RowSink()
			{
				@Override
				public boolean accept(final JsonNode pRow)
				{
					wholeCount[0]++;
					if (!pSink.accept(pRow))
					{
						stopped[0] = true;
						return false;
					}
					return true;
				}
			});
			allDelivered = !stopped[0];
		}
		// The sink commits what it still holds now, while this consumer can still commit on it.
		pSink.endOfRows();
		final int delivered = oneToOne ? this.ledger.deliveredCount() : wholeCount[0];
		if (live)
		{
			this.ledger.finishWhole(allDelivered, delivered);
			this.ledger.commit();
		}
		for (final String notice : this.ledger.closingNotices())
		{
			pSink.notice(notice);
		}
		return new Connector.RowStreamResult(delivered, false, -1, document);
	}

	/**
	 * A long run keeps this consumer idle between two polls; the group would drop it after
	 * {@code max.poll.interval.ms}. A poll with every partition paused returns nothing and tells the group it
	 * is alive.
	 */
	private void keepAlive()
	{
		try
		{
			this.consumer.pause(this.consumer.assignment());
			this.consumer.poll(Duration.ZERO);
		}
		catch (final RuntimeException ex)
		{
			// the commit that follows says if the group did drop this consumer
		}
	}
}
