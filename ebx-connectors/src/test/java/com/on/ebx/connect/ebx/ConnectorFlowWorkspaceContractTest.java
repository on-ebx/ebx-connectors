package com.on.ebx.connect.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Deterministic source contract for the connector-scoped flow workspace and native selectors. */
public final class ConnectorFlowWorkspaceContractTest
{
	private ConnectorFlowWorkspaceContractTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Path module = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String form = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorForm.java"));
		assertContains(form, "new FlowPane()", "connector form must expose the contextual flow workspace");
		assertContains(form, "workspaceButton(mappingTableURI(connector)", "flow workspace must offer the native Mapping table create path in the top window");
		assertNotContains(extractMethod(form, "private static String mappingTableURI", "\n\t}"), "UIHttpManagerComponent",
			"\"Create or manage a data flow\" must not go through the legacy /ebx/ bridge in a modal pop-up");
		assertContains(form, "FLOW_RETURN_KEY, flowId", "Edit flow and Design & test must remember the connector to return to");
		assertContains(form, "SERVICE_RETURN_KEY, flowId", "Run flow must feed the execution service's Back button");
		final String mappingForm = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/MappingForm.java"));
		assertContains(mappingForm, "MappingForm.flowReturn(pWriter, pContext.getCurrentRecord(), StorageScope.userId(pContext.getSession()))", "a flow opened from a connector must close back to it");
		assertContains(form, "workspaceButton(mappingTableURI(connector) + \"?service=@creation\"",
			"Create mapping must open the creation form itself, not the Mapping table where \"+\" still has to be clicked");
		assertContains(form, "MappingForm.CREATE_CONNECTOR_KEY, connector.getOccurrencePrimaryKey().format()",
			"Create mapping must remember its connector for the creation form (the new mapping opened blank, seen live 2026-09-15 on Slack)");
		assertContains(mappingForm, "MappingForm.createPrefill(pWriter.getPrefixedPath(Path.parse(\"./connector\")).format(), userId)",
			"a mapping created after Create mapping must start with that connector selected");
		assertContains(mappingForm, "ebx_form_setValue(\" + path + \",{key:s.id,label:s.label||s.id})",
			"the connector foreign key must be preset through EBX's documented form API");
		assertContains(mappingForm, "pending:true",
			"a mapping created from Create mapping must close back to its connector, not to the Mapping table (seen live 2026-09-16)");
		assertContains(mappingForm, "MappingForm.closeToConnectorScript(\"\", userId)",
			"the creation form must bind its native Close to the connector it was opened from");
		assertNotContains(form, "pWriter.addWidget(MAPPINGS)", "the nested association widget must not reintroduce the EBX response-build failure");
		assertContains(form, "native Mapping table retains EBX's standard Create (+) action", "flow workspace must expose the supported create path");
		assertContains(form, "MappingDesignerServiceDeclaration.SERVICE_NAME", "flow cards must open the visual designer/test service");
		assertContains(form, "ConnectorSyncServiceDeclaration.SERVICE_NAME", "flow cards must open the native execution service");
		assertContains(form, "service=ebx-connectors@", "flow cards must address the module-qualified execution service");
		final String service = read(module.resolve("src/main/java/com/on/ebx/connect/core/ConnectorService.java"));
		assertContains(service, "? \"peek\" : pFeed.getOperation()", "Kafka feed simulations must use the non-committing peek operation");
		assertNotContains(extractMethod(form, "private static final class FlowPane", "private static String mappingTableURI"), "UIHttpManagerComponent",
			"flow cards must never build their links through the legacy manager bridge -- its native Close revokes the nested hybrid sub-session");
		for (final String link : new String[] { "mappingRecordURI(flow)", "mappingDesignerURI(flow)", "runURI" })
		{
			assertNotContains(form, "buildButtonPreview(" + link,
				"flow-card workspace links must not open in the modal inner pop-up -- an ebx-ui route there nests a second EBX shell "
					+ "with the dataset's table tree inside the perspective (found live 2026-09-14)");
		}
		assertContains(form, "t.location.href=u", "flow-card workspace links must navigate the top window");
		assertContains(form, "'/ui/custom/'+m[1]+'/data/'", "flow-card workspace links must stay inside the perspective they were opened from");
		assertContains(form, "ConnectorsPaths.Mapping.ACTIVE", "flow state and activation affordance must use the persisted mapping state");
		assertContains(form, "mappingRecordURI(flow)",
			"the \"Edit flow\" button must open the Mapping record via its own recordForm in the modern workspace, not the legacy /ebx/ bridge");
		assertNotContains(extractMethod(form, "static String mappingRecordURI", "static String mappingDesignerURI"), "UIHttpManagerComponent",
			"opening a flow's Mapping record must never go through the legacy manager bridge again -- its own native Close revokes the nested "
				+ "hybrid sub-session and drops the whole tab back to the login page (found live 2026-09-11, on this exact button)");
		assertContains(form, "mappingDesignerURI(flow)",
			"the \"Design/test\" button must open the Mapping Designer via its own record-scoped service in the modern workspace, not the legacy /ebx/ bridge");
		assertNotContains(extractMethod(form, "static String mappingDesignerURI", "private static String pathSegment"), "UIHttpManagerComponent",
			"opening a flow's Mapping Designer must never go through the legacy manager bridge again -- its own native Close revokes the nested "
				+ "hybrid sub-session and drops the whole tab back to the login page (found live 2026-09-12, on this exact button)");
		assertContains(form, "workspaceDisplay=", "connector return navigation must remain in the modern EBX workspace");
		assertNotContains(form, "tableView=",
			"no connector-form link may assert a saved table view exists (\"The view [X] was not found\" — found live 2026-09-11, "
				+ "nothing ever creates a ConnectorView PersistedTableViewDefinition)");
		final String diagnostics = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorDiagnosticsUserService.java"));
		assertContains(diagnostics, "setCloseCallback", "diagnostics must override the hybrid service close lifecycle");
		// C2-11: the public UserServiceNext.nextClose() is the same call (javap: it returns UserServiceEventOutcomeInternal.nextClose())
		assertContains(diagnostics, "UserServiceNext.nextClose()",
			"diagnostics Close must close the native bridge without embedding a modern workspace");
		assertNotContains(diagnostics, "UserServiceEventOutcomeInternal", "diagnostics Close uses the documented API, not the internal class (C2-11)");
		assertNotContains(diagnostics, "nextClose(returnURI)",
			"diagnostics Close must not redirect a modern workspace inside the bridge");
		assertNotContains(extractMethod(form, "private static void connectionActions", "private static ConnectorConnectionStateService.State connectionState"),
			"setRedirectionURI", "diagnostic actions must retain their native parent workspace");

		final String xsd = read(module.resolve("src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd"));
		assertNotContains(xsd, "MappingDataspaceEnumeration", "dataspace must remain free-form text");
		assertNotContains(xsd, "MappingDatasetEnumeration", "dataset must remain free-form text");
		assertNotContains(xsd, "MappingTablesEnumeration", "table path must remain free-form text");
		assertContains(xsd, "com.on.ebx.connect.ebx.MappingTableConstraint", "canonical table validation must remain enabled");
		assertContains(form, "pathSegment(\"B\" + dataspace)", "Run flow must use EBX's branch-qualified workspace route");
		assertNotContains(form, "\"&mapping=\" + queryValue(mappingId)",
			"Run flow must not pass the flow id as a plain query parameter -- ebx-ui drops it and the service opens the first mapping (found live 2026-09-14)");
		assertContains(form, "\"&serviceParams=\" + queryValue(serviceParams(ConnectorSyncServiceDeclaration.PARAM_MAPPING, mappingId))",
			"Run flow must pass the selected flow id to the execution service");
		assertContains(form, "final Object value = pRecord.get(pPath);",
			"form text helper must support numeric mapping identifiers while rendering flow links");
		assertContains(form, "String.valueOf(value)",
			"form text helper must safely render non-string scalar values");
		assertContains(form, "pFlow.getOccurrencePrimaryKey().format()",
			"Run flow must retain its mapping handoff when a historical record omits the rendered id");
		final String syncService = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorSyncUserService.java"));
		assertContains(syncService, "MappingForm.closeReturnScript(\"ftcsClose\", \"ftServiceReturn\", null, pUserId)",
			"the run service's own Close must return to the connector a flow card came from, not to the target table (seen live 2026-09-15)");
		assertContains(read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/MappingDesignerUserService.java")),
			"MappingForm.closeReturnScript(\"ftmdClose\", ConnectorForm.FLOW_RETURN_KEY",
			"the designer's Close must return to the connector a flow card came from, not stop on the flow record (seen live 2026-09-15)");
		assertContains(syncService, "pContext.getParameter(ConnectorSyncServiceDeclaration.PARAM_MAPPING)",
			"execution service must honor the mapping selected from a connector flow card");
		assertContains(syncService, "for (final MappingDefinition m : this.mappings)",
			"flow-card mapping handoff must validate execution against the target table's eligible mappings");
		final String declaration = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorSyncServiceDeclaration.java"));
		assertContains(declaration, "addInputParameter(PARAM_MAPPING",
			"flow-card mapping handoff must declare its optional service parameter to EBX");

		final String designer = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/MappingDesignerUserService.java"));
		assertContains(designer, "ObjectDiscoveryRequest.firstPage(\"\", 500)", "source-table discovery must be bounded");
		assertContains(designer, "object.isReadable()", "source-table selector must expose only readable objects");
		assertContains(designer, "SELECT * FROM \" + quotePostgreSqlObject(object.getId()) + \" LIMIT 100",
			"PostgreSQL source selection must generate a bounded read-only query");
		assertContains(designer, "sourceDiscoveryError", "source discovery failure must degrade without breaking the designer");
		assertContains(designer, "posted == null ? definition.getInputs() : posted",
			"source-table preview must execute the posted SQL instead of the previously saved SQL");
		assertContains(service, "pDryRun && \"Kafka\".equals(pMapping.getConnectorId())",
			"Kafka dry-run record synchronization must use the non-committing peek operation");
		final String designerJs = read(module.resolve("src/main/webapp/www/common/jscripts/connect/mapping-designer.js"));
		assertContains(designerJs, "ftmdSourceObject", "designer must render and wire the source-table selector");
		assertContains(designerJs, "loader.click()", "selecting a source table must refresh its external field schema");
		assertContains(designerJs, "inputs[sql].expression = joinInput(object.query, pipeline)",
			"generated SQL must preserve the user's input transformation pipeline");
		assertContains(designerJs, "renderInputs()", "generated SQL must remain in the ordinary editable SQL input");

		final String installer = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java"));
		assertContains(installer, "resolveMappingDataspace", "upgrade must normalize historical mapping targets");
		assertContains(installer, "stored.charAt(0) == 'B'", "upgrade must recognize intrinsic EBX branch keys");
		assertContains(installer, "named.getCandidates().get(0)", "upgrade must persist the branch name expected by selectors");
		assertContains(installer, "left as stored", "upgrade must never replace a stored dataspace that no longer resolves");
		System.out.println("PASS: connector-scoped flow workspace and free-form mapping targets");
	}

	private static String read(final Path pPath) throws Exception
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void assertContains(final String pText, final String pExpected, final String pMessage)
	{
		if (!pText.contains(pExpected)) { throw new AssertionError(pMessage + ": missing " + pExpected); }
	}

	private static void assertNotContains(final String pText, final String pUnexpected, final String pMessage)
	{
		if (pText.contains(pUnexpected))
		{
			throw new AssertionError(pMessage + ": unexpected " + pUnexpected);
		}
	}

	private static String extractMethod(final String pText, final String pStart, final String pEnd)
	{
		final int start = pText.indexOf(pStart);
		final int end = pText.indexOf(pEnd, start + pStart.length());
		if (start < 0 || end < 0) { throw new AssertionError("Unable to isolate source contract between " + pStart + " and " + pEnd); }
		return pText.substring(start, end);
	}
}
