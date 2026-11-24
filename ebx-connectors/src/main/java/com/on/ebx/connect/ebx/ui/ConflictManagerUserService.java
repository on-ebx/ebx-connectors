package com.on.ebx.connect.ebx.ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConflictResolver;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.interactions.InteractionHelper;
import com.orchestranetworks.interactions.SessionInteraction;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.schema.dynamic.BeanDefinition;
import com.orchestranetworks.schema.dynamic.BeanElement;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.DatasetEntitySelection;
import com.orchestranetworks.ui.selection.DataspaceEntitySelection;
import com.orchestranetworks.ui.selection.EntitySelection;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.ui.selection.TableEntitySelection;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.ObjectKey;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceNext;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * "Manage connector conflicts": the steward screen for the proposals refused by the TRUSTED policy.
 * One service class for three entry points (dataspace, table view, record) and for the workflow
 * user task: the selection and the parameters only narrow the list. Each open conflict gets a
 * decision (take the proposed value, keep the current one, or decide later); "Apply" executes the
 * decisions, the bulk buttons decide everything at once. In a workflow, "Complete the task" closes
 * the work item and returns the counts to the data context.
 */
public final class ConflictManagerUserService<S extends EntitySelection> implements UserService<S>
{
	static final ObjectKey OPTIONS = ObjectKey.forName("ftConflictOptions");
	private static final Path P_COMMENT = Path.parse("./comment");
	static final String PARAM_DATASET = "dataset";
	static final String PARAM_TABLE = "table";
	static final String PARAM_RECORD_KEY = "recordKey";
	static final String OUT_RESOLVED = "resolvedCount";
	static final String OUT_REMAINING = "remainingCount";
	private static final String STYLE = "<style>"
			+ ".ftcfMsg{margin:8px 0;padding:8px 12px;border-radius:4px;background:#eef4fb;border:1px solid #b9d0ea}"
			+ ".ftcfErr{background:#fdecec;border-color:#e8a0a0}"
			+ ".ftcfHint{color:#666;margin:8px 0}"
			+ ".ftcfScope{margin:6px 0 10px;color:#444}"
			+ ".ftcfTable{border-collapse:collapse;width:100%;font-size:12px}"
			+ ".ftcfTable th,.ftcfTable td{border:1px solid var(--ebx-table-border,#ddd);padding:5px 8px;text-align:left;vertical-align:top}"
			+ ".ftcfTable th{background:var(--ebx-table-header,#f3f3f3);white-space:nowrap}"
			+ ".ftcfTable tr:nth-child(odd) td{background:var(--ebx-table-odd,transparent)}.ftcfTable tr:nth-child(even) td{background:var(--ebx-table-even,transparent)}"
			+ ".ftcfVal{font-family:monospace;white-space:pre-wrap;word-break:break-word}"
			+ ".ftcfField{font-family:monospace;white-space:nowrap}"
			+ ".ftcfBy{color:#666;font-size:11px;white-space:nowrap}"
			+ ".ftcfTrust{display:inline-block;min-width:26px;padding:0 5px;border-radius:9px;background:#e6e6e6;color:#333;font-weight:bold;text-align:center}"
			+ ".ftcfTrustHi{background:#d6f0dc;color:#1a7f37}.ftcfTrustLo{background:#fbe3d6;color:#a04a10}"
			+ ".ftcfDec label{display:block;white-space:nowrap;margin:1px 0}"
			+ ".ftcfKeep{color:#1a7f37}.ftcfTake{color:#0b5394}"
			+ ".ftcfTools{margin:6px 0;font-size:12px}.ftcfTools a{margin-right:12px;cursor:pointer;color:var(--ftc-accent-ink,#0b5394)}"
			+ ".ftcfLog{margin:8px 0;padding-left:16px;font-size:12px}.ftcfLogErr{color:#b00}"
			+ "</style>";

	private String dataspace;
	private String dataset;
	private String table;
	private String recordKey;
	private String scopeLabel;
	/** Conflict ids to manage when the service is launched from the Conflict table itself (null = no restriction). */
	private java.util.Set<String> conflictIds;
	private boolean inWorkflow;
	/** The conflicts on screen: one page of the open ones (audit C-39), newest first. */
	private List<Adaptation> conflicts = new ArrayList<Adaptation>();
	/** How many conflicts are open in the scope in all, the ones not on this page included. */
	private int openTotal;
	private final List<String> log = new ArrayList<String>();
	private String message;
	private boolean messageIsError;
	private int resolved;
	private String lastComment = "";

	// ------------------------------------------------------------------ scope

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<S> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		final Session session = pContext.getSession();
		final Locale locale = session.getLocale();
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), session);
		if (pContext.isInitialDisplay())
		{
			this.resolveScope(pContext.getEntitySelection(), pContext, session);
		}
		this.readConflicts(session, this.resolver(pContext));

		final BeanDefinition definition = pBuilder.createBeanDefinition();
		final BeanElement comment = definition.createElement(ConflictManagerUserService.P_COMMENT, SchemaTypeName.XS_STRING);
		comment.setLabel(ConflictManagerUserService.label(locale, "conflicts.comment"));
		comment.setDescription(ConflictManagerUserService.label(locale, "conflicts.comment.help"));
		comment.setDefaultValue(this.lastComment);
		comment.addFacetMaxLength(500);
		pBuilder.registerBean(ConflictManagerUserService.OPTIONS, definition);
	}

	private ConflictResolver resolver(final com.orchestranetworks.userservice.UserServiceRequest pContext)
	{
		return new ConflictResolver(ConnectorService.get().repository(pContext.getRepository()));
	}

	private void resolveScope(final EntitySelection pSelection, final com.orchestranetworks.userservice.UserServiceRequest pRequest, final Session pSession)
	{
		final Locale locale = pSession.getLocale();
		if (pSelection instanceof DataspaceEntitySelection)
		{
			this.dataspace = ((DataspaceEntitySelection) pSelection).getDataspace().getKey().getName();
			this.scopeLabel = ((DataspaceEntitySelection) pSelection).getDataspace().getLabelOrName(locale);
		}
		if (pSelection instanceof DatasetEntitySelection && ((DatasetEntitySelection) pSelection).getDataset() != null)
		{
			final Adaptation ds = ((DatasetEntitySelection) pSelection).getDataset();
			this.dataset = ds.getAdaptationName().getStringName();
			this.scopeLabel = ds.getLabelOrName(locale);
		}
		final boolean onConflictTable = pSelection instanceof TableEntitySelection && ((TableEntitySelection) pSelection).getTable() != null
			&& ConnectorsPaths.CONFLICT.equals(((TableEntitySelection) pSelection).getTable().getTablePath());
		if (onConflictTable)
		{
			// launched from the Conflict table itself: the selection designates conflicts, not a target table
			this.dataspace = null;
			this.dataset = null;
			this.table = null;
			this.recordKey = null;
			this.conflictIds = ConflictManagerUserService.selectedConflictIds(pSelection);
			this.scopeLabel = ConflictManagerUserService.label(locale, this.conflictIds == null ? "conflicts.scope.all" : "conflicts.scope.selected");
		}
		else if (pSelection instanceof TableEntitySelection && ((TableEntitySelection) pSelection).getTable() != null)
		{
			this.table = ((TableEntitySelection) pSelection).getTable().getTablePath().format();
			this.scopeLabel = ((TableEntitySelection) pSelection).getTable().getTableNode().getLabel(locale);
		}
		if (!onConflictTable && pSelection instanceof RecordEntitySelection && ((RecordEntitySelection) pSelection).getRecord() != null)
		{
			final Adaptation record = ((RecordEntitySelection) pSelection).getRecord();
			this.recordKey = record.getOccurrencePrimaryKey().format();
			this.scopeLabel = record.getLabel(locale);
		}
		// web component / workflow parameters narrow further (dataset, table, recordKey)
		final String dataset = ConflictManagerUserService.param(pRequest, pSession, ConflictManagerUserService.PARAM_DATASET);
		final String table = ConflictManagerUserService.param(pRequest, pSession, ConflictManagerUserService.PARAM_TABLE);
		final String key = ConflictManagerUserService.param(pRequest, pSession, ConflictManagerUserService.PARAM_RECORD_KEY);
		if (dataset != null)
		{
			this.dataset = dataset;
			this.scopeLabel = dataset;
		}
		if (table != null)
		{
			this.table = table;
			this.scopeLabel = table;
		}
		if (key != null)
		{
			this.recordKey = key;
			this.scopeLabel = (table == null ? "" : table + " ") + key;
		}
		this.inWorkflow = pSession.isInWorkflowInteraction(false);
	}

	/** Ids of the conflicts selected in the Conflict table view (or the opened conflict record); null when nothing is selected. */
	private static java.util.Set<String> selectedConflictIds(final EntitySelection pSelection)
	{
		final java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
		if (pSelection instanceof RecordEntitySelection && ((RecordEntitySelection) pSelection).getRecord() != null)
		{
			ids.add(((RecordEntitySelection) pSelection).getRecord().getOccurrencePrimaryKey().format());
		}
		else if (pSelection instanceof TableViewEntitySelection && ((TableViewEntitySelection) pSelection).getSelectedRecords() != null)
		{
			final com.onwbp.adaptation.RequestResult rows = ((TableViewEntitySelection) pSelection).getSelectedRecords().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					ids.add(row.getOccurrencePrimaryKey().format());
				}
			}
			finally
			{
				rows.close();
			}
		}
		return ids.isEmpty() ? null : ids;
	}

	/**
	 * Reads the page of open conflicts of the current scope, restricted to the selected conflict ids when the service was launched
	 * from the Conflict table. One page only (audit C-39): a screen that rendered every open conflict, and read them all at each
	 * round trip, could not be used on a table with thousands. What is decided leaves the open ones, so the next page is the
	 * first one again after Apply.
	 */
	private void readConflicts(final Session pSession, final ConflictResolver pResolver)
	{
		final ConflictResolver.Page page = this.conflictIds == null
			? pResolver.page(pSession, ConflictResolver.OPEN, this.dataspace, this.dataset, this.table, this.recordKey, ConflictResolver.pageSize())
			: pResolver.pageOfIds(pSession, this.conflictIds, ConflictResolver.OPEN, this.dataspace, this.dataset, this.table, this.recordKey, ConflictResolver.pageSize());
		this.conflicts = page.rows;
		this.openTotal = page.total;
	}

	private static String param(final com.orchestranetworks.userservice.UserServiceRequest pRequest, final Session pSession, final String pName)
	{
		String value = pRequest.getParameter(pName);
		if (value == null || value.trim().isEmpty())
		{
			try
			{
				final SessionInteraction interaction = pSession.getInteraction(false);
				if (interaction != null)
				{
					final InteractionHelper.ParametersMap inputs = interaction.getInputParameters();
					value = inputs == null ? null : inputs.getVariableString(pName);
				}
			}
			catch (final RuntimeException ex)
			{
				value = null;
			}
			catch (final Exception ex)
			{
				value = null;
			}
		}
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	// ------------------------------------------------------------------ display

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<S> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(ConflictManagerUserService.label(locale, "conflicts.title", this.scopeLabel == null ? "" : this.scopeLabel));
		final UIButtonSpecSubmit apply = pConfigurator.newSubmitButton(ConflictManagerUserService.label(locale, "conflicts.apply"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				ConflictManagerUserService.this.onApply(pEventContext, null);
				return null;
			}
		});
		apply.setId("ftcfApply");
		final UIButtonSpecSubmit acceptAll = pConfigurator.newSubmitButton(ConflictManagerUserService.label(locale, "conflicts.acceptAll"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				ConflictManagerUserService.this.onApply(pEventContext, "take");
				return null;
			}
		});
		acceptAll.setId("ftcfAcceptAll");
		final UIButtonSpecSubmit rejectAll = pConfigurator.newSubmitButton(ConflictManagerUserService.label(locale, "conflicts.rejectAll"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				ConflictManagerUserService.this.onApply(pEventContext, "keep");
				return null;
			}
		});
		rejectAll.setId("ftcfRejectAll");
		if (this.inWorkflow)
		{
			final UIButtonSpecSubmit complete = pConfigurator.newSubmitButton(ConflictManagerUserService.label(locale, "conflicts.complete"), new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					return ConflictManagerUserService.this.onComplete(pEventContext);
				}
			});
			complete.setId("ftcfComplete");
			pConfigurator.setLeftButtons(apply, acceptAll, rejectAll);
			pConfigurator.setRightButtons(complete);
		}
		else
		{
			final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
			close.setAskBeforeLeavingModifiedForm(false);
			pConfigurator.setLeftButtons(apply, acceptAll, rejectAll);
			pConfigurator.setRightButtons(close);
		}
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				ConflictManagerUserService.this.writeContent(pWriter, locale);
			}
		});
	}

	private void writeContent(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		pWriter.add(ConflictManagerUserService.STYLE);
		if (this.message != null)
		{
			pWriter.add("<div class=\"ftcfMsg").add(this.messageIsError ? " ftcfErr" : "").add("\">").add(ConflictManagerUserService.escape(this.message)).add("</div>");
		}
		if (!this.log.isEmpty())
		{
			pWriter.add("<ul class=\"ftcfLog\">");
			for (final String line : this.log)
			{
				pWriter.add("<li class=\"").add(line.startsWith("!") ? "ftcfLogErr" : "").add("\">").add(ConflictManagerUserService.escape(line.startsWith("!") ? line.substring(1) : line))
						.add("</li>");
			}
			pWriter.add("</ul>");
		}
		pWriter.add("<div class=\"ftcfScope\">").add(ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.scope", this.scopeText(), Integer.valueOf(this.openTotal))))
				.add("</div>");
		if (this.openTotal > this.conflicts.size())
		{
			pWriter.add("<div class=\"ftcfHint\">").add(ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.page",
				Integer.valueOf(this.conflicts.size()), Integer.valueOf(this.openTotal)))).add("</div>");
		}
		if (this.conflicts.isEmpty())
		{
			pWriter.add("<div class=\"ftcfHint\">").add(ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.none"))).add("</div>");
			return;
		}
		pWriter.add("<div class=\"ftcfHint\">").add(ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.hint"))).add("</div>");
		pWriter.add("<div class=\"ftcfTools\">"
				+ "<a onclick=\"ftcfAll('take')\">" + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.tools.take")) + "</a>"
				+ "<a onclick=\"ftcfAll('keep')\">" + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.tools.keep")) + "</a>"
				+ "<a onclick=\"ftcfAll('later')\">" + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.tools.later")) + "</a></div>");
		pWriter.setCurrentObject(ConflictManagerUserService.OPTIONS);
		pWriter.startTableFormRow();
		pWriter.addFormRow(ConflictManagerUserService.P_COMMENT);
		pWriter.endTableFormRow();
		pWriter.add("<table class=\"ftcfTable\"><thead><tr>");
		for (final String column : new String[] { "record", "field", "current", "proposed", "detected", "decision" })
		{
			pWriter.add("<th>").add(ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.column." + column))).add("</th>");
		}
		pWriter.add("</tr></thead><tbody>");
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		for (final Adaptation c : this.conflicts)
		{
			final String id = c.getOccurrencePrimaryKey().format();
			final Integer currentTrust = (Integer) c.get(ConnectorsPaths.Conflict.CURRENT_TRUST);
			final Integer proposedTrust = (Integer) c.get(ConnectorsPaths.Conflict.PROPOSED_TRUST);
			final String currentBy = c.getString(ConnectorsPaths.Conflict.CURRENT_CONNECTOR);
			final Date detected = (Date) c.get(ConnectorsPaths.Conflict.DETECTED_AT);
			pWriter.add("<tr>");
			pWriter.add("<td>").add(ConflictManagerUserService.escape(c.getString(ConnectorsPaths.Conflict.RECORD_LABEL))).add("<div class=\"ftcfBy\">")
					.add(ConflictManagerUserService.escape(c.getString(ConnectorsPaths.Conflict.TABLE_PATH) + " / " + c.getString(ConnectorsPaths.Conflict.RECORD_KEY))).add("</div></td>");
			pWriter.add("<td class=\"ftcfField\">").add(ConflictManagerUserService.escape(ConflictManagerUserService.shortPath(c.getString(ConnectorsPaths.Conflict.FIELD_PATH)))).add("</td>");
			pWriter.add("<td><div class=\"ftcfVal\">").add(ConflictManagerUserService.escape(c.getString(ConnectorsPaths.Conflict.CURRENT_VALUE))).add("</div><div class=\"ftcfBy\">")
					.add(ConflictManagerUserService.escape(currentBy == null ? ConflictManagerUserService.label(pLocale, "conflicts.manual") : currentBy)).add(" ")
					.add(ConflictManagerUserService.trust(currentTrust == null ? 100 : currentTrust.intValue(), proposedTrust == null ? 0 : proposedTrust.intValue())).add("</div></td>");
			pWriter.add("<td><div class=\"ftcfVal\">").add(ConflictManagerUserService.escape(c.getString(ConnectorsPaths.Conflict.PROPOSED_VALUE))).add("</div><div class=\"ftcfBy\">")
					.add(ConflictManagerUserService.escape(c.getString(ConnectorsPaths.Conflict.PROPOSED_CONNECTOR))).add(" ")
					.add(ConflictManagerUserService.trust(proposedTrust == null ? 0 : proposedTrust.intValue(), currentTrust == null ? 100 : currentTrust.intValue())).add("</div></td>");
			pWriter.add("<td class=\"ftcfBy\">").add(detected == null ? "" : format.format(detected)).add("</td>");
			pWriter.add("<td class=\"ftcfDec\">"
					+ "<label class=\"ftcfTake\"><input type=\"radio\" name=\"ftcfDecision_" + id + "\" value=\"take\"/> " + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.take")) + "</label>"
					+ "<label class=\"ftcfKeep\"><input type=\"radio\" name=\"ftcfDecision_" + id + "\" value=\"keep\"/> " + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.keep")) + "</label>"
					+ "<label><input type=\"radio\" name=\"ftcfDecision_" + id + "\" value=\"later\" checked=\"checked\"/> " + ConflictManagerUserService.escape(ConflictManagerUserService.label(pLocale, "conflicts.later")) + "</label>"
					+ "</td>");
			pWriter.add("</tr>");
		}
		pWriter.add("</tbody></table>");
		pWriter.addJS_cr("function ftcfAll(v){var r=document.querySelectorAll('input[type=radio][name^=ftcfDecision_]');for(var i=0;i<r.length;i++){r[i].checked=(r[i].value===v);}}");
	}

	private String scopeText()
	{
		final StringBuilder b = new StringBuilder();
		if (this.dataspace != null)
		{
			b.append(this.dataspace);
		}
		if (this.dataset != null)
		{
			b.append(b.length() > 0 ? " / " : "").append(this.dataset);
		}
		if (this.table != null)
		{
			b.append(b.length() > 0 ? " / " : "").append(this.table);
		}
		if (this.recordKey != null)
		{
			b.append(b.length() > 0 ? " / " : "").append(this.recordKey);
		}
		if (this.conflictIds != null)
		{
			b.append(b.length() > 0 ? " / " : "").append("#").append(this.conflictIds.size() == 1 ? this.conflictIds.iterator().next() : String.valueOf(this.conflictIds.size()));
		}
		return b.length() == 0 ? "*" : b.toString();
	}

	private static String trust(final int pTrust, final int pOther)
	{
		final String cls = pTrust > pOther ? " ftcfTrustHi" : pTrust < pOther ? " ftcfTrustLo" : "";
		return "<span class=\"ftcfTrust" + cls + "\">" + pTrust + "</span>";
	}

	private static String shortPath(final String pPath)
	{
		if (com.on.ebx.connect.core.DamaProposals.isAssetPath(pPath)) return com.on.ebx.connect.core.DamaProposals.display(pPath);
		return pPath != null && pPath.startsWith("./") ? pPath.substring(2) : pPath;
	}

	// ------------------------------------------------------------------ events

	private void onApply(final UserServiceEventContext pContext, final String pForceDecision)
	{
		final Locale locale = pContext.getSession().getLocale();
		this.log.clear();
		this.message = null;
		this.messageIsError = false;
		final ValueContext values = pContext.getValueContext(ConflictManagerUserService.OPTIONS);
		final Object comment = values == null ? null : values.getValue(ConflictManagerUserService.P_COMMENT);
		this.lastComment = comment == null ? "" : String.valueOf(comment);
		final ConflictResolver resolver = this.resolver(pContext);
		int accepted = 0;
		int rejected = 0;
		int failed = 0;
		for (final Adaptation conflict : new ArrayList<Adaptation>(this.conflicts))
		{
			final String id = conflict.getOccurrencePrimaryKey().format();
			final String decision = pForceDecision != null ? pForceDecision : pContext.getParameter("ftcfDecision_" + id);
			if (decision == null || "later".equals(decision))
			{
				continue;
			}
			final ConflictResolver.Outcome outcome = "take".equals(decision) ? resolver.accept(pContext.getSession(), conflict, this.lastComment)
					: resolver.reject(pContext.getSession(), conflict, this.lastComment);
			if (outcome.ok)
			{
				if ("take".equals(decision))
				{
					accepted++;
				}
				else
				{
					rejected++;
				}
				this.log.add(conflict.getString(ConnectorsPaths.Conflict.RECORD_LABEL) + " - " + outcome.message);
			}
			else
			{
				failed++;
				this.log.add("!" + conflict.getString(ConnectorsPaths.Conflict.RECORD_LABEL) + " - " + outcome.message);
			}
		}
		this.resolved += accepted + rejected;
		this.readConflicts(pContext.getSession(), resolver);
		this.message = ConflictManagerUserService.label(locale, "conflicts.applied", Integer.valueOf(accepted), Integer.valueOf(rejected), Integer.valueOf(failed),
			Integer.valueOf(this.openTotal));
		this.messageIsError = failed > 0;
	}

	private UserServiceEventOutcome onComplete(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		try
		{
			final SessionInteraction interaction = pContext.getSession().getInteraction(true);
			final InteractionHelper.ParametersMap outputs = new InteractionHelper.ParametersMap();
			outputs.setVariableString(ConflictManagerUserService.OUT_RESOLVED, String.valueOf(this.resolved));
			outputs.setVariableString(ConflictManagerUserService.OUT_REMAINING, String.valueOf(this.openTotal));
			interaction.complete(outputs);
			return UserServiceNext.nextClose();
		}
		catch (final Exception ex)
		{
			this.message = ConflictManagerUserService.label(locale, "conflicts.completeFailed", com.on.ebx.connect.core.Redaction.failure(ex));
			this.messageIsError = true;
			return null;
		}
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<S> pContext, final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<S> pContext)
	{
		// nothing: the decisions are plain radios
	}

	static String label(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(ConflictManagerUserService.class, pLocale, pKey, pArgs);
	}

	static String escape(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
