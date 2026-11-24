package com.on.ebx.connect.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Guards the JSON written into a page as script source: whatever a remote sample holds, the text carries nothing
 * the HTML parser reacts to, and it still reads back as the same value. Also guards that no screen writes plain
 * JSON into a script again.
 */
public final class JsonForScriptTest
{
	private JsonForScriptTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode model = Json.object();
		final String hostile = "</script><script>alert(1)</script><!-- a & b \u2028 c \u2029 d";
		model.put("sample", hostile);
		model.put(hostile, 1);
		model.putArray("rows").addObject().put("name", "<b>R&D</b>");
		model.put("plain", "caf\u00e9 \"quoted\" \\ back/slash");

		final String plain = Json.write(model);
		check(plain.contains("</script>"), "the plain writer leaves markup as is (what this writer exists for)");
		final String safe = Json.writeForScript(model);
		for (final char forbidden : new char[] { '<', '>', '&', '\u2028', '\u2029' })
		{
			check(safe.indexOf(forbidden) < 0, "character U+" + Integer.toHexString(forbidden) + " must not reach the page");
		}
		check(safe.contains("\\u003c/script\\u003e") && safe.contains("\\u0026") && safe.contains("\\u2028") && safe.contains("\\u2029"),
			"each character is written as its JSON escape");
		final JsonNode back = Json.parse(safe);
		check(model.equals(back), "the escaped text is the same JSON value");
		check(hostile.equals(back.get("sample").asText()) && back.has(hostile), "values and field names read back unchanged");

		final String harmless = "{\"a\":\"caf\u00e9\",\"b\":[1,2,null,true]}";
		check(Json.forScript(harmless) == harmless, "a text with nothing to escape is returned as is");
		check("null".equals(Json.forScript(null)) && "null".equals(Json.writeForScript(null)), "no value is the JSON null");
		check("\"\\u003c\\u003e\"".equals(Json.forScript("\"<>\"")), "hand-built JSON is covered too");

		final String moduleDir = System.getProperty("ebx.connectors.moduleDir");
		check(moduleDir != null, "-Debx.connectors.moduleDir is required: the screens are read as sources");
		final File[] screens = new File(moduleDir, "src/main/java/com/on/ebx/connect/ebx/ui").listFiles();
		check(screens != null && screens.length > 10, "the screens were not found under " + moduleDir);
		for (final File screen : screens)
		{
			if (!screen.getName().endsWith(".java"))
			{
				continue;
			}
			int number = 0;
			for (final String line : new String(Files.readAllBytes(screen.toPath()), StandardCharsets.UTF_8).split("\n"))
			{
				number++;
				check(!(line.contains("addJS") && line.contains("Json.write(")),
					screen.getName() + ":" + number + " writes plain JSON into a script: use Json.writeForScript");
			}
		}
		System.out.println("JsonForScriptTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
