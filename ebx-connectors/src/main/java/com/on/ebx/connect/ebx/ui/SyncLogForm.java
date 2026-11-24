package com.on.ebx.connect.ebx.ui;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.ui.form.UIForm;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormBottomBar;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWriter;
import com.orchestranetworks.ui.form.widget.UIWidget;

/**
 * Record form of the SyncLog table (declared by Connectors.xsd). The log is written by the connector
 * runtime only, so the form is a read-only view: a summary band, then the fields grouped by theme with
 * their editors disabled, and no save or revert button. The table's access rights are left untouched.
 */
public final class SyncLogForm extends UIForm
{
	private static final Path[] EXECUTION = { ConnectorsPaths.SyncLog.CONNECTOR, ConnectorsPaths.SyncLog.OPERATION,
		ConnectorsPaths.SyncLog.DIRECTION, ConnectorsPaths.SyncLog.MAPPING, ConnectorsPaths.SyncLog.RUN_ID,
		ConnectorsPaths.SyncLog.ORIGIN, ConnectorsPaths.SyncLog.USER };
	private static final Path[] RECORD = { ConnectorsPaths.SyncLog.DATASPACE, ConnectorsPaths.SyncLog.TABLE_PATH,
		ConnectorsPaths.SyncLog.RECORD_KEY, ConnectorsPaths.SyncLog.RECORD_LABEL };
	private static final Path[] OUTCOME = { ConnectorsPaths.SyncLog.STATUS, ConnectorsPaths.SyncLog.TIMESTAMP,
		ConnectorsPaths.SyncLog.DURATION_MS, ConnectorsPaths.SyncLog.MESSAGE, ConnectorsPaths.SyncLog.CHANGES, ConnectorsPaths.SyncLog.ID };

	@Override
	public void defineBody(final UIFormBody pBody, final UIFormContext pContext)
	{
		pBody.setContent(new Pane());
	}

	@Override
	public void defineBottomBar(final UIFormBottomBar pBar, final UIFormContext pContext)
	{
		pBar.setSubmitButtonDisplayable(false);
		pBar.setSubmitAndCloseButtonDisplayable(false);
		pBar.setRevertButtonDisplayable(false);
		pBar.setCloseButtonDisplayable(true);
	}

	private static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(SyncLogForm.class, pLocale, pKey, pArgs);
	}

	private static final class Pane implements UIFormPane
	{
		@Override
		public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			MappingForm.styles(pWriter);
			pWriter.add("<style>.ftslTiles{display:flex;flex-wrap:wrap;gap:12px;margin:8px 0 6px}"
				+ ".ftslTile{flex:1 1 170px;min-width:0;border:1px solid var(--ebx-table-border,#dadce0);border-radius:8px;padding:10px 14px;background:var(--ebx-table-even,#fff)}"
				+ ".ftslTileLabel{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--ftc-muted,#5f6368)}"
				+ ".ftslTileValue{font-size:16px;font-weight:600;color:var(--ftc-text,#202124);margin:4px 0 2px;overflow-wrap:anywhere}"
				+ ".ftslBadge{display:inline-block;font-size:13px;font-weight:600;padding:2px 10px;border-radius:12px;background:var(--ebx-table-header,#f1f3f4);color:var(--ftc-muted,#5f6368)}"
				+ ".ftslOK{background:var(--ftc-ok-bg,#e6f4ea);color:var(--ftc-ok,#137333)}.ftslERROR{background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-bad,#c5221f)}"
				+ ".ftslSKIPPED,.ftslNO_DATA{background:var(--ftc-warn-bg,#fef7e0);color:var(--ftc-warn,#b06000)}"
				+ ".ftslError{margin:6px 0 4px;padding:8px 12px;border-left:3px solid var(--ftc-bad,#d93025);background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-text,#3c4043);"
				+ "font-size:12px;white-space:pre-wrap;word-break:break-word}.ftslError b{color:var(--ftc-bad,#c5221f)}</style>");

			final Adaptation record = pContext.isCreatingRecord() ? null : pContext.getCurrentRecord();
			pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(text(locale, record == null ? "syncLog.form.creating" : "syncLog.form.readOnly"))).add("</div>");
			if (record != null)
			{
				Pane.summary(pWriter, record, locale);
			}
			Pane.block(pWriter, pContext, locale, "outcome", OUTCOME);
			Pane.block(pWriter, pContext, locale, "execution", EXECUTION);
			Pane.block(pWriter, pContext, locale, "record", RECORD);
		}

		/** A section of standard EBX rows whose editors are disabled; paths the loaded model lacks are skipped. */
		private static void block(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Locale pLocale, final String pName, final Path[] pPaths)
		{
			MappingForm.section(pWriter, text(pLocale, "syncLog.section." + pName), text(pLocale, "syncLog.section." + pName + ".hint"));
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

		/** Status, connector call, when and how long, plus the message when the call failed. */
		private static void summary(final UIFormPaneWriter pWriter, final Adaptation pRecord, final Locale pLocale)
		{
			final String status = Pane.value(pRecord, ConnectorsPaths.SyncLog.STATUS);
			final String connector = Pane.value(pRecord, ConnectorsPaths.SyncLog.CONNECTOR);
			final String operation = Pane.value(pRecord, ConnectorsPaths.SyncLog.OPERATION);
			final Object when = pRecord.get(ConnectorsPaths.SyncLog.TIMESTAMP);
			final Object duration = pRecord.get(ConnectorsPaths.SyncLog.DURATION_MS);

			pWriter.add("<div class=\"ftslTiles\">");
			Pane.tile(pWriter, text(pLocale, "syncLog.tile.status"), "<span class=\"ftslBadge ftsl" + (status.matches("[A-Z_]+") ? status : "") + "\">"
				+ MappingForm.escape(LogMessages.status(pLocale, status)) + "</span>");
			Pane.tile(pWriter, text(pLocale, "syncLog.tile.call"), MappingForm.escape(connector.isEmpty() && operation.isEmpty() ? "–" : connector + " / " + operation));
			Pane.tile(pWriter, text(pLocale, "syncLog.tile.when"), MappingForm.escape(when instanceof Date
				? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, pLocale).format((Date) when) : "–"));
			Pane.tile(pWriter, text(pLocale, "syncLog.tile.duration"), MappingForm.escape(duration instanceof Number ? duration + " ms" : "–"));
			pWriter.add("</div>");

			final String message = Pane.value(pRecord, ConnectorsPaths.SyncLog.MESSAGE);
			if ("ERROR".equals(status) && !message.trim().isEmpty())
			{
				// what happened and what to do, then the stored message folded under it (audit C-38)
				final String shown = message.length() > 600 ? message.substring(0, 600) + "…" : message;
				pWriter.add(ErrorTexts.STYLE);
				pWriter.add("<div class=\"ftslError\"><b>").add(MappingForm.escape(text(pLocale, "syncLog.error"))).add("</b> ")
					.add(ErrorTexts.of(pLocale, shown, null).html(pLocale)).add("</div>");
			}
			else if (!message.trim().isEmpty())
			{
				// the common sentences of the engine in the reader's language (audit C-37); the stored message is the field below
				final String localized = LogMessages.localize(pLocale, message);
				if (localized != null)
				{
					pWriter.add("<div class=\"ftmfHint\"><b>").add(MappingForm.escape(text(pLocale, "log.stored"))).add("</b> ").add(MappingForm.escape(localized)).add("</div>");
				}
			}
		}

		private static void tile(final UIFormPaneWriter pWriter, final String pLabel, final String pValueHtml)
		{
			pWriter.add("<div class=\"ftslTile\"><div class=\"ftslTileLabel\">").add(MappingForm.escape(pLabel)).add("</div><div class=\"ftslTileValue\">")
				.add(pValueHtml).add("</div></div>");
		}

		private static String value(final Adaptation pRecord, final Path pPath)
		{
			if (pRecord.getSchemaNode().getNode(pPath) == null)
			{
				return "";
			}
			final String value = pRecord.getString(pPath);
			return value == null ? "" : value;
		}
	}
}
