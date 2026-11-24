package com.on.ebx.connect.core;

import java.io.InputStream;
import java.util.Properties;

/** The common sentences of the synchronisation log decode to a code and arguments, and every code has a sentence in both bundles (audit C-37). */
public final class LogMessageCodesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// the sentences the engine builds, taken from its own helpers where it has one
		final MappingResult applied = new MappingResult();
		applied.getChanges().add(new MappingResult.Change("/name", "a", "b"));
		applied.getChanges().add(new MappingResult.Change("/city", "a", "b"));
		check(applied.summary().equals("2 change(s), 0 skipped"), "the engine summary: " + applied.summary());
		final LogMessageCodes.Coded created = LogMessageCodes.decode("created ACME, Paris, " + applied.summary());
		check(created != null && "created".equals(created.getCode()) && "ACME, Paris".equals(created.getArgs()[0]) && "2".equals(created.getArgs()[1])
			&& "0".equals(created.getArgs()[3]) && "0".equals(created.getArgs()[4]), "created with a comma in the label and optional counts at zero");
		final LogMessageCodes.Coded withErrors = LogMessageCodes.decode("updated Foo, 3 change(s), 1 skipped, 2 error(s), 1 conflict(s)");
		check(withErrors != null && "updated".equals(withErrors.getCode()) && "2".equals(withErrors.getArgs()[3]) && "1".equals(withErrors.getArgs()[4]), "updated with errors and conflicts");
		check("summaryNotWritten".equals(LogMessageCodes.decode("1 change(s), 0 skipped (not written)").getCode()), "not written");
		check("summarySub".equals(LogMessageCodes.decode("1 change(s), 0 skipped, 2 sub-object change(s)").getCode()), "sub-objects");
		check("summary".equals(LogMessageCodes.decode("1 change(s), 0 skipped").getCode()), "plain summary");
		check("unchanged".equals(LogMessageCodes.decode("unchanged (hash)").getCode()), "unchanged");
		check("wouldCreate".equals(LogMessageCodes.decode("would create (not written)").getCode()), "would create");
		check("wouldUpdate".equals(LogMessageCodes.decode("would update ACME (not written)").getCode()), "would update");
		check("inputEmpty".equals(LogMessageCodes.decode("Input 'lei' is empty on this record").getCode()) && "lei".equals(LogMessageCodes.decode("Input 'lei' is empty on this record").getArgs()[0]), "input empty");
		check("noData".equals(LogMessageCodes.decode("No data returned for {lei=123}").getCode()), "no data");
		check("recordGone".equals(LogMessageCodes.decode("record no longer exists").getCode()) && "mappingGone".equals(LogMessageCodes.decode("mapping 12 no longer exists").getCode()), "gone");
		check("noMatch".equals(LogMessageCodes.decode("No record matches ./id='1' and creation is off").getCode()), "no match");
		check("datasetMissingIn".equals(LogMessageCodes.decode("Dataset Companies not found in Main").getCode()) && "datasetMissing".equals(LogMessageCodes.decode("Dataset Companies not found").getCode()), "dataset");
		final ImportBatchSummary batch = new ImportBatchSummary();
		batch.created();
		batch.error();
		final LogMessageCodes.Coded coded = LogMessageCodes.decode(batch.message());
		check(coded != null && "batch".equals(coded.getCode()) && "2".equals(coded.getArgs()[0]) && "1".equals(coded.getArgs()[1]) && "1".equals(coded.getArgs()[4]), "the batch line: " + batch.message());
		check("cancelled".equals(LogMessageCodes.decode("Import cancelled after 51 item(s)").getCode()), "cancelled");
		check("cancelledRemainder".equals(LogMessageCodes.decode("Import cancelled after 51 item(s); 949 item(s) of 1000 available were left unprocessed").getCode()), "cancelled with remainder");
		check("breaker".equals(LogMessageCodes.decode("Import stopped after 3 consecutive fully-failed batches, having processed 3000 item(s)").getCode()), "breaker");
		check("limitReached".equals(LogMessageCodes.decode("Limit reached: 100 of the 250 items returned by jdbc/query were imported; raise max to import the others").getCode()), "limit");
		check("outboundWaiting".equals(LogMessageCodes.decode("5 unchanged since the last send, 2 failed and waiting for the retry delay").getCode()), "outbound waiting");
		check("replayFailed".equals(LogMessageCodes.decode("Replay of this record failed: boom").getCode()) && "boom".equals(LogMessageCodes.decode("Replay of this record failed: boom").getArgs()[0]), "replay");
		check("wouldCall".equals(LogMessageCodes.decode("would call delete with {key=1} (not sent)").getCode()) && "deletedRemotely".equals(LogMessageCodes.decode("deleted remotely (delete)").getCode()), "remote delete");

		// anything else is shown as stored
		check(LogMessageCodes.decode("Value must not exceed 10 character(s)") == null && LogMessageCodes.decode("") == null && LogMessageCodes.decode(null) == null, "other text is not decoded");
		check(LogMessageCodes.decode("created ACME and something else entirely") == null, "a sentence that only starts like one is not decoded");

		// every code has a sentence in both languages
		for (final String language : new String[] { "en", "fr" })
		{
			final Properties bundle = new Properties();
			try (InputStream in = LogMessageCodesTest.class.getResourceAsStream("/com/on/ebx/connect/ebx/ui/Messages_" + language + ".properties"))
			{
				bundle.load(in);
			}
			for (final String code : LogMessageCodes.codes())
			{
				check(bundle.getProperty("log." + code, "").trim().length() > 3, language + " lacks log." + code);
			}
			for (final String status : new String[] { "OK", "NO_CHANGE", "NO_DATA", "SKIPPED", "ERROR", "PARTIAL", "DRY_RUN" })
			{
				check(bundle.getProperty("status." + status, "").trim().length() > 1, language + " lacks status." + status);
			}
		}
		System.out.println("PASS: " + LogMessageCodes.codes().size() + " common log sentences decode to a code and have a sentence in en and fr");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
