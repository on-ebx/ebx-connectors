package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/**
 * Volume lot V2-D: the plan of one incremental feed run - what the stored checkpoint is, what
 * {@code ${watermark}} and {@code ${watermark.isSet}} resolve to (bound or as text), how a first run and a
 * full refresh differ, the boundary default, when the checkpoint may advance - plus a complete simulation
 * of successive runs over a source that cannot filter.
 */
public final class WatermarkRunTest
{
	private WatermarkRunTest() { }

	private static final WatermarkRun.Resolver PASS_THROUGH = new WatermarkRun.Resolver()
	{
		@Override
		public String resolve(final String pTemplate)
		{
			// what Expressions.resolve does to a placeholder it does not know: erases it - the sentinel must survive
			return pTemplate.replace("${param.tenant}", "acme");
		}
	};

	public static void main(final String[] args) throws Exception
	{
		mappingConfiguration();
		planning();
		placeholders();
		inputsAndCheckpointInput();
		advancing();
		successiveRuns();
		System.out.println("PASS: watermark run (configuration, first run vs stored vs full refresh, placeholders, boundary, advance rule, successive runs)");
	}

	private static MappingDefinition feed(final String pStrategy, final String pField, final String pInitial, final String pUnchangedIfEqual, final String pBoundary)
	{
		final MappingDefinition m = new MappingDefinition(7, "h2", "query", MappingDefinition.Direction.INBOUND, "Contacts", null, "DS", "/root/Contact", "", true);
		m.setFeed("rows", "./ID='${ID}'", true);
		m.setSynchronizationPolicy("INCREMENTAL", pStrategy, "", pField, pInitial);
		m.setUnchangedIfEqual(pUnchangedIfEqual);
		m.setCheckpointBoundary(pBoundary);
		return m;
	}

	private static void mappingConfiguration()
	{
		final MappingDefinition ok = feed("TIMESTAMP", "LAST_MODIFIED", "", "", "");
		check(ok.isWatermarkFeed(), "an INCREMENTAL feed with TIMESTAMP and a value path is a watermark feed");
		check(feed("MONOTONIC_ID", "ID", "", "", "").isWatermarkFeed(), "MONOTONIC_ID");
		check(feed("ORDERED_STRING", "ULID", "", "", "").isWatermarkFeed(), "ORDERED_STRING");
		check(!feed("VENDOR_CURSOR", "next", "", "", "").isWatermarkFeed(), "a vendor cursor is not a row watermark");
		check(!feed("TIMESTAMP", "", "", "", "").isWatermarkFeed(), "no value path, no watermark");
		final MappingDefinition full = new MappingDefinition(8, "h2", "query", MappingDefinition.Direction.INBOUND, "Full", null, "DS", "/root/Contact", "", true);
		full.setFeed("rows", "./ID='${ID}'", true);
		check(!full.isWatermarkFeed(), "a FULL feed is not a watermark feed");
		final MappingDefinition notFeed = new MappingDefinition(9, "h2", "query", MappingDefinition.Direction.INBOUND, "NF", null, "DS", "/root/Contact", "", true);
		notFeed.setSynchronizationPolicy("INCREMENTAL", "TIMESTAMP", "since", "updatedAt", "");
		check(!notFeed.isWatermarkFeed(), "a mapping without keyPredicate is not a feed, whatever its load mode");

		// boundary: automatic follows unchangedIfEqual; explicit wins; garbage is automatic
		check(!feed("TIMESTAMP", "L", "", "", "").isWatermarkInclusive(), "no unchangedIfEqual: exclusive (rows would be rewritten every run)");
		check(feed("TIMESTAMP", "L", "", "./_rowHash", "").isWatermarkInclusive(), "unchangedIfEqual set: inclusive (the hash absorbs the rows read again)");
		check(feed("TIMESTAMP", "L", "", "", "INCLUSIVE").isWatermarkInclusive(), "explicit INCLUSIVE");
		check(!feed("TIMESTAMP", "L", "", "./_rowHash", "EXCLUSIVE").isWatermarkInclusive(), "explicit EXCLUSIVE beats unchangedIfEqual");
		check(feed("TIMESTAMP", "L", "", "./_rowHash", "inclusive").isWatermarkInclusive() && "INCLUSIVE".equals(feed("TIMESTAMP", "L", "", "", "inclusive").getCheckpointBoundary()), "case-insensitive, stored upper case");
		check("".equals(feed("TIMESTAMP", "L", "", "", "maybe").getCheckpointBoundary()), "an unknown boundary is the automatic choice");

		// policy: a row-watermark feed needs no operation to advertise the strategy, but needs its value path
		check(MappingSynchronizationPolicy.problem(new NoStrategyConnector(), ok) == null, "a feed watermark is valid whatever the operation advertises");
		final MappingDefinition noField = feed("TIMESTAMP", "", "", "", "");
		final String problem = MappingSynchronizationPolicy.problem(new NoStrategyConnector(), noField);
		check(problem != null && problem.contains("checkpointValuePath"), "a watermark feed without the row field is refused with a clear message: " + problem);
	}

	private static SynchronizationCheckpoint stored(final SynchronizationStrategy pStrategy, final String pValue, final long pVersion)
	{
		return new SynchronizationCheckpoint(7, pStrategy, pValue, new Date(), pVersion);
	}

	private static void planning()
	{
		final MappingDefinition m = feed("TIMESTAMP", "LAST_MODIFIED", "", "", "");
		check(WatermarkRun.plan(new MappingDefinition(1, "h2", "query", MappingDefinition.Direction.INBOUND, "x", null, "DS", "/root/T", "", true), null, false) == null,
			"a mapping that is not an incremental feed has no plan");

		WatermarkRun run = WatermarkRun.plan(m, null, false);
		check(run.getStored() == null && !run.isSet(), "first run: nothing stored, not set");
		check("1900-01-01T00:00:00".equals(run.startValue()), "first run: ${watermark} is the floor: " + run.startValue());
		check(run.getStoredVersion() == 0L, "first run: version 0");
		check("LAST_MODIFIED".equals(run.getField()) && run.getKind() == Watermark.Kind.TIMESTAMP, "field and kind come from the mapping");

		run = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-31T10:15", 4L), false);
		check("2026-01-31T10:15".equals(run.getStored()) && run.isSet() && "2026-01-31T10:15".equals(run.startValue()), "stored checkpoint is in force");
		check(run.getStoredVersion() == 4L, "the row version is kept for the optimistic save");
		check(run.getNote() == null, "no note when the stored value is usable");

		// full refresh: reads as a first run, but still reports what was stored
		run = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-31T10:15", 4L), true);
		check("2026-01-31T10:15".equals(run.getStored()), "full refresh still reports the stored value as 'before'");
		check(!run.isSet() && "1900-01-01T00:00:00".equals(run.startValue()), "full refresh ignores it for reading");
		check(run.isFullRefresh(), "full refresh flag");

		// initial value: where the first run starts; not used by a full refresh
		final MappingDefinition withInitial = feed("TIMESTAMP", "LAST_MODIFIED", "2026-01-01T00:00:00", "", "");
		run = WatermarkRun.plan(withInitial, null, false);
		check(run.isSet() && "2026-01-01T00:00:00".equals(run.startValue()) && run.getStored() == null, "the initial value starts a first run");
		run = WatermarkRun.plan(withInitial, null, true);
		check(!run.isSet(), "a full refresh does not use the initial value");
		run = WatermarkRun.plan(withInitial, stored(SynchronizationStrategy.TIMESTAMP, "2026-05-01T00:00:00", 1L), false);
		check("2026-05-01T00:00:00".equals(run.startValue()), "a stored checkpoint beats the initial value");
		try
		{
			WatermarkRun.plan(feed("TIMESTAMP", "LAST_MODIFIED", "soon", "", ""), null, false);
			throw new AssertionError("an invalid initial value was accepted");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("checkpointInitialValue"), "the refusal names the field: " + expected.getMessage());
		}

		// a checkpoint saved under another strategy, or a corrupt one, is ignored with a note - never compared
		run = WatermarkRun.plan(m, stored(SynchronizationStrategy.MONOTONIC_ID, "12345", 9L), false);
		check(run.getStored() == null && !run.isSet() && run.getNote() != null && run.getNote().contains("MONOTONIC_ID"), "a checkpoint of another strategy is ignored: " + run.getNote());
		check(run.getStoredVersion() == 9L, "the version is kept so the run can replace it");
		run = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "garbage", 2L), false);
		check(run.getStored() == null && run.getNote() != null && run.getNote().contains("garbage"), "a corrupt checkpoint is ignored: " + run.getNote());
	}

	private static void placeholders()
	{
		final MappingDefinition m = feed("TIMESTAMP", "LAST_MODIFIED", "", "", "");
		final WatermarkRun first = WatermarkRun.plan(m, null, false);
		final WatermarkRun later = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-31T10:15", 1L), false);

		check(WatermarkRun.references("where x >= ${watermark}") && WatermarkRun.references("${ watermark.isSet }") && !WatermarkRun.references("${param.watermark}")
			&& !WatermarkRun.references("${./watermarkish}") && !WatermarkRun.references(null), "references() recognises the two placeholders only");

		// bound (JDBC sql): the value never appears in the text
		final String sql = "select * from CONTACT s where s.LAST_MODIFIED >= ${watermark}";
		String resolved = later.resolveInput(sql, true, PASS_THROUGH);
		check("select * from CONTACT s where s.LAST_MODIFIED >= :watermark".equals(resolved), "bound: the named parameter replaces the placeholder: " + resolved);
		check(!resolved.contains("2026-01-31"), "the checkpoint value is never concatenated into the SQL text");
		resolved = first.resolveInput(sql, true, PASS_THROUGH);
		check(resolved.endsWith(":watermark") && !resolved.contains("1900"), "first run bound: same text, the floor travels as a parameter");

		// text (REST since parameter)
		check("2026-01-31T10:15".equals(later.resolveInput("${watermark}", false, PASS_THROUGH)), "text: the checkpoint");
		check("1900-01-01T00:00:00".equals(first.resolveInput("${watermark}", false, PASS_THROUGH)), "text, first run: the floor");
		check("since=2026-01-31T10:15&t=acme".equals(later.resolveInput("since=${watermark}&t=${param.tenant}", false, PASS_THROUGH)), "the other placeholders are still resolved by the resolver");

		// isSet: a literal, valid in SQL
		final String isSetSql = "where (${watermark.isSet} = 0 or s.L >= ${watermark})";
		check("where (0 = 0 or s.L >= :watermark)".equals(first.resolveInput(isSetSql, true, PASS_THROUGH)), "isSet is 0 on a first run: " + first.resolveInput(isSetSql, true, PASS_THROUGH));
		check("where (1 = 0 or s.L >= :watermark)".equals(later.resolveInput("where (${watermark.isSet} = 0 or s.L >= ${watermark})", true, PASS_THROUGH)), "isSet is 1 once a checkpoint is in force");
		check("1".equals(later.resolveInput("${ watermark.isSet }", false, PASS_THROUGH)), "spaces inside the braces are tolerated");
		final WatermarkRun refresh = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-31T10:15", 1L), true);
		check("0".equals(refresh.resolveInput("${watermark.isSet}", false, PASS_THROUGH)), "a full refresh reads isSet = 0");

		// a resolver that erases what it does not know must not be able to erase the placeholder
		final WatermarkRun.Resolver erasing = new WatermarkRun.Resolver()
		{
			@Override
			public String resolve(final String pTemplate)
			{
				return pTemplate.replaceAll("\\$\\{[^}]*\\}", "");
			}
		};
		check("a  b".equals(later.resolveInput("a ${x} b", false, erasing)), "sanity: the erasing resolver erases unknown placeholders");
		check("v=2026-01-31T10:15".equals(later.resolveInput("v=${watermark}${x}", false, erasing)), "the watermark placeholder survives a resolver that erases the rest");

		// a stored value that itself looks like a placeholder is written as text, not resolved a second time
		final MappingDefinition s = feed("ORDERED_STRING", "code", "", "", "");
		final WatermarkRun tricky = WatermarkRun.plan(s, stored(SynchronizationStrategy.ORDERED_STRING, "${param.tenant}", 1L), false);
		check("${param.tenant}".equals(tricky.resolveInput("${watermark}", false, PASS_THROUGH)), "the value is substituted after resolution, never interpreted");
	}

	private static void inputsAndCheckpointInput()
	{
		final MappingDefinition m = feed("MONOTONIC_ID", "ID", "", "", "");
		final WatermarkRun later = WatermarkRun.plan(m, stored(SynchronizationStrategy.MONOTONIC_ID, "500", 1L), false);
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		later.resolveInput("select * from T where ID > ${watermark}", true, PASS_THROUGH);
		inputs.put("sql", "x");
		later.completeInputs(inputs, "");
		check("500".equals(inputs.get("watermark")) && "NUMBER".equals(inputs.get("watermarkType")), "a bound input adds the value and its type: " + inputs);

		final WatermarkRun textOnly = WatermarkRun.plan(m, stored(SynchronizationStrategy.MONOTONIC_ID, "500", 1L), false);
		textOnly.resolveInput("since=${watermark}", false, PASS_THROUGH);
		final Map<String, String> plain = new LinkedHashMap<String, String>();
		textOnly.completeInputs(plain, "");
		check(plain.isEmpty(), "a text-only use adds no bound parameter: " + plain);

		// checkpointInput (the pre-existing meaning): the checkpoint, when one is in force, becomes that input
		final Map<String, String> viaInput = new LinkedHashMap<String, String>();
		viaInput.put("since", "");
		later.completeInputs(viaInput, "since");
		check("500".equals(viaInput.get("since")), "the checkpoint input receives the checkpoint: " + viaInput);
		final WatermarkRun first = WatermarkRun.plan(m, null, false);
		final Map<String, String> none = new LinkedHashMap<String, String>();
		first.completeInputs(none, "since");
		check(none.isEmpty(), "with no checkpoint in force the checkpoint input is left alone (an unset 'since' means everything)");
		final WatermarkRun refresh = WatermarkRun.plan(m, stored(SynchronizationStrategy.MONOTONIC_ID, "500", 1L), true);
		final Map<String, String> refreshed = new LinkedHashMap<String, String>();
		refresh.completeInputs(refreshed, "since");
		check(refreshed.isEmpty(), "a full refresh does not send the checkpoint");
	}

	private static void advancing()
	{
		check(WatermarkRun.mayAdvance(false, "DONE", false, 0, false, false), "a clean DONE run may advance");
		check(!WatermarkRun.mayAdvance(true, "DONE", false, 0, false, false), "a dry run never advances");
		check(!WatermarkRun.mayAdvance(false, "PARTIAL", false, 0, false, false), "PARTIAL does not advance");
		check(!WatermarkRun.mayAdvance(false, "ERROR", false, 1, false, false), "ERROR does not advance");
		check(!WatermarkRun.mayAdvance(false, "STOPPED", false, 0, false, true), "STOPPED does not advance");
		check(!WatermarkRun.mayAdvance(false, "DONE", true, 0, false, false), "a run truncated by max does not advance (rows past the maximum were never read)");
		check(!WatermarkRun.mayAdvance(false, "DONE", false, 1, false, false), "a failed item does not advance, even if the status said DONE");
		check(!WatermarkRun.mayAdvance(false, "DONE", false, 0, true, false), "an exception does not advance, even if the counters said DONE (a thrown error leaves them at 0)");
		check(!WatermarkRun.mayAdvance(false, "DONE", false, 0, false, true), "a run stopped by the circuit breaker does not advance");
		check(!WatermarkRun.mayAdvance(false, "RUNNING", false, 0, false, false), "only DONE");

		final MappingDefinition m = feed("TIMESTAMP", "LAST_MODIFIED", "", "", "");
		final WatermarkRun run = WatermarkRun.plan(m, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-05T00:00:00", 3L), false);
		final WatermarkFilter filter = run.filter(new Connector.RowSink() { @Override public boolean accept(final JsonNode r) { return true; } }, null);
		check(run.next(filter) == null, "no row seen: the checkpoint stays");
		accept(filter, "2026-01-09T00:00:00");
		accept(filter, "2026-01-07T00:00:00");
		check("2026-01-09T00:00:00".equals(run.next(filter)), "the highest value seen is the next checkpoint: " + run.next(filter));
		check(run.next(null) == null, "no filter, no advance");
	}

	/** Three successive runs over a source that cannot filter (a file): first, then a change, then nothing new; plus a failed run and a full refresh. */
	private static void successiveRuns() throws Exception
	{
		final MappingDefinition m = feed("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash", "");
		final List<String> source = new ArrayList<String>();
		source.add("2026-01-01T08:00");
		source.add("2026-01-02T09:00");
		source.add("2026-01-03T10:00");

		SynchronizationCheckpoint cp = null;
		// run 1: first run, everything is read; the checkpoint becomes the highest value
		Result r = run(m, cp, false, source, "DONE", false);
		check(r.delivered == 3 && r.skipped == 0, "first run reads the three rows: " + r);
		check("2026-01-03T10:00".equals(r.after), "first run: checkpoint = highest value: " + r.after);
		cp = stored(SynchronizationStrategy.TIMESTAMP, r.after, 1L);

		// run 2: two rows changed and one is new; the unchanged one is skipped before any transformation
		source.set(1, "2026-01-04T09:00");
		source.add("2026-01-05T00:00");
		r = run(m, cp, false, source, "DONE", false);
		check(r.skipped == 1, "the row below the checkpoint is skipped: " + r);
		check(r.delivered == 3, "inclusive (unchangedIfEqual set): the row EQUAL to the checkpoint is read again with the two new ones: " + r);
		check("2026-01-05T00:00".equals(r.after), "checkpoint advanced: " + r.after);
		cp = stored(SynchronizationStrategy.TIMESTAMP, r.after, 2L);

		// run 3: nothing new; only the row at the boundary is re-read and the checkpoint stays
		r = run(m, cp, false, source, "DONE", false);
		check(r.delivered == 1 && r.skipped == 3, "nothing new: only the boundary row is read again: " + r);
		check(r.after == null, "the checkpoint does not move when nothing is above it: " + r.after);

		// run 4: the run fails: the checkpoint keeps its value even though the rows had higher watermarks
		source.add("2026-02-01T00:00");
		r = run(m, cp, false, source, "PARTIAL", false);
		check(r.after == null && !r.advance, "a run that did not end DONE keeps the old checkpoint: " + r);

		// run 5: full refresh reads everything and resets the checkpoint to what the source holds now
		source.remove(source.size() - 1);
		r = run(m, cp, true, source, "DONE", false);
		check(r.delivered == 4 && r.skipped == 0, "full refresh reads every row: " + r);
		check("2026-01-05T00:00".equals(r.after) && r.advance, "and resets the checkpoint: " + r);

		// the same source with an exclusive boundary
		final MappingDefinition exclusive = feed("TIMESTAMP", "LAST_MODIFIED", "", "", "");
		r = run(exclusive, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-05T00:00", 2L), false, source, "DONE", false);
		check(r.delivered == 0 && r.skipped == 4, "exclusive with no unchangedIfEqual: nothing is re-read: " + r);
		check(r.noRow, "and the empty run says so");

		// the initial value bounds a first run
		final MappingDefinition bounded = feed("TIMESTAMP", "LAST_MODIFIED", "2026-01-04T00:00", "", "");
		r = run(bounded, null, false, source, "DONE", false);
		check(r.delivered == 2 && r.skipped == 2, "the initial value bounds the first run: " + r);
	}

	private static Result run(final MappingDefinition pMapping, final SynchronizationCheckpoint pStored, final boolean pFullRefresh,
		final List<String> pSource, final String pFinalStatus, final boolean pFailed) throws Exception
	{
		final WatermarkRun plan = WatermarkRun.plan(pMapping, pStored, pFullRefresh);
		final int[] delivered = new int[1];
		final ImportOutcome outcome = new ImportOutcome();
		outcome.setWatermark(plan.getField(), plan.getStored(), plan.isFullRefresh(), plan.getNote());
		final WatermarkFilter filter = plan.filter(new Connector.RowSink()
		{
			@Override
			public boolean accept(final JsonNode pRow)
			{
				delivered[0]++;
				return true;
			}
		}, outcome);
		for (final String value : pSource)
		{
			filter.accept(Json.parse("{\"LAST_MODIFIED\":\"" + value + "\"}"));
		}
		final Result r = new Result();
		r.delivered = delivered[0];
		r.skipped = outcome.getSkippedByWatermark();
		r.advance = WatermarkRun.mayAdvance(false, pFinalStatus, false, "ERROR".equals(pFinalStatus) ? 1 : 0, pFailed, false);
		r.after = r.advance ? plan.next(filter) : null;
		r.noRow = delivered[0] == 0 && filter.getSeen() > 0;
		if (r.noRow)
		{
			check(plan.describeEmpty(filter).contains("No row past the watermark"), "the empty-run message: " + plan.describeEmpty(filter));
		}
		return r;
	}

	private static final class Result
	{
		int delivered;
		int skipped;
		boolean advance;
		boolean noRow;
		String after;

		@Override
		public String toString()
		{
			return "{delivered=" + this.delivered + ", skipped=" + this.skipped + ", advance=" + this.advance + ", after=" + this.after + "}";
		}
	}

	private static void accept(final WatermarkFilter pFilter, final String pValue)
	{
		try
		{
			pFilter.accept(Json.parse("{\"LAST_MODIFIED\":\"" + pValue + "\"}"));
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
	}

	/** A connector that advertises FULL only for its "query" operation. */
	private static final class NoStrategyConnector implements Connector
	{
		@Override public String getType() { return "h2"; }
		@Override public String getLabel() { return "H2"; }
		@Override public List<com.on.ebx.connect.spi.Operation> getOperations()
		{
			final List<com.on.ebx.connect.spi.Operation> ops = new ArrayList<com.on.ebx.connect.spi.Operation>();
			ops.add(com.on.ebx.connect.spi.Operation.search("query", "Query", new String[0]));
			return ops;
		}
		@Override public void configure(final com.on.ebx.connect.spi.ConnectorConfig pConfig) { }
		@Override public JsonNode execute(final com.on.ebx.connect.spi.ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
			final JsonNode pPayload) { return null; }
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
