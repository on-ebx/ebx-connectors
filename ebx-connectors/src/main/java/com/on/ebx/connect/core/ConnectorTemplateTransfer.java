package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Account-free JSON interchange for connector templates. */
public final class ConnectorTemplateTransfer
{
	public static final String FORMAT = "ebx-connectors-templates";
	public static final int FORMAT_VERSION = 1;

	private ConnectorTemplateTransfer() { }

	public static String exportTemplates(final Iterable<ConnectorTemplate> pTemplates)
	{
		final List<ConnectorTemplate> sorted = new ArrayList<ConnectorTemplate>();
		for (final ConnectorTemplate template : pTemplates) { sorted.add(template); }
		Collections.sort(sorted, new Comparator<ConnectorTemplate>()
		{
			@Override public int compare(final ConnectorTemplate pLeft, final ConnectorTemplate pRight)
			{
				return pLeft.getId().compareTo(pRight.getId());
			}
		});
		final ObjectNode document = Json.object();
		document.put("format", FORMAT);
		document.put("formatVersion", FORMAT_VERSION);
		final ArrayNode templates = document.putArray("templates");
		for (final ConnectorTemplate template : sorted)
		{
			final ObjectNode node = templates.addObject();
			node.put("id", template.getId());
			node.put("version", template.getVersion());
			node.put("ownership", template.getOwnership().name().toLowerCase(java.util.Locale.ENGLISH));
			if (template.getBasedOn() != null)
			{
				node.put("basedOn", template.getBasedOn());
				node.put("basedOnVersion", template.getBasedOnVersion());
			}
			node.set("configuration", template.getConfiguration());
		}
		return Json.write(document);
	}

	public static List<ConnectorTemplate> importTemplates(final String pDocument) throws IOException
	{
		final JsonNode root = Json.parse(pDocument);
		if (root == null || !FORMAT.equals(text(root, "format")))
		{
			throw new IllegalArgumentException("Not an " + FORMAT + " document");
		}
		if (integer(root, "formatVersion") != FORMAT_VERSION)
		{
			throw new IllegalArgumentException("Unsupported template format version");
		}
		final JsonNode entries = root.get("templates");
		if (entries == null || !entries.isArray())
		{
			throw new IllegalArgumentException("templates must be an array");
		}
		final List<ConnectorTemplate> result = new ArrayList<ConnectorTemplate>();
		for (final JsonNode entry : entries)
		{
			if (!entry.isObject() || entry.get("configuration") == null || !entry.get("configuration").isObject())
			{
				throw new IllegalArgumentException("Every template requires an object configuration");
			}
			final String ownership = text(entry, "ownership");
			final ConnectorTemplate.Ownership owner;
			try { owner = ConnectorTemplate.Ownership.valueOf(ownership.toUpperCase(java.util.Locale.ENGLISH)); }
			catch (final Exception ex) { throw new IllegalArgumentException("Unknown template ownership: " + ownership); }
			result.add(new ConnectorTemplate(text(entry, "id"), integer(entry, "version"), owner,
				text(entry, "basedOn"), integer(entry, "basedOnVersion"), (ObjectNode) entry.get("configuration")));
		}
		return Collections.unmodifiableList(result);
	}

	private static String text(final JsonNode pNode, final String pName)
	{
		final JsonNode value = pNode.get(pName);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static int integer(final JsonNode pNode, final String pName)
	{
		final JsonNode value = pNode.get(pName);
		return value == null || !value.canConvertToInt() ? 0 : value.asInt();
	}
}
