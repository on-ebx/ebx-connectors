package com.on.ebx.connect.ebx.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
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

/** EBX-native dashboard which deliberately consumes the filtered selection to retain EBX permissions. */
public final class ConnectorOperationsUserService implements UserService<TableViewEntitySelection>
{
	private TableViewEntitySelection selection;
	private Locale locale = Locale.ENGLISH;
	@Override public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder) { selection = pContext.getEntitySelection(); }
	@Override public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(ConnectorOperationsUserService.class, this.locale, "operations.label"));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane() {
			@Override public void writePane(final UserServicePaneContext pContext, final UserServicePaneWriter pWriter) { ThemeTokens.write(pWriter, pContext); writeDashboard(pWriter); }
		});
	}

	private void writeDashboard(final UserServicePaneWriter pWriter)
	{
		final Map<String, Summary> summaries = new LinkedHashMap<String, Summary>();
		int total = 0, failures = 0;
		final RequestResult result = selection.getFilteredRecords().execute();
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				total++;
				final String connector = value(row, ConnectorsPaths.SyncLog.CONNECTOR, "Unknown");
				Summary summary = summaries.get(connector);
				if (summary == null)
				{
					summary = new Summary();
					summaries.put(connector, summary);
				}
				summary.total++;
				final String status = value(row, ConnectorsPaths.SyncLog.STATUS, "UNKNOWN");
				if ("ERROR".equals(status))
				{
					summary.failures++;
					failures++;
				}
				else if ("SKIPPED".equals(status)) summary.skipped++;
				else summary.success++;
				final Object duration = row.get(ConnectorsPaths.SyncLog.DURATION_MS);
				if (duration instanceof Number) summary.duration += ((Number) duration).longValue();
			}
		}
		finally { result.close(); }
		pWriter.add("<style>.ftops{max-width:1200px}.ftops-kpis{display:flex;gap:14px;margin-bottom:18px}.ftops-kpi,.ftops-card{background:var(--ebx-table-even,#fff);border:1px solid var(--ebx-table-border,#ddd);border-radius:8px;padding:16px}.ftops-kpi{min-width:150px}.ftops-kpi b{font-size:25px;display:block}.ftops-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}.ftops-card h3{margin:0 0 12px}.ftops-bad{color:var(--ftc-bad,#b3261e)}.ftops-ok{color:var(--ftc-ok,#237a3b)}.ftops-meta{color:var(--ftc-muted,#666);font-size:12px}a.ftops-link{display:block;margin-top:8px;color:var(--ftc-bad,#b3261e);font-size:12px;font-weight:600}a.ftops-bad{text-decoration:underline}</style>");
		pWriter.add("<div class=\"ftops\"><div class=\"ftops-kpis\"><div class=\"ftops-kpi\"><b>").add(String.valueOf(total)).add("</b>").add(text("operations.visibleCalls")).add("</div><div class=\"ftops-kpi\"><b>").add(String.valueOf(summaries.size())).add("</b>").add(text("operations.connectors")).add("</div><div class=\"ftops-kpi\"><b class=\"ftops-bad\">").add(String.valueOf(failures)).add("</b>").add(text("operations.failures"));
		if (failures > 0)
			pWriter.add("<a class=\"ftops-link\" target=\"_top\" href=\"").add(MappingForm.escape(failuresURI(null))).add("\">").add(MappingForm.escape(text("operations.showFailures"))).add(" &#8594;</a>");
		pWriter.add("</div></div><div class=\"ftops-grid\">");
		for (final Map.Entry<String, Summary> entry : summaries.entrySet())
		{
			final Summary s = entry.getValue();
			final String failed = Messages.get(ConnectorOperationsUserService.class, this.locale, "operations.failed", Integer.valueOf(s.failures));
			pWriter.add("<section class=\"ftops-card\"><h3>").add(MappingForm.escape(entry.getKey())).add("</h3><div><span class=\"ftops-ok\">").add(Messages.get(ConnectorOperationsUserService.class, this.locale, "operations.successful", Integer.valueOf(s.success))).add("</span> · ");
			if (s.failures > 0)
				pWriter.add("<a class=\"ftops-bad\" target=\"_top\" href=\"").add(MappingForm.escape(failuresURI(entry.getKey()))).add("\">").add(failed).add("</a>");
			else
				pWriter.add("<span class=\"ftops-bad\">").add(failed).add("</span>");
			pWriter.add(" · ").add(Messages.get(ConnectorOperationsUserService.class, this.locale, "operations.skipped", Integer.valueOf(s.skipped))).add("</div><p class=\"ftops-meta\">").add(text("operations.averageDuration")).add(": ").add(s.total == 0 ? "—" : String.valueOf(s.duration / s.total) + " ms").add("</p></section>");
		}
		pWriter.add("</div></div>");
		// Same client-side perspective detection as ConnectorCatalogUserService: keep the failure
		// links inside /ui/custom/<key>/ when the dashboard runs in a perspective.
		pWriter.addJS_cr("(function(){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "document.querySelectorAll('.ftops a[href*=\"'+o+'\"]').forEach(function(a){a.setAttribute('href',a.getAttribute('href').split(o).join(n));});})();");
	}

	/**
	 * The synchronization log filtered on ERROR rows, optionally for one connector. Not a /ebx/ web
	 * component: EBX opens those in the user's default perspective, not the current one (found live
	 * 2026-09-15). The ebx-ui data route takes the table filter as a predicate query parameter; the
	 * script in writeDashboard moves it onto the perspective the dashboard was opened from.
	 */
	private String failuresURI(final String pConnector)
	{
		String predicate = ConnectorsPaths.SyncLog.STATUS.format() + "='ERROR'";
		if (pConnector != null && !"Unknown".equals(pConnector))
			predicate += " and " + ConnectorsPaths.SyncLog.CONNECTOR.format() + "='" + pConnector.replace("'", "''") + "'";
		final Adaptation dataset = this.selection.getTable().getContainerAdaptation();
		return MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/"
			+ ConnectorCatalogUserService.pathSegment(dataset.getHome().getKey().format()) + "/"
			+ ConnectorCatalogUserService.pathSegment(dataset.getAdaptationName().getStringName()) + ConnectorsPaths.SYNC_LOG.format()
			+ "?predicate=" + ConnectorCatalogUserService.queryValue(predicate);
	}
	private String text(final String pKey) { return Messages.get(ConnectorOperationsUserService.class, this.locale, pKey); }
	private static String value(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath, final String pFallback)
	{
		final String value = pRow.getString(pPath);
		return value == null || value.isEmpty() ? pFallback : value;
	}
	private static final class Summary
	{
		int total, success, failures, skipped;
		long duration;
	}
	@Override public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext,
		final UserServiceEventOutcome pOutcome) { return pOutcome; }
	@Override public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext) { }
}
