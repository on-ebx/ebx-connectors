package com.on.ebx.connect.ebx.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingDefinition;

/**
 * Wave 13: {@code connectors_sync(scope=..., async=true)} on an outbound mapping. What can be checked without a repository: which
 * calls {@code async} is accepted for (and the sentence the MDM Factory recognises when it is not), the shape of the start answer,
 * what {@code connectors_run_get} adds for an outbound run (direction and mapping), and what the tool descriptions promise.
 */
public final class ConnectorsToolsAsyncSendTest
{
	private static int checks;

	public static void main(final String[] pArgs)
	{
		final MappingDefinition.Direction out = MappingDefinition.Direction.OUTBOUND;
		final MappingDefinition.Direction in = MappingDefinition.Direction.INBOUND;

		// accepted: an outbound mapping, a scope, no key, a real run (a predicate narrows the table and is allowed: it is not an argument here)
		yes(ConnectorsTools.scopeAsyncRefusal(true, false, out, false) == null, "an outbound mapping sent on a scope may run asynchronously");

		// refused with dryRun: the same sentence the feed gives
		eq("async cannot be combined with dryRun", ConnectorsTools.scopeAsyncRefusal(true, false, out, true), "async with dryRun is refused");

		// refused: no scope, a key, an inbound mapping - with the sentence the Factory recognises to fall back to the synchronous call
		for (final String refusal : new String[] { ConnectorsTools.scopeAsyncRefusal(false, false, out, false), ConnectorsTools.scopeAsyncRefusal(true, true, out, false),
			ConnectorsTools.scopeAsyncRefusal(true, false, in, false) })
		{
			yes(ConnectorsTools.ASYNC_REFUSAL.equals(refusal), "refused with the module's async sentence: " + refusal);
		}
		yes(ConnectorsTools.ASYNC_REFUSAL.toLowerCase().contains("async only applies") && ConnectorsTools.ASYNC_REFUSAL.contains("inbound feed"),
			"the refusal keeps the words the MDM Factory's SendRuns.asyncRefused looks for");
		yes(ConnectorsTools.ASYNC_REFUSAL.contains("outbound mapping sent on a scope"), "and now says what is accepted: " + ConnectorsTools.ASYNC_REFUSAL);

		// the start answer: what the Factory's isAsyncStart reads (runId, status RUNNING, no count)
		final ObjectNode start = ConnectorsTools.asyncSendStart("R20261012-101500-0001", 417, "ALL");
		eq("R20261012-101500-0001", start.path("runId").asText(), "start: runId");
		eq("RUNNING", start.path("status").asText(), "start: status RUNNING");
		yes(start.path("async").asBoolean() && !start.has("count"), "start: async true and no count (the shape of an acknowledgement, not of a finished send)");
		eq(417, start.path("mapping").asInt(), "start: mapping");
		eq("OUTBOUND", start.path("direction").asText(), "start: direction");
		eq("ALL", start.path("scope").asText(), "start: scope");
		yes(start.path("nextStep").asText().contains("connectors_run_get") && start.path("nextStep").asText().contains("connectors_run_stop"), "start: says how to follow and stop it");
		// ignored inputs are still said, RUNNING kept (the same putNotices as the feed)
		ConnectorsTools.putNotices(start, java.util.Arrays.asList("loadId"), null);
		yes("RUNNING".equals(start.path("status").asText()) && start.path("warning").asBoolean(false), "an ignored input warns without hiding RUNNING");

		// connectors_run_get on an outbound run: direction and mapping at the top and in execution{}
		final MappingDefinition send = new MappingDefinition(417, "Kafka", "produce", out, "Distribution", "Master", "Master", "/root/T", "", false);
		final ObjectNode answer = Json.object();
		final ObjectNode execution = answer.putObject("execution");
		execution.put("status", "RUNNING");
		ConnectorsTools.putDirection(answer, execution, send);
		eq("OUTBOUND", answer.path("direction").asText(), "run_get: direction OUTBOUND at the top");
		eq(417, answer.path("mapping").asInt(), "run_get: the mapping id at the top");
		eq("OUTBOUND", execution.path("direction").asText(), "run_get: direction in execution{}");
		eq("RUNNING", execution.path("status").asText(), "run_get: the rest of execution{} is untouched");
		final MappingDefinition feed = new MappingDefinition(5, "Kafka", "consume", in, "Feed", "Master", "Master", "/root/T", "", true);
		final ObjectNode feedAnswer = Json.object();
		final ObjectNode feedExecution = feedAnswer.putObject("execution");
		ConnectorsTools.putDirection(feedAnswer, feedExecution, feed);
		eq("INBOUND", feedAnswer.path("direction").asText(), "run_get: a feed import reads INBOUND");
		final ObjectNode gone = Json.object();
		final ObjectNode goneExecution = gone.putObject("execution");
		ConnectorsTools.putDirection(gone, goneExecution, null);
		yes(!gone.has("direction") && !goneExecution.has("direction"), "run_get: a run whose mapping is gone says no direction rather than a guess");

		// the tool descriptions say it
		yes(ConnectorsTools.D_SYNC.contains("outbound mapping sent on a scope") && ConnectorsTools.D_SYNC.contains("direction:OUTBOUND")
			&& ConnectorsTools.D_SYNC.contains("scope=changed send does not resend them"), "connectors_sync describes the asynchronous send");
		yes(ConnectorsTools.D_RUN.contains("outbound scope send") && ConnectorsTools.D_RUN.contains("updated = records sent") && ConnectorsTools.D_RUN.contains("direction"),
			"connectors_run_get describes the counters of a send");
		yes(ConnectorsTools.D_STOP.contains("outbound scope send") && ConnectorsTools.D_STOP.contains("keep their outbound state"), "connectors_run_stop describes the send");

		System.out.println("PASS: asynchronous outbound send: accepted for a scoped outbound mapping, refused with dryRun, start answer, run_get direction (" + checks + " checks)");
	}

	private static void eq(final Object pExpected, final Object pActual, final String pMessage)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pMessage + " (expected " + pExpected + ", got " + pActual + ")");
	}

	private static void yes(final boolean pCondition, final String pMessage)
	{
		checks++;
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
