package com.on.ebx.connect.ebx.mcp;

import java.util.Arrays;
import java.util.Collections;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;

/**
 * Audit C-35: what {@code connectors_sync} and {@code POST /sync} add to their answer ({@link ConnectorsTools#putNotices}).
 * An ignored run-time input is listed, said in {@code message} and turns a plain OK into WARNING; a run cut at max says
 * its sentence next to {@code truncated}; both together are both said; a run with nothing to say is left as it was, so
 * the fields the MDM Factory reads ({@code status}, {@code count}, {@code ok}, {@code errors}, {@code truncated}) keep
 * their meaning.
 */
public final class ConnectorsToolsNoticesTest
{
	private ConnectorsToolsNoticesTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		// nothing to say: the answer is untouched
		final ObjectNode clean = answer("OK", false);
		ConnectorsTools.putNotices(clean, Collections.<String> emptyList(), null);
		check("OK".equals(clean.path("status").asText()) && !clean.has("warning") && !clean.has("message") && !clean.has("ignoredInputs"),
			"a clean run is left as it was: " + clean);

		// an ignored input: listed, said, and OK becomes WARNING
		final ObjectNode ignored = answer("OK", false);
		ConnectorsTools.putNotices(ignored, Arrays.asList("loadId"), null);
		check("WARNING".equals(ignored.path("status").asText()), "OK with an ignored input reads WARNING: " + ignored);
		check(ignored.path("warning").asBoolean(false) && ignored.path("ignoredInputs").size() == 1 && "loadId".equals(ignored.path("ignoredInputs").get(0).asText()),
			"the input is listed: " + ignored);
		check(ignored.path("message").asText().contains("loadId") && ignored.path("warnings").size() == 1, "and said in message: " + ignored.path("message"));
		check(ignored.path("count").asInt() == 3 && ignored.path("errors").asInt() == 0, "the counters are not touched");

		// a cut run: PARTIAL stays PARTIAL, its sentence is said
		final ObjectNode cut = answer("PARTIAL", true);
		ConnectorsTools.putNotices(cut, null, "Limit reached: 200 record(s) of scope ALL were taken (max=200) and more remain: raise max to process the others");
		check("PARTIAL".equals(cut.path("status").asText()) && cut.path("truncated").asBoolean(false), "a cut run stays PARTIAL and truncated");
		check(cut.path("warning").asBoolean(false) && cut.path("message").asText().startsWith("Limit reached: 200"), "and says why: " + cut.path("message"));

		// both: both sentences, neither hides the other
		final ObjectNode both = answer("PARTIAL", true);
		ConnectorsTools.putNotices(both, Arrays.asList("region", "loadId"), "Limit reached: 499 of the 2590 items returned were imported");
		check(both.path("warnings").size() == 2 && both.path("message").asText().contains("region, loadId") && both.path("message").asText().contains("Limit reached"),
			"an ignored input and a cut are both said: " + both.path("message"));
		check("PARTIAL".equals(both.path("status").asText()), "PARTIAL is never turned into WARNING");

		// an asynchronous start: RUNNING is kept (the MDM Factory waits for exactly that word)
		final ObjectNode running = Json.object();
		running.put("runId", "R1");
		running.put("status", "RUNNING");
		ConnectorsTools.putNotices(running, Arrays.asList("loadId"), null);
		check("RUNNING".equals(running.path("status").asText()) && running.path("warning").asBoolean(false), "a started run keeps RUNNING and still warns");

		// a limit message on a run that was not truncated is not said
		final ObjectNode notCut = answer("OK", false);
		ConnectorsTools.putNotices(notCut, null, "Limit reached: should not appear");
		check(!notCut.has("message"), "only a truncated run says its limit");

		// the tool says what max and inputs do
		final String description = ConnectorsTools.D_SYNC;
		check(description.contains("max (default 200)") && description.contains("truncated=true") && description.contains("ignoredInputs") && description.contains("WARNING"),
			"connectors_sync describes max, the cut and the ignored inputs");
		check(ConnectorsTools.D_UPSERT.contains("policyDefaulted") && ConnectorsTools.D_UPSERT.contains("rulePolicies") && ConnectorsTools.D_UPSERT.contains("ALWAYS"),
			"connectors_mapping_upsert describes the policies and what its answer says");

		System.out.println("PASS: connectors_sync and POST /sync say what they ignored and where they stopped (audit C-35)");
	}

	private static ObjectNode answer(final String pStatus, final boolean pTruncated)
	{
		final ObjectNode out = Json.object();
		out.put("runId", "R1");
		out.put("status", pStatus);
		out.put("count", 3);
		out.put("ok", 3);
		out.put("errors", 0);
		out.put("truncated", pTruncated);
		return out;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
