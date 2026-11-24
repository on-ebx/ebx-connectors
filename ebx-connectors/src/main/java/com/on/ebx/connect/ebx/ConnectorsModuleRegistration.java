package com.on.ebx.connect.ebx;

import jakarta.servlet.annotation.WebListener;

import com.on.ebx.connect.core.ConnectorOperationalMetrics;
import com.on.ebx.connect.core.ConnectorOperationsRuntime;
import com.on.ebx.connect.ebx.ui.ConflictManagerOnRecordDeclaration;
import com.on.ebx.connect.ebx.ui.ConflictManagerOnTableDeclaration;
import com.on.ebx.connect.ebx.ui.ConflictManagerServiceDeclaration;
import com.on.ebx.connect.ebx.ui.ConnectorSyncServiceDeclaration;
import com.on.ebx.connect.ebx.ui.OpenApiImportServiceDeclaration;
import com.orchestranetworks.module.ModuleContextOnRepositoryStartup;
import com.orchestranetworks.module.ModuleRegistrationListener;
import com.orchestranetworks.module.ModuleServiceRegistrationContext;
import com.orchestranetworks.service.OperationException;

/**
 * Registration of the ebx-connectors module: the Connectors configuration dataspace and dataset
 * are created and seeded at repository startup, and the "Synchronise with an external
 * application" service is offered on every table view. The mapping designer is registered by the
 * Connectors data model itself ({@link ConnectorsSchemaExtensions}), the REST API by {@link #handleContextInitialized}.
 */
@WebListener
public final class ConnectorsModuleRegistration extends ModuleRegistrationListener
{
	/** The Actions submenu that holds this module's services. */
	public static final com.orchestranetworks.service.ServiceGroupKey SERVICE_GROUP = com.orchestranetworks.service.ServiceGroupKey.forServiceGroupInModule("ebx-connectors", "Connectors");

	/**
	 * The REST API, through the documented registration of the EBX REST Toolkit (C2-11): it replaces the servlet of
	 * {@code web.xml} that named an internal class. The application maps itself on its {@code @ApplicationPath}
	 * ({@code /rest}), so the routes stay {@code /ebx-connectors/rest/v1/...}.
	 */
	@Override
	public void handleContextInitialized(final com.orchestranetworks.module.ModuleInitializedContext pContext)
	{
		// lot 1.4.1: the Jackson read bounds are the default of every reader created from here on, so they are set before anything else
		com.on.ebx.connect.ebx.rest.JsonReadLimits.apply();
		super.handleContextInitialized(pContext);
		pContext.registerRESTApplication(com.on.ebx.connect.ebx.rest.ConnectorsRestApplication.class);
	}

	@Override
	public void handleRepositoryStartup(final ModuleContextOnRepositoryStartup pContext) throws OperationException
	{
		super.handleRepositoryStartup(pContext);
		// every line of the start goes to the kernel log: the category a context hands a module (log.wbp.<module>.main) is at FATAL in the standard
		// ebx.properties, so the installer's lines, its errors included, appeared in no file on a standard instance (measured 2026-11-21)
		final com.orchestranetworks.service.LoggingCategory log = com.orchestranetworks.service.LoggingCategory.getKernel();
		// before anything below starts a thread: a repository restarted in the same class loader finds
		// the executors stopped by the previous shutdown
		com.on.ebx.connect.core.BackgroundWork.start();
		// C2-12: the installer's own session, kept for the later calls that find the installation incomplete
		final com.orchestranetworks.service.Session installSession = pContext.createSystemUserSession("connectors");
		ConnectorsInstaller.useInstallSession(installSession);
		ConnectorsInstaller.install(pContext.getRepository(), installSession, pContext.getModuleName(), log);
		// which connections hold a secret in clear (refused at use unless -Debx.connectors.secrets.allowClear=true)
		com.on.ebx.connect.core.ClearSecrets.logAtStartup(pContext.getRepository());
		com.on.ebx.connect.core.Egress.logAtStartup();
		com.on.ebx.connect.core.PreviewFeatures.logAtStartup();
		PackagedProductProfileInstaller.install(pContext.getRepository(), pContext.createSystemUserSession("connectors-product-profiles"),
			log);
		// the "Data connector" perspective, created when absent (never modified once it exists)
		PerspectiveInstaller.install(pContext.getRepository(), pContext.createSystemUserSession("connectors-perspective"),
			log);
		/*
		 * Synchronisation results are logged by ConnectorRepository.  Install the
		 * durable observer during repository startup so those same live results
		 * also populate the operational-metrics table.  Without this registration
		 * the runtime deliberately uses its no-op observer, leaving the health
		 * dashboard empty even after successful connector work.
		 */
		ConnectorsModuleRegistration.observer = ConnectorOperationsRuntime.install(new ConnectorOperationsRuntime.MetricsAndAlertsObserver(
			new ConnectorOperationalMetrics(new EbxConnectorOperationalMetricsPersistence(pContext.getRepository(),
				pContext.createSystemUserSession("connectors-metrics"))), null));
		// the purge of old log lines, decided conflicts, finished runs and publication reports: one background
		// task, on by default at 90 days (-Debx.connectors.log.retentionDays=0 turns it off)
		com.on.ebx.connect.core.Retention.start(pContext.getRepository(), pContext.createSystemUserSession("connectors-retention"));
		// the lineage every mapping declares, sent to the OpenLineage sinks once the modules are up (and after each change, by the mapping triggers)
		com.on.ebx.connect.core.DeclaredLineage.install(pContext.getRepository());
		// the MCP tools for the other modules of this JVM, run with the caller's own session (no HTTP, no account)
		com.on.ebx.connect.ebx.rest.InJvmMcp.register();
		// the versioned contract for the sibling modules (ask the language model, declare and run flows), with the caller's session
		try
		{
			com.on.ebx.connect.ebx.contract.InJvmContract.register();
		}
		catch (final IllegalStateException ex)
		{
			log.warn("[connectors] in-JVM contract not registered: " + ex.getCause());
		}
		// audit C-07 (lot L8): table triggers queue their sends; this sender writes the queue and sends after the user's commit.
		// Rows left PENDING by a stop or a crash are taken by its first pass.
		{
			final com.orchestranetworks.service.Session queueSession = pContext.createSystemUserSession("connectors-outbound-queue");
			final com.orchestranetworks.instance.Repository queueRepository = pContext.getRepository();
			com.on.ebx.connect.core.OutboundQueue.start(new com.on.ebx.connect.core.OutboundQueueStore(queueRepository, queueSession),
				new com.on.ebx.connect.core.OutboundQueue.Sender()
				{
					@Override
					public com.on.ebx.connect.core.OutboundQueue.Outcome send(final com.on.ebx.connect.core.OutboundQueue.Entry pEntry)
					{
						return com.on.ebx.connect.core.ConnectorService.get().sendQueued(queueSession, queueRepository, pEntry);
					}
				}, new com.on.ebx.connect.core.OutboundQueue.Resolver()
				{
					@Override
					public com.on.ebx.connect.core.OutboundQueue.Resolution resolve(final com.on.ebx.connect.core.OutboundQueue.Entry pEntry)
					{
						return com.on.ebx.connect.core.ConnectorService.get().resolveQueued(queueRepository, pEntry);
					}
				});
		}
		// Volume lot 7a: a run truly stuck RUNNING across a restart can only mean the JVM that was
		// running it (and every in-JVM-only cancellation flag, executor thread and ExecutionRun
		// checkpoint writer with it) is gone - mark it INTERRUPTED so connectors_run_get never reports
		// a phantom "still running".
		final int interrupted = com.on.ebx.connect.core.ConnectorService.get().repository(pContext.getRepository())
			.markInterruptedRunningRuns(pContext.createSystemUserSession("connectors-run-recovery"));
		if (interrupted > 0)
		{
			log.warn("[connectors] marked " + interrupted + " run(s) INTERRUPTED at startup (was RUNNING across a restart)");
		}
	}

	@Override
	public void handleRepositoryShutdown()
	{
		ConnectorsModuleRegistration.stopBackgroundWork();
		super.handleRepositoryShutdown();
	}

	@Override
	public void destroyBeforeUnregisterModule()
	{
		ConnectorsModuleRegistration.stopBackgroundWork();
		super.destroyBeforeUnregisterModule();
	}

	/** {@code -Debx.connectors.shutdown.waitSeconds}: how long a stop of the module waits, in total, for its background threads. Default 10. */
	static final String SHUTDOWN_WAIT_PROPERTY = "ebx.connectors.shutdown.waitSeconds";

	/** The observer installed at startup; it holds the repository and a session, and is taken back at stop. */
	private static volatile ConnectorOperationsRuntime.Registration observer;

	/**
	 * Everything the module started on its own is stopped here, from both callbacks (EBX calls one or
	 * the other depending on whether the repository or only the module goes away) and harmlessly
	 * twice: a thread, a client or a registration that outlives the web application keeps its class
	 * loader reachable, and the kernel calling into it after a reload.
	 */
	private static void stopBackgroundWork()
	{
		final com.orchestranetworks.service.LoggingCategory log = com.on.ebx.connect.core.ConnectorService.log();
		try
		{
			com.on.ebx.connect.ebx.rest.InJvmMcp.unregister();
			com.on.ebx.connect.ebx.contract.InJvmContract.unregister();
			ConnectorsInstaller.forgetInstallSession();
			// Running imports are asked to stop at their next batch boundary, where they write their own
			// final state; the executors are then stopped and waited for.
			com.on.ebx.connect.core.ConnectorService.requestStopOfAsyncImports();
			// what a trigger handed over and the sender has not written yet is written before the sender stops (audit C-07)
			com.on.ebx.connect.core.OutboundQueue.flushIntake();
			final java.util.List<String> stillRunning = com.on.ebx.connect.core.BackgroundWork.stop(
				1000L * com.on.ebx.connect.core.BackgroundWork.intProperty(ConnectorsModuleRegistration.SHUTDOWN_WAIT_PROPERTY, 10, 0));
			com.on.ebx.connect.core.ConnectorService.forgetAsyncImports();
			com.on.ebx.connect.core.OutboundQueue.forget();
			com.on.ebx.connect.core.DeclaredLineage.forgetPending();
			// the clients the cached connector instances hold open (a Kafka producer and its network thread)
			com.on.ebx.connect.core.ConnectorRegistry.get().invalidate();
			final ConnectorOperationsRuntime.Registration installed = ConnectorsModuleRegistration.observer;
			ConnectorsModuleRegistration.observer = null;
			if (installed != null)
			{
				installed.close();
			}
			if (!stillRunning.isEmpty())
			{
				log.warn("[connectors] still running after the stop of the module: " + stillRunning
					+ " (interrupted; a run they were executing is marked INTERRUPTED at the next start)");
			}
		}
		catch (final RuntimeException ex)
		{
			// a stop that fails halfway must not prevent EBX from stopping the rest
			log.warn("[connectors] stop of the background work incomplete: " + com.on.ebx.connect.core.Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	@Override
	public void handleServiceRegistration(final ModuleServiceRegistrationContext pContext)
	{
		super.handleServiceRegistration(pContext);
		// one submenu per module in the Actions menus of business tables, registered before the services that use it
		final com.onwbp.base.text.UserMessageString groupLabel = new com.onwbp.base.text.UserMessageString();
		groupLabel.setString(java.util.Locale.FRENCH, "Connecteurs");
		groupLabel.setStringForDefault("Connectors");
		final com.onwbp.base.text.UserMessageString groupDescription = new com.onwbp.base.text.UserMessageString();
		groupDescription.setString(java.util.Locale.FRENCH, "Synchronisation avec les systèmes externes et conflits.");
		groupDescription.setStringForDefault("Synchronisation with external systems and conflicts.");
		pContext.registerServiceGroup(SERVICE_GROUP, groupLabel, groupDescription);
		pContext.registerUserService(new ConnectorSyncServiceDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new OpenApiImportServiceDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new ConflictManagerServiceDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new ConflictManagerOnTableDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new ConflictManagerOnRecordDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.DeleteMappingOnRecordDeclaration(pContext.getModuleName()));
		pContext.registerUserService(new com.on.ebx.connect.ebx.ui.DeleteMappingOnTableDeclaration(pContext.getModuleName()));
		ConnectorsModuleRegistration.registerMcpServer(pContext);
	}

	/**
	 * Registers the module's MCP server with the native MCP runtime of EBX 6.3, through its public
	 * API ({@code ModuleServiceAdvancedRegistrationContext.registerMcpServer} and the
	 * {@code @McpServer} / {@code @McpTool} annotations). Done by reflection so that the module
	 * still loads on older EBX versions, where the runtime does not exist: there, and whenever EBX
	 * declines the registration, the same tools stay available on {@code /ebx-connectors/rest/mcp}.
	 * {@code -Debx.connectors.mcp=native|endpoint|both|none} (default both).
	 */
	private static void registerMcpServer(final ModuleServiceRegistrationContext pContext)
	{
		final String mode = System.getProperty("ebx.connectors.mcp", "both").trim().toLowerCase();
		if (!"both".equals(mode) && !"native".equals(mode))
		{
			return;
		}
		final com.orchestranetworks.service.LoggingCategory log = com.on.ebx.connect.core.ConnectorService.log();
		try
		{
			final Class<?> category = Class.forName("com.orchestranetworks.mcp.core.McpServerCategory");
			final java.lang.reflect.Method register = pContext.getClass().getMethod("registerMcpServer", String.class, String.class, category, Class[].class);
			final Class<?> server = Class.forName("com.on.ebx.connect.ebx.mcp.ConnectorsMcpServer");
			final Object production = category.getMethod("valueOf", String.class).invoke(null, "PRODUCTION");
			register.invoke(pContext, com.on.ebx.connect.ebx.mcp.ConnectorsTools.SERVER_NAME, com.on.ebx.connect.ebx.mcp.ConnectorsTools.SERVER_DESCRIPTION, production,
				new Class<?>[] { server });
			log.info("[connectors] MCP server '" + com.on.ebx.connect.ebx.mcp.ConnectorsTools.SERVER_NAME + "' registered with the EBX MCP runtime ("
					+ com.on.ebx.connect.ebx.mcp.ConnectorsTools.CATALOG.size() + " tools)");
		}
		catch (final ClassNotFoundException ex)
		{
			log.info("[connectors] no native MCP runtime in this EBX version: the MCP tools are served by /ebx-connectors/rest/mcp");
		}
		catch (final NoSuchMethodException ex)
		{
			log.info("[connectors] this EBX version does not let modules register MCP servers: the MCP tools are served by /ebx-connectors/rest/mcp");
		}
		catch (final java.lang.reflect.InvocationTargetException ex)
		{
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			log.warn("[connectors] EBX declined the MCP server registration (" + cause.getMessage() + "): the MCP tools are served by /ebx-connectors/rest/mcp");
		}
		catch (final Throwable ex)
		{
			log.warn("[connectors] MCP server registration failed: " + ex + "; the MCP tools are served by /ebx-connectors/rest/mcp");
		}
	}
}
