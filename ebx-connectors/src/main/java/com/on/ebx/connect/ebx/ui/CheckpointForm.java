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
 * Record form of the SynchronizationCheckpoint table (declared by Connectors.xsd), read-only: where an
 * incremental mapping will resume, when it last moved, then the stored fields.
 */
public final class CheckpointForm extends ReadOnlyForm
{
	private static final Path[] CHECKPOINT = { ConnectorsPaths.SynchronizationCheckpoint.MAPPING, ConnectorsPaths.SynchronizationCheckpoint.STRATEGY,
		ConnectorsPaths.SynchronizationCheckpoint.VALUE };
	private static final Path[] CONCURRENCY = { ConnectorsPaths.SynchronizationCheckpoint.UPDATED_AT, ConnectorsPaths.SynchronizationCheckpoint.VERSION };

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
			ReadOnlyForm.hint(pWriter, text(locale, record == null ? "checkpoint.form.creating" : "checkpoint.form.readOnly"));
			if (record != null)
			{
				Pane.summary(pWriter, record, locale);
			}
			ReadOnlyForm.block(pWriter, pContext, text(locale, "checkpoint.section.checkpoint"), text(locale, "checkpoint.section.checkpoint.hint"), CHECKPOINT);
			ReadOnlyForm.block(pWriter, pContext, text(locale, "checkpoint.section.concurrency"), text(locale, "checkpoint.section.concurrency.hint"), CONCURRENCY);
		}

		private static void summary(final UIFormPaneWriter pWriter, final Adaptation pRecord, final Locale pLocale)
		{
			final Date now = new Date();
			final String strategy = ReadOnlyForm.value(pRecord, ConnectorsPaths.SynchronizationCheckpoint.STRATEGY);
			final String value = ReadOnlyForm.value(pRecord, ConnectorsPaths.SynchronizationCheckpoint.VALUE);
			final Date updated = ReadOnlyForm.date(pRecord, ConnectorsPaths.SynchronizationCheckpoint.UPDATED_AT);
			final String version = ReadOnlyForm.value(pRecord, ConnectorsPaths.SynchronizationCheckpoint.VERSION);
			final String mapping = ReadOnlyForm.value(pRecord, ConnectorsPaths.SynchronizationCheckpoint.MAPPING);

			ReadOnlyForm.tilesStart(pWriter);
			ReadOnlyForm.tile(pWriter, text(pLocale, "checkpoint.tile.strategy"), ReadOnlyForm.badge(strategy.isEmpty() ? "–" : strategy, "Info"),
				strategy.matches("TIMESTAMP|MONOTONIC_ID|ORDERED_STRING|VENDOR_CURSOR|ODATA_DELTA|EBX_DELTA|SNAPSHOT") ? text(pLocale, "checkpoint.strategy." + strategy) : null);
			ReadOnlyForm.tile(pWriter, text(pLocale, "checkpoint.tile.value"),
				MappingForm.escape(value.isEmpty() ? "–" : value.length() > 80 ? value.substring(0, 80) + "…" : value),
				value.length() > 80 ? text(pLocale, "checkpoint.tile.value.detail", String.valueOf(value.length())) : null);
			ReadOnlyForm.tile(pWriter, text(pLocale, "checkpoint.tile.updated"), MappingForm.escape(ReadOnlyForm.age(pLocale, updated, now)),
				ReadOnlyForm.dateTime(pLocale, updated));
			ReadOnlyForm.tile(pWriter, text(pLocale, "checkpoint.tile.version"), MappingForm.escape(version.isEmpty() ? "–" : version),
				text(pLocale, "checkpoint.tile.version.detail"));
			ReadOnlyForm.tilesEnd(pWriter);

			if (!mapping.isEmpty())
			{
				ReadOnlyForm.linksStart(pWriter);
				ReadOnlyForm.logLink(pWriter, pRecord, ReadOnlyForm.equals(ConnectorsPaths.SyncLog.MAPPING, mapping), text(pLocale, "checkpoint.link.log"));
				ReadOnlyForm.linksEnd(pWriter);
			}
		}
	}
}
