package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.on.ebx.connect.spi.Operation;

/**
 * When a scheduled run that keeps failing is told to somebody (audit C-18): the settings the properties give, the streak of
 * consecutive failures, the decision to send, the operation the message can go through, and the text. The call to the
 * chosen connection needs a repository and is not run here.
 */
public final class ScheduledFailureAlertTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// nothing chosen: nothing sent, whatever else is set
		final ScheduledFailureAlert.Settings none = settings();
		check(!none.isConfigured() && none.threshold == 3 && none.reminderEvery == 0 && "notify".equals(none.operation), "defaults: no connection, threshold 3, notify");
		check(!settings("ebx.connectors.alert.connector", "  ").isConfigured(), "blank is not a choice");
		final ScheduledFailureAlert.Settings chosen = settings("ebx.connectors.alert.connector", " Slack ops ", "ebx.connectors.alert.operation", "post",
			"ebx.connectors.alert.threshold", "5", "ebx.connectors.alert.reminderEvery", "10", "ebx.connectors.alert.input.channel", "#alerts");
		check(chosen.isConfigured() && "Slack ops".equals(chosen.connector) && "post".equals(chosen.operation) && chosen.threshold == 5 && chosen.reminderEvery == 10, "chosen");
		check("#alerts".equals(chosen.inputs.get("channel")) && chosen.inputs.size() == 1, "fixed inputs: " + chosen.inputs);
		check(settings("ebx.connectors.alert.threshold", "1").threshold == 2 && settings("ebx.connectors.alert.threshold", "x").threshold == 3
			&& settings("ebx.connectors.alert.threshold", "-4").threshold == 2, "threshold at least 2, unreadable = default");
		check(settings("ebx.connectors.alert.reminderEvery", "-1").reminderEvery == 0, "no negative reminder");

		// the streak: failures add up, a success starts again, mappings are apart
		ScheduledFailureAlert.forgetAll();
		check(ScheduledFailureAlert.record(7, true) == 1 && ScheduledFailureAlert.record(7, true) == 2 && ScheduledFailureAlert.record(8, true) == 1, "streaks per mapping");
		check(ScheduledFailureAlert.record(7, false) == 0 && ScheduledFailureAlert.record(7, true) == 1, "a success starts a new streak");

		// when to send: at the threshold, then only on a reminder, never below it
		check(!ScheduledFailureAlert.shouldAlert(0, 3, 0) && !ScheduledFailureAlert.shouldAlert(1, 3, 0) && !ScheduledFailureAlert.shouldAlert(2, 3, 0), "below the threshold: nothing");
		check(ScheduledFailureAlert.shouldAlert(3, 3, 0), "the failure that reaches the threshold sends");
		check(!ScheduledFailureAlert.shouldAlert(4, 3, 0) && !ScheduledFailureAlert.shouldAlert(30, 3, 0), "no reminder: once per streak");
		check(!ScheduledFailureAlert.shouldAlert(4, 3, 5) && ScheduledFailureAlert.shouldAlert(8, 3, 5) && !ScheduledFailureAlert.shouldAlert(9, 3, 5) && ScheduledFailureAlert.shouldAlert(13, 3, 5),
			"a reminder every 5 failures after the first message");
		int sent = 0;
		ScheduledFailureAlert.forgetAll();
		for (int run = 1; run <= 10; run++)
		{
			if (ScheduledFailureAlert.shouldAlert(ScheduledFailureAlert.record(1, true), 3, 0))
			{
				sent++;
			}
		}
		check(sent == 1, "ten failures in a row: one message, not eight: " + sent);
		ScheduledFailureAlert.record(1, false);
		for (int run = 1; run <= 3; run++)
		{
			if (ScheduledFailureAlert.shouldAlert(ScheduledFailureAlert.record(1, true), 3, 0))
			{
				sent++;
			}
		}
		check(sent == 2, "a success in between: the next streak says it again: " + sent);

		// the operation: it must take a text and have every required input given
		final Map<String, String> fixed = new LinkedHashMap<String, String>();
		fixed.put("channel", "#alerts");
		final Operation slack = new Operation("post", Operation.Kind.WRITE, "post", new String[] { "text" }, new String[] { "channel", "title", "blocks" });
		final Map<String, String> inputs = ScheduledFailureAlert.inputsFor(slack, fixed, "hello", "T");
		check(inputs != null && "hello".equals(inputs.get("text")) && "T".equals(inputs.get("title")) && "#alerts".equals(inputs.get("channel")), "text, title and the fixed channel: " + inputs);
		final Operation chat = Operation.write("notify", "Post a message (text, optional title)", "text");
		check(ScheduledFailureAlert.inputsFor(chat, new LinkedHashMap<String, String>(), "hello", "T").size() == 1, "no title when the operation has none");
		final Operation update = new Operation("update", Operation.Kind.WRITE, "edit", new String[] { "ts", "text" }, new String[] { "channel" });
		check(ScheduledFailureAlert.inputsFor(update, fixed, "hello", "T") == null, "a required input nobody gives: not usable");
		check(ScheduledFailureAlert.inputsFor(Operation.search("channels", "list"), fixed, "hello", "T") == null, "an operation without a text: not usable");
		check(ScheduledFailureAlert.inputsFor(null, fixed, "hello", "T") == null, "unknown operation");

		// the text: mapping, failures in a row, run, last error redacted and cut; nothing else
		final String text = ScheduledFailureAlert.message(12, "Enrich GLEIF", 3, "R20261006-1200-0001", "1 item(s) in error: \"Timeout\"");
		check(text.contains("mapping 12 (Enrich GLEIF)") && text.contains("failed 3 time(s) in a row") && text.contains("R20261006-1200-0001") && text.contains("Timeout"), text);
		check(ScheduledFailureAlert.message(1, "", 2, "R", null).contains("(no message)") && !ScheduledFailureAlert.message(1, "", 2, "R", null).contains("()"), "no label, no message");
		final StringBuilder longError = new StringBuilder();
		for (int i = 0; i < 2000; i++)
		{
			longError.append('x');
		}
		check(ScheduledFailureAlert.message(1, "m", 2, "R", longError.toString()).length() < 700, "the error is cut");

		System.out.println("PASS: scheduled-run alert: nothing without a chosen connection; once per streak at the threshold (reminders optional); the text names mapping, count, run, last error");
	}

	private static ScheduledFailureAlert.Settings settings(final String... pKeyValues)
	{
		final Properties properties = new Properties();
		for (int i = 0; i < pKeyValues.length; i += 2)
		{
			properties.setProperty(pKeyValues[i], pKeyValues[i + 1]);
		}
		return new ScheduledFailureAlert.Settings(properties);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
