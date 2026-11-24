package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.instance.Repository;

/**
 * Keeps the <b>declared</b> lineage of the mappings in step with their definition, so that an
 * OpenLineage sink (the ON EBX data catalog) shows where the data goes as soon as a flow is designed,
 * not only after its first run:
 * <ul>
 * <li>the triggers of {@code Mapping}, {@code MappingInput} and {@code MappingRule} call
 * {@link #mappingChanged}; the mapping is described again {@value #DEFAULT_DEBOUNCE_MS} ms after
 * the last change, outside the transaction, so that a whole import or a designer save gives one
 * event per mapping and reads the committed definition;</li>
 * <li>an active root mapping is sent as a {@code JobEvent} ({@link OpenLineageEmitter#declaration});
 * a deactivated or deleted one, and the old name of a renamed one, as a retirement;</li>
 * <li>at repository startup every mapping is swept once ({@code ebx.connectors.lineage.declareOnStartup},
 * default true, after {@code ebx.connectors.lineage.startupDelaySeconds}, default 90, retried twice when a
 * sink refused), which also catches up the mappings declared before this existed;</li>
 * <li>{@link #declareNow} does the same on request (REST {@code POST /v1/lineage/declare}, MCP
 * {@code connectors_lineage_declare}).</li>
 * </ul>
 * {@code -Debx.connectors.lineage.declared=false} turns the whole mechanism off; a sink turns it
 * off for itself with its property {@code emit.declared=false}. Nothing here ever fails a
 * transaction: errors are logged.
 */
public final class DeclaredLineage
{
	private DeclaredLineage()
	{
	}

	static final long DEFAULT_DEBOUNCE_MS = 2000L;
	static final int STARTUP_ATTEMPTS = 3;
	static final long STARTUP_RETRY_SECONDS = 120L;

	/** One thread for every declaration, created on first use and stopped with the module ({@link BackgroundWork}). */
	static final BackgroundWork.Managed<ScheduledExecutorService> EXECUTOR = BackgroundWork.managed("declared lineage",
		new BackgroundWork.Factory<ScheduledExecutorService>()
		{
			@Override
			public ScheduledExecutorService create()
			{
				return new java.util.concurrent.ScheduledThreadPoolExecutor(1, BackgroundWork.daemonThreads("ebx-connectors-declared-lineage", false));
			}
		});

	/**
	 * Schedules a declaration, or drops it when the module is stopping: this is called from table
	 * triggers, inside the transaction that changed a mapping, and must neither fail that transaction
	 * nor start a thread for a module that is going away. What is dropped is caught up by the sweep of
	 * the next start.
	 *
	 * @return null when the task was dropped
	 */
	private static ScheduledFuture<?> schedule(final Runnable pTask, final long pDelay, final TimeUnit pUnit)
	{
		try
		{
			return DeclaredLineage.EXECUTOR.get().schedule(pTask, pDelay, pUnit);
		}
		catch (final java.util.concurrent.RejectedExecutionException stopping)
		{
			return null;
		}
	}

	/**
	 * Module stop: the declarations still waiting were cancelled with the executor; what they held (a
	 * repository, mapping ids) is let go. The sweep of the next start describes every mapping again.
	 */
	public static void forgetPending()
	{
		synchronized (DeclaredLineage.PENDING)
		{
			DeclaredLineage.PENDING.clear();
			DeclaredLineage.FUTURES.clear();
		}
	}

	/** Mapping id → job names to retire when the change is processed; guarded by itself. */
	static final Map<Integer, Set<String>> PENDING = new HashMap<Integer, Set<String>>();
	static final Map<Integer, ScheduledFuture<?>> FUTURES = new HashMap<Integer, ScheduledFuture<?>>();

	static boolean enabled()
	{
		return !"false".equalsIgnoreCase(System.getProperty("ebx.connectors.lineage.declared", "true").trim());
	}

	/** Repository startup: schedules the sweep of every mapping. */
	public static void install(final Repository pRepository)
	{
		if (!DeclaredLineage.enabled() || "false".equalsIgnoreCase(System.getProperty("ebx.connectors.lineage.declareOnStartup", "true").trim()))
		{
			return;
		}
		long delay = 90L;
		try
		{
			delay = Long.parseLong(System.getProperty("ebx.connectors.lineage.startupDelaySeconds", "90").trim());
		}
		catch (final NumberFormatException ex)
		{
			delay = 90L;
		}
		DeclaredLineage.schedule(new StartupSweep(pRepository, 1), Math.max(0L, delay), TimeUnit.SECONDS);
	}

	/** Called by the triggers: the mapping will be described again once the changes stop. {@code pRetiredJobName} = a job name that no longer applies (old label). */
	public static void mappingChanged(final Repository pRepository, final int pMappingId, final String pRetiredJobName)
	{
		if (!DeclaredLineage.enabled() || pRepository == null || pMappingId < 0)
		{
			return;
		}
		final Integer id = Integer.valueOf(pMappingId);
		synchronized (DeclaredLineage.PENDING)
		{
			Set<String> retired = DeclaredLineage.PENDING.get(id);
			if (retired == null)
			{
				retired = new LinkedHashSet<String>();
				DeclaredLineage.PENDING.put(id, retired);
			}
			if (pRetiredJobName != null && !pRetiredJobName.trim().isEmpty())
			{
				retired.add(pRetiredJobName.trim());
			}
			final ScheduledFuture<?> previous = DeclaredLineage.FUTURES.get(id);
			if (previous != null)
			{
				previous.cancel(false);
			}
			final ScheduledFuture<?> scheduled = DeclaredLineage.schedule(new Change(pRepository, id), DeclaredLineage.debounceMs(), TimeUnit.MILLISECONDS);
			if (scheduled == null)
			{
				DeclaredLineage.FUTURES.remove(id);
				DeclaredLineage.PENDING.remove(id);
			}
			else
			{
				DeclaredLineage.FUTURES.put(id, scheduled);
			}
		}
	}

	/**
	 * A connector's type, base URL or activity changed ({@code ConnectorLineageTrigger}, configuration
	 * import): the datasets its mappings declare carry its namespace, so every root mapping of that
	 * connector is described again, after the usual debounce, outside the transaction.
	 */
	public static void connectorChanged(final Repository pRepository, final String pConnectorId)
	{
		if (!DeclaredLineage.enabled() || pRepository == null || pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			return;
		}
		DeclaredLineage.schedule(new ConnectorChange(pRepository, pConnectorId.trim()), DeclaredLineage.debounceMs(), TimeUnit.MILLISECONDS);
	}

	/** The mappings of one changed connector, handed to {@link #mappingChanged}. */
	static final class ConnectorChange implements Runnable
	{
		final Repository repository;
		final String connectorId;

		ConnectorChange(final Repository pRepository, final String pConnectorId)
		{
			this.repository = pRepository;
			this.connectorId = pConnectorId;
		}

		@Override
		public void run()
		{
			try
			{
				OpenLineageEmitter.invalidate();
				final ConnectorRepository repo = ConnectorService.get().repository(this.repository);
				if (repo.dataset() == null)
				{
					return;
				}
				int scheduled = 0;
				for (final MappingDefinition mapping : repo.mappings())
				{
					if (!mapping.isChild() && this.connectorId.equals(mapping.getConnectorId()))
					{
						DeclaredLineage.mappingChanged(this.repository, mapping.getId(), null);
						scheduled++;
					}
				}
				if (scheduled > 0)
				{
					ConnectorService.log().info("[connectors] connector " + this.connectorId + " changed: declared lineage of " + scheduled + " mapping(s) scheduled");
				}
			}
			catch (final Throwable ex)
			{
				ConnectorService.log().warn("[connectors] declared lineage after a change of connector " + this.connectorId + " failed: "
					+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
	}

	static long debounceMs()
	{
		try
		{
			return Math.max(0L, Long.parseLong(System.getProperty("ebx.connectors.lineage.debounceMs", String.valueOf(DeclaredLineage.DEFAULT_DEBOUNCE_MS)).trim()));
		}
		catch (final NumberFormatException ex)
		{
			return DeclaredLineage.DEFAULT_DEBOUNCE_MS;
		}
	}

	/** One debounced change. */
	static final class Change implements Runnable
	{
		final Repository repository;
		final Integer mappingId;

		Change(final Repository pRepository, final Integer pMappingId)
		{
			this.repository = pRepository;
			this.mappingId = pMappingId;
		}

		@Override
		public void run()
		{
			final Set<String> retired;
			synchronized (DeclaredLineage.PENDING)
			{
				retired = DeclaredLineage.PENDING.remove(this.mappingId);
				DeclaredLineage.FUTURES.remove(this.mappingId);
			}
			try
			{
				final OpenLineageEmitter.Dispatch d = DeclaredLineage.process(this.repository, this.mappingId.intValue(),
					retired == null ? Collections.<String>emptySet() : retired);
				if (d.sent + d.failed > 0)
				{
					ConnectorService.log().info("[connectors] declared lineage of mapping " + this.mappingId + ": " + d.sent + " event(s) sent, " + d.failed + " refused");
				}
			}
			catch (final Throwable ex)
			{
				ConnectorService.log().warn("[connectors] declared lineage of mapping " + this.mappingId + " failed: "
					+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
	}

	/** The startup sweep, retried when a sink was not reachable yet (the catalog module may start after this one). */
	static final class StartupSweep implements Runnable
	{
		final Repository repository;
		final int attempt;

		StartupSweep(final Repository pRepository, final int pAttempt)
		{
			this.repository = pRepository;
			this.attempt = pAttempt;
		}

		@Override
		public void run()
		{
			try
			{
				final ObjectNode report = DeclaredLineage.declareNow(this.repository, null);
				ConnectorService.log().info("[connectors] declared lineage at startup (attempt " + this.attempt + "): " + report.path("declared").asInt() + " mapping(s) declared, "
					+ report.path("retired").asInt() + " retired, " + report.path("sent").asInt() + " event(s) sent, " + report.path("failed").asInt() + " refused");
				if (report.path("failed").asInt() > 0 && this.attempt < DeclaredLineage.STARTUP_ATTEMPTS)
				{
					DeclaredLineage.schedule(new StartupSweep(this.repository, this.attempt + 1), DeclaredLineage.STARTUP_RETRY_SECONDS, TimeUnit.SECONDS);
				}
			}
			catch (final Throwable ex)
			{
				ConnectorService.log().warn("[connectors] declared lineage at startup failed: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
	}

	/** Describes one mapping as it stands now: declared when active, retired otherwise; a sub-object mapping describes its root. */
	static OpenLineageEmitter.Dispatch process(final Repository pRepository, final int pMappingId, final Set<String> pRetired)
	{
		final OpenLineageEmitter.Dispatch out = new OpenLineageEmitter.Dispatch();
		final ConnectorRepository repo = ConnectorService.get().repository(pRepository);
		if (repo.dataset() == null)
		{
			return out;
		}
		final Map<Integer, MappingDefinition> all = DeclaredLineage.byId(repo.mappings());
		final MappingDefinition mapping = DeclaredLineage.root(all, all.get(Integer.valueOf(pMappingId)));
		final Set<String> retired = new LinkedHashSet<String>(pRetired);
		if (mapping == null)
		{
			// deleted (or a sub-object mapping whose root is gone): retire what it was known as
			if (retired.isEmpty())
			{
				retired.add(OpenLineageEmitter.jobName(pMappingId, null));
			}
			for (final String name : retired)
			{
				out.add(OpenLineageEmitter.retire(repo, name, pMappingId, null));
			}
			return out;
		}
		final String current = OpenLineageEmitter.jobName(mapping);
		retired.remove(current);
		for (final String name : retired)
		{
			out.add(OpenLineageEmitter.retire(repo, name, mapping.getId(), mapping));
		}
		out.add(mapping.isActive() ? OpenLineageEmitter.declare(repo, mapping) : OpenLineageEmitter.retire(repo, current, mapping.getId(), mapping));
		return out;
	}

	/**
	 * Declares every root mapping (or the one named, sub-object mappings resolving to their root) and
	 * retires the inactive ones, synchronously. The report lists what was sent per mapping.
	 */
	public static ObjectNode declareNow(final Repository pRepository, final Integer pMappingId)
	{
		final ObjectNode out = Json.object();
		final ConnectorRepository repo = ConnectorService.get().repository(pRepository);
		if (repo.dataset() == null)
		{
			out.put("error", "The Connectors dataset does not exist");
			return out;
		}
		OpenLineageEmitter.invalidate(); // read the sinks as they are now
		final int sinks = OpenLineageEmitter.targets(repo).size();
		out.put("sinks", sinks);
		if (sinks == 0)
		{
			out.put("note", "No active connector of type openlineage: nothing to send to.");
		}
		final Map<Integer, MappingDefinition> all = DeclaredLineage.byId(repo.mappings());
		final Integer only;
		if (pMappingId == null)
		{
			only = null;
		}
		else
		{
			final MappingDefinition root = DeclaredLineage.root(all, all.get(pMappingId));
			if (root == null)
			{
				out.put("error", "No mapping " + pMappingId);
				return out;
			}
			only = Integer.valueOf(root.getId());
		}
		final OpenLineageEmitter.Dispatch total = new OpenLineageEmitter.Dispatch();
		int declared = 0;
		int retired = 0;
		final ArrayNode items = out.putArray("mappings");
		for (final MappingDefinition mapping : all.values())
		{
			if (mapping.isChild() || only != null && mapping.getId() != only.intValue())
			{
				continue;
			}
			final OpenLineageEmitter.Dispatch d = mapping.isActive() ? OpenLineageEmitter.declare(repo, mapping)
				: OpenLineageEmitter.retire(repo, OpenLineageEmitter.jobName(mapping), mapping.getId(), mapping);
			// an active catalog flow is retired too (exclude.catalogFlows): say so instead of DECLARED
			final boolean catalogFlow = mapping.isActive() && OpenLineageEmitter.excludedCatalogFlow(repo, mapping);
			if (mapping.isActive() && !catalogFlow) declared++;
			else retired++;
			total.add(d);
			final ObjectNode item = items.addObject();
			item.put("id", mapping.getId());
			item.put("label", mapping.getLabel() == null ? "" : mapping.getLabel());
			item.put("state", !mapping.isActive() ? "RETIRED" : catalogFlow ? "RETIRED_CATALOG_FLOW" : "DECLARED");
			item.put("sent", d.sent);
			item.put("failed", d.failed);
			item.put("skipped", d.skipped);
		}
		out.put("declared", declared);
		out.put("retired", retired);
		out.put("sent", total.sent);
		out.put("failed", total.failed);
		out.put("skipped", total.skipped);
		final ArrayNode messages = out.putArray("messages");
		for (final String m : total.messages)
		{
			messages.add(m);
		}
		// where it went: the data catalog found here (only a dataset of its own model), the sinks' contracts, advice
		out.setAll(CatalogDetection.summary(repo));
		return out;
	}

	static Map<Integer, MappingDefinition> byId(final List<MappingDefinition> pMappings)
	{
		final Map<Integer, MappingDefinition> out = new LinkedHashMap<Integer, MappingDefinition>();
		for (final MappingDefinition m : pMappings)
		{
			out.put(Integer.valueOf(m.getId()), m);
		}
		return out;
	}

	/** The root of a sub-object mapping (itself for a root), or null when a parent is missing. */
	static MappingDefinition root(final Map<Integer, MappingDefinition> pAll, final MappingDefinition pMapping)
	{
		MappingDefinition current = pMapping;
		for (int depth = 0; current != null && current.isChild() && depth < 10; depth++)
		{
			current = pAll.get(current.getParentId());
		}
		return current == null || current.isChild() ? null : current;
	}
}
