package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards contextual catalog return without changing native table navigation. */
public final class ConnectorCatalogReturnContractTest
{
	private ConnectorCatalogReturnContractTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final Path root = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String catalog = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorCatalogUserService.java"));
		final String form = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorForm.java"));
		check(catalog.contains("data-record-id") && catalog.contains("data-return-url"), "catalog card has no scoped return metadata");
		check(catalog.contains("sessionStorage.setItem(key") && catalog.contains("sessionStorage.removeItem(key)"), "catalog does not manage a tab-scoped origin token");
		check(form.contains("state.id!==\" + recordId") && form.contains("sessionStorage.getItem(key)"), "native record launches are not isolated by the expected record id");
		check(catalog.contains("{create:true,url:create.dataset.returnUrl}") && form.contains("if(state.create){") && form.contains("state={id:\" + recordId"), "the New connection button leaves no create token, or the form never narrows it to the saved record");
		check(form.contains("target.origin!==topWindow.location.origin"), "catalog return accepts a cross-origin destination");
		check(form.contains("target.pathname.indexOf('/ebx-ui/')!==0"), "catalog return accepts a non-EBX destination");
		check(form.contains("event.stopImmediatePropagation()") && form.contains("sessionStorage.removeItem(key);topWindow.location.href"), "catalog Close neither suppresses the native handler nor consumes its token");
		System.out.println("PASS: catalog-origin Close is scoped, same-origin, and leaves native table navigation unchanged");
	}
	private static String read(final Path pPath) throws Exception
	{ return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8); }
	private static void check(final boolean pCondition, final String pMessage)
	{ if (!pCondition) throw new AssertionError(pMessage); }
}
