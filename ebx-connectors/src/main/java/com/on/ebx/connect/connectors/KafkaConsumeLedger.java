package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Which polled Kafka messages EBX holds, and which offsets may therefore be committed.
 * <p>
 * A consumer group's committed offset of a partition means "everything below was handled". Committing
 * the consumer's position after a poll (what {@code commitSync()} does) states that before EBX has
 * written anything, so a failed batch, a stopped run or a crash lost the messages. This ledger keeps
 * the polled messages of one read in the order they came, and commits <em>explicit</em> offsets: per
 * partition, the offset after the last message of the unbroken run of handled messages that starts at
 * the first polled one. A message that was not written stops the run of its partition: it, and
 * everything after it on that partition, is delivered again by the next read, which is safe for an
 * upsert feed and is what keeps a bad message from being forgotten. Messages polled beyond what was
 * delivered ({@code max}) are never committed.
 * <p>
 * Handled means: written by EBX, or deliberately set aside - a tombstone (null value), a JSON
 * {@code null}, a message moved to the dead-letter topic. A message that is not valid JSON, or that EBX
 * refused to write, is moved to the dead-letter topic when one is configured and held back otherwise.
 * <p>
 * Single-threaded, like the consumer it commits on. Nothing here touches EBX; {@code KafkaConsumeLedgerTest}
 * drives it with a {@code MockConsumer}.
 */
final class KafkaConsumeLedger
{
	/** Where a message goes when it cannot be written; the connector backs it with a producer. */
	interface DeadLetters
	{
		/** Sends the message to the dead-letter topic; throws when the broker did not take it. {@code pReason} carries no data value. */
		void send(Entry pEntry, String pReason) throws Exception;
	}

	/** One polled message. */
	static final class Entry
	{
		final TopicPartition partition;
		final long offset;
		final String key;
		/** The value as polled, kept for the dead-letter topic; null for a tombstone. */
		final String rawValue;
		boolean handled;
		/** Not written and not moved aside: stays where it is until a read writes it. */
		boolean held;

		Entry(final ConsumerRecord<String, String> pRecord)
		{
			this.partition = new TopicPartition(pRecord.topic(), pRecord.partition());
			this.offset = pRecord.offset();
			this.key = pRecord.key();
			this.rawValue = pRecord.value();
		}

		/** {@code topic-partition@offset}: where the message is, with no data value. */
		String where()
		{
			return this.partition.topic() + "-" + this.partition.partition() + "@" + this.offset;
		}
	}

	/** Longest list of individual notices; the rest is summarised. */
	static final int NOTICES_MAX = 20;

	private final Consumer<String, String> consumer;
	private final DeadLetters deadLetters;
	private final String deadLetterTopic;
	private final List<Entry> entries = new ArrayList<Entry>();
	/** The messages handed to the sink, in the order it numbers them from 0. */
	private final List<Entry> delivered = new ArrayList<Entry>();
	private final Map<TopicPartition, Long> committed = new HashMap<TopicPartition, Long>();
	private final List<String> notices = new ArrayList<String>();
	private int noticesOmitted;
	private int deadLettered;
	private int commitFailures;
	private boolean oneToOne = true;
	private int handledCount;
	private int failedCount;

	/**
	 * @param pDeadLetters where a message that cannot be written goes; null to hold such a message back instead
	 * @param pDeadLetterTopic the topic {@code pDeadLetters} sends to, for the notices; ignored when {@code pDeadLetters} is null
	 */
	KafkaConsumeLedger(final Consumer<String, String> pConsumer, final DeadLetters pDeadLetters, final String pDeadLetterTopic)
	{
		this.consumer = pConsumer;
		this.deadLetters = pDeadLetters;
		this.deadLetterTopic = pDeadLetterTopic;
	}

	/** Registers a polled message, in poll order. */
	Entry add(final ConsumerRecord<String, String> pRecord)
	{
		final Entry entry = new Entry(pRecord);
		this.entries.add(entry);
		return entry;
	}

	int size()
	{
		return this.entries.size();
	}

	/** Messages dead-lettered so far. */
	int deadLettered()
	{
		return this.deadLettered;
	}

	// ------------------------------------------------------------------ settling

	/** A message with nothing to write (a tombstone, a JSON null): handled as soon as it is seen. */
	void skip(final Entry pEntry)
	{
		pEntry.handled = true;
	}

	/**
	 * A message that is not valid JSON. When the read commits ({@code pLive}) it is moved to the dead-letter topic
	 * if one is configured and held back otherwise; when it does not (a simulation) it is only reported. Either
	 * way it is told in a notice (no payload value in it).
	 */
	void invalid(final Entry pEntry, final boolean pLive)
	{
		this.unwritten(pEntry, "Payload is not valid JSON", pLive);
	}

	/** The message becomes the next row delivered to the sink (its number is the count so far). */
	void delivered(final Entry pEntry)
	{
		this.delivered.add(pEntry);
	}

	/** Rows delivered to the sink so far. */
	int deliveredCount()
	{
		return this.delivered.size();
	}

	/**
	 * The delivered rows cannot be matched to messages one to one (an items path that is not the
	 * message list): the run is then settled as a whole, see {@link #finishWhole}.
	 */
	void wholeRunOnly()
	{
		this.oneToOne = false;
	}

	/**
	 * The sink's report: rows {@code [pFrom, pTo)} are settled, {@code pNotWritten} are the ones that were not
	 * written. Commits what became committable.
	 */
	void handled(final int pFrom, final int pTo, final Set<Integer> pNotWritten, final boolean pLive)
	{
		if (!this.oneToOne)
		{
			this.handledCount += Math.max(0, pTo - pFrom);
			this.failedCount += pNotWritten == null ? 0 : pNotWritten.size();
			return;
		}
		for (int row = Math.max(0, pFrom); row < pTo && row < this.delivered.size(); row++)
		{
			final Entry entry = this.delivered.get(row);
			if (pNotWritten != null && pNotWritten.contains(Integer.valueOf(row)))
			{
				this.unwritten(entry, "EBX did not write it", pLive);
			}
			else
			{
				entry.handled = true;
			}
		}
		this.commit();
	}

	/**
	 * The end of a read whose rows could not be matched to messages: every message is handled when every one
	 * was delivered, the sink settled each delivered row and none failed - and none is otherwise.
	 */
	void finishWhole(final boolean pAllDelivered, final int pDelivered)
	{
		if (this.oneToOne)
		{
			return;
		}
		if (pAllDelivered && this.failedCount == 0 && this.handledCount == pDelivered)
		{
			for (final Entry entry : this.entries)
			{
				entry.handled = true;
			}
		}
		this.commit();
	}

	/** Every message not yet settled is handled - the caller holds the values (an interactive {@code consume}) - and committed. */
	void handleAll()
	{
		for (final Entry entry : this.entries)
		{
			if (!entry.handled && !entry.held)
			{
				entry.handled = true;
			}
		}
		this.commit();
	}

	private void unwritten(final Entry pEntry, final String pReason, final boolean pLive)
	{
		pEntry.held = true;
		if (!pLive)
		{
			this.notice("Message " + pEntry.where() + " - " + pReason + ": nothing is committed by this read, so it stays on the topic");
			return;
		}
		if (this.deadLetters != null)
		{
			try
			{
				this.deadLetters.send(pEntry, pReason);
				pEntry.handled = true;
				pEntry.held = false;
				this.deadLettered++;
				this.notice("Message " + pEntry.where() + " - " + pReason + ": moved to the dead-letter topic " + this.deadLetterTopic);
				return;
			}
			catch (final Exception ex)
			{
				pEntry.held = true;
				this.notice("Message " + pEntry.where() + " - " + pReason + ": it could not be moved to the dead-letter topic " + this.deadLetterTopic
					+ " (" + KafkaConsumeLedger.reasonOf(ex) + "), so it is left uncommitted and will be delivered again");
				return;
			}
		}
		pEntry.held = true;
		this.notice("Message " + pEntry.where() + " - " + pReason + ": left uncommitted, so it will be delivered again and"
			+ " its partition does not move past it; set the connector property dead.letter.topic to move such messages aside");
	}

	private static String reasonOf(final Exception pException)
	{
		final String text = pException.getMessage() == null ? pException.getClass().getSimpleName() : pException.getMessage();
		return text.length() > 120 ? text.substring(0, 120) : text;
	}

	// ------------------------------------------------------------------ committing

	/**
	 * The offsets to commit now: per partition, the offset after the last message of the unbroken run of
	 * handled messages from the first polled one, when that is above what was already committed.
	 */
	Map<TopicPartition, OffsetAndMetadata> plan()
	{
		final Map<TopicPartition, Long> target = new LinkedHashMap<TopicPartition, Long>();
		final java.util.Set<TopicPartition> blocked = new java.util.HashSet<TopicPartition>();
		for (final Entry entry : this.entries)
		{
			if (blocked.contains(entry.partition))
			{
				continue;
			}
			if (entry.handled)
			{
				target.put(entry.partition, Long.valueOf(entry.offset + 1));
			}
			else
			{
				blocked.add(entry.partition);
			}
		}
		final Map<TopicPartition, OffsetAndMetadata> plan = new LinkedHashMap<TopicPartition, OffsetAndMetadata>();
		for (final Map.Entry<TopicPartition, Long> t : target.entrySet())
		{
			final Long done = this.committed.get(t.getKey());
			if (done == null || t.getValue().longValue() > done.longValue())
			{
				plan.put(t.getKey(), new OffsetAndMetadata(t.getValue().longValue()));
			}
		}
		return plan;
	}

	/** Commits {@link #plan()}. A refused commit is told and never fails the run: the messages are delivered again. */
	void commit()
	{
		final Map<TopicPartition, OffsetAndMetadata> plan = this.plan();
		if (plan.isEmpty())
		{
			return;
		}
		try
		{
			this.consumer.commitSync(plan);
			for (final Map.Entry<TopicPartition, OffsetAndMetadata> done : plan.entrySet())
			{
				this.committed.put(done.getKey(), Long.valueOf(done.getValue().offset()));
			}
		}
		catch (final RuntimeException ex)
		{
			this.commitFailures++;
			this.notice("Kafka refused the offset commit (" + KafkaConsumeLedger.reasonOf(ex) + "): the messages written are delivered again by the next read");
		}
	}

	/** Offsets committed so far, for the tests and the run's result. */
	Map<TopicPartition, Long> committedOffsets()
	{
		return new LinkedHashMap<TopicPartition, Long>(this.committed);
	}

	// ------------------------------------------------------------------ notices

	private void notice(final String pText)
	{
		if (this.notices.size() < KafkaConsumeLedger.NOTICES_MAX)
		{
			this.notices.add(pText);
		}
		else
		{
			this.noticesOmitted++;
		}
	}

	/**
	 * What the run should say, once it is over: the notices raised on the way, then one line per partition whose
	 * commit stopped at a message that was not written.
	 */
	List<String> closingNotices()
	{
		final List<String> out = new ArrayList<String>(this.notices);
		if (this.noticesOmitted > 0)
		{
			out.add(this.noticesOmitted + " more notice(s) of the same kind are not listed");
		}
		final Map<TopicPartition, Entry> stops = new LinkedHashMap<TopicPartition, Entry>();
		final Map<TopicPartition, Integer> behind = new HashMap<TopicPartition, Integer>();
		for (final Entry entry : this.entries)
		{
			if (!stops.containsKey(entry.partition))
			{
				if (!entry.handled)
				{
					stops.put(entry.partition, entry);
					behind.put(entry.partition, Integer.valueOf(1));
				}
			}
			else
			{
				behind.put(entry.partition, Integer.valueOf(behind.get(entry.partition).intValue() + 1));
			}
		}
		for (final Map.Entry<TopicPartition, Entry> stop : stops.entrySet())
		{
			out.add("Partition " + stop.getKey().partition() + " of " + stop.getKey().topic() + " stays at offset " + stop.getValue().offset
				+ ": " + behind.get(stop.getKey()) + " polled message(s) from there will be delivered again");
		}
		return out;
	}

	/** True when some polled message is not handled, so the next read will see it again. */
	boolean hasUnhandled()
	{
		for (final Entry entry : this.entries)
		{
			if (!entry.handled)
			{
				return true;
			}
		}
		return false;
	}

	int commitFailures()
	{
		return this.commitFailures;
	}
}
