package com.on.ebx.connect.core;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Work item notifications without EBX: the inbox link built from a REST work item key exactly as the one verified
 * in a browser on 2026-09-16, Slack markup escaped in the blocks, the outcome line, and the channel/ts state read
 * back from the sync log.
 */
public final class WorkItemNoticesTest
{
	private WorkItemNoticesTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		final String key = "1|V2026-09-15T01:58:44.696|CatalogTagReview|504|0";
		final String verified = "http://localhost:8080/ebx/?service=%40workflow&workflowView=inbox&xpath=%5B.%2Fpnode%3D%271%27%20and%20.%2Fprocess%3D%27"
			+ "V2026-09-15T01%3A58%3A44.696%7CCatalogTagReview%7C504%27%20and%20.%2FitemIndex%3D%270%27%5D&startWorkItem=false";
		check(verified.equals(WorkItemNotices.link("http://localhost:8080/", key, false)), "link identical to the verified one: " + WorkItemNotices.link("http://localhost:8080/", key, false));
		check(WorkItemNotices.link("https://ebx.acme.com", key, true).startsWith("https://ebx.acme.com/ebx/?") && WorkItemNotices.link(null, key, true).endsWith("&startWorkItem=true"),
			"public base URL and startWorkItem");
		for (final String bad : new String[] { "", "504", "1|2", "a|b|", "|b|c", "1|x'y|0" })
		{
			try
			{
				WorkItemNotices.link("http://h", bad, false);
				throw new AssertionError("accepted a bad key: " + bad);
			}
			catch (final IllegalArgumentException expected)
			{
				// refused
			}
		}

		final JsonNode offered = WorkItemNotices.offeredBlocks("Review <tags> & values", "Check the suggestions", "Data catalog: tag review", "administrator",
			"http://h/ebx/?x=1", Locale.ENGLISH);
		final String main = offered.get(0).path("text").path("text").asText();
		check(main.startsWith("*Review &lt;tags&gt; &amp; values*\nCheck the suggestions\n<http://h/ebx/?x=1|Open the task in EBX>"), "plain bold title, escaped, and one link: " + main);
		check(main.indexOf("<http://h/ebx/?x=1|") == main.lastIndexOf("<http://h/ebx/?x=1|"), "exactly one link: a link wrapped in * is not rendered reliably by Slack");
		check("Offered to administrator · Workflow Data catalog: tag review".equals(offered.get(1).path("elements").get(0).path("text").asText()), "context line");
		check(WorkItemNotices.offeredBlocks("T", null, null, null, "u", Locale.FRENCH).size() == 1, "no context line without offer or workflow");
		check(WorkItemNotices.offeredText("Revoir", "Catalogue", Locale.FRENCH).equals("Nouvelle tâche : Revoir (Catalogue)"), "French fallback text");

		check(WorkItemNotices.outcome(true, false, "Ana", Locale.ENGLISH).endsWith("Accepted by Ana"), "accepted");
		check(WorkItemNotices.outcome(false, true, "Ana", Locale.FRENCH).endsWith("Rejetée par Ana"), "rejected in French");
		check(WorkItemNotices.outcome(false, false, null, Locale.ENGLISH).endsWith("Completed"), "completed without a user");
		final JsonNode closed = WorkItemNotices.closedBlocks("Review <tags>", "Wf", "Accepted by Ana");
		check(closed.get(0).path("text").path("text").asText().equals("*Review &lt;tags&gt;*\nAccepted by Ana") && !closed.toString().contains("Open the task"), "closed message has no link");

		final String[] state = WorkItemNotices.parseState(WorkItemNotices.state("C0C2589KETT", "1789508277.586659"));
		check(state != null && "C0C2589KETT".equals(state[0]) && "1789508277.586659".equals(state[1]), "state read back");
		check(WorkItemNotices.parseState("sent") == null && WorkItemNotices.parseState(null) == null && WorkItemNotices.parseState("channel=C1;ts=") == null, "no state in other messages");
		check(WorkItemNotices.runId(key).equals("workitem:" + key) && WorkItemNotices.runId(repeat("x", 300)).length() == 160, "run id bounded to 160");
		System.out.println("PASS: work item notices (inbox link, Slack blocks, outcome, message state)");
	}

	private static String repeat(final String pText, final int pCount)
	{
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < pCount; i++)
		{
			b.append(pText);
		}
		return b.toString();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
