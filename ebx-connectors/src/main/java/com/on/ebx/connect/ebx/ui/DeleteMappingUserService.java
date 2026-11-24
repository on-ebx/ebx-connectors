package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.ebx.ConfigTransfer;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.EntitySelection;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
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
 * "Delete the mapping and everything attached": on a Mapping record or on the mappings selected in
 * the table view, shows what will go (rules, inputs, sub-object mappings, outbound states) and what
 * will only lose its reference (provenance, conflicts), then deletes through
 * {@link ConfigTransfer#deleteMapping}. The cascade trigger of the Mapping table does the same when
 * a mapping is deleted any other way; this screen adds the preview and the confirmation.
 */
public final class DeleteMappingUserService<S extends EntitySelection> implements UserService<S>
{
	private static final String STYLE = "<style>"
			+ ".ftdmIntro{margin:8px 0 12px;color:#444}"
			+ ".ftdmTable{border-collapse:collapse;font-size:13px}.ftdmTable th,.ftdmTable td{border:1px solid var(--ebx-table-border,#ddd);padding:5px 10px;text-align:left}"
			+ ".ftdmTable th{background:var(--ebx-table-header,#f4f4f4)}.ftdmTable tr:nth-child(odd) td{background:var(--ebx-table-odd,transparent)}.ftdmTable tr:nth-child(even) td{background:var(--ebx-table-even,transparent)}.ftdmNum{text-align:right}"
			+ ".ftdmMsg{margin:8px 0;padding:8px 12px;border-radius:4px;background:#e8f4ea;color:#1e5b2a}.ftdmErr{background:#fbe9e7;color:#8a1c0e}"
			+ ".ftdmHint{color:#777;margin-top:10px}</style>";

	private List<Adaptation> mappings = new ArrayList<Adaptation>();
	private String message;
	private boolean messageIsError;
	private boolean done;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<S> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		if (pContext.isInitialDisplay())
		{
			this.mappings = DeleteMappingUserService.selected(pContext.getEntitySelection());
		}
		else
		{
			// re-read the selection: rows may be gone
			final List<Adaptation> fresh = new ArrayList<Adaptation>();
			for (final Adaptation m : this.mappings)
			{
				final AdaptationTable table = m.getContainerTable();
				final Adaptation again = table.lookupAdaptationByPrimaryKey(m.getOccurrencePrimaryKey());
				if (again != null)
				{
					fresh.add(again);
				}
			}
			this.mappings = fresh;
		}
	}

	private static List<Adaptation> selected(final EntitySelection pSelection)
	{
		final Set<String> seen = new LinkedHashSet<String>();
		final List<Adaptation> out = new ArrayList<Adaptation>();
		if (pSelection instanceof RecordEntitySelection && ((RecordEntitySelection) pSelection).getRecord() != null)
		{
			out.add(((RecordEntitySelection) pSelection).getRecord());
		}
		else if (pSelection instanceof TableViewEntitySelection && ((TableViewEntitySelection) pSelection).getSelectedRecords() != null)
		{
			final RequestResult rows = ((TableViewEntitySelection) pSelection).getSelectedRecords().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (seen.add(row.getOccurrencePrimaryKey().format()))
					{
						out.add(row);
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		return out;
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<S> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(DeleteMappingUserService.label(locale, "deleteMapping.title", Integer.valueOf(this.mappings.size())));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		if (this.done || this.mappings.isEmpty())
		{
			pConfigurator.setRightButtons(close);
		}
		else
		{
			final UIButtonSpecSubmit confirm = pConfigurator.newSubmitButton(DeleteMappingUserService.label(locale, "deleteMapping.confirm", Integer.valueOf(this.mappings.size())),
				new UserServiceEvent()
				{
					@Override
					public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
					{
						DeleteMappingUserService.this.onDelete(pEventContext);
						return null;
					}
				});
			confirm.setId("ftdmConfirm");
			pConfigurator.setLeftButtons(confirm);
			pConfigurator.setRightButtons(close);
		}
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				DeleteMappingUserService.this.writeContent(pWriter, locale);
			}
		});
	}

	private void writeContent(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		pWriter.add(DeleteMappingUserService.STYLE);
		if (this.message != null)
		{
			pWriter.add("<div class=\"ftdmMsg").add(this.messageIsError ? " ftdmErr" : "").add("\">").add(DeleteMappingUserService.escape(this.message)).add("</div>");
		}
		if (this.done)
		{
			return;
		}
		if (this.mappings.isEmpty())
		{
			pWriter.add("<div class=\"ftdmHint\">").add(DeleteMappingUserService.escape(DeleteMappingUserService.label(pLocale, "deleteMapping.none"))).add("</div>");
			return;
		}
		pWriter.add("<div class=\"ftdmIntro\">").add(DeleteMappingUserService.escape(DeleteMappingUserService.label(pLocale, "deleteMapping.intro"))).add("</div>");
		pWriter.add("<table class=\"ftdmTable\"><thead><tr>");
		for (final String key : new String[] { "deleteMapping.col.mapping", "deleteMapping.col.rules", "deleteMapping.col.inputs", "deleteMapping.col.children",
				"deleteMapping.col.outbound", "deleteMapping.col.provenance", "deleteMapping.col.conflicts" })
		{
			pWriter.add("<th>").add(DeleteMappingUserService.escape(DeleteMappingUserService.label(pLocale, key))).add("</th>");
		}
		pWriter.add("</tr></thead><tbody>");
		for (final Adaptation mapping : this.mappings)
		{
			final int[] counts = ConfigTransfer.attachments(mapping.getContainer(), mapping.getOccurrencePrimaryKey().format());
			pWriter.add("<tr><td>").add(DeleteMappingUserService.escape(mapping.getOccurrencePrimaryKey().format() + " - " + mapping.getString(ConnectorsPaths.Mapping.LABEL))).add("</td>");
			for (final int count : counts)
			{
				pWriter.add("<td class=\"ftdmNum\">").add(String.valueOf(count)).add("</td>");
			}
			pWriter.add("</tr>");
		}
		pWriter.add("</tbody></table>");
		pWriter.add("<div class=\"ftdmHint\">").add(DeleteMappingUserService.escape(DeleteMappingUserService.label(pLocale, "deleteMapping.hint"))).add("</div>");
	}

	private void onDelete(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		int deleted = 0;
		final List<String> failures = new ArrayList<String>();
		for (final Adaptation mapping : new ArrayList<Adaptation>(this.mappings))
		{
			final com.fasterxml.jackson.databind.node.ObjectNode report = ConfigTransfer.deleteMapping(pContext.getSession(), pContext.getRepository(),
				mapping.getOccurrencePrimaryKey().format());
			if (report.has("error"))
			{
				failures.add(mapping.getString(ConnectorsPaths.Mapping.LABEL) + ": " + report.get("error").asText());
			}
			else
			{
				deleted += report.get("deletedRows").asInt();
			}
		}
		this.done = failures.isEmpty();
		this.messageIsError = !failures.isEmpty();
		this.message = failures.isEmpty() ? DeleteMappingUserService.label(locale, "deleteMapping.done", Integer.valueOf(this.mappings.size()), Integer.valueOf(deleted))
				: DeleteMappingUserService.label(locale, "deleteMapping.failed", failures.toString());
		if (this.done)
		{
			this.mappings = new ArrayList<Adaptation>();
		}
	}

	@Override
	public void validate(final UserServiceValidateContext<S> pContext)
	{
		// nothing to validate
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<S> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}

	static String label(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(DeleteMappingUserService.class, pLocale, pKey, pArgs);
	}

	static String escape(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
