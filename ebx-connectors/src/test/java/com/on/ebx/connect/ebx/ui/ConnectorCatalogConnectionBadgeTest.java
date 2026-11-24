package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the catalog's separate readiness and persisted-connection indicators. */
public final class ConnectorCatalogConnectionBadgeTest
{
	private ConnectorCatalogConnectionBadgeTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final Path root = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String java = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorCatalogUserService.java"));
		final String css = read(root.resolve("src/main/webapp/www/common/stylesheets/connect/connector-catalog.css"));
		final String en = read(root.resolve("src/main/resources/com/on/ebx/connect/ebx/ui/Messages_en.properties"));
		final String fr = read(root.resolve("src/main/resources/com/on/ebx/connect/ebx/ui/Messages_fr.properties"));
		check(java.contains("new EbxConnectorConnectionStateStore(repository, pSession)"), "catalog does not use persisted EBX connection state");
		check(java.contains("pStates.status(pConfig).getStatus()"), "catalog does not derive stale state from the current material configuration");
		check(java.contains("ftcc-status") && java.contains("ftcc-connection-status"), "readiness and connection badges were not kept separate");
		check(css.contains(".ftcc-connection-connected .ftcc-connection-status") && css.contains("background:#ecfdf3"), "Connected is not the explicit green state");
		check(css.contains(".ftcc-logo img{display:block;width:128px;height:82px") && css.contains("object-fit:contain"),
			"catalog logos must share a fixed box without distorting their aspect ratio");
		for (final String key : new String[] { "catalog.connection.connected", "catalog.connection.stale", "catalog.connection.failed", "catalog.connection.notConnected" })
			check(en.contains(key) && fr.contains(key), "missing EN/FR catalog connection label: " + key);
		System.out.println("PASS: catalog renders localized persisted connection state separately from readiness");
	}
	private static String read(final Path pPath) throws Exception { return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8); }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
