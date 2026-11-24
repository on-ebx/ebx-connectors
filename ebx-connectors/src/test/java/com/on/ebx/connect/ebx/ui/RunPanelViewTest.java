package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RunProgress;

/** The live run panel: who sees Stop and Resume, what a failure says, nothing carried by colour alone, nothing secret (audit C-19, C-38). */
public final class RunPanelViewTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode execution = Json.object();
		execution.put("status", "RUNNING");
		execution.put("created", 40);
		execution.put("updated", 2);
		execution.put("noChange", 8);
		execution.put("errorCount", 1);
		execution.put("nextIndex", 51);
		execution.put("startedAt", "2026-10-06T10:00:00");
		final RunPanelView.View view = new RunPanelView.View();
		view.runId = "RUN-7";
		view.label = "Companies feed";
		view.mayRun = true;

		final String running = RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view);
		check(running.contains("data-ftrp-action=\"stop\"") && !running.contains("data-ftrp-action=\"resume\""), "a running run offers Stop and no Resume");
		check(running.contains(">Running<") && running.contains("role=\"progressbar\"") && running.contains("aria-live=\"polite\""), "the state is a word, the bar has a role");
		check(running.contains("data-ftrp-action=\"status\""), "Refresh is always there");
		check(running.contains("run RUN-7") && running.contains("Companies feed"), "the run and its mapping are named");

		// a reader with READ only watches
		view.mayRun = false;
		final String watching = RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view);
		check(!watching.contains("data-ftrp-action=\"stop\"") && watching.contains("data-ftrp-action=\"status\""), "no Stop without the RUN right");
		view.mayRun = true;

		// Stop asked: the button is disabled and says what happens
		view.stopRequested = true;
		final String stopping = RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view);
		check(stopping.contains("data-ftrp-action=\"stop\" disabled") && stopping.contains("next batch boundary"), "a stop asked is shown");
		view.stopRequested = false;

		// stopped: Resume only when the screen holds what a continuation needs
		execution.put("status", "STOPPED");
		execution.put("message", "Import cancelled after 51 item(s)");
		view.canResume = true;
		final String stopped = RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view);
		check(stopped.contains("data-ftrp-action=\"resume\"") && stopped.contains("Resume from item 51") && stopped.contains(">Stopped<") && !stopped.contains("role=\"progressbar\""), "a stopped run can be resumed");
		check(stopped.contains("same order"), "the resume caveat is said");
		view.canResume = false;
		check(!RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view).contains("data-ftrp-action=\"resume\""), "no Resume for a run the screen only watches");

		// a failed run says what to do, and shows no secret
		execution.put("status", "ERROR");
		execution.put("message", "GET https://api.example.com/v1?api_key=SECRETVALUE123 failed: Connection refused");
		final String failed = RunPanelView.body(Locale.ENGLISH, RunProgress.of(execution), view);
		check(failed.contains("accepted no connection") && failed.contains("Check the address") && !failed.contains("SECRETVALUE123"), "cause and action, no secret: " + failed);
		final String french = RunPanelView.body(Locale.FRENCH, RunProgress.of(execution), view);
		check(french.contains("aucune connexion") && french.contains("En échec") && !french.contains("SECRETVALUE123"), "the same in French: " + french);

		// the Ajax answer carries the run, the polling flag and the body
		final String answer = RunPanelView.answer(Locale.ENGLISH, RunProgress.of(execution), view);
		check(answer.contains("\"running\":false") && answer.contains("\"runId\":\"RUN-7\"") && !answer.contains("<"), "the answer is inert text for a script: " + answer);
		check(RunPanelView.expired(Locale.ENGLISH).contains("\"expired\":true"), "an expired panel says so");

		check("0:05".equals(RunPanelView.duration(5)) && "2:03".equals(RunPanelView.duration(123)) && "1:01:01".equals(RunPanelView.duration(3661)), "durations");
		System.out.println("PASS: the run panel offers Stop and Resume by right, says the state in words and the cause of a failure, and leaks no secret");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
