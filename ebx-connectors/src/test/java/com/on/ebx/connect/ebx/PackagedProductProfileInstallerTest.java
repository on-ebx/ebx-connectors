package com.on.ebx.connect.ebx;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.on.ebx.connect.core.ConnectorTemplate;
import com.on.ebx.connect.core.ProductConnectorTemplates;

/** Account-free selection contract for packaged profiles and existing connector records. */
public final class PackagedProductProfileInstallerTest
{
	public static void main(final String[] pArgs)
	{
		final List<ConnectorTemplate> selected = PackagedProductProfileInstaller.selectAvailable(
			ProductConnectorTemplates.packaged(), new HashSet<String>(Arrays.asList("Salesforce", "SAP", "HubSpot")));
		check(selected.size() == 3, "only profiles backed by installed connectors are selected");
		check("product.salesforce".equals(selected.get(0).getId()), "packaged order is retained");
		check("product.sap".equals(selected.get(1).getId()), "SAP profile selected");
		check("product.hubspot".equals(selected.get(2).getId()), "HubSpot profile selected");
		for (final ConnectorTemplate template : selected)
		{
			check(template.getConfiguration().path("active").asBoolean(), "selected profile must remain inactive", false);
		}
		System.out.println("PASS: packaged profiles bind only to existing connector records");
	}

	private static void check(final boolean pActual, final String pMessage) { check(pActual, pMessage, true); }
	private static void check(final boolean pActual, final String pMessage, final boolean pExpected)
	{
		if (pActual != pExpected) throw new AssertionError(pMessage);
	}
}
