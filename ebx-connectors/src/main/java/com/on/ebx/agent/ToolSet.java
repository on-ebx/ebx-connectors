package com.on.ebx.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The tools an {@link AgentLoop} run is allowed to show the model, drawn from two kinds of sources —
 * {@link McpClient} servers, filtered to an explicit allow-list per server, and in-process {@link LocalTool}
 * groups, every tool of which is allowed by being registered here at all — nothing beyond that is ever
 * described to the model, and (see {@link AgentLoop}) a call to anything else is refused rather than
 * executed.
 * <p>
 * A tool is exposed to the model as {@code <group>__<tool>}, where {@code group} is the MCP server's name or
 * the local tools' group name, so two groups (of either kind, or one of each) may reuse the same tool name
 * without colliding; the combined name must still fit every provider's {@code ^[a-zA-Z0-9_-]{1,64}$} rule,
 * checked at {@link #build} time so a bad combination fails fast and by name, not with an opaque 400 from
 * the provider mid-run. A {@link ToolSet} made only of local groups, with no MCP server at all, is a normal,
 * fully supported case — this is how a run that must never write through MCP is built.
 */
public final class ToolSet
{
	/** The pattern both the Anthropic and the OpenAI-shaped API require of every tool name. */
	public static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

	/** Which kind of source an {@link Entry} resolves to. */
	public enum Kind
	{
		MCP, LOCAL
	}

	/** One MCP server's client and the subset of its tools this run may call. */
	public static final class Server
	{
		public final McpClient client;
		public final Set<String> allowedTools;

		public Server(final McpClient pClient, final Set<String> pAllowedTools)
		{
			this.client = pClient;
			this.allowedTools = pAllowedTools;
		}
	}

	/** A group of in-process tools this run may call; every tool listed here is allowed. */
	public static final class LocalGroup
	{
		public final List<LocalTool> tools;

		public LocalGroup(final List<LocalTool> pTools)
		{
			this.tools = pTools;
		}
	}

	/**
	 * Where an exposed tool name resolves back to. For {@link Kind#MCP}: {@code server} is the MCP server
	 * name, {@code client} is non-null, {@code toolName} is the tool's real (unprefixed) name, {@code
	 * localTool} is null. For {@link Kind#LOCAL}: {@code server} is the local group name (it plays the same
	 * role an MCP server name would), {@code client} is null, {@code toolName} is the {@link LocalTool}'s own
	 * name, {@code localTool} is that tool.
	 */
	public static final class Entry
	{
		public final Kind kind;
		public final String server;
		public final McpClient client;
		public final String toolName;
		public final LocalTool localTool;

		private Entry(final Kind pKind, final String pServer, final McpClient pClient, final String pToolName, final LocalTool pLocalTool)
		{
			this.kind = pKind;
			this.server = pServer;
			this.client = pClient;
			this.toolName = pToolName;
			this.localTool = pLocalTool;
		}

		static Entry mcp(final String pServer, final McpClient pClient, final String pToolName)
		{
			return new Entry(Kind.MCP, pServer, pClient, pToolName, null);
		}

		static Entry local(final String pGroup, final LocalTool pTool)
		{
			return new Entry(Kind.LOCAL, pGroup, null, pTool.name, pTool);
		}
	}

	private final List<LlmClient.ToolSpec> specs = new ArrayList<LlmClient.ToolSpec>();
	private final Map<String, Entry> byExposedName = new LinkedHashMap<String, Entry>();

	private ToolSet()
	{
	}

	/** Same as {@link #build(Map, Map)} with no local tool groups. */
	public static ToolSet build(final Map<String, Server> pServers) throws McpException
	{
		return ToolSet.build(pServers, Collections.<String, LocalGroup>emptyMap());
	}

	/**
	 * Calls {@code listTools()} on every MCP server and keeps only the allow-listed names, then adds every
	 * tool of every local group (local tools need no such call: registering one here is what allows it).
	 * Either map may be null or empty — including both {@code pServers} empty/null and only local groups
	 * given, which is a normal, supported {@link ToolSet}. Throws {@link McpException} if a server cannot be
	 * reached, or {@link IllegalArgumentException} — refusing at build time, before any model call is made —
	 * if an exposed name does not fit {@link #NAME_PATTERN} or collides with another entry's, MCP or local.
	 */
	public static ToolSet build(final Map<String, Server> pServers, final Map<String, LocalGroup> pLocalGroups) throws McpException
	{
		final ToolSet set = new ToolSet();
		if (pServers != null)
		{
			for (final Map.Entry<String, Server> serverEntry : pServers.entrySet())
			{
				final String server = serverEntry.getKey();
				final Server config = serverEntry.getValue();
				for (final McpClient.Tool tool : config.client.listTools())
				{
					if (config.allowedTools == null || !config.allowedTools.contains(tool.name))
					{
						continue;
					}
					set.add(server, tool.name, Entry.mcp(server, config.client, tool.name), tool.description, tool.inputSchema);
				}
			}
		}
		if (pLocalGroups != null)
		{
			for (final Map.Entry<String, LocalGroup> groupEntry : pLocalGroups.entrySet())
			{
				final String group = groupEntry.getKey();
				final List<LocalTool> tools = groupEntry.getValue() == null ? null : groupEntry.getValue().tools;
				if (tools == null)
				{
					continue;
				}
				for (final LocalTool tool : tools)
				{
					set.add(group, tool.name, Entry.local(group, tool), tool.description, tool.inputSchema);
				}
			}
		}
		return set;
	}

	private void add(final String pGroup, final String pToolName, final Entry pEntry, final String pDescription, final JsonNode pInputSchema)
	{
		final String exposed = pGroup + "__" + pToolName;
		if (!ToolSet.NAME_PATTERN.matcher(exposed).matches())
		{
			throw new IllegalArgumentException("tool name '" + exposed + "' (group '" + pGroup + "', tool '" + pToolName
				+ "') does not fit the provider pattern ^[a-zA-Z0-9_-]{1,64}$; rename the group or exclude the tool from the allow-list");
		}
		if (this.byExposedName.containsKey(exposed))
		{
			throw new IllegalArgumentException("duplicate exposed tool name '" + exposed + "'");
		}
		this.byExposedName.put(exposed, pEntry);
		this.specs.add(new LlmClient.ToolSpec(exposed, pDescription, pInputSchema));
	}

	/** The tools to offer the model, in {@code <group>__<tool>} form. */
	public List<LlmClient.ToolSpec> specs()
	{
		return this.specs;
	}

	/** Which kind, and which server/client/real-name or which {@link LocalTool}, an exposed tool name resolves to — or null if the model invented a name that is not in this set. */
	public Entry resolve(final String pExposedName)
	{
		return this.byExposedName.get(pExposedName);
	}
}
