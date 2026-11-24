package com.on.ebx.connect.ebx.ui;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.ui.UIBodyWriter;
import com.orchestranetworks.ui.form.UIForm;
import com.orchestranetworks.ui.form.UIFormBottomBar;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPaneWriter;
import com.orchestranetworks.ui.form.widget.UIWidget;

/**
 * Base of the record forms of the tables the connector runtime writes on its own (runs, outbound
 * deliveries, checkpoints, metrics): no save or revert button, editors disabled, and the shared
 * pieces of their summary band. Same presentation as {@link SyncLogForm}; access rights are left
 * untouched.
 */
abstract class ReadOnlyForm extends UIForm
{
	@Override
	public void defineBottomBar(final UIFormBottomBar pBar, final UIFormContext pContext)
	{
		pBar.setSubmitButtonDisplayable(false);
		pBar.setSubmitAndCloseButtonDisplayable(false);
		pBar.setRevertButtonDisplayable(false);
		pBar.setCloseButtonDisplayable(true);
	}

	static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(ReadOnlyForm.class, pLocale, pKey, pArgs);
	}

	/** Also written by user services (the run screen), hence the plain body writer. */
	static void styles(final UIBodyWriter pWriter)
	{
		MappingForm.styles(pWriter);
		pWriter.add("<style>.ftroTiles{display:flex;flex-wrap:wrap;gap:12px;margin:8px 0 6px}"
			+ ".ftroTile{flex:1 1 170px;min-width:0;border:1px solid var(--ebx-table-border,#dadce0);border-radius:8px;padding:10px 14px;background:var(--ebx-table-even,#fff)}"
			+ ".ftroTileLabel{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--ftc-muted,#5f6368)}"
			+ ".ftroTileValue{font-size:16px;font-weight:600;color:var(--ftc-text,#202124);margin:4px 0 2px;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}"
			+ ".ftroTileDetail{font-size:12px;color:var(--ftc-muted,#5f6368);overflow-wrap:anywhere}"
			+ ".ftroBadge{display:inline-block;font-size:13px;font-weight:600;padding:2px 10px;border-radius:12px;background:var(--ebx-table-header,#f1f3f4);color:var(--ftc-muted,#5f6368)}"
			+ ".ftroGood{background:var(--ftc-ok-bg,#e6f4ea);color:var(--ftc-ok,#137333)}.ftroBad{background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-bad,#c5221f)}.ftroWarn{background:var(--ftc-warn-bg,#fef7e0);color:var(--ftc-warn,#b06000)}"
			+ ".ftroInfo{background:var(--ftc-accent-soft,#e8f0fe);color:var(--ftc-accent-ink,#174ea6)}"
			+ ".ftroError{margin:6px 0 4px;padding:8px 12px;border-left:3px solid var(--ftc-bad,#d93025);background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-text,#3c4043);"
			+ "font-size:12px;white-space:pre-wrap;word-break:break-word}.ftroError b{color:var(--ftc-bad,#c5221f)}"
			+ ".ftroLinks{display:flex;flex-wrap:wrap;gap:6px 18px;margin:2px 0 6px;font-size:12px}"
			+ "a.ftroLink{color:var(--ftc-accent-ink,#1a73e8);font-weight:600;text-decoration:none}a.ftroLink:hover{text-decoration:underline}</style>");
	}

	static void hint(final UIBodyWriter pWriter, final String pText)
	{
		pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(pText)).add("</div>");
	}

	/** A titled section of standard EBX rows with disabled editors; paths the loaded model lacks are skipped. */
	static void block(final UIFormPaneWriter pWriter, final UIFormContext pContext, final String pTitle, final String pHint, final Path[] pPaths)
	{
		MappingForm.section(pWriter, pTitle, pHint);
		final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
		pWriter.startTableFormRow();
		for (final Path path : pPaths)
		{
			if (root.getNode(path) == null)
			{
				continue;
			}
			final UIWidget widget = pWriter.newBestMatching(path);
			widget.setEditorDisabled(true);
			pWriter.addFormRow(widget);
		}
		pWriter.endTableFormRow();
	}

	static void tilesStart(final UIBodyWriter pWriter)
	{
		pWriter.add("<div class=\"ftroTiles\">");
	}

	static void tilesEnd(final UIBodyWriter pWriter)
	{
		pWriter.add("</div>");
	}

	/** One tile; the value is HTML, the detail plain text (omitted when empty). */
	static void tile(final UIBodyWriter pWriter, final String pLabel, final String pValueHtml, final String pDetail)
	{
		pWriter.add(ReadOnlyForm.tileHtml(pLabel, pValueHtml, pDetail));
	}

	/** {@link #tile} as a string, for a panel that is rebuilt by an Ajax answer. */
	static String tileHtml(final String pLabel, final String pValueHtml, final String pDetail)
	{
		final StringBuilder out = new StringBuilder("<div class=\"ftroTile\"><div class=\"ftroTileLabel\">").append(MappingForm.escape(pLabel))
			.append("</div><div class=\"ftroTileValue\">").append(pValueHtml).append("</div>");
		if (pDetail != null && !pDetail.isEmpty())
		{
			out.append("<div class=\"ftroTileDetail\">").append(MappingForm.escape(pDetail)).append("</div>");
		}
		return out.append("</div>").toString();
	}

	/** A coloured pill; pTone is one of Good, Bad, Warn, Info, or null for grey. */
	static String badge(final String pText, final String pTone)
	{
		return "<span class=\"ftroBadge" + (pTone == null ? "" : " ftro" + pTone) + "\">" + MappingForm.escape(pText) + "</span>";
	}

	static void error(final UIBodyWriter pWriter, final String pTitle, final String pMessage)
	{
		if (pMessage == null || pMessage.trim().isEmpty())
		{
			return;
		}
		final String shown = pMessage.length() > 600 ? pMessage.substring(0, 600) + "…" : pMessage;
		pWriter.add("<div class=\"ftroError\"><b>").add(MappingForm.escape(pTitle)).add("</b> ").add(MappingForm.escape(shown)).add("</div>");
	}

	/** String value of a field, or "" when empty or not defined by the loaded model. */
	static String value(final Adaptation pRecord, final Path pPath)
	{
		if (pRecord.getSchemaNode().getNode(pPath) == null)
		{
			return "";
		}
		final Object value = pRecord.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}

	static Date date(final Adaptation pRecord, final Path pPath)
	{
		if (pRecord.getSchemaNode().getNode(pPath) == null)
		{
			return null;
		}
		final Object value = pRecord.get(pPath);
		return value instanceof Date ? (Date) value : null;
	}

	static String dateTime(final Locale pLocale, final Date pWhen)
	{
		return pWhen == null ? "" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, pLocale).format(pWhen);
	}

	/** "3 min ago", "2 h ago"... (in the future: "in 3 min"). */
	static String age(final Locale pLocale, final Date pWhen, final Date pNow)
	{
		if (pWhen == null)
		{
			return text(pLocale, "readOnly.age.never");
		}
		final long delta = pNow.getTime() - pWhen.getTime();
		final long minutes = Math.abs(delta) / 60000L;
		final String amount;
		if (minutes < 1L)
		{
			return text(pLocale, "readOnly.age.now");
		}
		else if (minutes < 60L)
		{
			amount = text(pLocale, "readOnly.unit.minutes", String.valueOf(minutes));
		}
		else if (minutes < 48L * 60L)
		{
			amount = text(pLocale, "readOnly.unit.hours", String.valueOf(minutes / 60L));
		}
		else
		{
			amount = text(pLocale, "readOnly.unit.days", String.valueOf(minutes / (24L * 60L)));
		}
		return text(pLocale, delta >= 0 ? "readOnly.age.past" : "readOnly.age.future", amount);
	}

	/**
	 * Link to the synchronization log of the record's dataset filtered by an XPath predicate. Same
	 * ebx-ui route as the Connector operations dashboard; {@link #perspectiveLinks} keeps it inside
	 * the perspective the form was opened from.
	 */
	static void logLink(final UIFormPaneWriter pWriter, final Adaptation pRecord, final String pPredicate, final String pLabel)
	{
		pWriter.add("<a class=\"ftroLink\" target=\"_top\" href=\"").add(MappingForm.escape(ReadOnlyForm.logHref(pRecord.getContainer(), pPredicate))).add("\">")
			.add(MappingForm.escape(pLabel)).add(" &#8594;</a>");
	}

	/** The address of the synchronization log of a Connectors dataset, filtered by an XPath predicate (the link of {@link #logLink}, also written by the run panel). */
	static String logHref(final Adaptation pDataset, final String pPredicate)
	{
		return MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/"
			+ ConnectorCatalogUserService.pathSegment(pDataset.getHome().getKey().format()) + "/"
			+ ConnectorCatalogUserService.pathSegment(pDataset.getAdaptationName().getStringName()) + ConnectorsPaths.SYNC_LOG.format()
			+ "?predicate=" + ConnectorCatalogUserService.queryValue(pPredicate);
	}

	/** XPath equality on a string field, quotes doubled. */
	static String equals(final Path pPath, final String pValue)
	{
		return pPath.format() + "='" + pValue.replace("'", "''") + "'";
	}

	static void linksStart(final UIFormPaneWriter pWriter)
	{
		pWriter.add("<div class=\"ftroLinks\">");
	}

	static void linksEnd(final UIFormPaneWriter pWriter)
	{
		pWriter.add("</div>");
		pWriter.addJS_cr("(function(){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "document.querySelectorAll('a.ftroLink[href*=\"'+o+'\"]').forEach(function(a){a.setAttribute('href',a.getAttribute('href').split(o).join(n));});})();");
	}
}
