package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Deterministic contract guarding dataset-only activation and read-only rendering. */
public final class ReleaseNotesDatasetContractTest
{
	private ReleaseNotesDatasetContractTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final Path root = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String declaration = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ReleaseNotesServiceDeclaration.java"));
		final String service = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ReleaseNotesUserService.java"));
		final String extensions = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ConnectorsSchemaExtensions.java"));
		check(declaration.contains("implements UserServiceDeclaration.OnDataset"), "release notes must be a dataset service");
		check(!declaration.contains("OnTableView") && !declaration.contains("OnRecord"), "release notes leaked into table or record scope");
		check(declaration.contains("ConnectorsPaths.MODEL_PATH.equals(path)"), "activation is not restricted to the Connectors model");
		check(declaration.contains("setActivationRule"), "defensive dataset activation rule is absent");
		check(declaration.contains("ServiceKey.forName(SERVICE_NAME)"), "schema service must use a data-model service key");
		check(!declaration.contains("QUALIFIED_SERVICE_KEY") && !declaration.contains("forModuleServiceName"),
			"schema service declares a second module-qualified identity");
		check(extensions.contains("new com.on.ebx.connect.ebx.ui.ReleaseNotesServiceDeclaration()"), "dataset service is not registered by schema extensions");
		check(extensions.contains("disableReleaseNotesOnTables") && extensions.contains("ServicePermissionRule.ALWAYS_DISABLED"),
			"dataset service is still inherited into table Actions menus");
		check(extensions.contains("ReleaseNotesServiceDeclaration.SERVICE_KEY"),
			"table suppression does not target the exact service key returned by the declaration");
		check(service.contains("ConnectorsPaths.RELEASE_ITEM") && service.contains("ConnectorsPaths.RELEASE_METRIC")
			&& service.contains("ConnectorsPaths.RELEASE_PASS_STATUS"), "renderer does not consume all release detail tables");
		check(service.contains("ConnectorsPaths.RELEASE_PASS_STEP") && service.contains("relatedSteps"),
			"renderer does not expose per-pass historical substeps");
		check(service.contains("cloneNode(true)") && service.contains("window.open") && service.contains("source.querySelectorAll('details')"),
			"PDF export must print a standalone, fully expanded document rather than the clipped EBX iframe");
		check(service.contains("if (\"DRAFT\".equals"), "draft release records could be exposed");
		check(!service.contains("Procedure") && !service.contains("ValueContextForUpdate"), "release notes renderer is not read-only");
		check("&lt;&amp;&quot;&#39;".equals(ReleaseNotesUserService.escape("<&\"'")), "HTML escaping contract failed");
		System.out.println("PASS: Release Notes is Connectors-model dataset-only, read-only, localized, and escaped");
	}
	private static String read(final Path pPath) throws Exception { return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8); }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
