package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.RunProgress;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.ui.UIAjaxComponent;
import com.orchestranetworks.ui.UIAjaxContext;

/**
 * The Ajax answer of the live run panel inside the execution-run record form (audit C-19): the state of the run whose key the
 * address carries, and Stop when the reader holds the RUN right. The synchronisation screen has its own request handler, which
 * can also resume what it started; a record form only watches and stops. Declared by name through
 * {@code UIFormPaneWriter.getURLForAjaxComponent}, hence public with an empty constructor.
 */
public final class RunPanelAjax extends UIAjaxComponent
{
	@Override
	public void doAjaxResponse(final UIAjaxContext pContext)
	{
		final Locale locale = pContext.getLocale();
		final String runId = pContext.getOptionalRequestParameterValue(RunPanelView.P_RUN);
		if (runId == null || runId.trim().isEmpty() || !ConnectorsAccess.has(pContext.getSession(), ConnectorsAccess.Right.READ))
		{
			pContext.add(RunPanelView.expired(locale));
			return;
		}
		final Repository repository = Repository.getDefault();
		final boolean mayRun = ConnectorsAccess.has(pContext.getSession(), ConnectorsAccess.Right.RUN);
		final boolean stop = mayRun && RunPanelView.ACTION_STOP.equals(pContext.getOptionalRequestParameterValue(RunPanelView.P_ACTION));
		if (stop)
		{
			ConnectorService.requestCancel(runId.trim());
		}
		final RunProgress progress = RunPanelView.progress(repository, runId.trim());
		pContext.add(RunPanelView.answer(locale, progress, RunPanelAjax.view(runId.trim(), mayRun, stop, repository)));
	}

	/** The view of a run seen from a record form: no continuation (the form holds no inputs), the log links of the dataset. */
	static RunPanelView.View view(final String pRunId, final boolean pMayRun, final boolean pStopRequested, final Repository pRepository)
	{
		final RunPanelView.View view = new RunPanelView.View();
		view.runId = pRunId;
		view.mayRun = pMayRun;
		view.stopRequested = pStopRequested;
		view.canResume = false;
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		view.dataset = dataset;
		return view;
	}
}
