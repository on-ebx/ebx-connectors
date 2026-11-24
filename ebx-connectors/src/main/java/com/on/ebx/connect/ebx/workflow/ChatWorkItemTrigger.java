package com.on.ebx.connect.ebx.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.core.WorkItemNotices;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.directory.DirectoryHandler;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.workflow.UserTask;
import com.orchestranetworks.workflow.WorkflowTriggerAfterProcessInstanceStartContext;
import com.orchestranetworks.workflow.WorkflowTriggerAfterWorkItemCreationContext;
import com.orchestranetworks.workflow.WorkflowTriggerBean;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeProcessInstanceTerminationContext;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeWorkItemAllocationContext;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeWorkItemDeallocationContext;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeWorkItemReallocationContext;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeWorkItemStartContext;
import com.orchestranetworks.workflow.WorkflowTriggerBeforeWorkItemTerminationContext;
import com.orchestranetworks.workflow.WorkflowTriggerContext;

/**
 * Workflow trigger that posts every new work item to a chat channel with a link that opens it in the EBX inbox, and
 * edits that message when the work item ends ("Accepted by Ana"). Set it as the {@code workflowTrigger} of a
 * workflow model ({@code ebx-connectors@com.on.ebx.connect.ebx.workflow.ChatWorkItemTrigger}); declared in module.xml.
 * <p>
 * The connector is {@code Slack} ({@code -Debx.connectors.workItemNotifier.connector} for another id). Its
 * properties drive the messages: {@code workflow.channel} (default: the connector's {@code channel}),
 * {@code workflow.channel.<role>} (channel of the work items offered to that role, e.g.
 * {@code workflow.channel.Steward = #stewards}), {@code workflow.baseUrl} (the address users open EBX with, default
 * {@code http://localhost:8080}) and {@code workflow.startWorkItem} (default true: the link takes the work item).
 * A {@code chat-webhook} connector gets the text and the link, without the later edit.
 * <p>
 * The decision stays in EBX, made by the user in their own session. A notification failure never stops the
 * workflow: it is logged (Connectors sync log, run id {@code workitem:<key>}, and the kernel log).
 */
public final class ChatWorkItemTrigger extends WorkflowTriggerBean
{
	public static final String CONNECTOR_PROPERTY = "ebx.connectors.workItemNotifier.connector";
	private static final String ORIGIN = "workflow trigger";
	private static final Path LOG_STATUS = Path.parse("./status");
	private static final Path LOG_MESSAGE = Path.parse("./message");
	private static final Path LOG_OPERATION = Path.parse("./operation");

	@Override
	public void handleAfterWorkItemCreation(final WorkflowTriggerAfterWorkItemCreationContext pContext) throws OperationException
	{
		final Notice notice = Notice.open(pContext, pContext.getWorkItem());
		if (notice == null)
		{
			return;
		}
		final UserTask.WorkItem item = pContext.getWorkItem();
		final Locale locale = notice.locale;
		final String description = ChatWorkItemTrigger.text(item.getDescription(), locale);
		final String offeredTo = ChatWorkItemTrigger.offeredTo(pContext.getRepository(), item, locale);
		final String url = WorkItemNotices.link(notice.config.getProperty("workflow.baseUrl", "http://localhost:8080"), notice.key,
			!"false".equalsIgnoreCase(notice.config.getProperty("workflow.startWorkItem", "true").trim()));
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		final String channel = ChatWorkItemTrigger.channel(notice.config, item);
		if (channel != null)
		{
			inputs.put("channel", channel);
		}
		if (notice.slack)
		{
			inputs.put("text", WorkItemNotices.offeredText(notice.title, notice.workflow, locale));
			inputs.put("blocks", Json.write(WorkItemNotices.offeredBlocks(notice.title, description, notice.workflow, offeredTo, url, locale)));
		}
		else
		{
			inputs.put("title", WorkItemNotices.offeredText(notice.title, notice.workflow, locale));
			inputs.put("text", (description == null ? "" : description + "\n") + url);
		}
		notice.run(notice.slack ? "post" : "notify", inputs);
	}

	@Override
	public void handleBeforeWorkItemTermination(final WorkflowTriggerBeforeWorkItemTerminationContext pContext) throws OperationException
	{
		final Notice notice = Notice.open(pContext, pContext.getWorkItem());
		if (notice == null || !notice.slack)
		{
			return; // a webhook cannot edit what it posted
		}
		final UserTask.WorkItem item = pContext.getWorkItem();
		final UserReference user = item.getUserReference();
		String who = null;
		if (user != null)
		{
			try
			{
				who = DirectoryHandler.getInstance(pContext.getRepository()).displayUser(user, notice.locale);
			}
			catch (final RuntimeException ex)
			{
				who = user.getUserId();
			}
		}
		final String outcome = WorkItemNotices.outcome(pContext.isAccepted(), !pContext.isAccepted() && item.isRejected(), who, notice.locale);
		final String[] posted = notice.postedMessage();
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("text", notice.title + " - " + outcome);
		inputs.put("blocks", Json.write(WorkItemNotices.closedBlocks(notice.title, notice.workflow, outcome)));
		if (posted != null)
		{
			inputs.put("channel", posted[0]);
			inputs.put("ts", posted[1]);
			notice.run("update", inputs);
		}
		else
		{
			// message not found (never posted, log purged): say it in the channel rather than nothing
			final String channel = ChatWorkItemTrigger.channel(notice.config, item);
			if (channel != null)
			{
				inputs.put("channel", channel);
			}
			notice.run("post", inputs);
		}
	}

	/** One notification: the connector, the work item and how to log the call. */
	private static final class Notice
	{
		final Repository repository;
		final Session session;
		final ConnectorRepository connectors;
		final ConnectorConfig config;
		final String key;
		final String title;
		final String workflow;
		final Locale locale;
		final boolean slack;

		private Notice(final WorkflowTriggerContext pContext, final ConnectorRepository pConnectors, final ConnectorConfig pConfig, final String pKey,
			final String pTitle, final Locale pLocale)
		{
			this.repository = pContext.getRepository();
			this.session = pContext.getSession();
			this.connectors = pConnectors;
			this.config = pConfig;
			this.key = pKey;
			this.title = pTitle;
			this.locale = pLocale;
			String label = null;
			try
			{
				final com.orchestranetworks.workflow.ProcessInstance process = pContext.getProcessInstance();
				label = process == null ? null : ChatWorkItemTrigger.text(process.getLabel(), pLocale);
			}
			catch (final RuntimeException ex)
			{
				// label unavailable: the message goes without it
			}
			this.workflow = label;
			this.slack = "slack".equals(pConfig.getType());
		}

		/** Null when there is nothing to do: connector absent or inactive, or an unexpected failure (logged). */
		static Notice open(final WorkflowTriggerContext pContext, final UserTask.WorkItem pItem)
		{
			final String id = System.getProperty(ChatWorkItemTrigger.CONNECTOR_PROPERTY, "Slack").trim();
			try
			{
				final ConnectorRepository connectors = ConnectorService.get().repository(pContext.getRepository());
				final ConnectorConfig config = connectors.connector(id);
				if (config == null || !config.isActive())
				{
					LoggingCategory.getKernel().info("[connectors] work item notification skipped: connector '" + id + "' missing or inactive");
					return null;
				}
				final Locale locale = pContext.getSession() != null && pContext.getSession().getLocale() != null ? pContext.getSession().getLocale()
					: pContext.getRepository().getDefaultLocale();
				String title = ChatWorkItemTrigger.text(pItem.getSpecificLabel(), locale);
				if (title == null)
				{
					title = ChatWorkItemTrigger.text(pItem.getLabel(), locale);
				}
				return new Notice(pContext, connectors, config, pItem.getWorkItemKey().format(), title == null ? pItem.getWorkItemKey().format() : title, locale);
			}
			catch (final RuntimeException ex)
			{
				LoggingCategory.getKernel().warn("[connectors] work item notification skipped: " + ex);
				return null;
			}
		}

		/** Runs the operation; never throws: a notification must not stop the workflow. */
		void run(final String pOperation, final Map<String, String> pInputs)
		{
			final SyncResult result = new SyncResult(this.config.getId(), pOperation, "OUTBOUND", null, null, this.key, this.title);
			result.setRunId(WorkItemNotices.runId(this.key));
			final long started = System.currentTimeMillis();
			try
			{
				final Connector connector = ConnectorRegistry.get().connector(this.config.getId(), this.connectors);
				final JsonNode answer = connector.execute(new ConnectorContext(this.session, this.repository, null, this.connectors.dataset()), pOperation, pInputs, null);
				result.setStatus(SyncResult.Status.OK);
				result.setMessage(answer != null && answer.hasNonNull("ts") ? WorkItemNotices.state(answer.path("channel").asText(), answer.path("ts").asText())
					: "sent");
			}
			catch (final Exception ex)
			{
				result.setStatus(SyncResult.Status.ERROR);
				result.setMessage(ex.getMessage() == null ? ex.toString() : ex.getMessage());
				LoggingCategory.getKernel().warn("[connectors] work item notification failed (" + this.key + ", " + pOperation + "): " + result.getMessage());
			}
			result.setDurationMs(System.currentTimeMillis() - started);
			this.connectors.log(this.session, result, ChatWorkItemTrigger.ORIGIN);
		}

		/** {channel, ts} of the message posted for this work item, from the sync log; null when not found. */
		String[] postedMessage()
		{
			final List<Adaptation> rows = new ArrayList<Adaptation>(this.connectors.logOfRun(WorkItemNotices.runId(this.key)));
			for (int i = rows.size() - 1; i >= 0; i--)
			{
				final Adaptation row = rows.get(i);
				if ("OK".equals(row.getString(ChatWorkItemTrigger.LOG_STATUS)) && "post".equals(row.getString(ChatWorkItemTrigger.LOG_OPERATION)))
				{
					final String[] state = WorkItemNotices.parseState(row.getString(ChatWorkItemTrigger.LOG_MESSAGE));
					if (state != null)
					{
						return state;
					}
				}
			}
			return null;
		}
	}

	/** workflow.channel.<role> of the first offered role that has one, else workflow.channel, else null (connector default). */
	static String channel(final ConnectorConfig pConfig, final UserTask.WorkItem pItem)
	{
		final List<Profile> profiles = pItem.getOfferedToProfiles();
		if (profiles != null)
		{
			for (final Profile profile : profiles)
			{
				final String specific = pConfig.getProperty("workflow.channel." + ChatWorkItemTrigger.name(profile), null);
				if (specific != null && !specific.trim().isEmpty() && !pConfig.isPlaceholder(specific))
				{
					return specific.trim();
				}
			}
		}
		final String channel = pConfig.getProperty("workflow.channel", null);
		return channel == null || channel.trim().isEmpty() || pConfig.isPlaceholder(channel) ? null : channel.trim();
	}

	/** Role or user id without the profile prefix of Profile.toString (B built-in, R role, U user). */
	static String name(final Profile pProfile)
	{
		if (pProfile instanceof UserReference)
		{
			return ((UserReference) pProfile).getUserId();
		}
		final String text = String.valueOf(pProfile);
		return text.length() > 1 && (text.charAt(0) == 'B' || text.charAt(0) == 'R' || text.charAt(0) == 'U') ? text.substring(1) : text;
	}

	private static String offeredTo(final Repository pRepository, final UserTask.WorkItem pItem, final Locale pLocale)
	{
		final List<Profile> profiles = pItem.getOfferedToProfiles();
		if (profiles == null || profiles.isEmpty())
		{
			return null;
		}
		final StringBuilder b = new StringBuilder();
		for (final Profile profile : profiles)
		{
			String label = ChatWorkItemTrigger.name(profile);
			if (profile instanceof UserReference)
			{
				try
				{
					label = DirectoryHandler.getInstance(pRepository).displayUser((UserReference) profile, pLocale);
				}
				catch (final RuntimeException ex)
				{
					// keep the user id
				}
			}
			b.append(b.length() == 0 ? "" : ", ").append(label);
		}
		return b.toString();
	}

	private static String text(final UserMessage pMessage, final Locale pLocale)
	{
		if (pMessage == null)
		{
			return null;
		}
		final String text = pMessage.formatMessage(pLocale);
		return text == null || text.trim().isEmpty() ? null : text.trim();
	}

	@Override
	public void handleAfterProcessInstanceStart(final WorkflowTriggerAfterProcessInstanceStartContext pContext)
	{
		// nothing
	}

	@Override
	public void handleBeforeProcessInstanceTermination(final WorkflowTriggerBeforeProcessInstanceTerminationContext pContext)
	{
		// nothing
	}

	@Override
	public void handleBeforeWorkItemStart(final WorkflowTriggerBeforeWorkItemStartContext pContext)
	{
		// nothing
	}

	@Override
	public void handleBeforeWorkItemAllocation(final WorkflowTriggerBeforeWorkItemAllocationContext pContext)
	{
		// nothing
	}

	@Override
	public void handleBeforeWorkItemDeallocation(final WorkflowTriggerBeforeWorkItemDeallocationContext pContext)
	{
		// nothing
	}

	@Override
	public void handleBeforeWorkItemReallocation(final WorkflowTriggerBeforeWorkItemReallocationContext pContext)
	{
		// nothing
	}
}
