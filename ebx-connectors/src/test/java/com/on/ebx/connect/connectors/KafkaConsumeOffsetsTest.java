package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;

/**
 * Which Kafka offsets a feed commits (audit C-06), measured on a {@code MockConsumer} and a sink that plays
 * the engine: it commits batches and reports them the way {@code ConnectorService.FeedSink} does. No broker,
 * no EBX. What a real broker does with the commit (a group that dropped the consumer, a rebalance) is not here.
 */
public final class KafkaConsumeOffsetsTest
{
	private static final TopicPartition P0 = new TopicPartition("orders", 0);
	private static final TopicPartition P1 = new TopicPartition("orders", 1);

	public static void main(final String[] pArgs) throws Exception
	{
		everythingWritten();
		nothingIsCommittedBeforeAReport();
		aFailedItemHoldsItsPartition();
		aFailedItemGoesToTheDeadLetterTopic();
		aRunCutAtMaxCommitsOnlyWhatWasDelivered();
		aStoppedRunCommitsTheBatchesDone();
		anInvalidMessageHoldsItsPartition();
		anInvalidMessageGoesToTheDeadLetterTopic();
		aDeadLetterThatFailsHoldsTheMessage();
		partitionsAreIndependent();
		aSinkThatCannotConfirmCommitsNothing();
		aSimulationDoesNotMoveMessages();
		aTombstoneIsHandledAndNeverBlocks();
		aJsonNullIsHandledWithoutDelivery();
		anItemsPathThatIsNotTheMessageListSettlesTheWholeRun();
		aRefusedCommitIsToldAndNotFatal();
		aCallerOfConsumeGetsWhatItReceivedCommitted();
		System.out.println("KafkaConsumeOffsetsTest OK");
	}

	// ------------------------------------------------------------------ cases

	private static void everythingWritten() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 5));
		final EngineSink sink = new EngineSink(2, consumer);
		final KafkaConsumption read = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null);
		final Connector.RowStreamResult result = read.deliver("messages[*].value", sink);
		check(result.count == 5, "5 rows delivered: " + result.count);
		check(committed(consumer, P0) == 5L, "all five committed after the last report: " + committed(consumer, P0));
		check(sink.commitsSeen.equals(Arrays.asList(Long.valueOf(2), Long.valueOf(4), Long.valueOf(5))),
			"offsets follow the batches written: " + sink.commitsSeen);
		check(sink.notices.isEmpty(), "nothing to tell: " + sink.notices);
	}

	private static void nothingIsCommittedBeforeAReport() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 3));
		final boolean[] early = new boolean[1];
		final EngineSink sink = new EngineSink(10, consumer)
		{
			@Override
			public boolean accept(final JsonNode pRow)
			{
				if (!consumer.committed(Collections.singleton(P0)).isEmpty() && consumer.committed(Collections.singleton(P0)).get(P0) != null)
				{
					early[0] = true;
				}
				return super.accept(pRow);
			}
		};
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null).deliver("messages[*].value", sink);
		check(!early[0], "the poll and the delivery commit nothing: the offset is written when EBX has the rows");
		check(committed(consumer, P0) == 3L, "and the tail batch is committed at the end of the rows");
	}

	private static void aFailedItemHoldsItsPartition() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 6));
		final EngineSink sink = new EngineSink(2, consumer);
		sink.failing.add(Integer.valueOf(2)); // the third item (offset 2) is refused by EBX
		final KafkaConsumption read = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null);
		read.deliver("messages[*].value", sink);
		check(committed(consumer, P0) == 2L, "the partition stops at the first message EBX did not write: " + committed(consumer, P0));
		check(read.ledger().hasUnhandled(), "the message is still owed");
		check(joined(sink.notices).contains("Partition 0 of orders stays at offset 2"), "the run says where it stopped: " + sink.notices);
	}

	private static void aFailedItemGoesToTheDeadLetterTopic() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 4));
		final EngineSink sink = new EngineSink(2, consumer);
		sink.failing.add(Integer.valueOf(1));
		final FakeDeadLetters dead = new FakeDeadLetters();
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), dead, "orders.dlq").deliver("messages[*].value", sink);
		check(committed(consumer, P0) == 4L, "a message moved aside no longer blocks: " + committed(consumer, P0));
		check(dead.sent.size() == 1 && dead.sent.get(0).endsWith("@1"), "only the refused message went to the dead-letter topic: " + dead.sent);
		check(joined(sink.notices).contains("moved to the dead-letter topic orders.dlq"), "and the run says so: " + sink.notices);
	}

	private static void aRunCutAtMaxCommitsOnlyWhatWasDelivered() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 10));
		final EngineSink sink = new EngineSink(2, consumer);
		final KafkaConsumption read = KafkaConsumption.poll(consumer, "orders", 4, 10, deadline(), null, null);
		final Connector.RowStreamResult result = read.deliver("messages[*].value", sink);
		check(result.count == 4, "max bounds what is delivered: " + result.count);
		check(committed(consumer, P0) == 4L, "the six messages past max were polled but are not committed: " + committed(consumer, P0));
		check(result.document.path("moreAvailable").asBoolean(false), "the document says more were left");
	}

	private static void aStoppedRunCommitsTheBatchesDone() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 8));
		final EngineSink sink = new EngineSink(2, consumer);
		sink.stopAfterBatches = 2; // the circuit breaker or a cancellation: accept answers false after the second batch
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", sink);
		check(result.count == 4, "delivery stopped with the sink: " + result.count);
		check(committed(consumer, P0) == 4L, "the two batches written are committed, nothing after: " + committed(consumer, P0));
	}

	private static void anInvalidMessageHoldsItsPartition() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(
			record(P0, 0, "k0", "{\"id\":0}"), record(P0, 1, "k1", "{not json"), record(P0, 2, "k2", "{\"id\":2}"));
		final EngineSink sink = new EngineSink(10, consumer);
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", sink);
		check(result.count == 2, "the invalid message is never delivered: " + result.count);
		check(committed(consumer, P0) == 1L, "only what lies before it is committed: " + committed(consumer, P0));
		check(joined(sink.notices).contains("orders-0@1") && joined(sink.notices).contains("not valid JSON")
			&& joined(sink.notices).contains("dead.letter.topic"), "it is reported, with the way out: " + sink.notices);
		check(!joined(sink.notices).contains("not json"), "the payload is never repeated in a notice");
		check(result.document.path("invalidCount").asInt() == 1, "and listed in the document");
	}

	private static void anInvalidMessageGoesToTheDeadLetterTopic() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(
			record(P0, 0, "k0", "{\"id\":0}"), record(P0, 1, "k1", "{not json"), record(P0, 2, "k2", "{\"id\":2}"));
		final EngineSink sink = new EngineSink(10, consumer);
		final FakeDeadLetters dead = new FakeDeadLetters();
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), dead, "orders.dlq").deliver("messages[*].value", sink);
		check(committed(consumer, P0) == 3L, "moved aside, it lets the partition go on: " + committed(consumer, P0));
		check(dead.sent.size() == 1 && dead.values.get(0).equals("{not json"), "the dead letter carries the original value: " + dead.values);
	}

	private static void aDeadLetterThatFailsHoldsTheMessage() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(record(P0, 0, "k0", "oops"), record(P0, 1, "k1", "{\"id\":1}"));
		final EngineSink sink = new EngineSink(10, consumer);
		final FakeDeadLetters dead = new FakeDeadLetters();
		dead.fail = true;
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), dead, "orders.dlq").deliver("messages[*].value", sink);
		check(committed(consumer, P0) == null, "a message nobody took stays: nothing committed on the partition");
		check(joined(sink.notices).contains("could not be moved to the dead-letter topic orders.dlq"), "and the run says it: " + sink.notices);
	}

	private static void partitionsAreIndependent() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 3), json(P1, 0, 3));
		final EngineSink sink = new EngineSink(10, consumer);
		sink.failing.add(Integer.valueOf(0)); // first delivered item: offset 0 of partition 0
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null).deliver("messages[*].value", sink);
		check(committed(consumer, P0) == null, "partition 0 is held at its first message");
		check(committed(consumer, P1) == 3L, "partition 1 goes on: " + committed(consumer, P1));
	}

	private static void aSinkThatCannotConfirmCommitsNothing() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(json(P0, 0, 3));
		final List<JsonNode> seen = new ArrayList<JsonNode>();
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", new Connector.RowSink()
			{
				@Override
				public boolean accept(final JsonNode pRow)
				{
					seen.add(pRow);
					return true;
				}
			});
		check(result.count == 3 && seen.size() == 3, "the rows are delivered");
		check(committed(consumer, P0) == null, "but a sink that cannot say what it wrote gets no commit");
	}

	private static void aSimulationDoesNotMoveMessages() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(record(P0, 0, "k0", "garbage"));
		final EngineSink sink = new EngineSink(10, consumer);
		sink.live = false;
		final FakeDeadLetters dead = new FakeDeadLetters();
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), dead, "orders.dlq").deliver("messages[*].value", sink);
		check(dead.sent.isEmpty(), "a simulation sends nothing to the dead-letter topic");
		check(committed(consumer, P0) == null, "and commits nothing");
		check(joined(sink.notices).contains("orders-0@0"), "though it still reports the message: " + sink.notices);
	}

	private static void aTombstoneIsHandledAndNeverBlocks() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(record(P0, 0, "k0", null), record(P0, 1, "k1", "{\"id\":1}"));
		final EngineSink sink = new EngineSink(10, consumer);
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", sink);
		check(result.count == 1 && committed(consumer, P0) == 2L, "the null value is skipped and committed over: " + committed(consumer, P0));
		check(result.document.path("tombstonesSkipped").asInt() == 1, "and counted");
	}

	private static void aJsonNullIsHandledWithoutDelivery() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(record(P0, 0, "k0", "null"), record(P0, 1, "k1", "{\"id\":1}"));
		final EngineSink sink = new EngineSink(10, consumer);
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", sink);
		check(result.count == 1, "the JSON null is not an item");
		check(committed(consumer, P0) == 2L, "the row that follows is numbered 0 by the sink and still maps to its own message: " + committed(consumer, P0));
	}

	private static void anItemsPathThatIsNotTheMessageListSettlesTheWholeRun() throws Exception
	{
		MockConsumer<String, String> consumer = consumer(json(P0, 0, 3));
		EngineSink sink = new EngineSink(2, consumer);
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null).deliver("", sink); // the document itself: one item
		check(committed(consumer, P0) == 3L, "one item standing for every message: committed once it is written: " + committed(consumer, P0));

		consumer = consumer(json(P0, 0, 3));
		sink = new EngineSink(2, consumer);
		sink.failing.add(Integer.valueOf(0));
		KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null).deliver("", sink);
		check(committed(consumer, P0) == null, "and not at all when it failed: nothing can be told apart");
	}

	private static void aRefusedCommitIsToldAndNotFatal() throws Exception
	{
		final MockConsumer<String, String> consumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST)
		{
			@Override
			public synchronized void commitSync(final Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> pOffsets)
			{
				throw new org.apache.kafka.clients.consumer.CommitFailedException();
			}
		};
		prepare(consumer, json(P0, 0, 2));
		final EngineSink sink = new EngineSink(10, consumer);
		final Connector.RowStreamResult result = KafkaConsumption.poll(consumer, "orders", 100, 10, deadline(), null, null)
			.deliver("messages[*].value", sink);
		check(result.count == 2, "the rows were delivered and written");
		check(joined(sink.notices).contains("refused the offset commit"), "the refusal is told: " + sink.notices);
	}

	private static void aCallerOfConsumeGetsWhatItReceivedCommitted() throws Exception
	{
		final MockConsumer<String, String> consumer = consumer(
			record(P0, 0, "k0", "{\"id\":0}"), record(P0, 1, "k1", "{\"id\":1}"), record(P0, 2, "k2", "{\"id\":2}"), record(P0, 3, "k3", "{\"id\":3}"));
		final KafkaConsumption read = KafkaConsumption.poll(consumer, "orders", 2, 10, deadline(), null, null);
		final com.fasterxml.jackson.databind.node.ObjectNode document = read.document();
		read.settleForCaller(document);
		check(document.path("count").asInt() == 2, "the caller receives max messages");
		check(committed(consumer, P0) == 2L, "and exactly those are committed, not the consumer's position: " + committed(consumer, P0));
	}

	// ------------------------------------------------------------------ the engine, played

	/** The sink {@code ConnectorService.FeedSink} stands for: commits every {@code batch} items, reports them, can stop. */
	private static class EngineSink implements Connector.RowSink
	{
		final int batch;
		final MockConsumer<String, String> consumer;
		final Set<Integer> failing = new HashSet<Integer>();
		final List<Long> commitsSeen = new ArrayList<Long>();
		final List<String> notices = new ArrayList<String>();
		boolean live = true;
		int stopAfterBatches = -1;
		private int index;
		private int pending;
		private int batches;
		private Connector.HandledListener listener;

		EngineSink(final int pBatch, final MockConsumer<String, String> pConsumer)
		{
			this.batch = pBatch;
			this.consumer = pConsumer;
		}

		@Override
		public boolean onHandled(final Connector.HandledListener pListener)
		{
			if (!this.live)
			{
				return false;
			}
			this.listener = pListener;
			return true;
		}

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.index++;
			this.pending++;
			if (this.pending >= this.batch)
			{
				return this.flush();
			}
			return true;
		}

		private boolean flush()
		{
			if (this.pending == 0)
			{
				return true;
			}
			final int from = this.index - this.pending;
			final Set<Integer> failed = new HashSet<Integer>();
			for (int i = from; i < this.index; i++)
			{
				if (this.failing.contains(Integer.valueOf(i)))
				{
					failed.add(Integer.valueOf(i));
				}
			}
			this.pending = 0;
			this.batches++;
			if (this.listener != null)
			{
				this.listener.handled(from, this.index, failed);
				final Long done = committed(this.consumer, P0);
				if (done != null)
				{
					this.commitsSeen.add(done);
				}
			}
			return this.stopAfterBatches < 0 || this.batches < this.stopAfterBatches;
		}

		@Override
		public void endOfRows()
		{
			this.flush();
		}

		@Override
		public void notice(final String pMessage)
		{
			this.notices.add(pMessage);
		}
	}

	private static final class FakeDeadLetters implements KafkaConsumeLedger.DeadLetters
	{
		final List<String> sent = new ArrayList<String>();
		final List<String> values = new ArrayList<String>();
		boolean fail;

		@Override
		public void send(final KafkaConsumeLedger.Entry pEntry, final String pReason) throws Exception
		{
			if (this.fail)
			{
				throw new java.util.concurrent.TimeoutException("broker down");
			}
			this.sent.add(pEntry.where());
			this.values.add(pEntry.rawValue);
		}
	}

	// ------------------------------------------------------------------ plumbing

	@SafeVarargs
	private static MockConsumer<String, String> consumer(final List<ConsumerRecord<String, String>>... pRecords)
	{
		final MockConsumer<String, String> consumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
		prepare(consumer, pRecords);
		return consumer;
	}

	@SafeVarargs
	private static void prepare(final MockConsumer<String, String> pConsumer, final List<ConsumerRecord<String, String>>... pRecords)
	{
		final Map<TopicPartition, Long> beginning = new HashMap<TopicPartition, Long>();
		beginning.put(P0, Long.valueOf(0));
		beginning.put(P1, Long.valueOf(0));
		pConsumer.assign(Arrays.asList(P0, P1));
		pConsumer.updateBeginningOffsets(beginning);
		for (final List<ConsumerRecord<String, String>> list : pRecords)
		{
			for (final ConsumerRecord<String, String> record : list)
			{
				pConsumer.addRecord(record);
			}
		}
	}

	private static List<ConsumerRecord<String, String>> json(final TopicPartition pPartition, final int pFrom, final int pCount)
	{
		final List<ConsumerRecord<String, String>> out = new ArrayList<ConsumerRecord<String, String>>();
		for (int i = pFrom; i < pFrom + pCount; i++)
		{
			out.add(record(pPartition, i, "k" + i, "{\"id\":" + i + "}").get(0));
		}
		return out;
	}

	private static List<ConsumerRecord<String, String>> record(final TopicPartition pPartition, final long pOffset, final String pKey, final String pValue)
	{
		return Collections.singletonList(new ConsumerRecord<String, String>(pPartition.topic(), pPartition.partition(), pOffset, pKey, pValue));
	}

	private static Long committed(final MockConsumer<String, String> pConsumer, final TopicPartition pPartition)
	{
		final org.apache.kafka.clients.consumer.OffsetAndMetadata offset = pConsumer.committed(Collections.singleton(pPartition)).get(pPartition);
		return offset == null ? null : Long.valueOf(offset.offset());
	}

	private static String joined(final List<String> pNotices)
	{
		final StringBuilder out = new StringBuilder();
		for (final String notice : pNotices)
		{
			out.append(notice).append('\n');
		}
		return out.toString();
	}

	private static long deadline()
	{
		return System.currentTimeMillis() + 5000;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
