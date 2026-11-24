package com.on.ebx.connect.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure helpers of the work item notifications ({@code ebx/workflow/ChatWorkItemTrigger}): the link that opens one
 * work item in the EBX inbox, the Slack Block Kit messages, and the {@code channel;ts} state kept in the sync log so
 * that the message can be edited when the work item ends. No EBX repository needed.
 * <p>
 * The link is what {@code UIHttpManagerComponent.selectWorkItem} builds: {@code service=@workflow},
 * {@code workflowView=inbox} and a predicate on the work item key {@code pnode|process|itemIndex}, where
 * {@code process} is everything between the first and the last {@code |} (verified in a browser on 2026-09-16).
 */
public final class WorkItemNotices
{
	/** Run id of the log rows of one work item (SyncLog runId is at most 160 characters). */
	public static final String RUN_PREFIX = "workitem:";

	private WorkItemNotices()
	{
	}

	public static String runId(final String pWorkItemKey)
	{
		final String id = WorkItemNotices.RUN_PREFIX + pWorkItemKey;
		return id.length() > 160 ? id.substring(0, 160) : id;
	}

	/** {@code <base>/ebx/?service=@workflow&workflowView=inbox&xpath=[...]&startWorkItem=...} */
	public static String link(final String pBaseUrl, final String pWorkItemKey, final boolean pStart)
	{
		final String key = pWorkItemKey == null ? "" : pWorkItemKey.trim();
		final int first = key.indexOf('|');
		final int last = key.lastIndexOf('|');
		if (first <= 0 || last <= first || last == key.length() - 1 || key.indexOf('\'') >= 0)
		{
			throw new IllegalArgumentException("Not a work item key: " + pWorkItemKey);
		}
		final String predicate = "[./pnode='" + key.substring(0, first) + "' and ./process='" + key.substring(first + 1, last)
			+ "' and ./itemIndex='" + key.substring(last + 1) + "']";
		String base = pBaseUrl == null || pBaseUrl.trim().isEmpty() ? "http://localhost:8080" : pBaseUrl.trim();
		while (base.endsWith("/"))
		{
			base = base.substring(0, base.length() - 1);
		}
		return base + "/ebx/?service=%40workflow&workflowView=inbox&xpath=" + WorkItemNotices.encode(predicate) + "&startWorkItem=" + pStart;
	}

	static String encode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue, "UTF-8").replace("+", "%20");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/** The three characters Slack reads as markup, in text written into blocks. */
	public static String escape(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static boolean french(final Locale pLocale)
	{
		return pLocale != null && "fr".equals(pLocale.getLanguage());
	}

	/** Plain-text fallback of the offered message (notification text, and the whole message on a chat webhook). */
	public static String offeredText(final String pTitle, final String pWorkflow, final Locale pLocale)
	{
		return (WorkItemNotices.french(pLocale) ? "Nouvelle tâche : " : "New task: ") + pTitle + (pWorkflow == null || pWorkflow.isEmpty() ? "" : " (" + pWorkflow + ")");
	}

	/** Title with the link, description, then who it is offered to and in which workflow. */
	public static ArrayNode offeredBlocks(final String pTitle, final String pDescription, final String pWorkflow, final String pOfferedTo, final String pUrl,
		final Locale pLocale)
	{
		final boolean fr = WorkItemNotices.french(pLocale);
		final ArrayNode blocks = Json.mapper().createArrayNode();
		// the title is bold text, never a link: Slack does not render a link wrapped in formatting
		// markers reliably (*<url|label>* opened a broken address, measured 2026-09-16), so the one
		// link of the message is the explicit line below
		final StringBuilder main = new StringBuilder("*").append(WorkItemNotices.escape(pTitle)).append('*');
		if (pDescription != null && !pDescription.trim().isEmpty())
		{
			main.append('\n').append(WorkItemNotices.escape(pDescription.trim()));
		}
		main.append("\n<").append(pUrl).append('|').append(fr ? "Ouvrir la tâche dans EBX" : "Open the task in EBX").append('>');
		WorkItemNotices.section(blocks, main.toString());
		WorkItemNotices.context(blocks, WorkItemNotices.details(pWorkflow, pOfferedTo, fr));
		return blocks;
	}

	/** Same title without the link, and the outcome instead of the link. */
	public static ArrayNode closedBlocks(final String pTitle, final String pWorkflow, final String pOutcome)
	{
		final ArrayNode blocks = Json.mapper().createArrayNode();
		WorkItemNotices.section(blocks, "*" + WorkItemNotices.escape(pTitle) + "*\n" + WorkItemNotices.escape(pOutcome));
		if (pWorkflow != null && !pWorkflow.isEmpty())
		{
			WorkItemNotices.context(blocks, WorkItemNotices.escape(pWorkflow));
		}
		return blocks;
	}

	/** "Accepted by Ana", "Rejected by Ana" or "Completed", with a mark. */
	public static String outcome(final boolean pAccepted, final boolean pRejected, final String pUser, final Locale pLocale)
	{
		final boolean fr = WorkItemNotices.french(pLocale);
		final String by = pUser == null || pUser.trim().isEmpty() ? "" : (fr ? " par " : " by ") + pUser.trim();
		if (pAccepted)
		{
			return "✅ " + (fr ? "Acceptée" : "Accepted") + by;
		}
		if (pRejected)
		{
			return "❌ " + (fr ? "Rejetée" : "Rejected") + by;
		}
		return "☑️ " + (fr ? "Terminée" : "Completed") + by;
	}

	private static String details(final String pWorkflow, final String pOfferedTo, final boolean pFrench)
	{
		final StringBuilder b = new StringBuilder();
		if (pOfferedTo != null && !pOfferedTo.isEmpty())
		{
			b.append(pFrench ? "Offerte à " : "Offered to ").append(WorkItemNotices.escape(pOfferedTo));
		}
		if (pWorkflow != null && !pWorkflow.isEmpty())
		{
			b.append(b.length() == 0 ? "" : " · ").append(pFrench ? "Workflow " : "Workflow ").append(WorkItemNotices.escape(pWorkflow));
		}
		return b.toString();
	}

	private static void section(final ArrayNode pBlocks, final String pMarkdown)
	{
		final ObjectNode section = pBlocks.addObject();
		section.put("type", "section");
		section.putObject("text").put("type", "mrkdwn").put("text", pMarkdown.length() > 3000 ? pMarkdown.substring(0, 2997) + "..." : pMarkdown);
	}

	private static void context(final ArrayNode pBlocks, final String pMarkdown)
	{
		if (pMarkdown == null || pMarkdown.isEmpty())
		{
			return;
		}
		final ObjectNode context = pBlocks.addObject();
		context.put("type", "context");
		context.putArray("elements").addObject().put("type", "mrkdwn").put("text", pMarkdown);
	}

	/** What the sync log keeps to find the message again. */
	public static String state(final String pChannel, final String pTs)
	{
		return "channel=" + pChannel + ";ts=" + pTs;
	}

	/** {channel, ts}, or null when the text is not a state written by {@link #state}. */
	public static String[] parseState(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		String channel = null;
		String ts = null;
		for (final String part : pText.trim().split(";"))
		{
			if (part.startsWith("channel="))
			{
				channel = part.substring("channel=".length()).trim();
			}
			else if (part.startsWith("ts="))
			{
				ts = part.substring("ts=".length()).trim();
			}
		}
		return channel == null || channel.isEmpty() || ts == null || ts.isEmpty() ? null : new String[] { channel, ts };
	}
}
