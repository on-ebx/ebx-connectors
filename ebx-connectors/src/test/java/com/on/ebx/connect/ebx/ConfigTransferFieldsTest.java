package com.on.ebx.connect.ebx;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Every field of the Mapping table is either carried by the configuration export, import and
 * {@code connectors_mapping_upsert}, or listed with the reason it is not. A setting added to the
 * model without one of the two fails here instead of being lost on the way to another environment.
 */
public final class ConfigTransferFieldsTest
{
	private static final String XS = "http://www.w3.org/2001/XMLSchema";

	public static void main(final String[] pArgs) throws Exception
	{
		// C2-02: an export carries no webhook URL, only the placeholder an import expects; other URLs travel as stored
		check("TO_BE_SET".equals(ConfigTransfer.exportedBaseUrl("chat-webhook", "https://hooks.slack.com/services/T0/B0/tok")), "a webhook URL would be exported");
		check(ConfigTransfer.exportedBaseUrl("rest", "https://api.example.com/v1") == null, "an ordinary base URL must stay in the export");
		check(ConfigTransfer.exportedBaseUrl("chat-webhook", "TO_BE_SET") == null, "an exported placeholder is already safe");
		final List<String> model = modelFields("Mapping");
		check(model.size() > 25 && model.contains("tablePath"), "the Mapping table was not read from the model: " + model);
		final List<String> transferred = ConfigTransfer.mappingFields();
		final Set<String> notTransferred = ConfigTransfer.mappingFieldsNotTransferred().keySet();

		for (final String field : model)
		{
			check(transferred.contains(field) || notTransferred.contains(field),
				"Mapping/" + field + " is in the model and neither transferred nor listed as not transferred");
		}
		for (final String field : transferred)
		{
			check(model.contains(field), "ConfigTransfer lists Mapping/" + field + ", which the model does not have");
			check(!notTransferred.contains(field), "Mapping/" + field + " is both transferred and listed as not transferred");
		}
		for (final String field : notTransferred)
		{
			check(model.contains(field), "the not-transferred list names Mapping/" + field + ", which the model does not have");
		}
		check(new HashSet<String>(transferred).size() == transferred.size(), "a Mapping field is listed twice");
		for (final String reason : ConfigTransfer.mappingFieldsNotTransferred().values())
		{
			check(reason != null && reason.length() > 10, "a field left out needs its reason");
		}
		// the two settings the audit found missing
		check(transferred.contains("batchSize") && transferred.contains("logEveryRecord"), "batchSize and logEveryRecord are transferred");
		System.out.println("PASS: every Mapping field is transferred or explained (" + transferred.size() + " transferred, " + notTransferred.size() + " explained)");
	}

	private static List<String> modelFields(final String pTable) throws Exception
	{
		final String configured = System.getProperty("ebx.connectors.moduleDir", ".");
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		final Document doc = factory.newDocumentBuilder().parse(Paths.get(configured, "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd").toFile());
		final NodeList all = doc.getElementsByTagNameNS(XS, "element");
		for (int i = 0; i < all.getLength(); i++)
		{
			final Element table = (Element) all.item(i);
			if (!pTable.equals(table.getAttribute("name")) || !"unbounded".equals(table.getAttribute("maxOccurs")))
			{
				continue;
			}
			final List<String> out = new ArrayList<String>();
			final NodeList nested = table.getElementsByTagNameNS(XS, "element");
			for (int j = 0; j < nested.getLength(); j++)
			{
				final Element field = (Element) nested.item(j);
				// direct fields of the table's own sequence only
				final Node sequence = field.getParentNode();
				if (sequence != null && sequence.getParentNode() != null && sequence.getParentNode().getParentNode() == table)
				{
					out.add(field.getAttribute("name"));
				}
			}
			return out;
		}
		throw new IllegalStateException("table " + pTable + " not found in the model");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
