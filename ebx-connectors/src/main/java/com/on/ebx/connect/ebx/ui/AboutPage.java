package com.on.ebx.connect.ebx.ui;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.ModuleVersion;
import com.orchestranetworks.instance.Repository;

/**
 * The markup of the About page and the small decisions behind it (all pure, so they are tested without EBX): what the
 * line for support says, which version of each guide the session's language gets, how the build time reads.
 *
 * <p>The guides are static pages generated from the repository's Markdown by {@code tools/build_help.py} into
 * {@code www/common/help}; the link carries the version as {@code ?version=}, which {@code help-version.js} writes in the
 * top bar of the page, so the committed pages do not depend on the build.
 */
final class AboutPage
{
	static final String HELP_FOLDER = "/" + ConnectorsPaths.MODULE_NAME + "/www/common/help/";
	static final String PRODUCT = "ON EBX Connectors";

	/** Copies the support line (the button carries it as data-line); the fallback serves a frame that may not write to the clipboard. */
	static final String COPY_SCRIPT = "(function(){var b=document.querySelector('.ftab-copy');if(!b||b.getAttribute('data-bound')){return;}"
		+ "b.setAttribute('data-bound','1');b.addEventListener('click',function(){var t=b.getAttribute('data-line');var s=document.getElementById('ftab-copied');"
		+ "function done(ok){s.textContent=ok?b.getAttribute('data-ok'):b.getAttribute('data-fail');}"
		+ "function fallback(){try{var a=document.createElement('textarea');a.value=t;a.setAttribute('readonly','');a.style.position='fixed';a.style.opacity='0';"
		+ "document.body.appendChild(a);a.select();var ok=document.execCommand('copy');document.body.removeChild(a);done(ok);}catch(e){done(false);}}"
		+ "if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(function(){done(true);},fallback);}else{fallback();}});})();";

	private static final String STYLE = "<style>.ftab{max-width:860px;margin:0 auto;padding:20px 24px;font:14px/1.55 sans-serif}"
		+ ".ftab h1{margin:0 0 4px;font-size:26px;color:var(--ftc-accent-ink,#0b5394)}.ftab h2{margin:26px 0 8px;font-size:18px;"
		+ "border-bottom:2px solid var(--ftc-accent-line,#c6dafc);padding-bottom:4px}.ftab-lead{margin:0;color:#555}"
		+ ".ftab dl{display:grid;grid-template-columns:max-content 1fr;gap:6px 18px;margin:0}.ftab dt{font-weight:600}.ftab dd{margin:0;font-family:Menlo,Consolas,monospace}"
		+ ".ftab-note{margin:10px 0 0;padding:8px 12px;background:var(--ftc-accent-soft,#f5f9fe);border-left:4px solid var(--ftc-accent-line,#c6dafc)}"
		+ ".ftab-copy{margin-top:12px;padding:5px 14px;border:1px solid var(--ebx-button,var(--ftc-accent-ink,#0b5394));border-radius:4px;background:#fff;"
		+ "color:var(--ftc-accent-ink,#0b5394);cursor:pointer;font:inherit}.ftab-copy:focus-visible,.ftab a:focus-visible{outline:3px solid var(--ebx-focus,#0b5394);outline-offset:2px}"
		+ ".ftab-copied{margin-left:10px;color:#555}.ftab ul{margin:0;padding-left:20px}.ftab li{margin:8px 0}.ftab a{color:var(--ftc-accent-ink,#0b5394)}"
		+ ".ftab-other{color:#555}</style>";

	/** One guide, with the language versions that exist: the session's language first. */
	static final class Guide
	{
		final String titleKey;
		final List<String[]> variants; // {file, language}

		Guide(final String pTitleKey, final List<String[]> pVariants)
		{
			this.titleKey = pTitleKey;
			this.variants = pVariants;
		}
	}

	private AboutPage()
	{
	}

	static boolean isFrench(final Locale pLocale)
	{
		return pLocale != null && "fr".equalsIgnoreCase(pLocale.getLanguage());
	}

	/**
	 * The documents offered: the user guide and the reference exist in English only; the configuration guide exists in both
	 * languages and the session's language is listed first. Operations and security documents are not offered here: they
	 * describe the internals of an installation, not what a user does.
	 */
	static List<Guide> guides(final Locale pLocale)
	{
		final String[] en = { "configuration-guide.html", "en" };
		final String[] fr = { "guide-configuration.html", "fr" };
		final List<Guide> guides = new ArrayList<Guide>();
		guides.add(new Guide("about.doc.userGuide", Collections.singletonList(new String[] { "user-guide.html", "en" })));
		final List<String[]> configuration = new ArrayList<String[]>();
		configuration.add(isFrench(pLocale) ? fr : en);
		configuration.add(isFrench(pLocale) ? en : fr);
		guides.add(new Guide("about.doc.configurationGuide", configuration));
		guides.add(new Guide("about.doc.reference", Collections.singletonList(new String[] { "reference.html", "en" })));
		return guides;
	}

	/** The commit as short as a reader needs it: seven characters of a full hash, anything else as stamped. */
	static String shortCommit(final String pCommit)
	{
		if (pCommit == null)
		{
			return ModuleVersion.UNKNOWN;
		}
		final String commit = pCommit.trim();
		return commit.matches("[0-9a-fA-F]{8,64}") ? commit.substring(0, 7) : commit;
	}

	/**
	 * The line a user pastes into a support request: {@code ON EBX Connectors 1.1.0 (995cc10, built 2026-10-06T08:15:00Z)}.
	 * A part the build did not stamp is left out, never written as "unknown" next to the others.
	 */
	static String copyLine(final String pVersion, final String pCommit, final String pBuiltAt)
	{
		final StringBuilder line = new StringBuilder(PRODUCT).append(' ').append(known(pVersion) ? pVersion.trim() : ModuleVersion.UNKNOWN);
		final List<String> details = new ArrayList<String>();
		if (known(pCommit))
		{
			details.add(shortCommit(pCommit));
		}
		if (known(pBuiltAt))
		{
			details.add("built " + pBuiltAt.trim());
		}
		if (!details.isEmpty())
		{
			line.append(" (").append(join(details)).append(')');
		}
		return line.toString();
	}

	/** The build time in the user's language and in UTC; a value that is not an ISO instant is shown as stamped. */
	static String formatBuiltAt(final String pBuiltAt, final Locale pLocale)
	{
		if (!known(pBuiltAt))
		{
			return ModuleVersion.UNKNOWN;
		}
		try
		{
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(pLocale == null ? Locale.ENGLISH : pLocale).withZone(ZoneOffset.UTC)
				.format(Instant.parse(pBuiltAt.trim())) + " UTC";
		}
		catch (final RuntimeException ex)
		{
			return pBuiltAt.trim();
		}
	}

	/** The EBX version that runs this module, null when it cannot be read (the line is then left out). */
	static String ebxVersion()
	{
		try
		{
			final Package pack = Repository.class.getPackage();
			final String version = pack == null ? null : pack.getImplementationVersion();
			return version == null || version.trim().isEmpty() ? null : version.trim();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	static String render(final Locale pLocale, final String pVersion, final String pCommit, final String pBuiltAt, final String pEbxVersion)
	{
		final boolean stamped = known(pVersion);
		final String line = copyLine(pVersion, pCommit, pBuiltAt);
		final StringBuilder html = new StringBuilder(STYLE).append("<main class=\"ftab\"><header><h1>").append(escape(PRODUCT)).append("</h1><p class=\"ftab-lead\">")
			.append(escape(text(pLocale, "about.description"))).append("</p></header>");
		html.append("<section aria-labelledby=\"ftab-version\"><h2 id=\"ftab-version\">").append(escape(text(pLocale, "about.version.title"))).append("</h2><dl>");
		row(html, text(pLocale, "about.version"), stamped ? pVersion.trim() : ModuleVersion.UNKNOWN);
		row(html, text(pLocale, "about.commit"), known(pCommit) ? shortCommit(pCommit) : ModuleVersion.UNKNOWN);
		row(html, text(pLocale, "about.builtAt"), formatBuiltAt(pBuiltAt, pLocale));
		if (pEbxVersion != null && !pEbxVersion.trim().isEmpty())
		{
			row(html, text(pLocale, "about.ebx"), pEbxVersion.trim());
		}
		html.append("</dl>");
		if (!stamped)
		{
			html.append("<p class=\"ftab-note\">").append(escape(text(pLocale, "about.unstamped"))).append("</p>");
		}
		html.append("<button type=\"button\" class=\"ftab-copy\" data-line=\"").append(escape(line)).append("\" data-ok=\"").append(escape(text(pLocale, "about.copied")))
			.append("\" data-fail=\"").append(escape(text(pLocale, "about.copyFailed"))).append("\">").append(escape(text(pLocale, "about.copy")))
			.append("</button><span id=\"ftab-copied\" class=\"ftab-copied\" role=\"status\" aria-live=\"polite\"></span></section>");
		html.append("<section aria-labelledby=\"ftab-docs\"><h2 id=\"ftab-docs\">").append(escape(text(pLocale, "about.docs.title"))).append("</h2><p>")
			.append(escape(text(pLocale, "about.docs.intro"))).append("</p><ul>");
		final String query = stamped ? "?version=" + urlEncode(pVersion.trim()) : "";
		for (final Guide guide : guides(pLocale))
		{
			html.append("<li>");
			boolean first = true;
			for (final String[] variant : guide.variants)
			{
				final String label = first ? text(pLocale, guide.titleKey) : text(pLocale, guide.titleKey) + " (" + text(pLocale, "about.lang." + variant[1]) + ")";
				if (!first)
				{
					html.append(" <span class=\"ftab-other\">&middot; ");
				}
				html.append("<a href=\"").append(escape(HELP_FOLDER + variant[0] + query)).append("\" target=\"_blank\" rel=\"noopener\" hreflang=\"").append(variant[1])
					.append("\">").append(escape(label)).append("</a>");
				if (first && !variant[1].equals(isFrench(pLocale) ? "fr" : "en"))
				{
					html.append(" <span class=\"ftab-other\">(").append(escape(text(pLocale, "about.lang." + variant[1]))).append(")</span>");
				}
				if (!first)
				{
					html.append("</span>");
				}
				first = false;
			}
			html.append("</li>");
		}
		return html.append("</ul></section></main>").toString();
	}

	private static void row(final StringBuilder pHtml, final String pLabel, final String pValue)
	{
		pHtml.append("<dt>").append(escape(pLabel)).append("</dt><dd>").append(escape(pValue)).append("</dd>");
	}

	private static String text(final Locale pLocale, final String pKey)
	{
		return Messages.get(AboutPage.class, pLocale, pKey);
	}

	private static boolean known(final String pValue)
	{
		return pValue != null && !pValue.trim().isEmpty() && !ModuleVersion.UNKNOWN.equals(pValue.trim());
	}

	private static String join(final List<String> pParts)
	{
		final StringBuilder out = new StringBuilder();
		for (final String part : pParts)
		{
			out.append(out.length() == 0 ? "" : ", ").append(part);
		}
		return out.toString();
	}

	private static String urlEncode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			return "";
		}
	}

	static String escape(final String pValue)
	{
		return ReleaseNotesUserService.escape(pValue);
	}
}
