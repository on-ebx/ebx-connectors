package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.selection.DatasetEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/** Read-only, dataset-scoped presentation of release records. */
public final class ReleaseNotesUserService implements UserService<DatasetEntitySelection>
{
	private Adaptation dataset;
	private Locale locale = Locale.ENGLISH;

	@Override public void setupObjectContext(final UserServiceSetupObjectContext<DatasetEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder) { this.dataset = pContext.getEntitySelection().getDataset(); }

	@Override public void setupDisplay(final UserServiceSetupDisplayContext<DatasetEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(message("releaseNotes.label"));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{ ThemeTokens.write(pWriter, pPaneContext); ReleaseNotesUserService.this.writeReleaseNotes(pWriter); }
		});
	}

	private void writeReleaseNotes(final UserServicePaneWriter pWriter)
	{
		final List<Adaptation> releases = rows(this.dataset, ConnectorsPaths.RELEASE);
		// newest first, on the date itself and the version number (ReleaseOrder says what the text of a date did to this page)
		Collections.sort(releases, (a, b) -> ReleaseOrder.compare(releaseDate(a), text(a, ConnectorsPaths.Release.VERSION),
			releaseDate(b), text(b, ConnectorsPaths.Release.VERSION)));
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "connect/release-notes.css")).add("?v=3\"/>");
		pWriter.addJS_cr("function ftrnExportPdf(){var source=document.querySelector('.ftrn').cloneNode(true);"
			+ "source.querySelectorAll('details').forEach(function(d){d.open=true;});"
			+ "var button=source.querySelector('.ftrn-export');if(button){button.remove();}"
			+ "var css=document.querySelector('link[href*=\"release-notes.css\"]').href;"
			// the colours of the perspective (audit C-40) go along with the copy
			+ "var theme=document.querySelector('style[data-ebx-theme]');"
			+ "var win=window.open('','_blank');if(!win){return;}"
			+ "win.document.open();win.document.write('<!doctype html><html><head><meta charset=\"UTF-8\"><title>EBX Connectors Release Notes</title><link rel=\"stylesheet\" href=\"'+css+'\">'+(theme?theme.outerHTML:'')+'</head><body class=\"ftrn-pdf\">'+source.outerHTML+'</body></html>');win.document.close();"
			+ "win.focus();win.setTimeout(function(){win.print();},500);}");
		pWriter.add("<main class=\"ftrn\"><header><div class=\"ftrn-header-copy\"><p class=\"ftrn-kicker\">").add(escape(message("releaseNotes.kicker")))
			.add("</p><h1>").add(escape(message("releaseNotes.heading"))).add("</h1><p>").add(escape(message("releaseNotes.intro")))
			.add("</p></div><button class=\"ftrn-export\" type=\"button\" onclick=\"ftrnExportPdf()\">")
			.add(escape(message("releaseNotes.exportPdf"))).add("</button></header>");
		writeGettingStarted(pWriter);
		boolean rendered = false;
		for (final Adaptation release : releases)
		{
			if ("DRAFT".equals(text(release, ConnectorsPaths.Release.STATUS))) { continue; }
			rendered = true;
			writeRelease(pWriter, release);
		}
		if (!rendered) { pWriter.add("<section class=\"ftrn-empty\">").add(escape(message("releaseNotes.empty"))).add("</section>"); }
		pWriter.add("</main>");
	}

	/** The release history is technical by nature; lead with the tasks a business user can perform. */
	private void writeGettingStarted(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<section class=\"ftrn-start\"><h2>").add(escape(message("releaseNotes.start.title"))).add("</h2><p>")
			.add(escape(message("releaseNotes.start.intro"))).add("</p><div class=\"ftrn-start-grid\">");
		writeStartCard(pWriter, "releaseNotes.start.connect.title", "releaseNotes.start.connect.detail");
		writeStartCard(pWriter, "releaseNotes.start.design.title", "releaseNotes.start.design.detail");
		writeStartCard(pWriter, "releaseNotes.start.test.title", "releaseNotes.start.test.detail");
		writeStartCard(pWriter, "releaseNotes.start.run.title", "releaseNotes.start.run.detail");
		pWriter.add("</div><p class=\"ftrn-start-note\">").add(escape(message("releaseNotes.start.note"))).add("</p></section>");
	}

	private void writeStartCard(final UserServicePaneWriter pWriter, final String pTitleKey, final String pDetailKey)
	{
		pWriter.add("<div><h3>").add(escape(message(pTitleKey))).add("</h3><p>").add(escape(message(pDetailKey))).add("</p></div>");
	}

	private void writeRelease(final UserServicePaneWriter pWriter, final Adaptation pRelease)
	{
		final String version = text(pRelease, ConnectorsPaths.Release.VERSION);
		final String status = text(pRelease, ConnectorsPaths.Release.STATUS);
		final List<Adaptation> items = related(ConnectorsPaths.RELEASE_ITEM, ConnectorsPaths.ReleaseItem.RELEASE, version, ConnectorsPaths.ReleaseItem.DISPLAY_ORDER);
		final List<Adaptation> metrics = related(ConnectorsPaths.RELEASE_METRIC, ConnectorsPaths.ReleaseMetric.RELEASE, version, ConnectorsPaths.ReleaseMetric.DISPLAY_ORDER);
		final List<Adaptation> passes = related(ConnectorsPaths.RELEASE_PASS_STATUS, ConnectorsPaths.ReleasePassStatus.RELEASE, version, ConnectorsPaths.ReleasePassStatus.DISPLAY_ORDER);
		final String versionLabel = version.startsWith("development-preview") ? message("releaseNotes.developmentPreview") : "v" + version;
		final String releaseTitle = version.startsWith("development-preview") ? message("releaseNotes.previewTitle") : text(pRelease, ConnectorsPaths.Release.TITLE);
		pWriter.add("<article class=\"ftrn-release\"><div class=\"ftrn-title\"><div><span class=\"ftrn-version\">").add(escape(versionLabel))
			.add("</span><span class=\"ftrn-status\">").add(escape(enumMessage("releaseNotes.status.", status))).add("</span><h2>")
			.add(escape(releaseTitle)).add("</h2></div><time datetime=\"").add(escape(ReleaseOrder.isoDate(releaseDate(pRelease)))).add("\">")
			.add(escape(ReleaseOrder.dateLabel(releaseDate(pRelease), this.locale)))
			.add("</time></div><p class=\"ftrn-summary\">").add(escape(text(pRelease, ConnectorsPaths.Release.SUMMARY))).add("</p>");
		if (!metrics.isEmpty())
		{
			pWriter.add("<details class=\"ftrn-technical\"><summary>").add(escape(message("releaseNotes.technicalDetails"))).add("</summary><div class=\"ftrn-metrics\">");
			for (final Adaptation metric : metrics) pWriter.add("<div><strong>").add(escape(text(metric, ConnectorsPaths.ReleaseMetric.VALUE)))
				.add("</strong><span>").add(escape(text(metric, ConnectorsPaths.ReleaseMetric.LABEL))).add("</span></div>");
			pWriter.add("</div></details>");
		}
		final Map<String, List<Adaptation>> groups = new LinkedHashMap<String, List<Adaptation>>();
		for (final Adaptation item : items) groups.computeIfAbsent(text(item, ConnectorsPaths.ReleaseItem.TYPE), k -> new ArrayList<Adaptation>()).add(item);
		for (final Map.Entry<String, List<Adaptation>> group : groups.entrySet())
		{
			pWriter.add("<section class=\"ftrn-items\"><h3>").add(escape(enumMessage("releaseNotes.type.", group.getKey()))).add("</h3><ul>");
			for (final Adaptation item : group.getValue())
			{
				final String title = text(item, ConnectorsPaths.ReleaseItem.TITLE);
				final String detail = text(item, ConnectorsPaths.ReleaseItem.DETAIL);
				pWriter.add("<li><strong>").add(escape(title)).add("</strong>");
				if (!detail.isEmpty() && !detail.equals(title)) pWriter.add("<p>").add(escape(detail)).add("</p>");
				pWriter.add("</li>");
			}
			pWriter.add("</ul></section>");
		}
		if (!passes.isEmpty())
		{
			pWriter.add("<details class=\"ftrn-delivery\"><summary>").add(escape(message("releaseNotes.delivery"))).add("</summary><p class=\"ftrn-delivery-intro\">")
				.add(escape(message("releaseNotes.deliveryIntro"))).add("</p>");
			for (final Adaptation pass : passes) writePass(pWriter, version, pass);
			pWriter.add("</details>");
		}
		final String build = text(pRelease, ConnectorsPaths.Release.BUILD_VERSION), commit = text(pRelease, ConnectorsPaths.Release.COMMIT_ID);
		if (!build.isEmpty() || !commit.isEmpty()) pWriter.add("<footer>").add(escape(message("releaseNotes.build", build, commit))).add("</footer>");
		pWriter.add("</article>");
	}

	private void writePass(final UserServicePaneWriter pWriter, final String pVersion, final Adaptation pPass)
	{
		final String passCode = text(pPass, ConnectorsPaths.ReleasePassStatus.CODE);
		final String status = text(pPass, ConnectorsPaths.ReleasePassStatus.STATUS);
		final String statusClass = status.toLowerCase(Locale.ROOT).replace('_', '-');
		final List<Adaptation> steps = relatedSteps(pVersion, passCode);
		pWriter.add("<details class=\"ftrn-pass\"");
		if (!"COMPLETE".equals(status)) pWriter.add(" open");
		pWriter.add("><summary><strong>").add(escape(text(pPass, ConnectorsPaths.ReleasePassStatus.LABEL)))
			.add("</strong><span class=\"ftrn-pass-status ").add(escape(statusClass)).add("\">")
			.add(escape(enumMessage("releaseNotes.pass.", status))).add("</span><progress max=\"100\" value=\"")
			.add(escape(text(pPass, ConnectorsPaths.ReleasePassStatus.PROGRESS))).add("\"></progress></summary>")
			.add("<div class=\"ftrn-substeps\"><p class=\"ftrn-gate\">")
			.add(escape(text(pPass, ConnectorsPaths.ReleasePassStatus.REMAINING_GATES))).add("</p><ul>");
		for (final Adaptation step : steps)
		{
			final String stepStatus = text(step, ConnectorsPaths.ReleasePassStep.STATUS);
			final String stepClass = stepStatus.toLowerCase(Locale.ROOT).replace('_', '-');
			pWriter.add("<li><span class=\"ftrn-step-status ").add(escape(stepClass)).add("\">")
				.add(escape(enumMessage("releaseNotes.pass.", stepStatus))).add("</span><strong>")
				.add(escape(text(step, ConnectorsPaths.ReleasePassStep.LABEL))).add("</strong>");
			final String detail = text(step, ConnectorsPaths.ReleasePassStep.DETAIL);
			if (!detail.isEmpty()) pWriter.add("<p>").add(escape(detail)).add("</p>");
			pWriter.add("</li>");
		}
		pWriter.add("</ul></div></details>");
	}

	private List<Adaptation> relatedSteps(final String pVersion, final String pPassCode)
	{
		final List<Adaptation> result = rows(this.dataset, ConnectorsPaths.RELEASE_PASS_STEP);
		result.removeIf(row -> !pVersion.equals(text(row, ConnectorsPaths.ReleasePassStep.RELEASE))
			|| !pPassCode.equals(text(row, ConnectorsPaths.ReleasePassStep.PASS_CODE)));
		Collections.sort(result, Comparator.comparingInt(row -> number(row.get(ConnectorsPaths.ReleasePassStep.DISPLAY_ORDER))));
		return result;
	}

	private List<Adaptation> related(final com.orchestranetworks.schema.Path pTable, final com.orchestranetworks.schema.Path pRelease,
		final String pVersion, final com.orchestranetworks.schema.Path pOrder)
	{
		final List<Adaptation> result = rows(this.dataset, pTable);
		result.removeIf(row -> !pVersion.equals(text(row, pRelease)));
		Collections.sort(result, Comparator.comparingInt(row -> number(row.get(pOrder))));
		return result;
	}

	private static java.util.Date releaseDate(final Adaptation pRelease)
	{
		final Object value = pRelease.get(ConnectorsPaths.Release.RELEASE_DATE);
		return value instanceof java.util.Date ? (java.util.Date) value : null;
	}

	private static List<Adaptation> rows(final Adaptation pDataset, final com.orchestranetworks.schema.Path pPath)
	{
		final List<Adaptation> result = new ArrayList<Adaptation>();
		if (pDataset == null) { return result; }
		final AdaptationTable table = pDataset.getTable(pPath);
		if (table == null) { return result; }
		try (RequestResult rows = table.createRequest().execute()) { for (Adaptation row; (row = rows.nextAdaptation()) != null;) result.add(row); }
		return result;
	}

	private String enumMessage(final String pPrefix, final String pValue)
	{
		final String translated = message(pPrefix + pValue);
		return translated.equals(pPrefix + pValue) ? pValue.replace('_', ' ') : translated;
	}
	private String message(final String pKey, final Object... pArgs) { return Messages.get(ReleaseNotesUserService.class, this.locale, pKey, pArgs); }
	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}
	private static int number(final Object pValue) { return pValue instanceof Number ? ((Number) pValue).intValue() : 0; }
	static String escape(final String pValue) { return pValue == null ? "" : pValue.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

	@Override public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<DatasetEntitySelection> pContext, final UserServiceEventOutcome pOutcome) { return pOutcome; }
	@Override public void validate(final UserServiceValidateContext<DatasetEntitySelection> pContext) { }
}
