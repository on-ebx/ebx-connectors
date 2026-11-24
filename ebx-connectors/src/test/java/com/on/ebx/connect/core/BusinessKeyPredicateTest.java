package com.on.ebx.connect.core;

import java.util.Arrays;

/** The keyPredicate shapes that may be answered from an in-memory index, and the many that may not (audit C-10). */
public final class BusinessKeyPredicateTest
{
	private BusinessKeyPredicateTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final BusinessKeyPredicate one = BusinessKeyPredicate.parse("./sourceId='${customerId}'");
		check(one != null && one.getFields().equals(Arrays.asList("sourceId")) && one.getPlaceholders().equals(Arrays.asList("customerId")), "the module's own recommended pattern");

		final BusinessKeyPredicate two = BusinessKeyPredicate.parse("  ./sourceSystem = '${system}'   and ./sourceId='${data.id}' ");
		check(two != null && two.getFields().equals(Arrays.asList("sourceSystem", "sourceId")), "two clauses, spaces tolerated, template order kept");
		check(two.getPlaceholders().equals(Arrays.asList("system", "data.id")), "placeholders follow the fields: " + two.getPlaceholders());
		check(BusinessKeyPredicate.parse("./a='${x y}'").getPlaceholders().equals(Arrays.asList("x y")), "the expression is taken whole between ${ and }");

		final String[] refused = {
			null, "", "   ",
			"./a='${x}' or ./b='${y}'",
			"./a='${x}' and ./a='${y}'",
			"./a!='${x}'",
			"./a='${x}' and ./b",
			"./a='x'",
			"./a='x${y}'",
			"./a='${y}z'",
			"./a='${x}' and ./b='${y}' and",
			"./a=${x}",
			"./group/a='${x}'",
			"a='${x}'",
			"./a=concat('${x}','${y}')",
			"osd:is-null(./a)",
			"./a='${x}' and (./b='${y}')",
			"./a='${x}' and not(./b='${y}')",
			"./a='${x}' and ./active=true",
			"./a=\"${x}\"",
			"./a='${}'",
			"./a='${x}'; ./b='${y}'",
		};
		for (final String template : refused)
		{
			check(BusinessKeyPredicate.parse(template) == null, "must be refused (EBX keeps answering the XPath): " + template);
		}
		System.out.println("PASS: BusinessKeyPredicate recognizes only plain business-key equalities");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
