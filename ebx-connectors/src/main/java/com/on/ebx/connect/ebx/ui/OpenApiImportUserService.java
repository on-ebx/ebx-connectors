package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.ebx.OpenApiImport;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * Form: connector id, label, either a URL or the pasted specification (JSON or YAML), replace
 * flag, dry-run flag. Runs {@link OpenApiImport} and shows the report.
 */
public final class OpenApiImportUserService implements UserService<TableViewEntitySelection>
{
	private String id = "";
	private String label = "";
	private String url = "";
	private String spec = "";
	private boolean replace;
	private boolean dryRun;
	private OpenApiImport.Report report;
	private String error;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		// plain HTML fields, read back from the request
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(OpenApiImportUserService.class, locale, "openapi.title"));
		final UIButtonSpecSubmit run = pConfigurator.newSubmitButton(Messages.get(OpenApiImportUserService.class, locale, "openapi.run"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				OpenApiImportUserService.this.read(pEventContext);
				OpenApiImportUserService.this.run(pEventContext);
				return null;
			}
		});
		run.setId("ftoaRun");
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(run);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				OpenApiImportUserService.this.write(pWriter, pPaneContext.getSession().getLocale());
			}
		});
	}

	private void read(final UserServiceEventContext pContext)
	{
		this.id = OpenApiImportUserService.param(pContext, "ftoaId");
		this.label = OpenApiImportUserService.param(pContext, "ftoaLabel");
		this.url = OpenApiImportUserService.param(pContext, "ftoaUrl");
		this.spec = OpenApiImportUserService.param(pContext, "ftoaSpec");
		this.replace = "on".equals(pContext.getParameter("ftoaReplace"));
		this.dryRun = "on".equals(pContext.getParameter("ftoaDryRun"));
	}

	private static String param(final UserServiceEventContext pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value.trim();
	}

	private void run(final UserServiceEventContext pContext)
	{
		this.report = null;
		this.error = null;
		try
		{
			final String text = !this.url.isEmpty() ? OpenApiImport.fetch(this.url) : this.spec;
			this.report = OpenApiImport.run(pContext.getSession(), pContext.getRepository(), this.id, this.label, text, this.replace, this.dryRun);
		}
		catch (final Exception ex)
		{
			this.error = com.on.ebx.connect.core.Redaction.failure(ex);
		}
	}

	private void write(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		pWriter.add("<style>.ftoa{font-size:12px;max-width:980px}.ftoa label{display:block;font-weight:600;margin:10px 0 3px}.ftoa input[type=text],.ftoa textarea{width:100%;font:inherit;padding:5px;border:1px solid var(--ebx-table-border,#dadce0);border-radius:4px;box-sizing:border-box}"
				+ ".ftoa textarea{font-family:Menlo,Consolas,monospace;font-size:11px;min-height:260px}.ftoa .hint{color:var(--ftc-muted,#5f6368);margin:4px 0 8px}.ftoa .msg{margin:8px 0;padding:8px 12px;border-radius:6px;background:var(--ftc-accent-soft,#e8f0fe);border:1px solid var(--ftc-accent-line,#c6dafc)}"
				+ ".ftoa .err{background:var(--ftc-bad-bg,#fce8e6);border-color:var(--ftc-bad-line,#f5c2c0);color:var(--ftc-bad,#c5221f)}.ftoa ul{margin:4px 0 0 18px}.ftoa code{font-size:11px}</style><div class=\"ftoa\">");
		pWriter.add("<div class=\"hint\">").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.hint"))).add("</div>");
		if (this.error != null)
		{
			pWriter.add("<div class=\"msg err\">").add(MappingForm.escape(this.error)).add("</div>");
		}
		if (this.report != null)
		{
			pWriter.add("<div class=\"msg\"><b>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale,
				this.report.dryRun ? "openapi.previewed" : this.report.created ? "openapi.created" : "openapi.replaced", this.report.connectorId))).add("</b>");
			pWriter.add("<div>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.summary", this.report.baseUrl, this.report.authType,
				Integer.valueOf(this.report.operations.size()), Integer.valueOf(this.report.propertyCount)))).add("</div><ul>");
			for (final String op : this.report.operations)
			{
				pWriter.add("<li><code>").add(MappingForm.escape(op)).add("</code></li>");
			}
			pWriter.add("</ul>");
			if (!this.report.notes.isEmpty())
			{
				pWriter.add("<ul>");
				for (final String note : this.report.notes)
				{
					pWriter.add("<li>").add(MappingForm.escape(note)).add("</li>");
				}
				pWriter.add("</ul>");
			}
			pWriter.add("</div>");
		}
		pWriter.add("<label>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.id"))).add("</label>");
		pWriter.add("<input type=\"text\" name=\"ftoaId\" value=\"").add(MappingForm.escape(this.id)).add("\" placeholder=\"RechercheEntreprises\"/>");
		pWriter.add("<label>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.connectorLabel"))).add("</label>");
		pWriter.add("<input type=\"text\" name=\"ftoaLabel\" value=\"").add(MappingForm.escape(this.label)).add("\"/>");
		pWriter.add("<label>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.url"))).add("</label>");
		pWriter.add("<input type=\"text\" name=\"ftoaUrl\" value=\"").add(MappingForm.escape(this.url)).add("\" placeholder=\"https://.../openapi.json\"/>");
		pWriter.add("<label>").add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.spec"))).add("</label>");
		pWriter.add("<textarea name=\"ftoaSpec\" spellcheck=\"false\">").add(MappingForm.escape(this.spec)).add("</textarea>");
		pWriter.add("<div style=\"margin-top:10px\"><label style=\"display:inline;font-weight:400\"><input type=\"checkbox\" name=\"ftoaReplace\"").add(this.replace ? " checked" : "").add("/> ")
				.add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.replace"))).add("</label> &nbsp; ");
		pWriter.add("<label style=\"display:inline;font-weight:400\"><input type=\"checkbox\" name=\"ftoaDryRun\"").add(this.dryRun ? " checked" : "").add("/> ")
				.add(MappingForm.escape(Messages.get(OpenApiImportUserService.class, pLocale, "openapi.dryRun"))).add("</label></div></div>");
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// free text
	}
}
