package com.on.ebx.connect.ebx.ui;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorOperationalMetrics;
import com.on.ebx.connect.core.ConnectorOperationalMetrics.Health;
import com.on.ebx.connect.core.ConnectorOperationalMetrics.Snapshot;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWriter;

/**
 * Record form of the ConnectorOperationalMetrics table (declared by Connectors.xsd): a summary band
 * computed with the runtime's own rules (health, latency percentiles), then the stored fields
 * grouped by theme in two columns. Rows are written only for paths the loaded model defines. The rows
 * are written by the runtime only, so the form is read-only (see {@link ReadOnlyForm}).
 */
public final class ConnectorMetricsForm extends ReadOnlyForm
{
	private static final Path CONNECTOR = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.CONNECTOR;
	private static final Path REQUEST_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.REQUEST_COUNT;
	private static final Path READ_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.READ_COUNT;
	private static final Path WRITE_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.WRITE_COUNT;
	private static final Path SKIP_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.SKIP_COUNT;
	private static final Path CONFLICT_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.CONFLICT_COUNT;
	private static final Path RETRY_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.RETRY_COUNT;
	private static final Path THROTTLE_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.THROTTLE_COUNT;
	private static final Path DURATION_SAMPLES = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.DURATION_SAMPLES;
	private static final Path LAST_SUCCESS_AT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.LAST_SUCCESS_AT;
	private static final Path LAST_FAILURE_AT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE_AT;
	private static final Path LAST_FAILURE = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.LAST_FAILURE;
	private static final Path CONSECUTIVE_FAILURES = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.CONSECUTIVE_FAILURES;
	private static final Path CHECKPOINT_AT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.CHECKPOINT_AT;
	private static final Path SCHEMA_DRIFT_AT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_AT;
	private static final Path SCHEMA_DRIFT_COUNT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.SCHEMA_DRIFT_COUNT;
	private static final Path UPDATED_AT = com.on.ebx.connect.core.ConnectorsPaths.ConnectorOperationalMetrics.UPDATED_AT;

	private static final Path[] IDENTITY = { CONNECTOR, UPDATED_AT };
	private static final Path[] EXECUTIONS = { REQUEST_COUNT, READ_COUNT, WRITE_COUNT, SKIP_COUNT, CONFLICT_COUNT };
	private static final Path[] RELIABILITY = { LAST_SUCCESS_AT, LAST_FAILURE_AT, CONSECUTIVE_FAILURES, RETRY_COUNT, THROTTLE_COUNT };
	private static final Path[] SYNCHRONIZATION = { CHECKPOINT_AT, SCHEMA_DRIFT_AT, SCHEMA_DRIFT_COUNT };
	private static final Path[] DIAGNOSTICS = { LAST_FAILURE, DURATION_SAMPLES };

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
			MappingForm.styles(pWriter);
			pWriter.add("<style>.ftcmTiles{display:flex;flex-wrap:wrap;gap:12px;margin:8px 0 6px}"
				+ ".ftcmTile{flex:1 1 190px;min-width:0;border:1px solid var(--ebx-table-border,#dadce0);border-radius:8px;padding:10px 14px;background:var(--ebx-table-even,#fff)}"
				+ ".ftcmTileLabel{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--ftc-muted,#5f6368)}"
				+ ".ftcmTileValue{font-size:20px;font-weight:600;color:var(--ftc-text,#202124);margin:4px 0 2px;font-variant-numeric:tabular-nums}"
				+ ".ftcmTileDetail{font-size:12px;color:var(--ftc-muted,#5f6368)}"
				+ ".ftcmBadge{display:inline-block;font-size:14px;font-weight:600;padding:2px 10px;border-radius:12px}"
				+ ".ftcmHEALTHY{background:var(--ftc-ok-bg,#e6f4ea);color:var(--ftc-ok,#137333)}.ftcmDEGRADED{background:var(--ftc-warn-bg,#fef7e0);color:var(--ftc-warn,#b06000)}"
				+ ".ftcmFAILED{background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-bad,#c5221f)}.ftcmUNKNOWN{background:var(--ebx-table-header,#f1f3f4);color:var(--ftc-muted,#5f6368)}"
				+ ".ftcmError{margin:6px 0 4px;padding:8px 12px;border-left:3px solid var(--ftc-bad,#d93025);background:var(--ftc-bad-bg,#fce8e6);color:var(--ftc-text,#3c4043);"
				+ "font-size:12px;white-space:pre-wrap;word-break:break-word}"
				+ ".ftcmError b{color:var(--ftc-bad,#c5221f)}"
				+ ".ftcmColumns{display:flex;flex-wrap:wrap;gap:0 32px}.ftcmColumn{flex:1 1 420px;min-width:0}</style>");

			final Adaptation record = pContext.isCreatingRecord() ? null : pContext.getCurrentRecord();
			if (record == null)
			{
				pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(text(locale, "metrics.form.creating"))).add("</div>");
			}
			else
			{
				pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(text(locale, "metrics.form.readOnly"))).add("</div>");
				ConnectorMetricsForm.Pane.summary(pWriter, record, locale);
				final String connector = ReadOnlyForm.value(record, CONNECTOR);
				if (!connector.isEmpty())
				{
					ReadOnlyForm.styles(pWriter);
					ReadOnlyForm.linksStart(pWriter);
					ReadOnlyForm.logLink(pWriter, record, ReadOnlyForm.equals(com.on.ebx.connect.core.ConnectorsPaths.SyncLog.CONNECTOR, connector),
						text(locale, "metrics.link.log"));
					ReadOnlyForm.logLink(pWriter, record, ReadOnlyForm.equals(com.on.ebx.connect.core.ConnectorsPaths.SyncLog.CONNECTOR, connector) + " and "
						+ ReadOnlyForm.equals(com.on.ebx.connect.core.ConnectorsPaths.SyncLog.STATUS, "ERROR"), text(locale, "metrics.link.failures"));
					ReadOnlyForm.linksEnd(pWriter);
				}
			}

			ConnectorMetricsForm.Pane.block(pWriter, pContext, locale, "identity", IDENTITY);
			pWriter.add("<div class=\"ftcmColumns\"><div class=\"ftcmColumn\">");
			ConnectorMetricsForm.Pane.block(pWriter, pContext, locale, "executions", EXECUTIONS);
			pWriter.add("</div><div class=\"ftcmColumn\">");
			ConnectorMetricsForm.Pane.block(pWriter, pContext, locale, "reliability", RELIABILITY);
			pWriter.add("</div></div>");
			ConnectorMetricsForm.Pane.block(pWriter, pContext, locale, "synchronization", SYNCHRONIZATION);
			ConnectorMetricsForm.Pane.block(pWriter, pContext, locale, "diagnostics", DIAGNOSTICS);
		}

		private static void block(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Locale pLocale, final String pName,
			final Path[] pPaths)
		{
			ReadOnlyForm.block(pWriter, pContext, text(pLocale, "metrics.section." + pName), text(pLocale, "metrics.section." + pName + ".hint"), pPaths);
		}

		/** Health, volume, latency and freshness at a glance, plus the last error while the connector is failing. */
		private static void summary(final UIFormPaneWriter pWriter, final Adaptation pRecord, final Locale pLocale)
		{
			final Snapshot snapshot = ConnectorMetricsForm.Pane.snapshot(pRecord);
			if (snapshot == null)
			{
				return;
			}
			final Date now = new Date();
			final NumberFormat numbers = NumberFormat.getIntegerInstance(pLocale);
			final Health health = snapshot.getHealth();

			pWriter.add("<div class=\"ftcmTiles\">");

			final String healthDetail;
			if (health == Health.UNKNOWN)
			{
				healthDetail = text(pLocale, "metrics.health.detail.unknown");
			}
			else if (health == Health.HEALTHY)
			{
				healthDetail = text(pLocale, "metrics.health.detail.healthy", ConnectorMetricsForm.Pane.age(pLocale, snapshot.getLastSuccessAt(), now));
			}
			else
			{
				healthDetail = text(pLocale, "metrics.health.detail.failing", numbers.format(snapshot.getConsecutiveFailures()),
					ConnectorMetricsForm.Pane.age(pLocale, snapshot.getLastFailureAt(), now));
			}
			ConnectorMetricsForm.Pane.tile(pWriter, text(pLocale, "metrics.tile.health"),
				"<span class=\"ftcmBadge ftcm" + health.name() + "\">" + MappingForm.escape(text(pLocale, "metrics.health." + health.name())) + "</span>",
				healthDetail, null);

			ConnectorMetricsForm.Pane.tile(pWriter, text(pLocale, "metrics.tile.executions"),
				MappingForm.escape(numbers.format(ConnectorMetricsForm.Pane.counter(pRecord, REQUEST_COUNT))),
				text(pLocale, "metrics.tile.executions.detail", numbers.format(ConnectorMetricsForm.Pane.counter(pRecord, READ_COUNT)),
					numbers.format(ConnectorMetricsForm.Pane.counter(pRecord, WRITE_COUNT)),
					numbers.format(ConnectorMetricsForm.Pane.counter(pRecord, CONFLICT_COUNT))),
				null);

			final int samples = snapshot.getDurations().size();
			ConnectorMetricsForm.Pane.tile(pWriter, text(pLocale, "metrics.tile.latency"),
				samples == 0 ? "&ndash;"
					: MappingForm.escape(ConnectorMetricsForm.Pane.duration(pLocale, snapshot.percentile(50))) + " <span class=\"ftcmTileDetail\">/</span> "
						+ MappingForm.escape(ConnectorMetricsForm.Pane.duration(pLocale, snapshot.percentile(95))),
				samples == 0 ? text(pLocale, "metrics.tile.latency.none") : text(pLocale, "metrics.tile.latency.detail", numbers.format(samples)), null);

			final Date updated = snapshot.getUpdatedAt();
			ConnectorMetricsForm.Pane.tile(pWriter, text(pLocale, "metrics.tile.updated"),
				MappingForm.escape(updated == null ? text(pLocale, "metrics.age.never") : ConnectorMetricsForm.Pane.age(pLocale, updated, now)),
				updated == null ? null : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, pLocale).format(updated), null);

			pWriter.add("</div>");

			final String failure = snapshot.getLastFailure();
			if ((health == Health.DEGRADED || health == Health.FAILED) && failure != null && !failure.trim().isEmpty())
			{
				final String shown = failure.length() > 600 ? failure.substring(0, 600) + "…" : failure;
				pWriter.add("<div class=\"ftcmError\"><b>").add(MappingForm.escape(text(pLocale, "metrics.lastError"))).add("</b> ")
					.add(MappingForm.escape(shown)).add("</div>");
			}
		}

		private static void tile(final UIFormPaneWriter pWriter, final String pLabel, final String pValueHtml, final String pDetail, final String pTitle)
		{
			pWriter.add("<div class=\"ftcmTile\"").add(pTitle == null ? "" : " title=\"" + MappingForm.escape(pTitle) + "\"").add(">");
			pWriter.add("<div class=\"ftcmTileLabel\">").add(MappingForm.escape(pLabel)).add("</div>");
			pWriter.add("<div class=\"ftcmTileValue\">").add(pValueHtml).add("</div>");
			if (pDetail != null && !pDetail.isEmpty())
			{
				pWriter.add("<div class=\"ftcmTileDetail\">").add(MappingForm.escape(pDetail)).add("</div>");
			}
			pWriter.add("</div>");
		}

		/** Same rules as the runtime; null when a stored value is unreadable, so the fields still show. */
		private static Snapshot snapshot(final Adaptation pRecord)
		{
			try
			{
				final List<Long> durations = new ArrayList<Long>();
				final String samples = pRecord.getString(DURATION_SAMPLES);
				if (samples != null)
				{
					for (final String item : samples.split(","))
					{
						if (!item.trim().isEmpty())
						{
							durations.add(Long.valueOf(Long.parseLong(item.trim())));
						}
					}
				}
				return new ConnectorOperationalMetrics.Snapshot(pRecord.getString(CONNECTOR), null, durations, (Date) pRecord.get(LAST_SUCCESS_AT),
					(Date) pRecord.get(LAST_FAILURE_AT), pRecord.getString(LAST_FAILURE), ConnectorMetricsForm.Pane.integer(pRecord, CONSECUTIVE_FAILURES),
					(Date) pRecord.get(CHECKPOINT_AT), (Date) pRecord.get(SCHEMA_DRIFT_AT), ConnectorMetricsForm.Pane.integer(pRecord, SCHEMA_DRIFT_COUNT),
					(Date) pRecord.get(UPDATED_AT));
			}
			catch (final RuntimeException ex)
			{
				return null;
			}
		}

		private static long counter(final Adaptation pRecord, final Path pPath)
		{
			final String value = pRecord.getSchemaNode().getNode(pPath) == null ? null : pRecord.getString(pPath);
			try
			{
				return value == null || value.isEmpty() ? 0L : Long.parseLong(value);
			}
			catch (final NumberFormatException ex)
			{
				return 0L;
			}
		}

		private static int integer(final Adaptation pRecord, final Path pPath)
		{
			final Integer value = (Integer) pRecord.get(pPath);
			return value == null ? 0 : value.intValue();
		}

		private static String duration(final Locale pLocale, final long pMillis)
		{
			if (pMillis < 1000L)
			{
				return pMillis + " ms";
			}
			final NumberFormat seconds = NumberFormat.getNumberInstance(pLocale);
			seconds.setMaximumFractionDigits(pMillis < 10000L ? 1 : 0);
			return seconds.format(pMillis / 1000.0d) + " s";
		}

		private static String age(final Locale pLocale, final Date pWhen, final Date pNow)
		{
			if (pWhen == null)
			{
				return text(pLocale, "metrics.age.never");
			}
			final long minutes = Math.max(0L, pNow.getTime() - pWhen.getTime()) / 60000L;
			if (minutes < 1L)
			{
				return text(pLocale, "metrics.age.now");
			}
			if (minutes < 60L)
			{
				return text(pLocale, "metrics.age.minutes", String.valueOf(minutes));
			}
			if (minutes < 48L * 60L)
			{
				return text(pLocale, "metrics.age.hours", String.valueOf(minutes / 60L));
			}
			return text(pLocale, "metrics.age.days", String.valueOf(minutes / (24L * 60L)));
		}
	}
}
