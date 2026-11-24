package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message codes for the sentences the engine stores in the synchronisation log (audit C-37). The stored text stays what the engine
 * wrote, in English: it is what an agent, a search and a replay read. A screen turns the common ones into a code and its arguments
 * with {@link #decode} and writes them in the reader's language ({@code log.<code>} of the UI bundles). Pure: {@code LogMessageCodesTest}.
 * <p>
 * Only complete sentences of the engine are recognised, and the arguments are the pieces it put in them (counts, a record label,
 * an input name): a text that is not one of these decodes to null and the screen shows it as stored. This is the list of what
 * is localised on screens; the rest of what the engine writes stays English.
 */
public final class LogMessageCodes
{
	/** A recognised sentence: the code and the arguments in order. */
	public static final class Coded
	{
		private final String code;
		private final String[] args;

		Coded(final String pCode, final String... pArgs)
		{
			this.code = pCode;
			this.args = pArgs;
		}

		/** The bundle key of the sentence: {@code log.<code>}. */
		public String key()
		{
			return "log." + this.code;
		}

		public String getCode()
		{
			return this.code;
		}

		public String[] getArgs()
		{
			return this.args.clone();
		}
	}

	private static final class Rule
	{
		final Pattern pattern;
		final String code;
		final int[] groups;

		Rule(final String pRegex, final String pCode, final int... pGroups)
		{
			this.pattern = Pattern.compile(pRegex, Pattern.DOTALL);
			this.code = pCode;
			this.groups = pGroups;
		}
	}

	private static final List<Rule> RULES = new ArrayList<Rule>();
	private static final String SUMMARY = "(\\d+) change\\(s\\), (\\d+) skipped(?:, (\\d+) error\\(s\\))?(?:, (\\d+) conflict\\(s\\))?";

	static
	{
		// one record written (the engine's own summary follows its label)
		LogMessageCodes.RULES.add(new Rule("created (.+), " + LogMessageCodes.SUMMARY, "created", 1, 2, 3, 4, 5));
		LogMessageCodes.RULES.add(new Rule("updated (.+), " + LogMessageCodes.SUMMARY, "updated", 1, 2, 3, 4, 5));
		LogMessageCodes.RULES.add(new Rule(LogMessageCodes.SUMMARY + " \\(not written\\)", "summaryNotWritten", 1, 2, 3, 4));
		LogMessageCodes.RULES.add(new Rule(LogMessageCodes.SUMMARY + ", (\\d+) sub-object change\\(s\\)", "summarySub", 1, 2, 3, 4, 5));
		LogMessageCodes.RULES.add(new Rule(LogMessageCodes.SUMMARY, "summary", 1, 2, 3, 4));
		LogMessageCodes.RULES.add(new Rule("unchanged \\((.*)\\)", "unchanged", 1));
		LogMessageCodes.RULES.add(new Rule("would create \\(not written\\)", "wouldCreate"));
		LogMessageCodes.RULES.add(new Rule("would update (.+) \\(not written\\)", "wouldUpdate", 1));
		LogMessageCodes.RULES.add(new Rule("would call (.+) with (.*) \\(not sent\\)", "wouldCall", 1, 2));
		LogMessageCodes.RULES.add(new Rule("would send to (.+) with inputs (.*) \\(not sent\\)", "wouldSend", 1, 2));
		LogMessageCodes.RULES.add(new Rule("deleted remotely \\((.*)\\)", "deletedRemotely", 1));
		LogMessageCodes.RULES.add(new Rule("Input '(.*)' is empty on this record", "inputEmpty", 1));
		LogMessageCodes.RULES.add(new Rule("No data returned for (.*)", "noData", 1));
		LogMessageCodes.RULES.add(new Rule("No document", "noDocument"));
		LogMessageCodes.RULES.add(new Rule("record no longer exists", "recordGone"));
		LogMessageCodes.RULES.add(new Rule("mapping (\\d+) no longer exists", "mappingGone", 1));
		LogMessageCodes.RULES.add(new Rule("No record matches (.*) and creation is off", "noMatch", 1));
		LogMessageCodes.RULES.add(new Rule("Dataset (.+) not found in (.+)", "datasetMissingIn", 1, 2));
		LogMessageCodes.RULES.add(new Rule("Dataset (.+) not found", "datasetMissing", 1));
		LogMessageCodes.RULES.add(new Rule("batch of (\\d+): created=(\\d+) updated=(\\d+) noChange=(\\d+) errors=(\\d+) rejected=(\\d+)", "batch", 1, 2, 3, 4, 5, 6));
		LogMessageCodes.RULES.add(new Rule("Import cancelled after (\\d+) item\\(s\\); (\\d+) item\\(s\\) of (\\d+) available were left unprocessed", "cancelledRemainder", 1, 2, 3));
		LogMessageCodes.RULES.add(new Rule("Import cancelled after (\\d+) item\\(s\\)", "cancelled", 1));
		LogMessageCodes.RULES.add(new Rule("Import stopped after (\\d+) consecutive fully-failed batches, having processed (\\d+) item\\(s\\)", "breaker", 1, 2));
		LogMessageCodes.RULES.add(new Rule("Limit reached: (\\d+) of the (\\d+) items returned by (.+) were imported; raise max to import the others", "limitReached", 1, 2, 3));
		LogMessageCodes.RULES.add(new Rule("(\\d+) unchanged since the last send, (\\d+) failed and waiting for the retry delay", "outboundWaiting", 1, 2));
		LogMessageCodes.RULES.add(new Rule("Replay of this record failed: (.*)", "replayFailed", 1));
	}

	private LogMessageCodes()
	{
	}

	/** The code and arguments of a stored sentence; null when it is not one of the common ones. */
	public static Coded decode(final String pStored)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return null;
		}
		for (final Rule rule : LogMessageCodes.RULES)
		{
			final Matcher matcher = rule.pattern.matcher(pStored);
			if (matcher.matches())
			{
				final String[] args = new String[rule.groups.length];
				for (int i = 0; i < args.length; i++)
				{
					final String value = matcher.group(rule.groups[i]);
					// an optional count that the engine left out is zero
					args[i] = value == null ? "0" : value;
				}
				return new Coded(rule.code, args);
			}
		}
		return null;
	}

	/** Every code {@link #decode} can answer, for the bundles to be checked against. */
	public static List<String> codes()
	{
		final List<String> out = new ArrayList<String>();
		for (final Rule rule : LogMessageCodes.RULES)
		{
			out.add(rule.code);
		}
		return out;
	}
}
