package com.on.ebx.connect.ebx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;

/** The packaged perspective: readable from the classpath, every item well formed, every icon shipped by the module. */
public final class PerspectiveInstallerTest
{
	private PerspectiveInstallerTest() { }

	private static int actions;

	public static void main(final String[] pArgs) throws Exception
	{
		final Path webapp = Paths.get(System.getProperty("ebx.connectors.moduleDir", ".")).resolve("src/main/webapp");
		final JsonNode definition = PerspectiveInstaller.definition();
		check("DataConnector".equals(definition.path("key").asText()), "perspective key must be DataConnector");
		check(definition.path("labelEn").asText().length() > 0, "perspective needs a label");
		check(definition.path("items").size() > 0, "perspective needs menu items");
		walk(definition.path("items"), webapp);
		check(actions > 0, "perspective needs actions");
		about(definition);
		existingPerspective();
		System.out.println("PerspectiveInstallerTest OK (" + actions + " actions)");
	}

	/** The About entry ends the last section of the packaged perspective, with the info icon and no other icon. */
	private static void about(final JsonNode pDefinition)
	{
		final JsonNode item = PerspectiveInstaller.findItem(pDefinition.path("items"), PerspectiveInstaller.ABOUT_SERVICE_KEY);
		check(item != null, "the packaged perspective has an About entry");
		check("About".equals(item.path("labelEn").asText()) && item.path("labelFr").asText().startsWith("\u00c0"), "About is labelled in English and French");
		check("fa-info-circle".equals(item.path("iconCss").asText()), "About carries the info icon");
		final JsonNode sections = pDefinition.path("items");
		final JsonNode children = sections.get(sections.size() - 1).path("children");
		check(children.get(children.size() - 1) == item, "About is the last entry of the last section");
		check("About".equals(item.path("parameters").get(0).path("value").asText()), "the service parameter names the service");
	}

	/** What is done to a perspective installed before the About page existed: add once, at the end of the last section. */
	private static void existingPerspective()
	{
		final java.util.List<PerspectiveInstaller.MenuRow> rows = new java.util.ArrayList<PerspectiveInstaller.MenuRow>();
		rows.add(new PerspectiveInstaller.MenuRow("1", "section", null, null, 1000));
		rows.add(new PerspectiveInstaller.MenuRow("2", "action", "1", "x@A", 1000));
		rows.add(new PerspectiveInstaller.MenuRow("3", "section", "", null, 3000));
		rows.add(new PerspectiveInstaller.MenuRow("4", "action", "3", "x@B", 1000));
		rows.add(new PerspectiveInstaller.MenuRow("5", "action", "3", "x@C", 4000));
		rows.add(new PerspectiveInstaller.MenuRow("6", "section", null, null, 2000));
		check(!PerspectiveInstaller.hasService(rows, PerspectiveInstaller.ABOUT_SERVICE_KEY), "no About item yet");
		check("3".equals(PerspectiveInstaller.lastSection(rows).id), "the last section is the one with the highest order");
		check(PerspectiveInstaller.orderAfterChildren(rows, "3") == 5000, "after the last child");
		check(PerspectiveInstaller.orderAfterChildren(rows, "6") == 1000, "first child of an empty section");
		rows.add(new PerspectiveInstaller.MenuRow("7", "action", "1", PerspectiveInstaller.ABOUT_SERVICE_KEY, 9000));
		check(PerspectiveInstaller.hasService(rows, PerspectiveInstaller.ABOUT_SERVICE_KEY), "an About item anywhere in the menu counts: never duplicated");
		check(PerspectiveInstaller.lastSection(new java.util.ArrayList<PerspectiveInstaller.MenuRow>()) == null, "a menu without section has no last section");
	}

	private static void walk(final JsonNode pNodes, final Path pWebapp)
	{
		for (final JsonNode node : pNodes)
		{
			final String type = node.path("type").asText();
			final String name = node.path("labelEn").asText(node.path("xpath").asText());
			check("section".equals(type) || "group".equals(type) || "action".equals(type), "unknown item type " + type);
			if ("action".equals(type))
			{
				actions++;
				check(node.hasNonNull("serviceKey") || node.hasNonNull("dataspace") && node.hasNonNull("dataset") && node.hasNonNull("xpath"),
					"action " + name + " needs a serviceKey or a dataspace, dataset and xpath");
				check(!node.hasNonNull("serviceKey") || node.hasNonNull("labelEn"), "service action " + node.path("serviceKey").asText() + " needs a label");
			}
			else
			{
				check(node.hasNonNull("labelEn"), type + " needs a label");
			}
			final String icon = node.path("iconUrl").asText("");
			if (icon.length() > 0)
			{
				check(icon.startsWith("/ebx-connectors/"), "icon " + icon + " must be served by the module");
				check(Files.isRegularFile(pWebapp.resolve(icon.substring("/ebx-connectors/".length()))), "icon " + icon + " is missing from src/main/webapp");
			}
			walk(node.path("children"), pWebapp);
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
