package com.on.ebx.connect.ebx.ui;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The About page: the line for support, the guide chosen by language, a build without a stamp, escaping, and a text for
 * every key in both languages.
 */
public final class AboutPageTest
{
	private AboutPageTest() { }

	public static void main(final String[] pArgs)
	{
		copyLine();
		guides();
		builtAt();
		unstamped();
		stamped();
		bundles();
		System.out.println("PASS: About page: support line, guide by language, unstamped build, escaping and bundles");
	}

	private static void copyLine()
	{
		check("ON EBX Connectors 1.1.0 (995cc10, built 2026-10-06T08:15:00Z)".equals(
			AboutPage.copyLine("1.1.0", "995cc10aabbccddeeff00112233445566778899", "2026-10-06T08:15:00Z")), "full line");
		check("ON EBX Connectors 1.1.0-24-g995cc10 (995cc10, built 2026-10-06T08:15:00Z)".equals(
			AboutPage.copyLine("1.1.0-24-g995cc10", "995cc10", "2026-10-06T08:15:00Z")), "a short commit is kept, a between-tags version too");
		check("ON EBX Connectors unknown".equals(AboutPage.copyLine("unknown", "unknown", "unknown")), "nothing stamped: no unknown parts in brackets");
		check("ON EBX Connectors 2.0.0 (built 2026-10-06T08:15:00Z)".equals(AboutPage.copyLine("2.0.0", null, "2026-10-06T08:15:00Z")), "a missing commit is left out");
		check("abcdef0".equals(AboutPage.shortCommit("abcdef0123456789")) && "unknown".equals(AboutPage.shortCommit(null)), "short commit");
	}

	private static void guides()
	{
		final List<AboutPage.Guide> en = AboutPage.guides(Locale.ENGLISH);
		final List<AboutPage.Guide> fr = AboutPage.guides(Locale.FRANCE);
		check(en.size() == 3 && fr.size() == 3, "three documents");
		check("configuration-guide.html".equals(en.get(1).variants.get(0)[0]) && "guide-configuration.html".equals(en.get(1).variants.get(1)[0]),
			"English session: English configuration guide first, French next to it");
		check("guide-configuration.html".equals(fr.get(1).variants.get(0)[0]) && "configuration-guide.html".equals(fr.get(1).variants.get(1)[0]),
			"French session: French configuration guide first, English next to it");
		check("user-guide.html".equals(en.get(0).variants.get(0)[0]) && en.get(0).variants.size() == 1, "the user guide exists in English only");
		check(AboutPage.guides(null).get(1).variants.get(0)[1].equals("en"), "no locale reads as English");
	}

	private static void builtAt()
	{
		check("Oct 6, 2026, 8:15:00 AM UTC".equals(AboutPage.formatBuiltAt("2026-10-06T08:15:00Z", Locale.US)) || AboutPage.formatBuiltAt("2026-10-06T08:15:00Z", Locale.US).endsWith("UTC"),
			"the build time is shown in UTC: " + AboutPage.formatBuiltAt("2026-10-06T08:15:00Z", Locale.US));
		check(!AboutPage.formatBuiltAt("2026-10-06T08:15:00Z", Locale.FRANCE).equals(AboutPage.formatBuiltAt("2026-10-06T08:15:00Z", Locale.US)),
			"the build time follows the user's locale");
		check("not a date".equals(AboutPage.formatBuiltAt("not a date", Locale.US)), "an unparsable value is shown as stamped");
		check("unknown".equals(AboutPage.formatBuiltAt("unknown", Locale.US)) && "unknown".equals(AboutPage.formatBuiltAt(null, Locale.US)), "unknown");
	}

	private static void unstamped()
	{
		final String page = AboutPage.render(Locale.ENGLISH, "unknown", "unknown", "unknown", null);
		check(page.contains("<dd>unknown</dd>"), "the version reads unknown");
		check(page.contains(AboutPage.escape("This build carries no version stamp")), "the reason is said in one line");
		check(!page.contains("?version="), "no version in the links of an unstamped build");
		check(!page.contains(">ON EBX</dt>"), "no EBX line when the EBX version cannot be read");
		check(page.contains("data-line=\"ON EBX Connectors unknown\""), "the copy button carries the line");
	}

	private static void stamped()
	{
		final String page = AboutPage.render(Locale.FRANCE, "1.1.0", "995cc10aabbcc", "2026-10-06T08:15:00Z", "6.3.0-rc2");
		check(page.contains("<h1>ON EBX Connectors</h1>") && page.contains("<h2 id=\"ftab-version\">Version</h2>") && page.contains("<dl>"), "headings and a definition list");
		check(page.contains("<dt>ON EBX</dt><dd>6.3.0-rc2</dd>"), "the EBX version");
		check(page.contains("<dt>Commit</dt><dd>995cc10</dd>"), "short commit");
		check(page.contains("href=\"/ebx-connectors/www/common/help/guide-configuration.html?version=1.1.0\" target=\"_blank\" rel=\"noopener\""), "links open a new tab with the version");
		check(page.contains("Guide de l’utilisateur") && page.contains("(anglais)"), "French labels, English-only guide flagged");
		check(!page.contains("OPERATIONS") && !page.contains("operations"), "no operations document is offered");
		final String hostile = AboutPage.render(Locale.ENGLISH, "1<\"&", "x", "y", "<b>");
		check(!hostile.contains("<b>") && !hostile.contains("1<\"&"), "values are escaped");
		check(hostile.contains("?version=1%3C%22%26"), "the version in the link is URL-encoded");
	}

	private static void bundles()
	{
		final String[] keys = { "about.label", "about.description", "about.version.title", "about.version", "about.commit", "about.builtAt", "about.ebx", "about.unstamped",
			"about.copy", "about.copied", "about.copyFailed", "about.docs.title", "about.docs.intro", "about.doc.userGuide", "about.doc.configurationGuide",
			"about.doc.reference", "about.lang.en", "about.lang.fr" };
		final String bundle = "com.on.ebx.connect.ebx.ui.Messages";
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final ResourceBundle b = ResourceBundle.getBundle(bundle, locale);
			for (final String key : keys)
			{
				check(b.containsKey(key) && !b.getString(key).trim().isEmpty(), key + " in " + locale);
			}
		}
		check("À propos".equals(ResourceBundle.getBundle(bundle, Locale.FRENCH).getString("about.label")), "French label");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
