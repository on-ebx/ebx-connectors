package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.connectors.JdbcConnectorBoundValueProbe;

/**
 * Audit C2-04 (a value substituted in a key predicate never leaves its literal) and C2-05 (a value substituted in a
 * sql input becomes a bound parameter). Pure: no EBX session.
 */
public final class ValueTemplatesTest
{
	private ValueTemplatesTest() { }

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static String predicate(final String pTemplate, final String pValue) throws Exception
	{
		final JsonNode item = Json.parse("{\"v\":" + Json.write(Json.mapper().getNodeFactory().textNode(pValue)) + "}");
		return ConnectorService.resolvePredicate(pTemplate, item);
	}

	public static void main(final String[] pArgs) throws Exception
	{
		xpathValues();
		xpathInjection();
		xpathRefusals();
		sqlBinding();
		System.out.println("PASS: templates carry values as data, never as code");
	}

	private static void xpathValues() throws Exception
	{
		check("./id='abc'".equals(predicate("./id='${v}'", "abc")), "plain");
		check("./id=\"it's\"".equals(predicate("./id='${v}'", "it's")), "apostrophe");
		check("./id='say \"hi\"'".equals(predicate("./id='${v}'", "say \"hi\"")), "quotation marks");
		check("./id='both '' and \"'".equals(predicate("./id='${v}'", "both ' and \"")), "both kinds: " + predicate("./id='${v}'", "both ' and \""));
		check("./id=''".equals(predicate("./id='${v}'", "")), "empty");
		check("./id='é中☃'".equals(predicate("./id='${v}'", "é中☃")), "unicode");
		check("./a='x' and ./b='y'".equals(predicate("./a='x' and ./b='${v}'", "y")), "composite key");
		check("osd:is-null(./f) or ./g='z'".equals(predicate("osd:is-null(./f) or ./g='${v}'", "z")), "is-null keeps working");
		check("./id='AB-9'".equals(predicate("./id='AB-${v}'", "9")), "text around the placeholder stays in the literal");
	}

	private static void xpathInjection() throws Exception
	{
		final String evil = "x' or ./id!='";
		final String resolved = predicate("./id='${v}'", evil);
		check(resolved.equals("./id=" + ValueTemplates.literal(evil)) && resolved.startsWith("./id=\""), "an injected apostrophe stays inside one literal: " + resolved);
		final String quotes = "x\" or 1=1 or \"";
		check(predicate("./id='${v}'", quotes).equals("./id='x\" or 1=1 or \"'"), "an injected quotation mark stays inside one literal");
		final String legacy = predicate("./id=${v}", "1 or 1=1");
		check(legacy.equals("./id='1 or 1=1'"), "a placeholder outside quotes (older mapping) cannot carry an expression: " + legacy);
		check("./id=42".equals(predicate("./id=${v}", "42")), "an older numeric mapping keeps working");
	}

	private static void xpathRefusals() throws Exception
	{
		check(ValueTemplates.xpathRefusal("./id='${v}'") == null, "quoted accepted");
		check(ValueTemplates.xpathRefusal("./a='${x}' and ./b=\"${y}\"") == null, "double quotes accepted");
		check(ValueTemplates.xpathRefusal("osd:is-null(./f)") == null, "no placeholder accepted");
		check(ValueTemplates.xpathRefusal("") == null && ValueTemplates.xpathRefusal(null) == null, "empty accepted");
		final String refusal = ValueTemplates.xpathRefusal("./id=${v}");
		check(refusal != null && refusal.contains("./id='${v}'") && refusal.contains("FR:"), "outside apostrophes refused, EN + FR, expected form shown: " + refusal);
		check(ValueTemplates.xpathRefusal("./a='${x}' and ./b=${y}") != null, "one bad clause among good ones");
		check(ValueTemplates.xpathRefusal("./id='${v}") != null, "unclosed literal refused");
	}

	private static Map<String, String> bound()
	{
		return new LinkedHashMap<String, String>();
	}

	private static ValueTemplates.Values values(final String... pPairs)
	{
		final Map<String, String> map = new LinkedHashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2) map.put(pPairs[i], pPairs[i + 1]);
		return new ValueTemplates.Values()
		{
			@Override
			public String of(final String pExpression)
			{
				return map.get(pExpression);
			}
		};
	}

	private static void sqlBinding() throws Exception
	{
		Map<String, String> b = bound();
		String sql = ValueTemplates.sql("insert into T(a, b) values ('${./name}', ${./n})", values("./name", "O'Brien'); drop table T;--", "./n", "5"), b);
		check("insert into T(a, b) values (:__b0, :__b1)".equals(sql), "values become parameters: " + sql);
		check("O'Brien'); drop table T;--".equals(b.get("__b0")) && "5".equals(b.get("__b1")), "the value travels apart from the text: " + b);
		b = bound();
		sql = ValueTemplates.sql("select * from T where n like '%${x}%' and c = :c", values("x", "a%"), b);
		check("select * from T where n like :__b0 and c = :c".equals(sql) && "%a%%".equals(b.get("__b0")), "text around the placeholder is part of the bound value: " + sql + b);
		b = bound();
		sql = ValueTemplates.sql("select 1 -- ${x}\n", values("x", "1\ndrop"), b);
		check(!sql.contains("drop") && b.isEmpty(), "a comment never receives a value");
		// the watermark flow is untouched: its placeholder is protected before this code sees the text
		b = bound();
		sql = ValueTemplates.sql("select * from T s where s.M >= WATERMARK and s.k = '${k}'", values("k", "z"), b);
		check("select * from T s where s.M >= WATERMARK and s.k = :__b0".equals(sql), "WATERMARK sentinel left alone: " + sql);
		// identifier positions: written only when the value is a plain identifier
		b = bound();
		sql = ValueTemplates.sql("GRANT SELECT ON ${asset} TO ${user}", values("asset", "DB.SCH.TAB_1", "user", "ANALYST"), b);
		check("GRANT SELECT ON DB.SCH.TAB_1 TO ANALYST".equals(sql) && b.isEmpty(), "identifier positions: " + sql);
		for (final String evil : new String[] { "T; drop table X", "T\"", "a b", "" })
		{
			try
			{
				ValueTemplates.sql("GRANT SELECT ON ${asset} TO R", values("asset", evil), bound());
				check(false, "refused: " + evil);
			}
			catch (final IllegalArgumentException expected)
			{
				check(expected.getMessage().contains("SQL_PLACEHOLDER_NOT_BINDABLE") && expected.getMessage().contains("FR:"), "coded EN + FR message");
			}
		}
		check(ValueTemplates.sqlRefusal("select '${x}") != null && ValueTemplates.sqlRefusal("select '${x}'") == null, "unclosed quoted placeholder refused at save");
		check("12".equals(String.valueOf(JdbcConnectorBoundValueProbe.of("12"))) && JdbcConnectorBoundValueProbe.of("12") instanceof Long, "canonical number is a number");
		check(JdbcConnectorBoundValueProbe.of("0012") instanceof String && JdbcConnectorBoundValueProbe.of("007") instanceof String, "a code with leading zeros stays text");
		check(JdbcConnectorBoundValueProbe.of("1.50") instanceof String && JdbcConnectorBoundValueProbe.of("1.5") instanceof java.math.BigDecimal, "decimal only in canonical form");
	}
}
