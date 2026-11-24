package com.on.ebx.connect.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.BackgroundWork;
import com.on.ebx.connect.core.OutboundQueue;
import com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger;

/**
 * Audit C-07 (lot L8): a write in the table of a trigger does not call the remote system in the user's transaction. The
 * trigger is driven through its hand-over method (the EBX record objects are classes that cannot be stood in for); the sender counts the calls and records the thread. A source check pins that
 * the former in-transaction path is reached only when {@code blocking} is true.
 */
public final class ConnectorTableTriggerQueueTest
{
	private ConnectorTableTriggerQueueTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String root = System.getProperty("ebx.connectors.moduleDir", ".");
		System.setProperty("ebx.connectors.outboundQueue.settleMs", "200");
		System.setProperty("ebx.connectors.outboundQueue.intervalMs", "100");
		BackgroundWork.start();
		final List<String> sentOn = new ArrayList<String>();
		final com.on.ebx.connect.core.OutboundQueue.Store store = new com.on.ebx.connect.core.OutboundQueueTest.MemoryStore();
		OutboundQueue.start(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				synchronized (sentOn)
				{
					sentOn.add(Thread.currentThread().getName() + "|" + pEntry.recordKey + "|" + pEntry.connector + "/" + pEntry.operation + "|" + pEntry.direction);
				}
				return OutboundQueue.Outcome.sent();
			}
		});
		check(ConnectorTableTrigger.queueSend("CRM", "push", "OUTBOUND", "Master", "Customers", "/root/Customer", "77"), "the trigger's hand-over is accepted");
		synchronized (sentOn)
		{
			check(sentOn.isEmpty(), "the trigger returns without any remote call (nothing sent in the user's transaction)");
		}
		final long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end)
		{
			synchronized (sentOn)
			{
				if (!sentOn.isEmpty())
				{
					break;
				}
			}
			Thread.sleep(25);
		}
		synchronized (sentOn)
		{
			check(sentOn.size() == 1, "the queued record is sent after the write, got " + sentOn);
			check(sentOn.get(0).startsWith("ebx-connectors-outbound-queue|77|CRM/push|OUTBOUND"), "sent by the module's thread with the trigger's connector, operation and direction: " + sentOn.get(0));
		}
		BackgroundWork.stop(2000L);
		OutboundQueue.forget();
		source(root);
		System.out.println("PASS: the table trigger queues instead of calling the remote system in the transaction; blocking=true keeps the synchronous path");
	}

	private static void source(final String pRoot) throws Exception
	{
		final String src = new String(Files.readAllBytes(Paths.get(pRoot, "src/main/java/com/on/ebx/connect/ebx/trigger/ConnectorTableTrigger.java")), StandardCharsets.UTF_8);
		final int inTransaction = src.indexOf("private void syncInTransaction(");
		check(inTransaction > 0, "the synchronous path is kept as its own method");
		check(src.indexOf("syncRecordInProcedure") > inTransaction && src.indexOf("syncRecordInProcedure") == src.lastIndexOf("syncRecordInProcedure"),
			"syncRecordInProcedure is called from the synchronous method only");
		final String sync = src.substring(src.indexOf("private void sync(final ProcedureContext"), inTransaction);
		check(sync.contains("\"true\".equalsIgnoreCase(this.blocking)") && sync.contains("this.syncInTransaction(") && sync.contains("ConnectorTableTrigger.queueSend("),
			"sync() goes to the synchronous path when blocking is true and queues otherwise");
		check(sync.indexOf("this.syncInTransaction(") < sync.indexOf("ConnectorTableTrigger.queueSend("), "blocking is decided before queueing");
		check(!sync.contains("mappingFor"), "the trigger no longer reads the mapping tables on every write");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
