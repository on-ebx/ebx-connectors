package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.Operation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * Tells somebody when a scheduled run keeps failing (audit C-18). Nobody watches a scheduled task: it fails in the
 * scheduler's own report and that is all. When the same mapping's scheduled run has failed {@code threshold} times in
 * a row, ONE message is sent through the one connection the administrator chose, and nothing is sent while none is
 * chosen (the kernel log says so once per streak).
 * <p>
 * <b>The channel</b> is a connection that already exists in the Connectors dataset and one of its operations that takes
 * a {@code text} input (Slack {@code post}/{@code notify}, the chat webhook {@code notify} for Slack or Teams, any
 * connector with such an operation). It is chosen with JVM properties, read at each failure, so that no secret and no
 * model change is involved - the credentials stay on the connection:
 * <ul>
 * <li>{@code ebx.connectors.alert.connector}: the connection id; unset or blank = no alert is sent;</li>
 * <li>{@code ebx.connectors.alert.operation}: its operation, default {@code notify};</li>
 * <li>{@code ebx.connectors.alert.threshold}: consecutive failed scheduled runs of one mapping that trigger the message,
 * default 3, at least 2;</li>
 * <li>{@code ebx.connectors.alert.reminderEvery}: after the first message, one more every N further failures; default 0 =
 * none (one message per streak, a success starts a new streak);</li>
 * <li>{@code ebx.connectors.alert.input.<name>}: fixed inputs of the operation, e.g. {@code ...input.channel=#alerts}.</li>
 * </ul>
 * The message names the mapping, the number of failures in a row, the run and the last failure text (redacted, cut); it
 * never carries a value of a record. The streak is kept in memory of the JVM: a restart starts it again, and another
 * node of a cluster counts its own.
 */
public final class ScheduledFailureAlert
{
	public static final String PREFIX = "ebx.connectors.alert.";
	static final int DEFAULT_THRESHOLD = 3;
	static final int MAX_LAST_ERROR = 400;

	private ScheduledFailureAlert()
	{
	}

	/** What the properties say: no EBX type, so that it can be decided and tested on its own. */
	static final class Settings
	{
		final String connector;
		final String operation;
		final int threshold;
		final int reminderEvery;
		final Map<String, String> inputs = new LinkedHashMap<String, String>();

		Settings(final Properties pProperties)
		{
			final String id = pProperties.getProperty(ScheduledFailureAlert.PREFIX + "connector");
			this.connector = id == null || id.trim().isEmpty() ? null : id.trim();
			final String op = pProperties.getProperty(ScheduledFailureAlert.PREFIX + "operation");
			this.operation = op == null || op.trim().isEmpty() ? "notify" : op.trim();
			this.threshold = Math.max(2, ScheduledFailureAlert.intOf(pProperties.getProperty(ScheduledFailureAlert.PREFIX + "threshold"), ScheduledFailureAlert.DEFAULT_THRESHOLD));
			this.reminderEvery = Math.max(0, ScheduledFailureAlert.intOf(pProperties.getProperty(ScheduledFailureAlert.PREFIX + "reminderEvery"), 0));
			final String inputPrefix = ScheduledFailureAlert.PREFIX + "input.";
			for (final String name : pProperties.stringPropertyNames())
			{
				if (name.startsWith(inputPrefix) && name.length() > inputPrefix.length())
				{
					this.inputs.put(name.substring(inputPrefix.length()), pProperties.getProperty(name));
				}
			}
		}

		boolean isConfigured()
		{
			return this.connector != null;
		}
	}

	private static int intOf(final String pRaw, final int pDefault)
	{
		if (pRaw == null || pRaw.trim().isEmpty())
		{
			return pDefault;
		}
		try
		{
			return Integer.parseInt(pRaw.trim());
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	/**
	 * Whether the failure that makes the streak {@code pStreak} long sends a message: the one that reaches the threshold, and,
	 * when {@code pReminderEvery} is above 0, every {@code pReminderEvery}-th one after it. Never below the threshold, never
	 * on a success (streak 0).
	 */
	static boolean shouldAlert(final int pStreak, final int pThreshold, final int pReminderEvery)
	{
		if (pStreak < pThreshold || pThreshold < 1)
		{
			return false;
		}
		return pStreak == pThreshold || (pReminderEvery > 0 && (pStreak - pThreshold) % pReminderEvery == 0);
	}

	/** Consecutive failed scheduled runs, by mapping id. */
	private static final Map<Integer, Integer> STREAKS = new java.util.HashMap<Integer, Integer>();

	/** Records one scheduled run of a mapping and returns the length of its failure streak (0 after a success). */
	static synchronized int record(final int pMappingId, final boolean pFailed)
	{
		final Integer key = Integer.valueOf(pMappingId);
		if (!pFailed)
		{
			ScheduledFailureAlert.STREAKS.remove(key);
			return 0;
		}
		final Integer before = ScheduledFailureAlert.STREAKS.get(key);
		final int streak = (before == null ? 0 : before.intValue()) + 1;
		ScheduledFailureAlert.STREAKS.put(key, Integer.valueOf(streak));
		return streak;
	}

	/** The text sent: mapping, failures in a row, run, last failure (redacted and cut). No record value. */
	static String message(final int pMappingId, final String pMappingLabel, final int pStreak, final String pRunId, final String pLastError)
	{
		final String last = pLastError == null || pLastError.trim().isEmpty() ? "(no message)" : Redaction.redact(pLastError.trim());
		return "ON EBX Connectors: the scheduled run of mapping " + pMappingId + (pMappingLabel == null || pMappingLabel.isEmpty() ? "" : " (" + pMappingLabel + ")")
			+ " has failed " + pStreak + " time(s) in a row. Last run " + pRunId + ". Last error: "
			+ (last.length() > ScheduledFailureAlert.MAX_LAST_ERROR ? last.substring(0, ScheduledFailureAlert.MAX_LAST_ERROR - 3) + "..." : last);
	}

	/**
	 * The inputs of the alert call for an operation, or null when the operation cannot carry the message: it must take a
	 * {@code text} and ask for nothing else that the fixed inputs do not give. {@code title} is added when the operation
	 * accepts one.
	 */
	static Map<String, String> inputsFor(final Operation pOperation, final Map<String, String> pFixed, final String pText, final String pTitle)
	{
		if (pOperation == null
			|| (!pOperation.getRequiredInputs().contains("text") && !pOperation.getOptionalInputs().contains("text")))
		{
			return null;
		}
		final Map<String, String> inputs = new LinkedHashMap<String, String>(pFixed);
		inputs.put("text", pText);
		if (pOperation.getOptionalInputs().contains("title") || pOperation.getRequiredInputs().contains("title"))
		{
			inputs.put("title", pTitle);
		}
		for (final String required : pOperation.getRequiredInputs())
		{
			if (inputs.get(required) == null || inputs.get(required).trim().isEmpty())
			{
				return null;
			}
		}
		return inputs;
	}

	/**
	 * Called once per scheduled run of a mapping, after the verdict. A success clears the streak; a failure that reaches the
	 * threshold sends the message, or logs that none was sent. Never throws: an alert that cannot be sent is a log line, it
	 * must not change the outcome of the run.
	 */
	public static void onScheduledRun(final Session pSession, final Repository pRepository, final MappingDefinition pMapping, final String pRunId,
		final boolean pFailed, final String pLastError)
	{
		try
		{
			final int streak = ScheduledFailureAlert.record(pMapping.getId(), pFailed);
			final Settings settings = new Settings(System.getProperties());
			if (!ScheduledFailureAlert.shouldAlert(streak, settings.threshold, settings.reminderEvery))
			{
				return;
			}
			if (!settings.isConfigured())
			{
				if (streak == settings.threshold)
				{
					ConnectorService.log().warn("[connectors] the scheduled run of mapping " + pMapping.getId() + " has failed " + streak
						+ " times in a row; no alert was sent because no alert connection is chosen (set -D" + ScheduledFailureAlert.PREFIX + "connector)");
				}
				return;
			}
			final ConnectorRepository repo = ConnectorService.get().repository(pRepository);
			final Connector connector = ConnectorRegistry.get().connector(settings.connector, repo);
			final Operation operation = ConnectorService.operation(connector, settings.operation);
			final String lastError = SampleValueSafety.safeFailure(pMapping, pLastError == null ? "" : pLastError);
			final String text = ScheduledFailureAlert.message(pMapping.getId(), pMapping.getLabel(), streak, pRunId, lastError);
			final Map<String, String> inputs = ScheduledFailureAlert.inputsFor(operation, settings.inputs, text, "Connectors: scheduled run failing");
			if (inputs == null)
			{
				ConnectorService.log().warn("[connectors] alert not sent: " + settings.connector + "/" + settings.operation
					+ " is not an operation that takes a 'text' and whose other required inputs are given by -D" + ScheduledFailureAlert.PREFIX + "input.<name>");
				return;
			}
			connector.execute(new ConnectorContext(pSession, repo.getRepository(), null, null), settings.operation, inputs, null);
			ConnectorService.log().info("[connectors] alert sent through " + settings.connector + "/" + settings.operation + ": mapping " + pMapping.getId()
				+ " failed " + streak + " time(s) in a row");
		}
		catch (final Exception ex)
		{
			ConnectorService.log().warn(Redaction.redact("[connectors] alert for mapping " + pMapping.getId() + " could not be sent: "
				+ (ex.getMessage() == null ? ex.toString() : ex.getMessage())));
		}
	}

	/** The connection the alerts go through, null when none is chosen (nothing is sent then). */
	public static String connectorChosen()
	{
		return new Settings(System.getProperties()).connector;
	}

	static synchronized void forgetAll()
	{
		ScheduledFailureAlert.STREAKS.clear();
	}
}
