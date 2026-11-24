package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source contract of the mapping designer's keyboard and screen-reader support (audit C-42): no browser dialog, real buttons and
 * ARIA roles, a keyboard handler, and every label the script reads present in both bundles and in the model the service sends.
 * The behaviour was exercised in a browser; this keeps the structure from being undone by an edit.
 */
public final class DesignerAccessibilityContractTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final Path module = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String js = read(module.resolve("src/main/webapp/www/common/jscripts/connect/mapping-designer.js"));
		final String css = read(module.resolve("src/main/webapp/www/common/stylesheets/connect/mapping-designer.css"));
		final String service = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/MappingDesignerUserService.java"));

		check(!js.contains("window.prompt") && !js.contains("window.confirm"), "no browser prompt or confirm: an in-page dialog is used");
		check(js.contains("role=\"dialog\"") && js.contains("aria-modal=\"true\"") && js.contains("'Escape'"), "the dialog is a modal dialog that Esc closes");
		check(!js.contains("'<a ") && !js.contains("<a id=") && !js.contains("<a data-"), "actions are buttons, not anchors without a target");
		for (final String needed : new String[] { "role=\"tree\"", "role=\"treeitem\"", "aria-expanded", "aria-level", "aria-selected", "aria-label", "aria-pressed", "aria-live=\"polite\"",
			"tabindex", "'ArrowDown'", "'ArrowRight'", "'ArrowLeft'", "'Home'", "'Enter'", "' '", "'Delete'", "addEventListener('keydown'", "addEventListener('focusin'" })
		{
			check(js.contains(needed), "the designer script must contain " + needed);
		}
		check(css.contains(":focus-visible") && css.contains(".ftmdSr"), "a visible focus ring and a screen-reader-only class");
		check(css.contains(".ftmdRow.ftmdMapped .ftmdName:before") && css.contains(".ftmdSeg button.ftmdOn:before"), "a mapped field and the active view are marked by a glyph, not by colour alone");

		// every label the script reads exists in the model the service sends and in both bundles
		final Matcher used = Pattern.compile("t\\('(designer\\.[A-Za-z0-9_.]+)'").matcher(js);
		final Properties en = bundle(module, "en");
		final Properties fr = bundle(module, "fr");
		int checked = 0;
		while (used.find())
		{
			final String key = used.group(1);
			if (key.endsWith("."))
			{
				continue; // a prefix completed at run time (designer.policy.<POLICY>): its members are listed one by one
			}
			check(service.contains("\"" + key + "\""), "the service must send the label " + key);
			check(en.getProperty(key) != null, "English bundle lacks " + key);
			check(fr.getProperty(key) != null, "French bundle lacks " + key);
			checked++;
		}
		check(checked > 60, "the script reads many labels: " + checked);
		System.out.println("PASS: the mapping designer script is keyboard- and screen-reader-operable by contract (" + checked + " label uses checked in en and fr)");
	}

	private static Properties bundle(final Path pModule, final String pLanguage) throws Exception
	{
		final Properties out = new Properties();
		try (java.io.InputStream in = Files.newInputStream(pModule.resolve("src/main/resources/com/on/ebx/connect/ebx/ui/Messages_" + pLanguage + ".properties")))
		{
			out.load(in);
		}
		return out;
	}

	private static String read(final Path pPath) throws Exception
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
