package com.on.ebx.connect.ebx.ui;

import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.RunProgress;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.orchestranetworks.instance.Repository;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWriter;

/**
 * Record form of the ExecutionRun table (declared by Connectors.xsd), read-only: whether the run is
 * held by a process, how far it got, the items abandoned, then the stored fields. The comma-separated
 * values are read with the same rules as EbxRecoverableExecutionStore.
 */
public final class ExecutionRunForm extends ReadOnlyForm
{
	private static final Path[] PROGRESS = { ConnectorsPaths.ExecutionRun.NEXT_INDEX, ConnectorsPaths.ExecutionRun.COMPLETED,
		ConnectorsPaths.ExecutionRun.DEAD_LETTERS, ConnectorsPaths.ExecutionRun.ATTEMPTS };
	private static final Path[] LEASE = { ConnectorsPaths.ExecutionRun.LEASE_OWNER, ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT };
	private static final Path[] RUN = { ConnectorsPaths.ExecutionRun.RUN_ID, ConnectorsPaths.ExecutionRun.UPDATED_AT };

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
			ReadOnlyForm.hint(pWriter, text(locale, record == null ? "run.form.creating" : "run.form.readOnly"));
			if (record != null)
			{
				Pane.livePanel(pWriter, pContext, record, locale);
				Pane.summary(pWriter, record, locale);
			}
			ReadOnlyForm.block(pWriter, pContext, text(locale, "run.section.progress"), text(locale, "run.section.progress.hint"), PROGRESS);
			ReadOnlyForm.block(pWriter, pContext, text(locale, "run.section.lease"), text(locale, "run.section.lease.hint"), LEASE);
			ReadOnlyForm.block(pWriter, pContext, text(locale, "run.section.run"), null, RUN);
		}

		/**
		 * The state of the run as the status answers it, with a Refresh button, a poll while it runs and a Stop (RUN right) - what a
		 * read-only form could not show before (audit C-19). Nothing for a row that no feed run wrote.
		 */
		private static void livePanel(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Adaptation pRecord, final Locale pLocale)
		{
			final String runId = ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.RUN_ID);
			if (runId.isEmpty() || ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.STATUS).isEmpty())
			{
				return;
			}
			final Repository repository = pRecord.getHome().getRepository();
			final java.util.Map<String, String> parameters = new java.util.HashMap<String, String>();
			parameters.put(RunPanelView.P_RUN, runId);
			final RunProgress progress = RunPanelView.progress(repository, runId);
			final RunPanelView.View view = RunPanelAjax.view(runId, ConnectorsAccess.has(pContext.getSession(), ConnectorsAccess.Right.RUN), false, repository);
			RunPanelView.write(pWriter, pLocale, pWriter.getURLForAjaxComponent(RunPanelAjax.class.getName(), parameters), RunPanelView.body(pLocale, progress, view), runId,
				progress.isRunning());
		}

		private static void summary(final UIFormPaneWriter pWriter, final Adaptation pRecord, final Locale pLocale)
		{
			final Date now = new Date();
			final NumberFormat numbers = NumberFormat.getIntegerInstance(pLocale);
			final String owner = ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.LEASE_OWNER);
			final Date expires = ReadOnlyForm.date(pRecord, ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT);
			final int completed = Pane.count(ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.COMPLETED));
			final String deadLetters = ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.DEAD_LETTERS);
			final int abandoned = Pane.count(deadLetters);
			final long[] attempts = Pane.attempts(ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.ATTEMPTS));
			final String next = ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.NEXT_INDEX);
			final Date updated = ReadOnlyForm.date(pRecord, ConnectorsPaths.ExecutionRun.UPDATED_AT);

			ReadOnlyForm.tilesStart(pWriter);
			if (owner.isEmpty())
			{
				ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.state"), ReadOnlyForm.badge(text(pLocale, "run.state.idle"), null),
					text(pLocale, "run.state.idle.detail"));
			}
			else if (expires != null && expires.after(now))
			{
				ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.state"), ReadOnlyForm.badge(text(pLocale, "run.state.running"), "Info"),
					text(pLocale, "run.state.running.detail", owner, ReadOnlyForm.age(pLocale, expires, now)));
			}
			else
			{
				ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.state"), ReadOnlyForm.badge(text(pLocale, "run.state.stalled"), "Warn"),
					text(pLocale, "run.state.stalled.detail", owner));
			}
			ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.completed"), MappingForm.escape(numbers.format(completed)),
				next.isEmpty() ? null : text(pLocale, "run.tile.completed.detail", next));
			ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.deadLetters"),
				abandoned == 0 ? MappingForm.escape("0") : ReadOnlyForm.badge(numbers.format(abandoned), "Bad"), text(pLocale, "run.tile.deadLetters.detail"));
			ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.attempts"), MappingForm.escape(numbers.format(attempts[0])),
				attempts[1] == 0 ? null : text(pLocale, "run.tile.attempts.detail", numbers.format(attempts[1])));
			ReadOnlyForm.tile(pWriter, text(pLocale, "run.tile.updated"), MappingForm.escape(ReadOnlyForm.age(pLocale, updated, now)),
				ReadOnlyForm.dateTime(pLocale, updated));
			ReadOnlyForm.tilesEnd(pWriter);

			if (abandoned > 0)
			{
				ReadOnlyForm.error(pWriter, text(pLocale, "run.deadLetters.title"), deadLetters.replace(",", ", "));
			}
			final String runId = ReadOnlyForm.value(pRecord, ConnectorsPaths.ExecutionRun.RUN_ID);
			if (!runId.isEmpty())
			{
				ReadOnlyForm.linksStart(pWriter);
				ReadOnlyForm.logLink(pWriter, pRecord, ReadOnlyForm.equals(ConnectorsPaths.SyncLog.RUN_ID, runId), text(pLocale, "run.link.log"));
				ReadOnlyForm.logLink(pWriter, pRecord, ReadOnlyForm.equals(ConnectorsPaths.SyncLog.RUN_ID, runId) + " and "
					+ ReadOnlyForm.equals(ConnectorsPaths.SyncLog.STATUS, "ERROR"), text(pLocale, "run.link.failures"));
				ReadOnlyForm.linksEnd(pWriter);
			}
		}

		/** Number of non-empty comma-separated values. */
		private static int count(final String pText)
		{
			int count = 0;
			for (final String item : pText.split(","))
			{
				if (!item.trim().isEmpty())
				{
					count++;
				}
			}
			return count;
		}

		/** { total attempts, items tried more than once } from itemKey=count pairs; unreadable pairs are ignored. */
		private static long[] attempts(final String pText)
		{
			final long[] out = new long[2];
			for (final String item : pText.split(","))
			{
				final int split = item.lastIndexOf('=');
				if (split <= 0)
				{
					continue;
				}
				try
				{
					final int attempts = Integer.parseInt(item.substring(split + 1).trim());
					out[0] += attempts;
					if (attempts > 1)
					{
						out[1]++;
					}
				}
				catch (final NumberFormatException ex)
				{
					// ignored, as the store does not write such a pair
				}
			}
			return out;
		}
	}
}
