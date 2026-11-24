package com.on.ebx.connect.core;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Gartner lot A, offline: the mapping engine reads a document's confidence envelope - a value below the
 * threshold is a proposal (conflict with a comment), never staged; a value at or above it is written with
 * its source kept for FieldProvenance; a document without envelope behaves exactly as before.
 */
public final class DocumentConfidenceTest
{
	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private static MappingDefinition mapping()
	{
		final MappingDefinition m = new MappingDefinition(7, "docs", "extract", MappingDefinition.Direction.INBOUND,
			"invoices", "Suppliers", "Suppliers", "/root/Invoice", "", true);
		m.addRule(new MappingRule(1, "invoiceNumber", "./number", "", MappingRule.Policy.ALWAYS, ""));
		m.addRule(new MappingRule(2, "total", "./total", "", MappingRule.Policy.ALWAYS, ""));
		m.addRule(new MappingRule(3, "document", "./sourceDocument", "", MappingRule.Policy.ALWAYS, ""));
		return m;
	}

	public static void main(final String[] args)
	{
		final ObjectNode item = Json.object();
		item.put("document", "invoice-42.pdf");
		item.put("invoiceNumber", "INV-2026-0042");
		item.put("total", 1234.5);
		item.putObject(DocumentExtraction.CONFIDENCE).put("invoiceNumber", 0.97).put("total", 0.6);
		item.put(DocumentExtraction.MIN_CONFIDENCE, 0.8);
		item.putObject(DocumentExtraction.SOURCES).put("invoiceNumber", "invoice-42.pdf p.1: \"Invoice n INV-2026-0042\"")
			.put("total", "invoice-42.pdf p.2: \"Total: 1 234,50 EUR\"");

		final MappingEngine.Values values = MappingEngine.compute(mapping(), item, null);
		check(values.getProposals().size() == 2, "total (0.6) and document (no confidence) are proposals: " + values.getProposals());
		check(values.getProposals().get("./total").equals("confidence 0.60 < 0.80 - invoice-42.pdf p.2: \"Total: 1 234,50 EUR\""), values.getProposals().get("./total"));
		check(values.getProposals().get("./sourceDocument").startsWith("confidence 0.00 < 0.80"), "an attribute without confidence never writes alone");

		final SchemaNode field = (SchemaNode) Proxy.newProxyInstance(SchemaNode.class.getClassLoader(), new Class<?>[] { SchemaNode.class },
			(p, m, a) -> { if (m.getName().equals("isTerminalValue")) return Boolean.TRUE; throw new UnsupportedOperationException(m.getName()); });
		final SchemaNode table = (SchemaNode) Proxy.newProxyInstance(SchemaNode.class.getClassLoader(), new Class<?>[] { SchemaNode.class },
			(p, m, a) -> { if (m.getName().equals("getNode")) return field; throw new UnsupportedOperationException(m.getName()); });
		final Map<String, String> staged = new HashMap<String, String>();
		final ValueContextForUpdate update = (ValueContextForUpdate) Proxy.newProxyInstance(ValueContextForUpdate.class.getClassLoader(),
			new Class<?>[] { ValueContextForUpdate.class }, (p, m, a) -> {
				if (m.getName().equals("setValueFromXsString")) { staged.put(((com.orchestranetworks.schema.Path) a[1]).format(), (String) a[0]); return null; }
				throw new UnsupportedOperationException(m.getName());
			});
		final MappingResult result = MappingEngine.applyValues(values, null, update, table);
		check(staged.size() == 1 && "INV-2026-0042".equals(staged.get("./number")), "only the confident value is staged: " + staged);
		check(result.getChanges().size() == 1 && result.getChanges().get(0).getSource().contains("p.1"), "the change carries its source");
		check(result.getConflicts().size() == 2, "two proposals for the steward");
		final MappingResult.Conflict total = result.getConflicts().get(0).path.equals("./total") ? result.getConflicts().get(0) : result.getConflicts().get(1);
		check("1234.5".equals(total.proposedValue) && total.currentValue == null && total.comment.contains("p.2"), "proposal on a new record: " + total);

		// no envelope: unchanged behaviour
		item.remove(DocumentExtraction.MIN_CONFIDENCE);
		final MappingEngine.Values plain = MappingEngine.compute(mapping(), item, null);
		check(plain.getProposals().isEmpty(), "no threshold, no proposal");
		staged.clear();
		final MappingResult written = MappingEngine.applyValues(plain, null, update, table);
		check(staged.size() == 3 && written.getConflicts().isEmpty() && written.getChanges().get(0).getSource() == null, "every value written, no source");
		check(MappingEngine.proposalComment(0.1, 0.8, String.join("", java.util.Collections.nCopies(600, "x"))).length() == 500, "comment fits Conflict.comment");
		System.out.println("DocumentConfidenceTest PASS: below-threshold values proposed with comment, confident values written with source, no envelope unchanged");
	}
}
