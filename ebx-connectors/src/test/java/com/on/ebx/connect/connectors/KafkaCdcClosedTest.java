package com.on.ebx.connect.connectors;

import java.util.HashMap;
import java.util.Map;

import com.on.ebx.connect.core.PreviewFeatures;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Audit C3-13, C3-15 (1.4.2): the simple CDC of the Kafka connector is closed by default behind its own preview word {@code cdc}. Both
 * entries of the connector (the direct call that previews the next batch, and the run of a feed) refuse with the coded message before
 * they look at the connection; {@code consume}, {@code peek} and {@code publish}, which are older than November, are not asked for the word.
 * The refusal of a feed run by the dispatcher is {@code PreviewFeatures.requireOperation}, tested in {@code PreviewFeaturesTest}; the
 * offsets of an ordinary {@code consume} feed run with every word closed in {@code KafkaConsumeOffsetsTest}.
 */
public final class KafkaCdcClosedTest
{
	private KafkaCdcClosedTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String saved = System.getProperty(PreviewFeatures.PROPERTY);
		try
		{
			System.clearProperty(PreviewFeatures.PROPERTY);
			final KafkaConnector kafka = new KafkaConnector();
			final Map<String, String> inputs = new HashMap<String, String>();
			inputs.put("topic", "orders.cdc");

			// closed: the direct call and the feed's run refuse, coded, naming the property and the word
			try
			{
				kafka.execute(null, "cdc", inputs, null);
				throw new AssertionError("a direct cdc call ran while the function was closed");
			}
			catch (final ConnectorException expected)
			{
				coded(expected.getMessage(), "direct call");
			}
			try
			{
				kafka.executeCdc(7, "", 10, 1000, false, null, null, new java.util.Date());
				throw new AssertionError("a cdc feed run reached the broker while the function was closed");
			}
			catch (final ConnectorException expected)
			{
				coded(expected.getMessage(), "feed run");
			}

			// the frontier: the ordinary reads are NOT refused by the preview gate (they fail later, here because the connection is not configured)
			for (final String operation : new String[] { "consume", "peek", "publish" })
			{
				try
				{
					kafka.execute(null, operation, inputs, null);
				}
				catch (final Throwable later)
				{
					check(later.getMessage() == null || !later.getMessage().contains(PreviewFeatures.CODE), operation + " was refused by the preview gate: " + later.getMessage());
				}
			}

			// opened: the gate lets the call through to the connector's own checks
			System.setProperty(PreviewFeatures.PROPERTY, "cdc");
			try
			{
				kafka.execute(null, "cdc", inputs, null);
				throw new AssertionError("an unconfigured connector cannot preview");
			}
			catch (final ConnectorException later)
			{
				check(later.getMessage().contains("not configured"), "open: the connector answers its own error: " + later.getMessage());
			}
			try
			{
				kafka.executeCdc(7, "", 10, 1000, false, null, null, new java.util.Date());
				throw new AssertionError("an unconfigured connector cannot run");
			}
			catch (final IllegalStateException later)
			{
				check(later.getMessage().contains("not configured"), "open: the connector answers its own error: " + later.getMessage());
			}
		}
		finally
		{
			if (saved == null) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, saved);
		}
		System.out.println("PASS: the simple CDC of Kafka is closed by default (direct call and feed run), the ordinary reads stay open, the word cdc opens it");
	}

	private static void coded(final String pMessage, final String pWhere)
	{
		check(pMessage.startsWith("[" + PreviewFeatures.CODE + "] ") && pMessage.contains("-D" + PreviewFeatures.PROPERTY) && pMessage.contains("cdc")
			&& pMessage.contains("fermée sur ce serveur"), pWhere + ": coded and bilingual refusal, saw " + pMessage);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
