package com.on.ebx.connect.ebx.ui;

import java.util.Date;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWriter;

/**
 * Record form of the OutboundState table (declared by Connectors.xsd), read-only: delivery status of
 * one record for one outbound mapping, the last error while it is to be replayed, then the stored
 * fields grouped by delivery, record and sent document.
 */
public final class OutboundStateForm extends ReadOnlyForm
{
	private static final Path[] DELIVERY = { ConnectorsPaths.OutboundState.STATUS, ConnectorsPaths.OutboundState.LAST_SENT_AT,
		ConnectorsPaths.OutboundState.LAST_ATTEMPT_AT, ConnectorsPaths.OutboundState.ATTEMPTS, ConnectorsPaths.OutboundState.LAST_RUN_ID,
		ConnectorsPaths.OutboundState.LAST_ERROR };
	private static final Path[] RECORD = { ConnectorsPaths.OutboundState.MAPPING, ConnectorsPaths.OutboundState.DATASPACE,
		ConnectorsPaths.OutboundState.DATASET, ConnectorsPaths.OutboundState.TABLE_PATH, ConnectorsPaths.OutboundState.RECORD_KEY,
		ConnectorsPaths.OutboundState.RECORD_LABEL };
	private static final Path[] DOCUMENT = { ConnectorsPaths.OutboundState.HASH, ConnectorsPaths.OutboundState.LAST_DOCUMENT };

	@Override
	public void defineBody(final UIFormBody pBody, final UIFormContext pContext)
	{
		pBody.setContent(new Pane());
	}

	private static final class Pane implements UIFormPane
	{
		@Override
		public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			ReadOnlyForm.styles(pWriter);
			final Adaptation record = pContext.isCreatingRecord() ? null : pContext.getCurrentRecord();
			ReadOnlyForm.hint(pWriter, text(locale, record == null ? "outbound.form.creating" : "outbound.form.readOnly"));
			if (record != null)
			{
				Pane.summary(pWriter, record, locale);
			}
			ReadOnlyForm.block(pWriter, pContext, text(locale, "outbound.section.delivery"), text(locale, "outbound.section.delivery.hint"), DELIVERY);
			ReadOnlyForm.block(pWriter, pContext, text(locale, "outbound.section.record"), text(locale, "outbound.section.record.hint"), RECORD);
			ReadOnlyForm.block(pWriter, pContext, text(locale, "outbound.section.document"), text(locale, "outbound.section.document.hint"), DOCUMENT);
		}

		private static void summary(final UIFormPaneWriter pWriter, final Adaptation pRecord, final Locale pLocale)
		{
			final Date now = new Date();
			final String status = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.STATUS);
			final String label = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.RECORD_LABEL);
			final String key = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.RECORD_KEY);
			final String mapping = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.MAPPING);
			final Date sent = ReadOnlyForm.date(pRecord, ConnectorsPaths.OutboundState.LAST_SENT_AT);
			final Date attempt = ReadOnlyForm.date(pRecord, ConnectorsPaths.OutboundState.LAST_ATTEMPT_AT);
			final String attempts = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.ATTEMPTS);

			final String tone = "SENT".equals(status) ? "Good" : "FAILED".equals(status) ? "Bad" : "DELETED".equals(status) ? "Info" : null;
			final String statusText = status.matches("SENT|FAILED|DELETED") ? text(pLocale, "outbound.status." + status) : status.isEmpty() ? "–" : status;

			ReadOnlyForm.tilesStart(pWriter);
			ReadOnlyForm.tile(pWriter, text(pLocale, "outbound.tile.status"), ReadOnlyForm.badge(statusText, tone),
				attempt == null ? null : text(pLocale, "outbound.tile.status.detail", ReadOnlyForm.age(pLocale, attempt, now)));
			ReadOnlyForm.tile(pWriter, text(pLocale, "outbound.tile.record"), MappingForm.escape(label.isEmpty() ? (key.isEmpty() ? "–" : key) : label),
				label.isEmpty() || key.isEmpty() ? null : key);
			ReadOnlyForm.tile(pWriter, text(pLocale, "outbound.tile.lastSent"), MappingForm.escape(ReadOnlyForm.age(pLocale, sent, now)),
				ReadOnlyForm.dateTime(pLocale, sent));
			ReadOnlyForm.tile(pWriter, text(pLocale, "outbound.tile.attempts"),
				attempts.isEmpty() || "0".equals(attempts) ? MappingForm.escape("0") : ReadOnlyForm.badge(attempts, "Bad"),
				text(pLocale, "outbound.tile.attempts.detail"));
			ReadOnlyForm.tilesEnd(pWriter);

			if ("FAILED".equals(status))
			{
				ReadOnlyForm.error(pWriter, text(pLocale, "outbound.error"), ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.LAST_ERROR));
			}

			final String runId = ReadOnlyForm.value(pRecord, ConnectorsPaths.OutboundState.LAST_RUN_ID);
			if (!key.isEmpty() || !runId.isEmpty())
			{
				ReadOnlyForm.linksStart(pWriter);
				if (!key.isEmpty())
				{
					String predicate = ReadOnlyForm.equals(ConnectorsPaths.SyncLog.RECORD_KEY, key);
					if (!mapping.isEmpty())
					{
						predicate += " and " + ReadOnlyForm.equals(ConnectorsPaths.SyncLog.MAPPING, mapping);
					}
					ReadOnlyForm.logLink(pWriter, pRecord, predicate, text(pLocale, "outbound.link.record"));
				}
				if (!runId.isEmpty())
				{
					ReadOnlyForm.logLink(pWriter, pRecord, ReadOnlyForm.equals(ConnectorsPaths.SyncLog.RUN_ID, runId), text(pLocale, "outbound.link.run"));
				}
				ReadOnlyForm.linksEnd(pWriter);
			}
		}
	}
}
