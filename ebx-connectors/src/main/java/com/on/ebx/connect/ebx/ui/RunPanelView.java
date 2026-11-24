package com.on.ebx.connect.ebx.ui;

import java.util.Date;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.core.RunProgress;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.ui.UIComponentWriter;

/**
 * The live panel of a feed run (audit C-19), shared by the synchronisation screen and the execution-run form: the state of the
 * run as the module already answers it ({@link ConnectorsTools#runStatusJson}), its counters, a Stop button, a Resume button
 * where the engine supports it, and a script that polls the server every two seconds while the run is running. The server
 * writes the whole panel body, in the reader's language, so the script only swaps it in. Keyboard-operable (real buttons),
 * the state is written as a word and not only coloured, and only the state line is a live region, so a screen reader is not
 * told about every counter.
 */
final class RunPanelView
{
	/** Request parameters of the Ajax answer: the action ({@code status}, {@code stop}, {@code resume}) and the run it concerns. */
	static final String P_ACTION = "ftrpAction";
	static final String P_RUN = "ftrpRun";
	static final String ACTION_STATUS = "status";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESUME = "resume";

	static final String STYLE = "<style>.ftrp{margin:10px 0;padding:12px 14px;border-radius:8px;border:1px solid #dadce0;background:#fff;max-width:1100px}"
		+ ".ftrpHead{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;margin-bottom:6px}.ftrpTitle{font-weight:600;font-size:14px;color:#202124}"
		+ ".ftrpLabel{color:#5f6368;font-size:12px;overflow-wrap:anywhere}"
		+ ".ftrpTrack{position:relative;height:4px;margin:8px 0;border-radius:2px;background:#dfe3e7;overflow:hidden}"
		+ ".ftrpBar{position:absolute;top:0;left:0;height:100%;width:40%;border-radius:2px;background:var(--ebx-main,#1a73e8);animation:ftrpSlide 1.1s ease-in-out infinite}"
		+ "@keyframes ftrpSlide{0%{left:-40%}100%{left:100%}}@media (prefers-reduced-motion:reduce){.ftrpBar{animation:none;width:100%}}"
		+ ".ftrpActions{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:8px 0 2px}"
		+ ".ftrpBtn{font:inherit;font-size:13px;font-weight:600;cursor:pointer;padding:6px 14px;border-radius:6px;border:1px solid #dadce0;background:#fff;color:#3c4043}"
		+ ".ftrpBtn:hover{background:#f8f9fa}.ftrpBtn:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}.ftrpBtn[disabled]{opacity:.55;cursor:default}"
		+ ".ftrpStop{border-color:#c5221f;color:#a50e0e}"
		+ ".ftrpNote{margin:6px 0;font-size:12px;color:#5f6368}.ftrpWarn{padding:8px 12px;border-left:3px solid #b06000;background:#fef7e0;color:#7a4b00}"
		+ ".ftrpPre{margin:4px 0 0;white-space:pre-wrap;overflow-wrap:anywhere;font-family:monospace;font-size:12px}"
		+ ".ftrp details{margin:6px 0;font-size:12px;color:#5f6368}.ftrp summary{cursor:pointer}.ftrp summary:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}"
		+ "</style>";

	/** What a panel body depends on besides the run status. */
	static final class View
	{
		String runId;
		/** The mapping label, or empty when the screen does not know it. */
		String label = "";
		/** May the reader stop or resume a run (the RUN right); a reader with READ only watches. */
		boolean mayRun;
		/** Stop was asked from this panel; the run stops at its next batch boundary. */
		boolean stopRequested;
		/** The screen started the run itself and kept what a continuation needs (the inputs and the maximum). */
		boolean canResume;
		/** The Connectors dataset, for the links to the log rows of the run; null for none. */
		Adaptation dataset;
		/** A refused start or continuation, told as cause + action, shown above the state. */
		ErrorTexts failure;
	}

	private RunPanelView()
	{
	}

	/** The state of a run, read through the same answer as {@code connectors_run_get} (without its log page). */
	static RunProgress progress(final Repository pRepository, final String pRunId)
	{
		final ConnectorRepository repo = ConnectorService.get().repository(pRepository);
		final ObjectNode status = ConnectorsTools.runStatusJson(repo, pRunId, 0, 0);
		return RunProgress.of(status.path("execution"));
	}

	/** The panel in a page: its container (with the Ajax address and the run it follows) and the script that polls. */
	static void write(final UIComponentWriter pWriter, final Locale pLocale, final String pAjaxUrl, final String pBodyHtml, final String pRunId, final boolean pRunning)
	{
		pWriter.add(RunPanelView.STYLE);
		pWriter.add(ErrorTexts.STYLE);
		pWriter.add("<div class=\"ftrp\" id=\"ftrpPanel\" role=\"region\" aria-label=\"").add(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.region")))
			.add("\" data-url=\"").add(ErrorTexts.escape(pAjaxUrl)).add("\" data-run=\"").add(ErrorTexts.escape(pRunId)).add("\" data-poll=\"").add(pRunning ? "1" : "0")
			.add("\"><div id=\"ftrpBody\">").add(pBodyHtml).add("</div></div>");
		pWriter.addJS_cr("(function(){");
		pWriter.addJS_cr("  var d = document, box = d.getElementById('ftrpPanel'), body = d.getElementById('ftrpBody');");
		pWriter.addJS_cr("  if (!box || !body) { return; }");
		pWriter.addJS_cr("  var timer = null, busy = false;");
		pWriter.addJS_cr("  function links() {");
		pWriter.addJS_cr("    var m = /\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname); if (!m) { return; }");
		pWriter.addJS_cr("    var o = '/ui/advanced/data/', n = '/ui/custom/' + m[1] + '/data/';");
		pWriter.addJS_cr("    var a = box.querySelectorAll('a.ftroLink');");
		pWriter.addJS_cr("    for (var i = 0; i < a.length; i++) { var h = a[i].getAttribute('href'); if (h && h.indexOf(o) >= 0) { a[i].setAttribute('href', h.split(o).join(n)); } }");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  function swap(r) {");
		pWriter.addJS_cr("    if (!r || !r.html) { return; }");
		pWriter.addJS_cr("    var had = d.activeElement && body.contains(d.activeElement) ? d.activeElement.getAttribute('data-ftrp-action') : null;");
		pWriter.addJS_cr("    body.innerHTML = r.html; box.setAttribute('data-run', r.runId); box.setAttribute('data-poll', r.running ? '1' : '0'); links();");
		pWriter.addJS_cr("    if (had) { var again = body.querySelector('[data-ftrp-action=\"' + had + '\"]'); if (!again || again.disabled) { again = body.querySelector('[data-ftrp-action=\"status\"]'); } if (again) { again.focus(); } }");
		pWriter.addJS_cr("    if (r.running) { schedule(); }");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  function ask(action, done) {");
		pWriter.addJS_cr("    if (busy || typeof EBX_AJAXResponseHandler === 'undefined') { return; }");
		pWriter.addJS_cr("    busy = true;");
		pWriter.addJS_cr("    var Handler = function() {}; Handler.prototype = new EBX_AJAXResponseHandler(); var h = new Handler();");
		pWriter.addJS_cr("    h.handleAjaxResponseSuccess = function(text) { busy = false; var r = null; try { r = JSON.parse(text); } catch (e) { r = null; } done(r); };");
		pWriter.addJS_cr("    h.handleAjaxResponseFailed = function() { busy = false; done(null); };");
		pWriter.addJS_cr("    h.sendRequest(box.getAttribute('data-url') + '&" + RunPanelView.P_ACTION + "=' + encodeURIComponent(action) + '&" + RunPanelView.P_RUN
			+ "=' + encodeURIComponent(box.getAttribute('data-run')));");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  function schedule() { if (timer) { window.clearTimeout(timer); } timer = window.setTimeout(function() { timer = null; ask('" + RunPanelView.ACTION_STATUS
			+ "', function(r) { if (r) { swap(r); } else { schedule(); } }); }, 2000); }");
		pWriter.addJS_cr("  box.addEventListener('click', function(e) {");
		pWriter.addJS_cr("    var b = e.target && e.target.closest ? e.target.closest('[data-ftrp-action]') : null; if (!b || b.disabled) { return; }");
		pWriter.addJS_cr("    b.disabled = true; if (timer) { window.clearTimeout(timer); timer = null; }");
		pWriter.addJS_cr("    ask(b.getAttribute('data-ftrp-action'), function(r) { if (r) { swap(r); } else { b.disabled = false; if (box.getAttribute('data-poll') === '1') { schedule(); } } });");
		pWriter.addJS_cr("  }, false);");
		pWriter.addJS_cr("  links();");
		pWriter.addJS_cr("  if (box.getAttribute('data-poll') === '1') { schedule(); }");
		pWriter.addJS_cr("})();");
	}

	/** The answer of the Ajax request: the new body, the run it follows (a continuation starts another) and whether to keep polling. */
	static String answer(final Locale pLocale, final RunProgress pProgress, final View pView)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pView.runId);
		out.put("running", pProgress.isRunning());
		out.put("html", RunPanelView.body(pLocale, pProgress, pView));
		return Json.writeForScript(out);
	}

	/** The answer for a panel that no longer follows a run (another run replaced it, or the screen was rebuilt). */
	static String expired(final Locale pLocale)
	{
		final ObjectNode out = Json.object();
		out.put("expired", true);
		out.put("runId", "");
		out.put("running", false);
		out.put("html", "<div class=\"ftrpNote\">" + ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.expired")) + "</div>");
		return Json.writeForScript(out);
	}

	/** The inside of the panel. */
	static String body(final Locale pLocale, final RunProgress pProgress, final View pView)
	{
		final StringBuilder out = new StringBuilder();
		if (pView.failure != null)
		{
			out.append("<div class=\"ftrpWarn\" role=\"alert\">").append(pView.failure.html(pLocale)).append("</div>");
		}
		out.append("<div class=\"ftrpHead\"><span class=\"ftrpTitle\">").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.title"))).append("</span>")
			.append("<span aria-live=\"polite\">").append(ReadOnlyForm.badge(RunPanelView.text(pLocale, "runpanel.status." + pProgress.getStatus()), RunPanelView.tone(pProgress.getStatus())))
			.append("</span><span class=\"ftrpLabel\">")
			.append(ErrorTexts.escape((pView.label.isEmpty() ? "" : pView.label + " · ") + RunPanelView.text(pLocale, "runpanel.run", pView.runId))).append("</span></div>");
		if (pProgress.isRunning())
		{
			out.append("<div class=\"ftrpTrack\" role=\"progressbar\" aria-busy=\"true\" aria-label=\"").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.status.RUNNING")))
				.append("\"><div class=\"ftrpBar\"></div></div>");
		}
		out.append("<div class=\"ftroTiles\">")
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.processed"), RunPanelView.number(pLocale, pProgress.getProcessed()), null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.created"), RunPanelView.number(pLocale, pProgress.getCreated()), null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.updated"), RunPanelView.number(pLocale, pProgress.getUpdated()), null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.unchanged"), RunPanelView.number(pLocale, pProgress.getNoChange()), null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.skipped"), RunPanelView.number(pLocale, pProgress.getSkipped() + pProgress.getRejected()), null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.errors"),
				pProgress.getErrors() > 0 ? ReadOnlyForm.badge(String.valueOf(pProgress.getErrors()), "Bad") : "0", null))
			.append(ReadOnlyForm.tileHtml(RunPanelView.text(pLocale, "runpanel.tile.elapsed"), RunPanelView.duration(pProgress.elapsedSeconds(new Date())),
				pProgress.getNextIndex() > 0 ? RunPanelView.text(pLocale, "runpanel.tile.next", RunPanelView.number(pLocale, pProgress.getNextIndex())) : null))
			.append("</div>");
		if (pProgress.isRunning() && pView.stopRequested)
		{
			out.append("<div class=\"ftrpNote\">").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.stopping"))).append("</div>");
		}
		final String message = pProgress.getMessage();
		if (!message.isEmpty())
		{
			final ErrorTexts texts = ErrorTexts.of(pLocale, message, null);
			if (texts.isKnown() || RunProgress.ERROR.equals(pProgress.getStatus()) || RunProgress.INTERRUPTED.equals(pProgress.getStatus()))
			{
				out.append("<div class=\"ftrpWarn\">").append(texts.html(pLocale)).append("</div>");
			}
			else
			{
				out.append("<div class=\"ftrpNote\">").append(ErrorTexts.escape(Redaction.redact(message))).append("</div>");
			}
		}
		if (pProgress.isTruncated())
		{
			out.append("<div class=\"ftrpNote ftrpWarn\">").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.truncated", RunPanelView.number(pLocale, pProgress.getAvailable()))))
				.append("</div>");
		}
		if (!pProgress.getFirstErrors().isEmpty())
		{
			out.append("<details><summary>").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.firstErrors"))).append("</summary><div class=\"ftrpPre\">")
				.append(ErrorTexts.escape(Redaction.redact(pProgress.getFirstErrors()))).append("</div></details>");
		}
		out.append("<div class=\"ftrpActions\">");
		if (pProgress.isRunning() && pView.mayRun)
		{
			out.append("<button type=\"button\" class=\"ftrpBtn ftrpStop\" data-ftrp-action=\"").append(RunPanelView.ACTION_STOP).append('"')
				.append(pView.stopRequested ? " disabled" : "").append('>').append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.stop"))).append("</button>");
		}
		if (pProgress.isResumable() && pView.mayRun && pView.canResume)
		{
			out.append("<button type=\"button\" class=\"ftrpBtn\" data-ftrp-action=\"").append(RunPanelView.ACTION_RESUME).append("\">")
				.append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.resume", RunPanelView.number(pLocale, pProgress.getNextIndex())))).append("</button>");
		}
		out.append("<button type=\"button\" class=\"ftrpBtn\" data-ftrp-action=\"").append(RunPanelView.ACTION_STATUS).append("\">")
			.append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.refresh"))).append("</button></div>");
		if (pProgress.isResumable() && pView.mayRun && pView.canResume)
		{
			out.append("<div class=\"ftrpNote\">").append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.resume.hint"))).append("</div>");
		}
		if (pView.dataset != null && pView.runId != null && !pView.runId.isEmpty())
		{
			final String runPredicate = ReadOnlyForm.equals(ConnectorsPaths.SyncLog.RUN_ID, pView.runId);
			out.append("<div class=\"ftroLinks\"><a class=\"ftroLink\" target=\"_top\" href=\"").append(ErrorTexts.escape(ReadOnlyForm.logHref(pView.dataset, runPredicate))).append("\">")
				.append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.link.log"))).append(" &#8594;</a><a class=\"ftroLink\" target=\"_top\" href=\"")
				.append(ErrorTexts.escape(ReadOnlyForm.logHref(pView.dataset, runPredicate + " and " + ReadOnlyForm.equals(ConnectorsPaths.SyncLog.STATUS, "ERROR")))).append("\">")
				.append(ErrorTexts.escape(RunPanelView.text(pLocale, "runpanel.link.failures"))).append(" &#8594;</a></div>");
		}
		return out.toString();
	}

	static String tone(final String pStatus)
	{
		if (RunProgress.RUNNING.equals(pStatus))
		{
			return "Info";
		}
		if (RunProgress.DONE.equals(pStatus))
		{
			return "Good";
		}
		if (RunProgress.ERROR.equals(pStatus))
		{
			return "Bad";
		}
		if (RunProgress.PARTIAL.equals(pStatus) || RunProgress.STOPPED.equals(pStatus) || RunProgress.INTERRUPTED.equals(pStatus))
		{
			return "Warn";
		}
		return null;
	}

	/** {@code m:ss}, or {@code h:mm:ss} from an hour. */
	static String duration(final long pSeconds)
	{
		final long hours = pSeconds / 3600L;
		final long minutes = pSeconds / 60L % 60L;
		final long seconds = pSeconds % 60L;
		return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", Long.valueOf(hours), Long.valueOf(minutes), Long.valueOf(seconds))
			: String.format(Locale.ROOT, "%d:%02d", Long.valueOf(minutes), Long.valueOf(seconds));
	}

	private static String number(final Locale pLocale, final int pValue)
	{
		return java.text.NumberFormat.getIntegerInstance(pLocale == null ? Locale.ENGLISH : pLocale).format(pValue);
	}

	static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(RunPanelView.class, pLocale, pKey, pArgs);
	}
}
