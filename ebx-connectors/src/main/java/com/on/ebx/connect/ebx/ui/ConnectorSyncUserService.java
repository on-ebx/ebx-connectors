package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.core.SyncResult;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.Request;
import com.onwbp.base.text.Nomenclature;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.schema.dynamic.BeanDefinition;
import com.orchestranetworks.schema.dynamic.BeanElement;
import com.orchestranetworks.ui.UIButtonSpecJSAction;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.ObjectKey;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceAjaxContext;
import com.orchestranetworks.userservice.UserServiceAjaxRequest;
import com.orchestranetworks.userservice.UserServiceAjaxResponse;
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
 * Runs a mapping on the selected (or displayed) records of a table and shows what changed.
 * The bean is rebuilt on every request (see the profiling service for the same conventions).
 * Same presentation as the read-only record forms ({@link ReadOnlyForm}): sections, summary tiles and badges.
 */
public final class ConnectorSyncUserService implements UserService<TableViewEntitySelection>
{
	static final ObjectKey OPTIONS = ObjectKey.forName("ftConnectorSyncOptions");
	private static final Path P_MAPPING = Path.parse("./mapping");
	private static final Path P_SCOPE = Path.parse("./scope");
	private static final Path P_MAX = Path.parse("./maxRecords");
	private static final Path P_DRY_RUN = Path.parse("./dryRun");
	/**
	 * Ceiling of the maximum field. High enough for a whole instance in one run — a profile returns one
	 * item per column, 2590 of them on the FastTrack instance (2026-09-16) — while still guarding against
	 * an unbounded feed launched by mistake.
	 */
	private static final int ABSOLUTE_MAX = 5000;
	/** The highest value the form accepts: EBX treats the max facet as exclusive, and the ceiling itself was refused. */
	private static final int FORM_MAX = ConnectorSyncUserService.ABSOLUTE_MAX - 1;
	/**
	 * What a feed starts at, well under the ceiling: enough for a catalog scan of a whole instance, and
	 * short enough that a Kafka consume opened from this screen does not swallow thousands of messages.
	 * A run cut here says so and names the ceiling to raise it to.
	 */
	private static final int FEED_DEFAULT_MAX = com.on.ebx.connect.core.RunLimits.SCREEN_FEED;
	/**
	 * The run mode travels in a plain hidden input, not in the bean: the two mode cards replace the old
	 * "Simulate only" box, which users did not notice was ticked (2026-09-16). Read with getParameter, as the designer's rules.
	 */
	static final String MODE_PARAM = "ftcsMode";
	static final String MODE_SIMULATE = "simulate";
	static final String MODE_APPLY = "apply";
	/** Set by the confirmation panel to the token of what was confirmed; a real run on a whole table needs it. */
	static final String CONFIRM_PARAM = "ftcsConfirm";
	private static final String ICON_SIMULATE = "<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
			+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/></svg>";
	private static final String ICON_APPLY = "<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
			+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M7 4v16l13-8z\"/></svg>";
	private static final String STYLE = "<style>"
			// the run mode: two cards, blue for a simulation, amber for a run that writes
			+ ".ftcsModes{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px;margin:8px 0 4px;max-width:1100px}"
			+ ".ftcsMode{display:flex;gap:12px;align-items:flex-start;text-align:left;font:inherit;cursor:pointer;padding:12px 14px;border-radius:8px;"
			+ "border:1px solid #dadce0;background:#fff;color:#3c4043}"
			+ ".ftcsMode:hover{border-color:#9aa0a6}.ftcsMode:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}"
			+ ".ftcsModeIcon{flex:0 0 auto;margin-top:1px;color:#5f6368}"
			+ ".ftcsModeTitle{display:block;font-weight:600;font-size:14px;color:#202124;margin-bottom:2px}.ftcsModeText{display:block;font-size:12px}"
			+ ".ftcsModeSim.ftcsModeOn{border:2px solid var(--ebx-main,#1a73e8);padding:11px 13px;background:var(--ftc-accent-soft,#e8f0fe);color:var(--ftc-accent-ink,#174ea6)}"
			+ ".ftcsModeSim.ftcsModeOn .ftcsModeIcon,.ftcsModeSim.ftcsModeOn .ftcsModeTitle{color:var(--ftc-accent-ink,#174ea6)}"
			+ ".ftcsModeApply.ftcsModeOn{border:2px solid #b06000;padding:11px 13px;background:#fef7e0;color:#7a4b00}"
			+ ".ftcsModeApply.ftcsModeOn .ftcsModeIcon,.ftcsModeApply.ftcsModeOn .ftcsModeTitle{color:#7a4b00}"
			+ ".ftcsModeHint{margin:6px 0 10px;font-size:12px;color:#5f6368;max-width:1100px}"
			+ "#ftcsRun.ftcsRunApply{background:#b06000!important;border-color:#b06000!important;color:#fff!important}"
			// after a simulation: what would change, and the way to apply it
			+ ".ftcsBanner{display:flex;flex-wrap:wrap;align-items:center;gap:10px 16px;margin:8px 0;padding:10px 14px;border-radius:8px;max-width:1100px;"
			+ "border:1px solid #c6dafc;background:#e8f0fe;color:#174ea6;font-size:13px}"
			+ ".ftcsBannerText{flex:1 1 320px}"
			+ ".ftcsBtn{font:inherit;font-size:13px;font-weight:600;cursor:pointer;padding:6px 14px;border-radius:6px;border:1px solid #dadce0;background:#fff;color:#3c4043}"
			+ ".ftcsBtn:hover{background:#f8f9fa}.ftcsBtn:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}"
			+ ".ftcsBtnApply{background:#b06000;border-color:#b06000;color:#fff}.ftcsBtnApply:hover{background:#8f4e00}"
			// the confirmation of a real run on a whole table
			+ ".ftcsConfirm{margin:10px 0;padding:14px 16px;border-radius:8px;border:2px solid #b06000;background:#fef7e0;color:#3c4043;max-width:1100px}"
			+ ".ftcsConfirmTitle{font-weight:600;font-size:14px;color:#7a4b00;margin-bottom:4px}.ftcsConfirm p{margin:4px 0 10px}"
			+ ".ftcsConfirmActions{display:flex;flex-wrap:wrap;gap:8px}"
			// the details: status filters, and the row that loads the next page
			+ ".ftcsFilters{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 4px}"
			+ ".ftcsFilter{font:inherit;font-size:12px;cursor:pointer;padding:4px 12px;border-radius:14px;border:1px solid #dadce0;background:#fff;color:#3c4043}"
			+ ".ftcsFilter:hover{background:#f8f9fa}.ftcsFilter:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}"
			+ ".ftcsFilterCount{font-weight:600;font-variant-numeric:tabular-nums}"
			+ ".ftcsFilterOn{border-color:var(--ebx-main,#1a73e8);background:var(--ftc-accent-soft,#e8f0fe);color:var(--ftc-accent-ink,#174ea6)}.ftcsFilterErr .ftcsFilterCount{color:#c5221f}"
			+ ".ftcsMore{display:flex;align-items:center;gap:12px;margin:0 0 12px;font-size:12px}.ftcsMore .ftcsBtn[hidden]{display:none}"
			+ ".ftcsProgressTime{color:#5f6368;font-size:12px;font-variant-numeric:tabular-nums}"
			+ ".ftcsNotice{margin:8px 0;padding:10px 14px;border-radius:8px;border:1px solid #c6dafc;background:#e8f0fe;color:#174ea6;font-size:12px;max-width:1100px}"
			+ ".ftcsWarn{border-color:#f6d68a;background:#fef7e0;color:#7a4b00}"
			+ ".ftcsErr{border-color:#f5c2bd;background:#fce8e6;color:#a50e0e}"
			+ ".ftcsTable{border-collapse:separate;border-spacing:0;width:100%;font-size:12px;border:1px solid #dadce0;border-radius:8px;overflow:hidden;background:#fff;margin:6px 0 10px}"
			+ ".ftcsTable th{background:var(--ebx-table-header,#f8f9fa);color:#5f6368;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.04em;text-align:left;padding:8px 10px;border-bottom:1px solid var(--ebx-table-border,#dadce0)}"
			+ ".ftcsTable td{padding:8px 10px;border-bottom:1px solid var(--ebx-table-border,#f1f3f4);vertical-align:top;color:#202124}"
			+ ".ftcsTable tr:nth-child(odd) td{background:var(--ebx-table-odd,transparent)}.ftcsTable tr:nth-child(even) td{background:var(--ebx-table-even,transparent)}"
			+ ".ftcsTable tr:last-child td{border-bottom:0}.ftcsTable tr:hover td{background:#f8f9fa}"
			+ ".ftcsItem{font-weight:600;overflow-wrap:anywhere}.ftcsMs{color:#5f6368;white-space:nowrap;text-align:right;font-variant-numeric:tabular-nums}"
			+ ".ftcsTable .ftroBadge{font-size:12px;white-space:nowrap}"
			+ ".ftcsChanges{margin:0;padding-left:16px;color:#3c4043}.ftcsChangeErr{color:#c5221f}.ftcsMuted{color:#5f6368}"
			// the waiting strip of the material federated search: indeterminate bar + what is being done
			+ ".ftcsProgress{display:none;margin:12px 0;align-items:center;gap:10px}.ftcsProgressOn{display:flex}"
			+ ".ftcsProgressTrack{position:relative;flex:0 0 220px;height:4px;border-radius:2px;background:#dfe3e7;overflow:hidden}"
			+ ".ftcsProgressBar{position:absolute;top:0;left:0;height:100%;width:40%;border-radius:2px;background:var(--ebx-main,#1a73e8);animation:ftcsSlide 1.1s ease-in-out infinite}"
			+ "@keyframes ftcsSlide{0%{left:-40%}100%{left:100%}}"
			+ ".ftcsProgressLabel{color:var(--ftc-accent-ink,#174ea6);font-size:12px}"
			+ ".ftcsIntro{margin:8px 0 12px;padding:12px 14px;border-radius:8px;background:#fff;border:1px solid #dadce0;max-width:1100px}"
			+ ".ftcsIntroTitle{font-weight:600;font-size:14px;color:#202124;margin-bottom:4px}.ftcsIntro p{margin:4px 0 8px;color:#3c4043}"
			+ ".ftcsInputs{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;margin:0}.ftcsInputs dt{color:#5f6368}.ftcsInputs dd{margin:0;font-family:monospace;word-break:break-all}"
			+ "</style>";

	/** A feed run the panel follows, and what a continuation needs when the screen started it itself. */
	private static final class ActiveRun
	{
		final String runId;
		final MappingDefinition mapping;
		/** The run-time inputs and the maximum of a run this screen started; null inputs for a run it only attached to. */
		final java.util.Map<String, String> inputs;
		final int max;
		volatile boolean stopRequested;

		ActiveRun(final String pRunId, final MappingDefinition pMapping, final java.util.Map<String, String> pInputs, final int pMax)
		{
			this.runId = pRunId;
			this.mapping = pMapping;
			this.inputs = pInputs;
			this.max = pMax;
		}
	}

	private TableViewEntitySelection selection;
	private List<MappingDefinition> mappings;
	private String lastMapping;
	private String lastScope = "SELECTED";
	private int lastMax = com.on.ebx.connect.core.RunLimits.SCREEN_RECORDS;
	private boolean lastDryRun = true;
	/** Run-time inputs of a feed received with the service URL ({@code inputs=tables=SALES.ORDERS}); null when none. */
	private String requestedInputs;
	private boolean presetsApplied;
	private List<SyncResult> results;
	private String message;
	private boolean messageIsError;
	/**
	 * The feed run this screen follows in its live panel (audit C-19): the one it started in the background, or the one that holds the
	 * selected mapping. Read by the Ajax requests of the panel too, hence volatile.
	 */
	private volatile ActiveRun activeRun;
	/** The repository of the last display, to read the run the panel follows. */
	private com.orchestranetworks.instance.Repository repository;
	/** Whether the reader holds the RUN right of the module (Stop and Resume), re-read at every request. */
	private boolean mayRun;
	/** The failure of the last run or of the mapping list, told as cause + next action (audit C-38); null when there is none. */
	private ErrorTexts failure;
	/** A real run on the whole table waits for its confirmation: the panel shows, nothing ran. */
	private boolean pendingConfirm;
	private int pendingCount;
	/** The items of the last run, errors first, as the details page through them; null until the run is displayed. */
	private List<SyncResult> shown;
	private List<String> shownNames;
	private SyncResult limitNotice;
	private final java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
	/** Bumped for every run displayed: a details page asked by an older screen is refused. */
	private int generation;
	/** When the last run ended, shown in the result section. */
	java.util.Date lastRunAt;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder)
	{
		this.selection = pContext.getEntitySelection();
		this.repository = pContext.getRepository();
		this.mayRun = com.on.ebx.connect.ebx.ConnectorsAccess.has(pContext.getSession(), com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN);
		final Locale locale = pContext.getSession().getLocale();
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), pContext.getSession());
		this.mappings = this.loadMappings(pContext.getRepository(), locale);
		// A flow card may launch this table service with its mapping id. Preserve
		// that declared input for the initial form value. The executable path below
		// still resolves it only from this table's eligible mappings, so an invalid
		// URL parameter cannot trigger a different mapping or a write.
		// Web-component input parameters are immutable session inputs. Reading them
		// from the session is required after EBX normalizes the visible service URL
		// while it opens the native table-view popup.
		String requestedMapping = pContext.getSession().getInputParameterValue(false,
			ConnectorSyncServiceDeclaration.PARAM_MAPPING);
		if (requestedMapping == null || requestedMapping.trim().isEmpty())
		{
			requestedMapping = pContext.getParameter(ConnectorSyncServiceDeclaration.PARAM_MAPPING);
		}
		if (requestedMapping != null && !requestedMapping.trim().isEmpty())
		{
			this.lastMapping = requestedMapping.trim();
		}
		// Run-time inputs and the simulation preset come with the URL too (a catalog "Re-profile" link).
		// The inputs are shown before the run and only reach the inputs the mapping reads as ${param.name};
		// the preset only fills the box once, so the user's own choice survives the next requests.
		final String requestedInputs = ConnectorSyncUserService.inputParameter(pContext, ConnectorSyncServiceDeclaration.PARAM_INPUTS);
		if (requestedInputs != null)
		{
			this.requestedInputs = requestedInputs;
		}
		if (!this.presetsApplied)
		{
			this.presetsApplied = true;
			// A feed imports what its connector returns, and every item counts: one per column for a profile, one per
			// table for a catalog scan. At 100 a scan of 214 tables stopped halfway without a word (seen 2026-09-16).
			// the list shows the first mapping when the link named none: hold it, so that the Flow section describes what is selected
			if (this.lastMapping == null && !this.mappings.isEmpty())
			{
				this.lastMapping = String.valueOf(this.mappings.get(0).getId());
			}
			final MappingDefinition initial = this.selectedMapping();
			if (this.requestedInputs != null || initial != null && initial.isFeed())
			{
				this.lastMax = ConnectorSyncUserService.FEED_DEFAULT_MAX;
			}
			if ("false".equalsIgnoreCase(ConnectorSyncUserService.inputParameter(pContext, ConnectorSyncServiceDeclaration.PARAM_DRY_RUN)))
			{
				this.lastDryRun = false;
			}
		}
		final BeanDefinition definition = pBuilder.createBeanDefinition();

		final BeanElement mapping = definition.createElement(ConnectorSyncUserService.P_MAPPING, SchemaTypeName.XS_STRING);
		mapping.setLabel(ConnectorSyncUserService.label(locale, "option.mapping"));
		final Nomenclature<String> choices = new Nomenclature<String>();
		for (final MappingDefinition m : this.mappings)
		{
			choices.addItemValue(String.valueOf(m.getId()), m.getLabel() + " [" + m.getConnectorId() + " / "
					+ m.getOperation() + ", " + m.getDirection() + "]");
		}
		mapping.addFacetEnumeration(choices);
		mapping.setMinOccurs(1);
		if (this.lastMapping != null)
		{
			mapping.setDefaultValue(this.lastMapping);
		}
		else if (!this.mappings.isEmpty())
		{
			mapping.setDefaultValue(String.valueOf(this.mappings.get(0).getId()));
		}

		final BeanElement scope = definition.createElement(ConnectorSyncUserService.P_SCOPE, SchemaTypeName.XS_STRING);
		scope.setLabel(ConnectorSyncUserService.label(locale, "option.scope"));
		final Nomenclature<String> scopes = new Nomenclature<String>();
		scopes.addItemValue("SELECTED", ConnectorSyncUserService.label(locale, "scope.selected"));
		scopes.addItemValue("VIEW", ConnectorSyncUserService.label(locale, "scope.view"));
		scopes.addItemValue("ALL", ConnectorSyncUserService.label(locale, "scope.all"));
		scopes.addItemValue("UNSENT", ConnectorSyncUserService.label(locale, "scope.unsent"));
		scopes.addItemValue("CHANGED", ConnectorSyncUserService.label(locale, "scope.changed"));
		scopes.addItemValue("FAILED", ConnectorSyncUserService.label(locale, "scope.failed"));
		scopes.addItemValue("DATASPACE_DELTA", ConnectorSyncUserService.label(locale, "scope.delta"));
		scope.addFacetEnumeration(scopes);
		scope.setMinOccurs(1);
		scope.setDefaultValue(this.lastScope);

		final BeanElement max = definition.createElement(ConnectorSyncUserService.P_MAX, SchemaTypeName.XS_INT);
		max.setLabel(ConnectorSyncUserService.label(locale, "option.maxRecords"));
		max.setDefaultValue(Integer.valueOf(this.lastMax));
		max.addFacetMin(Integer.valueOf(1), true);
		// No upper bound on the form: a feed run for real starts in the background and has none; the other runs hold their
		// items in this request and are refused above FORM_MAX with a message that says so (audit C-19).
		max.setMinOccurs(1);

		final BeanElement dry = definition.createElement(ConnectorSyncUserService.P_DRY_RUN, SchemaTypeName.XS_BOOLEAN);
		dry.setLabel(ConnectorSyncUserService.label(locale, "option.dryRun"));
		dry.setDefaultValue(Boolean.valueOf(this.lastDryRun));
		dry.setMinOccurs(1);

		pBuilder.registerBean(ConnectorSyncUserService.OPTIONS, definition);
		this.attachToRunningFeed();
	}

	/**
	 * A feed that is already running (started from another screen, by MCP, REST or the scheduler on this node) shows its live panel as
	 * soon as it is the selected mapping, with the same Stop: the screen no longer answers "already running" and leaves the user blind.
	 */
	private void attachToRunningFeed()
	{
		final MappingDefinition selected = this.selectedMapping();
		if (selected == null || !selected.isFeed())
		{
			return;
		}
		final String holder = ConnectorService.runningRun(selected.getId());
		final ActiveRun current = this.activeRun;
		if (holder != null && (current == null || !holder.equals(current.runId)))
		{
			this.activeRun = new ActiveRun(holder, selected, null, 0);
		}
	}

	private List<MappingDefinition> loadMappings(final com.orchestranetworks.instance.Repository pRepository, final Locale pLocale)
	{
		try
		{
			final Adaptation dataset = this.selection.getDataset();
			final ConnectorRepository repo = ConnectorService.get().repository(pRepository);
			return repo.mappingsFor(dataset.getHome().getKey().getName(), dataset.getAdaptationName().getStringName(),
				this.selection.getTable().getTablePath().format());
		}
		catch (final RuntimeException ex)
		{
			ConnectorService.logFailure("synchronisation screen could not list the mappings", null, ex);
			this.failure = ErrorTexts.of(pLocale, ex, null);
			return new ArrayList<MappingDefinition>();
		}
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		final String tableLabel = this.selection.getTable().getTableNode().getLabel(locale);
		final MappingDefinition titled = this.selectedMapping();
		pConfigurator.setTitle(this.guided() ? ConnectorSyncUserService.label(locale, "service.titleGuided", titled.getLabel(), ConnectorSyncUserService.inputValues(this.requestedInputs))
			: ConnectorSyncUserService.label(locale, "service.title", tableLabel));
		// the button says what it does; the pane script relabels it when the mode changes
		final UIButtonSpecSubmit run = pConfigurator.newSubmitButton(ConnectorSyncUserService.label(locale, this.lastDryRun ? "button.simulate" : "button.apply"),
			new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					ConnectorSyncUserService.this.onRun(pEventContext);
					return null;
				}
			});
		run.setId("ftcsRun");
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		close.setId("ftcsClose");
		// Opened on a full ebx-ui page (a catalog "Re-profile" or flow "Run" link), EBX renders no Close button:
		// Back returns to the page the link was clicked on (kept in sessionStorage by the catalog), else one step back.
		// The pane script hides it wherever EBX does render its own Close.
		final UIButtonSpecJSAction back = new UIButtonSpecJSAction(com.onwbp.base.text.UserMessage.createInfo(ConnectorSyncUserService.label(locale, "button.back")),
			"(function(){var t=window.top,s=null,k=" + StorageScope.jsKey(StorageScope.userId(pContext.getSession()), "ftServiceReturn")
				+ ";try{s=JSON.parse(sessionStorage.getItem(k)||'null');}catch(e){}"
				+ "if(s&&s.url){try{var u=new URL(s.url,t.location.origin);if(u.origin===t.location.origin&&u.pathname.indexOf('/ebx-ui/')===0){"
				+ "sessionStorage.removeItem(k);t.location.href=u.href;return;}}catch(e){}}"
				+ "if(t.history.length>1){t.history.back();}else{t.location.href='/ebx-ui/';}})()");
		back.setId("ftcsBack");
		pConfigurator.setLeftButtons(run);
		pConfigurator.setRightButtons(back, close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				ConnectorSyncUserService.this.writeContent(pWriter, locale, StorageScope.userId(pPaneContext.getSession()));
			}
		});
	}

	private void onRun(final UserServiceEventContext pEventContext)
	{
		final Locale locale = pEventContext.getSession().getLocale();
		final ValueContext values = pEventContext.getValueContext(ConnectorSyncUserService.OPTIONS);
		final Object chosen = values.getValue(ConnectorSyncUserService.P_MAPPING);
		if (chosen != null)
		{
			this.lastMapping = String.valueOf(chosen);
		}
		final Object scope = values.getValue(ConnectorSyncUserService.P_SCOPE);
		if (scope != null)
		{
			this.lastScope = scope.toString();
		}
		final Object max = values.getValue(ConnectorSyncUserService.P_MAX);
		if (max instanceof Number)
		{
			this.lastMax = ((Number) max).intValue();
		}
		final String mode = pEventContext.getParameter(ConnectorSyncUserService.MODE_PARAM);
		if (ConnectorSyncUserService.MODE_APPLY.equals(mode))
		{
			this.lastDryRun = false;
		}
		else if (ConnectorSyncUserService.MODE_SIMULATE.equals(mode))
		{
			this.lastDryRun = true;
		}
		else
		{
			// no mode card in the page (should not happen): the bean value, a simulation by default
			final Object dry = values.getValue(ConnectorSyncUserService.P_DRY_RUN);
			this.lastDryRun = dry instanceof Boolean ? ((Boolean) dry).booleanValue() : true;
		}
		this.message = null;
		this.messageIsError = false;
		this.failure = null;
		this.results = null;
		this.lastRunAt = null;
		this.pendingConfirm = false;
		this.shown = null;
		final MappingDefinition mapping = this.selectedMapping();
		if (mapping == null)
		{
			this.message = ConnectorSyncUserService.label(locale, "message.noMapping");
			this.messageIsError = true;
			return;
		}
		try
		{
			final boolean background = mapping.isFeed() && !this.lastDryRun;
			if (!background && this.lastMax > ConnectorSyncUserService.FORM_MAX)
			{
				// this request holds the items it handles: only a feed run for real, which starts in the background, has no ceiling
				this.message = ConnectorSyncUserService.label(locale, "message.maxSync", Integer.valueOf(ConnectorSyncUserService.FORM_MAX));
				this.messageIsError = true;
				return;
			}
			if (background)
			{
				this.startInBackground(pEventContext, mapping, locale);
				return;
			}
			if (mapping.isFeed())
			{
				final List<String> ignored = new ArrayList<String>();
				final java.util.Map<String, String> inputs = ConnectorService.runtimeInputs(mapping, this.requestedInputs, ignored);
				// An explicit run, as on POST /sync: its lineage leaves in ONE OpenLineage event when it ends.
				// Without it every imported item sent its own event, and the catalog imported each one separately
				// (seen 2026-09-14: 58 events, 30 s for a 58-column profile against 4.6 s over REST).
				ConnectorService.beginRun();
				try
				{
					// Volume lot 7a: importFrom now reports counters + a bounded results sample
					// (com.on.ebx.connect.core.ImportOutcome) instead of one SyncResult per row held
					// for the whole run - a multi-million-row feed no longer means a multi-million-
					// entry list for this form to hold and render. this.results shows the sample;
					// isResultsOmitted() says when it is not the whole run.
					final com.on.ebx.connect.core.ImportOutcome outcome = ConnectorService.get().importFrom(pEventContext.getSession(),
						pEventContext.getRepository(), mapping, inputs, this.lastMax, this.lastDryRun, "userService");
					this.results = outcome.getResults();
					if (outcome.isResultsOmitted())
					{
						this.addMessage(ConnectorSyncUserService.label(locale, "message.resultsOmitted", String.valueOf(outcome.getResults().size()),
							String.valueOf(outcome.getCreated() + outcome.getUpdated() + outcome.getNoChange() + outcome.getErrorCount() + outcome.getRejected())));
					}
					// Rows about the run that could not be saved: rare, and never shown anywhere else to this user.
					this.addMessage(outcome.getBookkeepingNotice());
				}
				finally
				{
					ConnectorService.endRun();
				}
				// next to the omitted-results message, never in its place (audit C-35)
				this.addIgnoredInputs(locale, ignored);
				return;
			}
			// a run on records or on a scope reads no run-time input: every one the link gave is ignored, and said
			this.addIgnoredInputs(locale, ConnectorService.inputNames(this.requestedInputs));
			if ("UNSENT".equals(this.lastScope) || "CHANGED".equals(this.lastScope) || "FAILED".equals(this.lastScope) || "DATASPACE_DELTA".equals(this.lastScope))
			{
				final String runId = ConnectorService.beginRun();
				try
				{
					this.results = ConnectorService.get().sendScope(pEventContext.getSession(), pEventContext.getRepository(), mapping, this.selection.getTable(),
						null, com.on.ebx.connect.core.OutboundScope.parse(this.lastScope), this.lastMax, this.lastDryRun, "userService");
					this.addMessage(ConnectorSyncUserService.label(locale, "message.run", runId));
				}
				finally
				{
					ConnectorService.endRun();
				}
				return;
			}
			// A real run on the whole table is confirmed first, for this mapping. Checked here and not only in the
			// page, so that a script that fails to load cannot skip it.
			if (!this.lastDryRun && "ALL".equals(this.lastScope)
				&& !ConnectorSyncUserService.confirmToken(mapping).equals(pEventContext.getParameter(ConnectorSyncUserService.CONFIRM_PARAM)))
			{
				this.pendingConfirm = true;
				this.pendingCount = this.countAll();
				return;
			}
			Request request = "ALL".equals(this.lastScope) ? this.selection.getAllRecords()
					: "VIEW".equals(this.lastScope) ? this.selection.getFilteredRecords() : this.selection.getSelectedRecords();
			if (request == null)
			{
				request = this.selection.getAllRecords();
			}
			this.results = ConnectorService.get().syncRecords(pEventContext.getSession(), request, mapping, this.lastMax,
				this.lastDryRun, "userService");
			if (this.results.isEmpty())
			{
				this.addMessage(ConnectorSyncUserService.label(locale, "message.noRecords"));
			}
		}
		catch (final Exception ex)
		{
			// what the user reads is the redacted message of the failure; the stack stays in the log
			ConnectorService.logFailure("synchronisation screen failed", mapping, ex);
			this.failure = ErrorTexts.of(locale, ex, mapping);
		}
	}

	/**
	 * A feed run for real starts on a background thread through the same path as {@code connectors_sync(async=true)} and
	 * {@code POST /sync?async=true}, so it has their admission limits (as many runs as the instance allows, one run per mapping) and
	 * their stop and resume: the screen returns at once with the live panel instead of holding the HTTP request for the whole feed.
	 */
	private void startInBackground(final UserServiceEventContext pEventContext, final MappingDefinition pMapping, final Locale pLocale)
	{
		final List<String> ignored = new ArrayList<String>();
		final java.util.Map<String, String> inputs = ConnectorService.runtimeInputs(pMapping, this.requestedInputs, ignored);
		try
		{
			final String runId = ConnectorService.get().importFromAsync(pEventContext.getSession(), pEventContext.getRepository(), pMapping, inputs, this.lastMax,
				"userService", 0);
			this.activeRun = new ActiveRun(runId, pMapping, inputs, this.lastMax);
			this.message = ConnectorSyncUserService.label(pLocale, "message.asyncStarted", runId);
		}
		catch (final com.on.ebx.connect.core.MappingAlreadyRunningException running)
		{
			// nothing was started: say so, and follow the run that holds the mapping
			this.failure = ErrorTexts.of(pLocale, running, pMapping);
			this.attachToRunningFeed();
		}
		catch (final com.on.ebx.connect.core.TooManyRunsException full)
		{
			this.failure = ErrorTexts.of(pLocale, full, pMapping);
		}
		this.addIgnoredInputs(pLocale, ignored);
	}

	/** Adds a sentence to the message of the run, after what is already there: one notice never hides another (audit C-35). */
	private void addMessage(final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return;
		}
		this.message = this.message == null || this.message.isEmpty() ? pText : this.message + " " + pText;
	}

	/** The warning for run-time inputs the run did not use; nothing when there is none. */
	private void addIgnoredInputs(final Locale pLocale, final List<String> pIgnored)
	{
		if (pIgnored != null && !pIgnored.isEmpty())
		{
			this.addMessage(ConnectorSyncUserService.label(pLocale, "message.inputsIgnored", String.join(", ", pIgnored)));
		}
	}

	/** A declared input parameter of the service: from the session (EBX keeps it there once the popup opens), else the request; null when empty. */
	private static String inputParameter(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final String pName)
	{
		String value = pContext.getSession().getInputParameterValue(false, pName);
		if (value == null || value.trim().isEmpty())
		{
			value = pContext.getParameter(pName);
		}
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	private void writeContent(final UserServicePaneWriter pWriter, final Locale pLocale, final String pUserId)
	{
		ReadOnlyForm.styles(pWriter);
		pWriter.add(ConnectorSyncUserService.STYLE);
		pWriter.add(ErrorTexts.STYLE);
		pWriter.setCurrentObject(ConnectorSyncUserService.OPTIONS);
		final boolean guided = this.guided();
		final MappingDefinition current = this.selectedMapping();
		this.prepareItems(guided);
		final List<SyncResult> items = this.shown;
		final SyncResult limit = this.limitNotice;
		// the outcome first, above the form: a run started from a link (a catalog "Re-profile") must not look like nothing happened
		if (this.results != null)
		{
			this.writeOutcome(pWriter, pLocale, items, limit);
		}
		else if (this.requestedInputs != null && !this.messageIsError)
		{
			ConnectorSyncUserService.notice(pWriter, "ftcsReady", ConnectorSyncUserService.label(pLocale, guided ? "hint.readyGuided" : "hint.ready"));
		}
		this.writeProgress(pWriter, pLocale, pUserId);
		this.writeRunPanel(pWriter, pLocale);
		if (this.mappings.isEmpty())
		{
			ConnectorSyncUserService.notice(pWriter, "ftcsWarn", ConnectorSyncUserService.label(pLocale, "hint.noMapping",
				this.selection.getTable().getTablePath().format()));
		}
		if (guided)
		{
			// Opened from a link that names the flow and its inputs (a catalog "Re-profile this table"): say what will run in
			// words, keep the one choice that matters (simulate or not) and fold the technical ones away.
			pWriter.add("<div class=\"ftcsIntro\"><div class=\"ftcsIntroTitle\">").add(ConnectorSyncUserService.escape(current.getLabel())).add("</div><p>")
					.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "guided.what", current.getConnectorId(), current.getOperation(),
						this.selection.getTable().getTableNode().getLabel(pLocale))))
					.add("</p><dl class=\"ftcsInputs\">");
			for (final String[] input : ConnectorSyncUserService.inputPairs(this.requestedInputs))
			{
				pWriter.add("<dt>").add(ConnectorSyncUserService.escape(input[0])).add("</dt><dd>").add(ConnectorSyncUserService.escape(input[1])).add("</dd>");
			}
			pWriter.add("</dl></div>");
			this.writeMode(pWriter, pLocale, current);
			final com.orchestranetworks.ui.form.UIExpandCollapseBlock advanced = pWriter.newExpandCollapseBlock();
			advanced.setId("ftcsAdvanced");
			advanced.setLabel(ConnectorSyncUserService.label(pLocale, "guided.advanced"));
			// a run cut at its maximum is the moment to raise it: open the block that holds it
			if (limit != null) advanced.expand();
			else advanced.collapse();
			pWriter.startExpandCollapseBlock(advanced);
			pWriter.startTableFormRow();
			pWriter.addFormRow(ConnectorSyncUserService.P_MAPPING);
			pWriter.addFormRow(ConnectorSyncUserService.P_MAX);
			pWriter.endTableFormRow();
			ReadOnlyForm.hint(pWriter, ConnectorSyncUserService.label(pLocale, "guided.maxHelp"));
			pWriter.endExpandCollapseBlock();
		}
		else
		{
			if (current != null)
			{
				this.writeFlow(pWriter, pLocale, current);
			}
			MappingForm.section(pWriter, ConnectorSyncUserService.label(pLocale, "section.mode"), null);
			this.writeMode(pWriter, pLocale, current);
			MappingForm.section(pWriter, ConnectorSyncUserService.label(pLocale, "section.options"),
				this.results == null ? ConnectorSyncUserService.label(pLocale, "hint.initial") : null);
			pWriter.startTableFormRow();
			pWriter.addFormRow(ConnectorSyncUserService.P_MAPPING);
			pWriter.addFormRow(ConnectorSyncUserService.P_SCOPE);
			pWriter.addFormRow(ConnectorSyncUserService.P_MAX);
			pWriter.endTableFormRow();
			if (this.pendingConfirm && current != null)
			{
				this.writeConfirm(pWriter, pLocale, current);
			}
			if (current != null && current.isFeed())
			{
				ReadOnlyForm.hint(pWriter, ConnectorSyncUserService.label(pLocale, "hint.max"));
			}
			if (this.requestedInputs != null)
			{
				ConnectorSyncUserService.notice(pWriter, null, ConnectorSyncUserService.label(pLocale, "hint.inputs", this.requestedInputs));
			}
		}
		if (this.message != null)
		{
			ConnectorSyncUserService.notice(pWriter, this.messageIsError ? "ftcsErr" : null, this.message);
		}
		if (this.failure != null)
		{
			pWriter.add("<div class=\"ftcsNotice ftcsErr\" role=\"alert\">").add(this.failure.html(pLocale)).add("</div>");
		}
		if (this.results == null || items.isEmpty())
		{
			return;
		}
		this.writeDetails(pWriter, pLocale, items, guided);
	}

	/** The live panel of the feed run this screen follows (nothing when there is none). */
	private void writeRunPanel(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		final ActiveRun run = this.activeRun;
		if (run == null)
		{
			return;
		}
		final String url = pWriter.getURLForAjaxRequest(new UserServiceAjaxRequest()
		{
			@Override
			public void processRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
			{
				ConnectorSyncUserService.this.runRequest(pContext, pResponse);
			}
		});
		final com.on.ebx.connect.core.RunProgress progress = RunPanelView.progress(this.repository, run.runId);
		RunPanelView.write(pWriter, pLocale, url, RunPanelView.body(pLocale, progress, this.runView(run, this.mayRun, this.repository, null)), run.runId, progress.isRunning());
	}

	private RunPanelView.View runView(final ActiveRun pRun, final boolean pMayRun, final com.orchestranetworks.instance.Repository pRepository, final ErrorTexts pFailure)
	{
		final RunPanelView.View view = new RunPanelView.View();
		view.runId = pRun.runId;
		view.label = pRun.mapping.getLabel() == null ? "" : pRun.mapping.getLabel();
		view.mayRun = pMayRun;
		view.stopRequested = pRun.stopRequested;
		view.canResume = pRun.inputs != null;
		view.dataset = ConnectorService.get().repository(pRepository).dataset();
		view.failure = pFailure;
		return view;
	}

	/**
	 * The Ajax requests of the live panel: {@code status} (the poll and the Refresh button), {@code stop} and {@code resume}. A panel
	 * only drives the run it was written for; Stop and Resume need the RUN right and a reader with READ only just watches.
	 */
	void runRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
	{
		final Locale locale = pContext.getSession().getLocale();
		final ActiveRun run = this.activeRun;
		final String asked = pContext.getParameter(RunPanelView.P_RUN);
		if (run == null || asked == null || !asked.equals(run.runId)
			|| !com.on.ebx.connect.ebx.ConnectorsAccess.has(pContext.getSession(), com.on.ebx.connect.ebx.ConnectorsAccess.Right.READ))
		{
			pResponse.getWriter().add(RunPanelView.expired(locale));
			return;
		}
		final boolean mayRun = com.on.ebx.connect.ebx.ConnectorsAccess.has(pContext.getSession(), com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN);
		final String action = pContext.getParameter(RunPanelView.P_ACTION);
		ActiveRun shown = run;
		ErrorTexts failure = null;
		if (mayRun && RunPanelView.ACTION_STOP.equals(action))
		{
			ConnectorService.requestCancel(run.runId);
			run.stopRequested = true;
		}
		else if (mayRun && RunPanelView.ACTION_RESUME.equals(action) && run.inputs != null)
		{
			final com.on.ebx.connect.core.RunProgress before = RunPanelView.progress(pContext.getRepository(), run.runId);
			if (before.isResumable())
			{
				try
				{
					final String runId = ConnectorService.get().importFromAsync(pContext.getSession(), pContext.getRepository(), run.mapping, run.inputs, run.max,
						"userService resume", before.getNextIndex());
					shown = new ActiveRun(runId, run.mapping, run.inputs, run.max);
					this.activeRun = shown;
				}
				catch (final RuntimeException ex)
				{
					ConnectorService.logFailure("synchronisation screen could not resume run " + run.runId, run.mapping, ex);
					failure = ErrorTexts.of(locale, ex, run.mapping);
				}
			}
		}
		final com.on.ebx.connect.core.RunProgress progress = RunPanelView.progress(pContext.getRepository(), shown.runId);
		pResponse.getWriter().add(RunPanelView.answer(locale, progress, this.runView(shown, mayRun, pContext.getRepository(), failure)));
	}

	/** Result section: when the run ended, one tile per outcome, and the notice of a feed cut at its maximum. */
	private void writeOutcome(final UserServicePaneWriter pWriter, final Locale pLocale, final List<SyncResult> pItems, final SyncResult pLimit)
	{
		if (this.lastRunAt == null)
		{
			this.lastRunAt = new java.util.Date(); // first display of these results: the run has just ended
		}
		final String when = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM, pLocale).format(this.lastRunAt);
		pWriter.add("<div class=\"ftcsOutcome\" role=\"status\">");
		MappingForm.section(pWriter, ConnectorSyncUserService.label(pLocale, "section.result"),
			ConnectorSyncUserService.label(pLocale, this.lastDryRun ? "summary.finishedDry" : "summary.finished", when));
		if (this.lastDryRun && !pItems.isEmpty())
		{
			// a simulation must not read as a run: say it, and offer to apply the same options
			pWriter.add("<div class=\"ftcsBanner\">").add(ConnectorSyncUserService.ICON_SIMULATE).add("<span class=\"ftcsBannerText\">")
					.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "banner.dryRun", Integer.valueOf(this.count(ConnectorSyncUserService.F_CHANGED)))))
					.add("</span><button type=\"button\" class=\"ftcsBtn ftcsBtnApply\" id=\"ftcsApplyNow\">")
					.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "banner.apply"))).add("</button></div>");
		}
		ReadOnlyForm.tilesStart(pWriter);
		ReadOnlyForm.tile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.items"), MappingForm.escape(String.valueOf(pItems.size())),
			pLimit == null ? null : pLimit.getAvailable() < 0 ? ConnectorSyncUserService.label(pLocale, "limit.tileMore")
				: ConnectorSyncUserService.label(pLocale, "limit.tile", Integer.valueOf(pLimit.getAvailable())));
		// a simulated change is DRY_RUN, which SyncTargets.summarise counts as unchanged: count by category here
		ConnectorSyncUserService.countTile(pWriter, ConnectorSyncUserService.label(pLocale, this.lastDryRun ? "tile.planned" : "tile.updated"),
			this.count(ConnectorSyncUserService.F_CHANGED), this.lastDryRun ? "Info" : "Good");
		ConnectorSyncUserService.countTile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.unchanged"), this.count(ConnectorSyncUserService.F_UNCHANGED), null);
		ConnectorSyncUserService.countTile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.noData"), this.count(ConnectorSyncUserService.F_NO_DATA), "Warn");
		ConnectorSyncUserService.countTile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.skipped"), this.count(ConnectorSyncUserService.F_SKIPPED), "Warn");
		ConnectorSyncUserService.countTile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.errors"), this.count(ConnectorSyncUserService.F_ERROR), "Bad");
		ReadOnlyForm.tilesEnd(pWriter);
		if (pLimit != null && pLimit.getAvailable() < 0)
		{
			// the source, the table or the scope held more, how many is not known (a JDBC cursor, records, an outbound scope): said all the same
			ConnectorSyncUserService.notice(pWriter, "ftcsWarn", ConnectorSyncUserService.label(pLocale, "limit.reachedMore",
				Integer.valueOf(pItems.size()), Integer.valueOf(this.lastMax), Integer.valueOf(ConnectorSyncUserService.FORM_MAX)));
		}
		else if (pLimit != null)
		{
			final int imported = pItems.size();
			ConnectorSyncUserService.notice(pWriter, "ftcsWarn", ConnectorSyncUserService.label(pLocale,
				pLimit.getAvailable() > ConnectorSyncUserService.FORM_MAX ? "limit.reachedCap" : "limit.reached",
				Integer.valueOf(imported), Integer.valueOf(pLimit.getAvailable()), Integer.valueOf(ConnectorSyncUserService.FORM_MAX)));
		}
		pWriter.add("</div>");
	}

	/**
	 * The run mode as two cards (a radio group) above the options, and the hidden inputs the run reads.
	 * The labels the script swaps in (button, hint) travel as data attributes, so no text is written into the script.
	 */
	private void writeMode(final UserServicePaneWriter pWriter, final Locale pLocale, final MappingDefinition pMapping)
	{
		final boolean outbound = pMapping != null && pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
		final String table = this.selection.getTable().getTableNode().getLabel(pLocale);
		final String connector = pMapping == null ? "" : pMapping.getConnectorId();
		final String current = this.lastDryRun ? ConnectorSyncUserService.MODE_SIMULATE : ConnectorSyncUserService.MODE_APPLY;
		pWriter.add("<input type=\"hidden\" name=\"").add(ConnectorSyncUserService.MODE_PARAM).add("\" id=\"ftcsModeInput\" value=\"").add(current).add("\"/>");
		pWriter.add("<input type=\"hidden\" name=\"").add(ConnectorSyncUserService.CONFIRM_PARAM).add("\" id=\"ftcsConfirmInput\" value=\"\"/>");
		pWriter.add("<div class=\"ftcsModes\" id=\"ftcsModes\" role=\"radiogroup\" aria-label=\"")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "section.mode")))
				.add("\" data-label-simulate=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "button.simulate")))
				.add("\" data-label-apply=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "button.apply"))).add("\">");
		ConnectorSyncUserService.modeCard(pWriter, ConnectorSyncUserService.MODE_SIMULATE, "ftcsModeSim", ConnectorSyncUserService.ICON_SIMULATE,
			ConnectorSyncUserService.label(pLocale, "mode.simulate"),
			ConnectorSyncUserService.label(pLocale, outbound ? "mode.simulate.outbound" : "mode.simulate.inbound", table, connector), this.lastDryRun);
		ConnectorSyncUserService.modeCard(pWriter, ConnectorSyncUserService.MODE_APPLY, "ftcsModeApply", ConnectorSyncUserService.ICON_APPLY,
			ConnectorSyncUserService.label(pLocale, "mode.apply"),
			ConnectorSyncUserService.label(pLocale, outbound ? "mode.apply.outbound" : "mode.apply.inbound", table, connector), !this.lastDryRun);
		final String hintSimulate = ConnectorSyncUserService.label(pLocale, "mode.hint.simulate");
		final String hintApply = ConnectorSyncUserService.label(pLocale, outbound ? "mode.hint.apply.outbound" : "mode.hint.apply.inbound");
		pWriter.add("</div><div class=\"ftcsModeHint\" id=\"ftcsModeHint\" aria-live=\"polite\" data-simulate=\"")
				.add(ConnectorSyncUserService.escape(hintSimulate)).add("\" data-apply=\"").add(ConnectorSyncUserService.escape(hintApply)).add("\">")
				.add(ConnectorSyncUserService.escape(this.lastDryRun ? hintSimulate : hintApply)).add("</div>");
	}

	private static void modeCard(final UserServicePaneWriter pWriter, final String pMode, final String pClass, final String pIcon, final String pTitle,
		final String pText, final boolean pOn)
	{
		pWriter.add("<button type=\"button\" role=\"radio\" class=\"ftcsMode ").add(pClass).add(pOn ? " ftcsModeOn" : "")
				.add("\" data-mode=\"").add(pMode).add("\" aria-checked=\"").add(String.valueOf(pOn)).add("\" tabindex=\"").add(pOn ? "0" : "-1").add("\">")
				.add("<span class=\"ftcsModeIcon\">").add(pIcon).add("</span><span><span class=\"ftcsModeTitle\">")
				.add(ConnectorSyncUserService.escape(pTitle)).add("</span><span class=\"ftcsModeText\">")
				.add(ConnectorSyncUserService.escape(pText)).add("</span></span></button>");
	}

	/** The panel shown instead of a real run on the whole table: how many records, where they go, and three ways out. */
	private void writeConfirm(final UserServicePaneWriter pWriter, final Locale pLocale, final MappingDefinition pMapping)
	{
		final boolean outbound = pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
		String text = ConnectorSyncUserService.label(pLocale, outbound ? "confirm.outbound" : "confirm.inbound", Integer.valueOf(this.pendingCount),
			this.selection.getTable().getTableNode().getLabel(pLocale), pMapping.getConnectorId());
		if (this.pendingCount > this.lastMax)
		{
			text += " " + ConnectorSyncUserService.label(pLocale, "confirm.limit", Integer.valueOf(this.lastMax));
		}
		pWriter.add("<div class=\"ftcsConfirm\" id=\"ftcsConfirm\" role=\"alertdialog\" aria-labelledby=\"ftcsConfirmTitle\" aria-describedby=\"ftcsConfirmText\" data-token=\"")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.confirmToken(pMapping))).add("\">")
				.add("<div class=\"ftcsConfirmTitle\" id=\"ftcsConfirmTitle\">").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "confirm.title")))
				.add("</div><p id=\"ftcsConfirmText\">").add(ConnectorSyncUserService.escape(text)).add("</p><div class=\"ftcsConfirmActions\">")
				.add("<button type=\"button\" class=\"ftcsBtn ftcsBtnApply\" id=\"ftcsConfirmRun\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "confirm.run", Integer.valueOf(Math.min(this.pendingCount, this.lastMax)))))
				.add("</button><button type=\"button\" class=\"ftcsBtn\" id=\"ftcsConfirmSimulate\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "confirm.simulate")))
				.add("</button><button type=\"button\" class=\"ftcsBtn\" id=\"ftcsConfirmCancel\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "confirm.cancel"))).add("</button></div></div>");
	}

	/** What a confirmation covers: this mapping on the whole table. Another mapping chosen afterwards asks again. */
	static String confirmToken(final MappingDefinition pMapping)
	{
		return pMapping.getId() + "|ALL";
	}

	private int countAll()
	{
		final com.onwbp.adaptation.RequestResult result = this.selection.getAllRecords().execute();
		try
		{
			return result.getSize();
		}
		finally
		{
			result.close();
		}
	}

	/** Flow section: what runs, in which direction, into which table. */
	private void writeFlow(final UserServicePaneWriter pWriter, final Locale pLocale, final MappingDefinition pMapping)
	{
		MappingForm.section(pWriter, ConnectorSyncUserService.label(pLocale, "section.flow"), pMapping.getLabel());
		ReadOnlyForm.tilesStart(pWriter);
		ReadOnlyForm.tile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.flow"),
			MappingForm.escape(pMapping.getConnectorId() + " / " + pMapping.getOperation()), null);
		ReadOnlyForm.tile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.direction"),
			ReadOnlyForm.badge(String.valueOf(pMapping.getDirection()), "Info"),
			ConnectorSyncUserService.label(pLocale, pMapping.isFeed() ? "kind.feed" : "kind.records"));
		ReadOnlyForm.tile(pWriter, ConnectorSyncUserService.label(pLocale, "tile.target"),
			MappingForm.escape(this.selection.getTable().getTableNode().getLabel(pLocale)), this.selection.getTable().getTablePath().format());
		ReadOnlyForm.tilesEnd(pWriter);
	}

	/**
	 * The run is synchronous. Same strip as the material federated search: revealed on the Run click, it
	 * fills the window before EBX blanks the pane with its own spinner, and says what is running. Not a
	 * modal: if EBX refuses the form (a field constraint) the page never leaves, so a timer clears it.
	 */
	private void writeProgress(final UserServicePaneWriter pWriter, final Locale pLocale, final String pUserId)
	{
		pWriter.add("<div id=\"ftcsProgress\" class=\"ftcsProgress\" role=\"status\">")
				.add("<div class=\"ftcsProgressTrack\"><div class=\"ftcsProgressBar\"></div></div>")
				.add("<span class=\"ftcsProgressLabel\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "message.running"))).add("</span>")
				.add("<span class=\"ftcsProgressTime\" id=\"ftcsProgressTime\"></span></div>");
		pWriter.addJS_cr("(function(){");
		pWriter.addJS_cr("  var d = document, box = d.getElementById('ftcsProgress'), button = d.getElementById('ftcsRun');");
		pWriter.addJS_cr("  if (!box || !button) { return; }");
		pWriter.addJS_cr("  button.addEventListener('click', function() {");
		pWriter.addJS_cr("    var old = d.querySelectorAll('.ftcsOutcome,.ftcsReady,.ftcsConfirm');");
		pWriter.addJS_cr("    for (var i = 0; i < old.length; i++) { old[i].hidden = true; }");
		pWriter.addJS_cr("    box.className = 'ftcsProgress ftcsProgressOn';");
		// the elapsed time, so that a long run visibly keeps going
		pWriter.addJS_cr("    var clock = d.getElementById('ftcsProgressTime'), started = Date.now();");
		pWriter.addJS_cr("    if (clock && !clock.getAttribute('data-on')) { clock.setAttribute('data-on', '1'); window.setInterval(function() {");
		pWriter.addJS_cr("      var s = Math.floor((Date.now() - started) / 1000); clock.textContent = Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); }, 1000); }");
		pWriter.addJS_cr("    window.setTimeout(function(){ box.className = 'ftcsProgress';");
		pWriter.addJS_cr("      for (var i = 0; i < old.length; i++) { old[i].hidden = false; } }, 120000);");
		pWriter.addJS_cr("  }, false);");
		pWriter.addJS_cr("})();");
		// The mode cards: a radio group that fills the hidden input, relabels and recolours Run, and swaps the hint.
		// Apply now (after a simulation) and the confirmation panel set the mode, then click Run.
		pWriter.addJS_cr("(function(){");
		pWriter.addJS_cr("  var d = document, input = d.getElementById('ftcsModeInput'), group = d.getElementById('ftcsModes');");
		pWriter.addJS_cr("  var run = d.getElementById('ftcsRun'), hint = d.getElementById('ftcsModeHint'), confirmInput = d.getElementById('ftcsConfirmInput');");
		pWriter.addJS_cr("  if (!input || !group) { return; }");
		pWriter.addJS_cr("  var cards = group.querySelectorAll('.ftcsMode');");
		pWriter.addJS_cr("  function relabel(el, text) {");
		pWriter.addJS_cr("    if (!el || !text) { return; }");
		pWriter.addJS_cr("    var walk = d.createTreeWalker(el, 4, null, false), n, last = null;");
		pWriter.addJS_cr("    while ((n = walk.nextNode())) { if (/\\S/.test(n.nodeValue)) { last = n; } }");
		pWriter.addJS_cr("    if (last) { last.nodeValue = text; } else { el.textContent = text; }");
		pWriter.addJS_cr("    if (el.getAttribute('title')) { el.setAttribute('title', text); }");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  function set(mode) {");
		pWriter.addJS_cr("    input.value = mode;");
		pWriter.addJS_cr("    for (var i = 0; i < cards.length; i++) {");
		pWriter.addJS_cr("      var on = cards[i].getAttribute('data-mode') === mode;");
		pWriter.addJS_cr("      cards[i].classList.toggle('ftcsModeOn', on);");
		pWriter.addJS_cr("      cards[i].setAttribute('aria-checked', on ? 'true' : 'false');");
		pWriter.addJS_cr("      cards[i].tabIndex = on ? 0 : -1;");
		pWriter.addJS_cr("    }");
		pWriter.addJS_cr("    relabel(run, group.getAttribute('data-label-' + mode));");
		pWriter.addJS_cr("    if (run) { run.classList.toggle('ftcsRunApply', mode === 'apply'); }");
		pWriter.addJS_cr("    if (hint) { hint.textContent = hint.getAttribute('data-' + mode); }");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  for (var i = 0; i < cards.length; i++) {");
		pWriter.addJS_cr("    cards[i].addEventListener('click', function() { set(this.getAttribute('data-mode')); }, false);");
		pWriter.addJS_cr("    cards[i].addEventListener('keydown', function(e) {");
		pWriter.addJS_cr("      if (['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].indexOf(e.key) < 0) { return; }");
		pWriter.addJS_cr("      e.preventDefault();");
		pWriter.addJS_cr("      var other = this.getAttribute('data-mode') === 'apply' ? 'simulate' : 'apply';");
		pWriter.addJS_cr("      set(other);");
		pWriter.addJS_cr("      var next = group.querySelector('[data-mode=\"' + other + '\"]'); if (next) { next.focus(); }");
		pWriter.addJS_cr("    }, false);");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  set(input.value);");
		pWriter.addJS_cr("  function go(mode, token) { set(mode); if (confirmInput) { confirmInput.value = token || ''; } if (run) { run.click(); } }");
		pWriter.addJS_cr("  var applyNow = d.getElementById('ftcsApplyNow');");
		pWriter.addJS_cr("  if (applyNow) { applyNow.addEventListener('click', function() { go('apply'); }, false); }");
		pWriter.addJS_cr("  var box = d.getElementById('ftcsConfirm');");
		pWriter.addJS_cr("  if (box) {");
		pWriter.addJS_cr("    var ok = d.getElementById('ftcsConfirmRun');");
		pWriter.addJS_cr("    ok.addEventListener('click', function() { go('apply', box.getAttribute('data-token')); }, false);");
		pWriter.addJS_cr("    d.getElementById('ftcsConfirmSimulate').addEventListener('click', function() { go('simulate'); }, false);");
		pWriter.addJS_cr("    d.getElementById('ftcsConfirmCancel').addEventListener('click', function() { box.hidden = true; if (run) { run.focus(); } }, false);");
		pWriter.addJS_cr("    if (box.scrollIntoView) { box.scrollIntoView({ block: 'center' }); }");
		pWriter.addJS_cr("    ok.focus();");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("})();");
		// The details: the next page from the server on a click, or when the button scrolls into view; a filter reloads from the start.
		pWriter.addJS_cr("(function(){");
		pWriter.addJS_cr("  var d = document, box = d.getElementById('ftcsDetails');");
		pWriter.addJS_cr("  if (!box) { return; }");
		pWriter.addJS_cr("  var body = d.getElementById('ftcsRows'), more = d.getElementById('ftcsMoreBtn'), count = d.getElementById('ftcsMoreCount');");
		pWriter.addJS_cr("  var page = +box.getAttribute('data-page'), state = { filter: 'ALL', shown: +box.getAttribute('data-shown'), total: +box.getAttribute('data-total'), busy: false, dead: false };");
		pWriter.addJS_cr("  function fmt(t, a) { return String(t).replace(/\\{(\\d)\\}/g, function(m, i) { return a[+i]; }); }");
		pWriter.addJS_cr("  function num(n) { try { return n.toLocaleString(d.documentElement.lang || undefined); } catch (e) { return String(n); } }");
		pWriter.addJS_cr("  function refresh() {");
		pWriter.addJS_cr("    count.textContent = fmt(box.getAttribute('data-label-count'), [num(state.shown), num(state.total)]);");
		pWriter.addJS_cr("    var left = state.total - state.shown;");
		pWriter.addJS_cr("    more.hidden = left <= 0 || state.dead;");
		pWriter.addJS_cr("    if (left > 0) { more.textContent = fmt(box.getAttribute('data-label-more'), [num(Math.min(page, left))]); }");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  function stop(label) { state.busy = false; state.dead = true; more.hidden = true; count.textContent = box.getAttribute(label); }");
		pWriter.addJS_cr("  function inView() { var r = more.getBoundingClientRect(); return !more.hidden && r.top < (window.innerHeight || d.documentElement.clientHeight) + 200; }");
		pWriter.addJS_cr("  function load(reset) {");
		pWriter.addJS_cr("    if (state.busy || state.dead) { return; }");
		pWriter.addJS_cr("    if (typeof EBX_AJAXResponseHandler === 'undefined') { stop('data-label-failed'); return; }");
		pWriter.addJS_cr("    state.busy = true; more.disabled = true; count.textContent = box.getAttribute('data-label-loading');");
		pWriter.addJS_cr("    var Handler = function() {}; Handler.prototype = new EBX_AJAXResponseHandler(); var h = new Handler();");
		pWriter.addJS_cr("    h.handleAjaxResponseSuccess = function(text) {");
		pWriter.addJS_cr("      var r = null; try { r = JSON.parse(text); } catch (e) { r = null; }");
		pWriter.addJS_cr("      more.disabled = false;");
		pWriter.addJS_cr("      if (!r) { stop('data-label-failed'); return; }");
		pWriter.addJS_cr("      if (r.expired) { stop('data-label-expired'); return; }");
		pWriter.addJS_cr("      if (reset) { body.innerHTML = ''; }");
		pWriter.addJS_cr("      body.insertAdjacentHTML('beforeend', r.html);");
		pWriter.addJS_cr("      state.shown = r.shown; state.total = r.total; state.busy = false; refresh();");
		pWriter.addJS_cr("      if (auto && inView()) { load(false); }");
		pWriter.addJS_cr("    };");
		pWriter.addJS_cr("    h.handleAjaxResponseFailed = function() { more.disabled = false; state.busy = false; count.textContent = box.getAttribute('data-label-failed'); };");
		pWriter.addJS_cr("    h.sendRequest(box.getAttribute('data-url') + '&" + ConnectorSyncUserService.P_OFFSET + "=' + (reset ? 0 : state.shown)");
		pWriter.addJS_cr("      + '&" + ConnectorSyncUserService.P_FILTER + "=' + encodeURIComponent(state.filter) + '&" + ConnectorSyncUserService.P_GENERATION + "=' + encodeURIComponent(box.getAttribute('data-gen')));");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  more.addEventListener('click', function() { load(false); }, false);");
		pWriter.addJS_cr("  var chips = box.querySelectorAll('.ftcsFilter');");
		pWriter.addJS_cr("  for (var i = 0; i < chips.length; i++) {");
		pWriter.addJS_cr("    chips[i].addEventListener('click', function() {");
		pWriter.addJS_cr("      if (state.busy || state.dead || this.getAttribute('data-filter') === state.filter) { return; }");
		pWriter.addJS_cr("      for (var j = 0; j < chips.length; j++) { var on = chips[j] === this; chips[j].classList.toggle('ftcsFilterOn', on); chips[j].setAttribute('aria-pressed', on ? 'true' : 'false'); }");
		pWriter.addJS_cr("      state.filter = this.getAttribute('data-filter'); state.shown = 0; state.total = +this.getAttribute('data-count');");
		pWriter.addJS_cr("      load(true);");
		pWriter.addJS_cr("    }, false);");
		pWriter.addJS_cr("  }");
		// infinite scroll: only once the user scrolls down to the button, then as long as it stays in view
		pWriter.addJS_cr("  var auto = false, scrolled = false;");
		pWriter.addJS_cr("  if ('IntersectionObserver' in window) {");
		pWriter.addJS_cr("    var seen = false;");
		pWriter.addJS_cr("    new IntersectionObserver(function(entries) {");
		pWriter.addJS_cr("      for (var k = 0; k < entries.length; k++) { seen = entries[k].isIntersecting; }");
		pWriter.addJS_cr("      if (seen && scrolled && !more.hidden) { auto = true; load(false); }");
		pWriter.addJS_cr("    }, { rootMargin: '200px' }).observe(more);");
		// a scroll anywhere (the pane scrolls in its own container): from then on, the button in view loads the next page
		pWriter.addJS_cr("    d.addEventListener('scroll', function() { scrolled = true; if (seen && !more.hidden) { auto = true; load(false); } }, true);");
		pWriter.addJS_cr("  }");
		pWriter.addJS_cr("  refresh();");
		pWriter.addJS_cr("})();");
		// Back only where EBX drew no Close of its own next to it
		pWriter.addJS_cr("(function(){var b=document.getElementById('ftcsBack');if(!b||!b.parentElement)return;"
				+ "var o=b.parentElement.querySelectorAll('button');for(var i=0;i<o.length;i++){if(o[i]!==b&&o[i].id!=='ftcsRun'){b.style.display='none';return;}}})();");
		// ... and that Close returns where the link was clicked (a connector's flow card), not to the target table
		pWriter.addJS_cr(MappingForm.closeReturnScript("ftcsClose", "ftServiceReturn", null, pUserId));
	}

	/** Result categories, as the filters of the details show them. */
	static final String F_ALL = "ALL";
	static final String F_ERROR = "ERROR";
	static final String F_CHANGED = "CHANGED";
	static final String F_UNCHANGED = "UNCHANGED";
	static final String F_NO_DATA = "NO_DATA";
	static final String F_SKIPPED = "SKIPPED";
	private static final String[] FILTERS = { ConnectorSyncUserService.F_ALL, ConnectorSyncUserService.F_ERROR, ConnectorSyncUserService.F_CHANGED,
		ConnectorSyncUserService.F_UNCHANGED, ConnectorSyncUserService.F_NO_DATA, ConnectorSyncUserService.F_SKIPPED };
	/** Rows sent per page of details: a run can return up to 4999 items, each with its list of changes. */
	static final int PAGE_SIZE = 20;
	static final String P_OFFSET = "ftcsOffset";
	static final String P_FILTER = "ftcsFilter";
	static final String P_GENERATION = "ftcsGen";

	static String category(final SyncResult pResult)
	{
		switch (pResult.getStatus())
		{
			case OK:
			case DRY_RUN:
				return ConnectorSyncUserService.F_CHANGED;
			case NO_CHANGE:
				return ConnectorSyncUserService.F_UNCHANGED;
			case NO_DATA:
				return ConnectorSyncUserService.F_NO_DATA;
			case SKIPPED:
				return ConnectorSyncUserService.F_SKIPPED;
			default:
				return ConnectorSyncUserService.F_ERROR;
		}
	}

	static boolean matches(final SyncResult pResult, final String pFilter)
	{
		return ConnectorSyncUserService.F_ALL.equals(pFilter) || ConnectorSyncUserService.category(pResult).equals(pFilter);
	}

	private int count(final String pFilter)
	{
		final Integer n = this.counts.get(pFilter);
		return n == null ? 0 : n.intValue();
	}

	/**
	 * Once per run: the items apart from the notice of a feed cut at its maximum, errors first (the rest in run order),
	 * their short names on a guided screen, the count per category, and a new generation so that a page asked by an
	 * older screen is refused rather than mixed in.
	 */
	private void prepareItems(final boolean pGuided)
	{
		if (this.results == null || this.shown != null)
		{
			return;
		}
		final List<SyncResult> errors = new ArrayList<SyncResult>();
		final List<SyncResult> others = new ArrayList<SyncResult>();
		this.limitNotice = null;
		this.counts.clear();
		for (final SyncResult r : this.results)
		{
			if (r.isLimitNotice())
			{
				this.limitNotice = r;
				continue;
			}
			final String category = ConnectorSyncUserService.category(r);
			(ConnectorSyncUserService.F_ERROR.equals(category) ? errors : others).add(r);
			this.counts.put(category, Integer.valueOf(this.count(category) + 1));
		}
		errors.addAll(others);
		this.counts.put(ConnectorSyncUserService.F_ALL, Integer.valueOf(errors.size()));
		this.shownNames = pGuided ? ConnectorSyncUserService.shortLabels(errors) : null;
		this.shown = errors;
		this.generation++;
	}

	/** Details section: status filters, the first page of rows, and the button that asks the server for the next ones. */
	private void writeDetails(final UserServicePaneWriter pWriter, final Locale pLocale, final List<SyncResult> pItems, final boolean pGuided)
	{
		if (pGuided)
		{
			// the tiles above already give the counts: the item list is a detail, open only when something failed
			final com.orchestranetworks.ui.form.UIExpandCollapseBlock details = pWriter.newExpandCollapseBlock();
			details.setId("ftcsDetailsBlock");
			details.setLabel(ConnectorSyncUserService.label(pLocale, "guided.details", Integer.valueOf(pItems.size())));
			if (this.count(ConnectorSyncUserService.F_ERROR) > 0) details.expand();
			else details.collapse();
			pWriter.startExpandCollapseBlock(details);
		}
		else
		{
			MappingForm.section(pWriter, ConnectorSyncUserService.label(pLocale, "section.details"), null);
		}
		final String url = pWriter.getURLForAjaxRequest(new UserServiceAjaxRequest()
		{
			@Override
			public void processRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
			{
				ConnectorSyncUserService.this.writePage(pContext, pResponse);
			}
		});
		final int first = Math.min(ConnectorSyncUserService.PAGE_SIZE, pItems.size());
		pWriter.add("<div class=\"ftcsDetails\" id=\"ftcsDetails\" data-url=\"").add(ConnectorSyncUserService.escape(url))
				.add("\" data-gen=\"").add(String.valueOf(this.generation))
				.add("\" data-page=\"").add(String.valueOf(ConnectorSyncUserService.PAGE_SIZE))
				.add("\" data-shown=\"").add(String.valueOf(first))
				.add("\" data-total=\"").add(String.valueOf(pItems.size()))
				.add("\" data-label-more=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.more")))
				.add("\" data-label-count=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.count")))
				.add("\" data-label-loading=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.loading")))
				.add("\" data-label-expired=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.expired")))
				.add("\" data-label-failed=\"").add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.failed"))).add("\">");
		// the filters, when there is more than one category to tell apart
		int categories = 0;
		for (final String f : ConnectorSyncUserService.FILTERS)
		{
			if (!ConnectorSyncUserService.F_ALL.equals(f) && this.count(f) > 0) categories++;
		}
		if (categories > 1)
		{
			pWriter.add("<div class=\"ftcsFilters\" role=\"group\" aria-label=\"")
					.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.filters"))).add("\">");
			for (final String f : ConnectorSyncUserService.FILTERS)
			{
				final int n = this.count(f);
				if (n == 0) continue;
				final boolean on = ConnectorSyncUserService.F_ALL.equals(f);
				final String key = ConnectorSyncUserService.F_CHANGED.equals(f) && this.lastDryRun ? "filter.CHANGED.dry" : "filter." + f;
				pWriter.add("<button type=\"button\" class=\"ftcsFilter").add(on ? " ftcsFilterOn" : "").add(F_ERROR.equals(f) ? " ftcsFilterErr" : "")
						.add("\" data-filter=\"").add(f).add("\" data-count=\"").add(String.valueOf(n)).add("\" aria-pressed=\"").add(String.valueOf(on)).add("\">")
						.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, key)))
						.add(" <span class=\"ftcsFilterCount\">").add(ConnectorSyncUserService.number(pLocale, n)).add("</span></button>");
			}
			pWriter.add("</div>");
		}
		pWriter.add("<table class=\"ftcsTable\"><thead><tr><th>")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, pGuided ? "column.item" : "column.record"))).add("</th><th>")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "column.status"))).add("</th><th>")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "column.changes"))).add("</th><th>")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "column.message"))).add("</th><th class=\"ftcsMs\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "column.duration"))).add("</th></tr></thead><tbody id=\"ftcsRows\">");
		final StringBuilder rows = new StringBuilder();
		for (int n = 0; n < first; n++)
		{
			this.row(rows, pLocale, n);
		}
		pWriter.add(rows.toString()).add("</tbody></table>");
		pWriter.add("<div class=\"ftcsMore\"><span id=\"ftcsMoreCount\" class=\"ftcsMuted\" aria-live=\"polite\">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.count", ConnectorSyncUserService.number(pLocale, first),
					ConnectorSyncUserService.number(pLocale, pItems.size()))))
				.add("</span><button type=\"button\" class=\"ftcsBtn\" id=\"ftcsMoreBtn\"")
				.add(first < pItems.size() ? "" : " hidden").add(">")
				.add(ConnectorSyncUserService.escape(ConnectorSyncUserService.label(pLocale, "details.more",
					ConnectorSyncUserService.number(pLocale, Math.min(ConnectorSyncUserService.PAGE_SIZE, pItems.size() - first)))))
				.add("</button></div></div>");
		if (pGuided)
		{
			pWriter.endExpandCollapseBlock();
		}
	}

	/**
	 * One page of rows for the details, as JSON: {@code html}, {@code shown} (rows on the screen after this page) and
	 * {@code total} (rows of the filter). {@code expired} when the results are gone or belong to another run.
	 */
	void writePage(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
	{
		// written without any markup character: this answer, opened as a page instead of read by the script, shows inert text
		final com.fasterxml.jackson.databind.node.ObjectNode out = com.on.ebx.connect.core.Json.object();
		final List<SyncResult> items = this.shown;
		if (items == null || !String.valueOf(this.generation).equals(pContext.getParameter(ConnectorSyncUserService.P_GENERATION)))
		{
			out.put("expired", true);
			pResponse.getWriter().add(com.on.ebx.connect.core.Json.writeForScript(out));
			return;
		}
		final Locale locale = pContext.getSession().getLocale();
		String filter = pContext.getParameter(ConnectorSyncUserService.P_FILTER);
		if (filter == null || !java.util.Arrays.asList(ConnectorSyncUserService.FILTERS).contains(filter))
		{
			filter = ConnectorSyncUserService.F_ALL;
		}
		int offset = 0;
		try
		{
			offset = Math.max(0, Integer.parseInt(pContext.getParameter(ConnectorSyncUserService.P_OFFSET)));
		}
		catch (final NumberFormatException ex)
		{
			offset = 0;
		}
		final StringBuilder html = new StringBuilder();
		int matched = 0;
		int added = 0;
		for (int n = 0; n < items.size(); n++)
		{
			if (!ConnectorSyncUserService.matches(items.get(n), filter)) continue;
			if (matched >= offset && added < ConnectorSyncUserService.PAGE_SIZE)
			{
				this.row(html, locale, n);
				added++;
			}
			matched++;
		}
		out.put("html", html.toString());
		out.put("shown", Math.min(offset, matched) + added);
		out.put("total", matched);
		pResponse.getWriter().add(com.on.ebx.connect.core.Json.writeForScript(out));
	}

	/** The row of item pIndex (in the errors-first order): name, status badge, changes and errors, message, duration. */
	private void row(final StringBuilder pOut, final Locale pLocale, final int pIndex)
	{
		final SyncResult r = this.shown.get(pIndex);
		final String name = this.shownNames == null ? r.getRecordLabel() : this.shownNames.get(pIndex);
		pOut.append("<tr><td class=\"ftcsItem\" title=\"").append(ConnectorSyncUserService.escape(r.getRecordLabel())).append("\">")
				.append(ConnectorSyncUserService.escape(name)).append("</td><td>")
				.append(ReadOnlyForm.badge(ConnectorSyncUserService.label(pLocale, "status." + r.getStatus().name()), ConnectorSyncUserService.tone(r.getStatus())))
				.append("</td><td>");
		if (!r.getChanges().isEmpty() || !r.getErrors().isEmpty())
		{
			pOut.append("<ul class=\"ftcsChanges\">");
			for (final MappingResult.Change change : r.getChanges())
			{
				pOut.append("<li>").append(ConnectorSyncUserService.escape(change.toString())).append("</li>");
			}
			for (final String error : r.getErrors())
			{
				pOut.append("<li class=\"ftcsChangeErr\">").append(ConnectorSyncUserService.escape(error)).append("</li>");
			}
			pOut.append("</ul>");
		}
		else
		{
			pOut.append("<span class=\"ftcsMuted\">–</span>");
		}
		// the common sentences of the engine in the reader's language; the stored English text stays in the tooltip (audit C-37)
		final String localized = LogMessages.localize(pLocale, r.getMessage());
		if (localized == null)
		{
			pOut.append("</td><td>").append(ConnectorSyncUserService.escape(r.getMessage()));
		}
		else
		{
			pOut.append("</td><td><span title=\"").append(ConnectorSyncUserService.escape(r.getMessage())).append("\">").append(ConnectorSyncUserService.escape(localized)).append("</span>");
		}
		// a failure the catalogue knows says what to do next; the stored message above stays as the engine wrote it
		final String hint = ConnectorSyncUserService.category(r).equals(ConnectorSyncUserService.F_ERROR) ? ErrorTexts.hint(pLocale, r.getMessage()) : null;
		if (hint != null)
		{
			pOut.append("<div class=\"ftErrHint\">").append(ConnectorSyncUserService.escape(hint)).append("</div>");
		}
		pOut.append("</td><td class=\"ftcsMs\">").append(r.getDurationMs()).append(" ms</td></tr>");
	}

	static String number(final Locale pLocale, final int pValue)
	{
		return java.text.NumberFormat.getIntegerInstance(pLocale == null ? Locale.ENGLISH : pLocale).format(pValue);
	}

	/** A count tile: the number in a coloured pill when not zero, plain otherwise. */
	private static void countTile(final UserServicePaneWriter pWriter, final String pLabel, final int pCount, final String pTone)
	{
		ReadOnlyForm.tile(pWriter, pLabel, pCount > 0 && pTone != null ? ReadOnlyForm.badge(String.valueOf(pCount), pTone) : MappingForm.escape(String.valueOf(pCount)), null);
	}

	/** A message box; pKind is ftcsWarn, ftcsErr, ftcsReady or null for information. */
	private static void notice(final UserServicePaneWriter pWriter, final String pKind, final String pText)
	{
		pWriter.add("<div class=\"ftcsNotice").add(pKind == null ? "" : " " + pKind).add("\">").add(ConnectorSyncUserService.escape(pText)).add("</div>");
	}

	static String tone(final SyncResult.Status pStatus)
	{
		switch (pStatus)
		{
			case OK:
				return "Good";
			case ERROR:
			case PARTIAL:
				return "Bad";
			case NO_DATA:
			case SKIPPED:
				return "Warn";
			case DRY_RUN:
				return "Info";
			default:
				return null;
		}
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext,
		final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// nothing to validate: the form is an options panel
	}

	/** The mapping chosen on the screen (the one the link preset), null when none matches. */
	MappingDefinition selectedMapping()
	{
		for (final MappingDefinition m : this.mappings)
		{
			if (String.valueOf(m.getId()).equals(this.lastMapping))
			{
				return m;
			}
		}
		return null;
	}

	/** A feed opened from a link that already carries its run-time inputs: the screen keeps to what matters. */
	boolean guided()
	{
		final MappingDefinition mapping = this.selectedMapping();
		return this.requestedInputs != null && mapping != null && mapping.isFeed();
	}

	/** {@code name=value;name=value} as pairs, in order. */
	static List<String[]> inputPairs(final String pInputs)
	{
		final List<String[]> out = new ArrayList<String[]>();
		if (pInputs == null) return out;
		for (final String part : pInputs.split(";"))
		{
			final int eq = part.indexOf('=');
			if (part.trim().isEmpty()) continue;
			out.add(eq < 0 ? new String[] { part.trim(), "" } : new String[] { part.substring(0, eq).trim(), part.substring(eq + 1).trim() });
		}
		return out;
	}

	/** The values of the inputs, for a title ({@code Companies/Companies/root/Companies}). */
	static String inputValues(final String pInputs)
	{
		final StringBuilder out = new StringBuilder();
		for (final String[] pair : ConnectorSyncUserService.inputPairs(pInputs))
		{
			if (out.length() > 0) out.append(", ");
			out.append(pair[1].isEmpty() ? pair[0] : pair[1]);
		}
		return out.toString();
	}

	/**
	 * Readable names of imported items: the key an item was matched on is a predicate
	 * ({@code ./columnKey='EBX_TEST:Companies/Companies/root/Companies.Identification/Id' and date-equal(...)});
	 * its first quoted value is kept, less the part every item shares ({@code Identification/Id}).
	 */
	static List<String> shortLabels(final List<SyncResult> pResults)
	{
		final List<String> values = new ArrayList<String>();
		for (final SyncResult r : pResults)
		{
			final String label = r.getRecordLabel() == null ? "" : r.getRecordLabel();
			final java.util.regex.Matcher m = java.util.regex.Pattern.compile("'((?:[^']|'')*)'").matcher(label);
			values.add(m.find() ? m.group(1).replace("''", "'") : label);
		}
		String prefix = values.size() > 1 ? values.get(0) : "";
		for (final String v : values)
		{
			int k = 0;
			while (k < prefix.length() && k < v.length() && prefix.charAt(k) == v.charAt(k)) k++;
			prefix = prefix.substring(0, k);
		}
		int cut = Math.max(prefix.lastIndexOf('.'), Math.max(prefix.lastIndexOf(':'), prefix.lastIndexOf('/')));
		final List<String> out = new ArrayList<String>();
		for (final String v : values)
		{
			final String rest = cut < 0 ? v : v.substring(cut + 1);
			out.add(rest.isEmpty() ? v : rest);
		}
		return out;
	}

	static String label(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(ConnectorSyncUserService.class, pLocale, pKey, pArgs);
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
