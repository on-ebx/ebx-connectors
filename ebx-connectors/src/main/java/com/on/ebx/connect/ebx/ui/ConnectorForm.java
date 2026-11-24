package com.on.ebx.connect.ebx.ui;

import java.text.DateFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorConnectionStateService;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.DescriptorConfiguration;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.ebx.EbxConnectorConnectionStateStore;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecJSAction;
import com.orchestranetworks.ui.UIHttpManagerComponent;
import com.orchestranetworks.ui.form.UIForm;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWithTabs;
import com.orchestranetworks.ui.form.UIFormPaneWriter;

/**
 * ETL-style connector workspace. It uses native EBX form rows and association widgets, retaining
 * validation, permissions, encrypted fields and the standard Save/Revert transaction lifecycle.
 * Remove the recordForm declaration from Connectors.xsd to return to the generated form.
 */
public final class ConnectorForm extends UIForm
{
	private static final Path PROPERTIES = Path.parse("./properties");
	private static final Path[] IDENTITY = { ConnectorsPaths.Connector.ID, ConnectorsPaths.Connector.LABEL,
		ConnectorsPaths.Connector.DESCRIPTION, ConnectorsPaths.Connector.CATEGORY, ConnectorsPaths.Connector.TYPE,
		ConnectorsPaths.Connector.ACTIVE };
	private static final Path[] OPERATIONS = { ConnectorsPaths.Connector.CONNECT_TIMEOUT, ConnectorsPaths.Connector.READ_TIMEOUT,
		ConnectorsPaths.Connector.TRUST, ConnectorsPaths.Connector.PRICING, ConnectorsPaths.Connector.PRICING_NOTE };
	private static final Path[] BRANDING = { ConnectorsPaths.Connector.LOGO, ConnectorsPaths.Connector.UPLOADED_LOGO };

	@Override
	public void defineBody(final UIFormBody pBody, final UIFormContext pContext)
	{
		final Locale locale = pContext.getLocale();
		final UIFormPaneWithTabs tabs = new UIFormPaneWithTabs();
		tabs.addHomeTab(new OverviewPane());
		tabs.addTab(message(locale, "connectorForm.tab.connection"), new SettingsPane());
		tabs.addTab(message(locale, "connectorForm.tab.flows"), new FlowPane());
		pBody.setContent(tabs);
	}

	private static UserMessage message(final Locale pLocale, final String pKey)
	{ return UserMessage.createInfo(Messages.get(ConnectorForm.class, pLocale, pKey)); }
	private static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{ return Messages.get(ConnectorForm.class, pLocale, pKey, pArgs); }

	private static void stylesheet(final UIFormPaneWriter pWriter, final UIFormContext pContext)
	{
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "connect/connector-form.css"))
			.add("?v=4\"/>");
		contextualReturn(pWriter, pContext.getCurrentRecord(), StorageScope.userId(pContext.getSession()));
	}

	/**
	 * Restores the custom catalog only when this record was explicitly launched by that catalog: a card
	 * leaves a token scoped to its record id; the "New connection" button leaves a {@code create} token
	 * with no id, accepted while the form has no record yet and narrowed to the record's id once it has
	 * been saved (so the token never outlives the record it was left for).
	 */
	private static void contextualReturn(final UIFormPaneWriter pWriter, final Adaptation pRecord, final String pUserId)
	{
		final String recordId = FacetedPage.jsLiteral(text(pRecord, ConnectorsPaths.Connector.ID));
		pWriter.addJS_cr(StorageScope.purgeJs(pUserId));
		pWriter.addJS_cr("(function(){if(document.documentElement.dataset.ftccReturnBound)return;"
			+ "var key=" + StorageScope.jsKey(pUserId, "ftccRecordOrigin") + ",topWindow=window.top,state;try{state=JSON.parse(sessionStorage.getItem(key)||'null');}catch(e){sessionStorage.removeItem(key);return;}"
			+ "if(!state||!state.url)return;if(state.create){if(" + recordId + "){state={id:" + recordId + ",url:state.url};sessionStorage.setItem(key,JSON.stringify(state));}}"
			+ "else if(state.id!==" + recordId + ")return;var target;try{target=new URL(state.url,topWindow.location.origin);}catch(e){sessionStorage.removeItem(key);return;}"
			+ "if(target.origin!==topWindow.location.origin||target.pathname.indexOf('/ebx-ui/')!==0)return;"
			+ "document.documentElement.dataset.ftccReturnBound='true';"
			+ "function bind(){var bar=document.querySelector('.ebx_ActionsBack');if(!bar)return false;"
			+ "var buttons=bar.querySelectorAll('button[type=button]');if(!buttons.length)return false;"
			+ "var close=buttons[buttons.length-1];close.addEventListener('click',function(event){"
			+ "event.preventDefault();event.stopImmediatePropagation();sessionStorage.removeItem(key);topWindow.location.href=target.href;},true);return true;}"
			+ "if(!bind()){var observer=new MutationObserver(function(){if(bind())observer.disconnect();});"
			+ "observer.observe(document.documentElement,{childList:true,subtree:true});}})();");
	}

	private static void fields(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Path[] pPaths)
	{
		final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
		pWriter.startTableFormRow();
		for (final Path path : pPaths) { if (root.getNode(path) != null) { pWriter.addFormRow(path); } }
		pWriter.endTableFormRow();
	}

	private static String text(final Adaptation pRecord, final Path pPath)
	{
		if (pRecord == null) { return ""; }
		/*
		 * Mapping identifiers are numeric in the connector schema whereas most
		 * form fields are strings.  Adaptation#getString casts the stored value,
		 * which made the "Run" affordance fail while rendering a connector that
		 * has a mapping.  Use the schema value's display form instead so URI
		 * construction works for both scalar kinds.
		 */
		final Object value = pRecord.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}

	private static int count(final Adaptation pRecord, final Path pTable, final String pField)
	{
		if (pRecord == null) { return 0; }
		final String id = text(pRecord, ConnectorsPaths.Connector.ID).replace("'", "''");
		final Request request = pRecord.getContainer().getTable(pTable).createRequest();
		request.setXPathFilter("./" + pField + "='" + id + "'");
		final RequestResult result = request.execute();
		int count = 0;
		try { while (result.nextAdaptation() != null) { count++; } }
		finally { result.close(); }
		return count;
	}

	private static List<Adaptation> flows(final Adaptation pRecord)
	{
		final List<Adaptation> flows = new ArrayList<Adaptation>();
		if (pRecord == null) { return flows; }
		final String id = text(pRecord, ConnectorsPaths.Connector.ID).replace("'", "''");
		final Request request = pRecord.getContainer().getTable(ConnectorsPaths.MAPPING).createRequest();
		request.setXPathFilter("./connector='" + id + "'");
		final RequestResult result = request.execute();
		try
		{
			Adaptation flow;
			while ((flow = result.nextAdaptation()) != null) { flows.add(flow); }
		}
		finally { result.close(); }
		return flows;
	}

	static String flowRunURI(final Adaptation pFlow)
	{
		final String dataspace = text(pFlow, ConnectorsPaths.Mapping.DATASPACE);
		final String dataset = text(pFlow, ConnectorsPaths.Mapping.DATASET);
		final String table = text(pFlow, ConnectorsPaths.Mapping.TABLE_PATH);
		final String mappingId = resolveMappingId(pFlow);
		if (dataspace.isEmpty() || dataset.isEmpty() || table.isEmpty()) { return ""; }
		return MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/" + pathSegment("B" + dataspace) + "/" + pathSegment(dataset)
			+ table + "?service=ebx-connectors@" + ConnectorSyncServiceDeclaration.SERVICE_NAME + "&serviceMode=TABLE_VIEW"
			+ (mappingId.isEmpty() ? "" : "&serviceParams=" + queryValue(serviceParams(ConnectorSyncServiceDeclaration.PARAM_MAPPING, mappingId)));
	}

	/**
	 * The ebx-ui routes drop a service's plain query parameters: {@code &mapping=91} opened the sync
	 * service on the first mapping instead (seen 2026-09-14). They travel in {@code serviceParams}, the
	 * base64 of a JSON object, which EBX honors on {@code ui/advanced} and {@code ui/custom/<key>} alike.
	 */
	private static String serviceParams(final String pName, final String pValue)
	{
		final String json = "{" + jsonString(pName) + ":" + jsonString(pValue) + "}";
		return java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String jsonString(final String pValue)
	{
		return "\"" + pValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/**
	 * A flow's {@code Mapping/id} (auto-increment {@code xs:int}) as a string, with the same
	 * fallback chain {@link #flowRunURI} always needed: some historical mapping records expose
	 * their generated numeric id only through the occurrence key, and a handful only through the
	 * saved {@link MappingDefinition} matching this flow's own dataspace/dataset/table/connector/
	 * operation/direction (an incomplete flow stays non-runnable; the caller gets "" and omits the
	 * handoff rather than guessing).
	 */
	static String resolveMappingId(final Adaptation pFlow)
	{
		String mappingId = text(pFlow, ConnectorsPaths.Mapping.ID);
		if (mappingId.isEmpty()) { mappingId = pFlow.getOccurrencePrimaryKey().format(); }
		if (mappingId.isEmpty())
		{
			try
			{
				final ConnectorRepository repo = ConnectorService.get().repository(pFlow.getHome().getRepository());
				final MappingDefinition.Direction direction = MappingDefinition.Direction.valueOf(
					text(pFlow, ConnectorsPaths.Mapping.DIRECTION));
				final MappingDefinition resolved = repo.mappingFor(text(pFlow, ConnectorsPaths.Mapping.DATASPACE),
					text(pFlow, ConnectorsPaths.Mapping.DATASET), text(pFlow, ConnectorsPaths.Mapping.TABLE_PATH),
					text(pFlow, ConnectorsPaths.Mapping.CONNECTOR), text(pFlow, ConnectorsPaths.Mapping.OPERATION), direction);
				if (resolved != null) { mappingId = String.valueOf(resolved.getId()); }
			}
			catch (final IllegalArgumentException ignored)
			{
				// An incomplete flow stays non-runnable; the caller omits the handoff.
			}
		}
		return mappingId;
	}

	/**
	 * A flow's own {@code Mapping} record, opened via its registered recordForm ({@code MappingForm})
	 * directly in the modern workspace ("Edit flow"). Never through {@code UIHttpManagerComponent}
	 * against the legacy {@code /ebx/} manager: that bridge nests a hybrid legacy/modern/legacy frame
	 * stack, and the record's own native Close then revokes the whole hybrid sub-session and drops the
	 * entire tab back to the login page (found live 2026-09-11 on this exact button -- reproducible,
	 * not a one-off). {@code Mapping/id} is an auto-increment {@code xs:int} ({@code osd:autoIncrement}),
	 * so the predicate is unquoted -- confirmed against a real record's own EBX-generated URL, which
	 * addressed it as {@code ./id=67}, not {@code ./id='67'}.
	 */
	static String mappingRecordURI(final Adaptation pFlow)
	{
		return mappingWorkspaceURI(pFlow, "");
	}

	/**
	 * The same record with its Mapping Designer service opened on it ("Design/test") -- same bridge
	 * bug, reproduced live on this button 2026-09-12, same fix. {@code service=...&serviceMode=RECORD}
	 * next to {@code workspaceDisplay=record:...} is the query shape EBX itself emits for a
	 * record-scoped service (confirmed against the live nested frame's own URL). A table-scoped
	 * service on the same URL would instead win outright and drop the record (found live 2026-09-11),
	 * which is why the catalog's own record links never combine the two.
	 */
	static String mappingDesignerURI(final Adaptation pFlow)
	{
		return mappingWorkspaceURI(pFlow,
			"&service=" + queryValue(MappingDesignerServiceDeclaration.SERVICE_NAME) + "&serviceMode=RECORD");
	}

	/** Modern-workspace URL of a flow's Mapping record, or the bare workspace when its id can't be resolved. */
	private static String mappingWorkspaceURI(final Adaptation pFlow, final String pQuerySuffix)
	{
		if (pFlow == null || pFlow.getContainer() == null) { return MappingForm.EBX_UI_BASE_URI; }
		final String mappingId = resolveMappingId(pFlow);
		if (mappingId.isEmpty()) { return MappingForm.EBX_UI_BASE_URI; }
		final Adaptation dataset = pFlow.getContainer();
		return MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/"
			+ pathSegment(dataset.getHome().getKey().format()) + "/"
			+ pathSegment(dataset.getAdaptationName().getStringName()) + ConnectorsPaths.MAPPING.format()
			+ "?workspaceDisplay=" + queryValue("record:./id=" + mappingId) + pQuerySuffix;
	}

	private static String pathSegment(final String pValue)
	{
		try { return java.net.URLEncoder.encode(pValue, "UTF-8").replace("+", "%20"); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	private static String queryValue(final String pValue)
	{
		try { return java.net.URLEncoder.encode(pValue, "UTF-8").replace("+", "%20"); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	/** Descriptor of the currently selected implementation; null keeps the form usable for unknown extension types. */
	private static ConnectorDescriptor descriptor(final Adaptation pRecord)
	{
		final String type = text(pRecord, ConnectorsPaths.Connector.TYPE);
		if (type.isEmpty()) { return null; }
		try { return ConnectorRegistry.get().describeType(type).getDescriptor(); }
		catch (final ConnectorException ex) { return null; }
		catch (final RuntimeException ex) { return null; }
		catch (final LinkageError ex) { return null; }
	}

	private static boolean isPostgreSql(final Adaptation pRecord)
	{
		return "postgresql".equalsIgnoreCase(text(pRecord, ConnectorsPaths.Connector.TYPE));
	}

	private static boolean isSampleFile(final Adaptation pRecord)
	{
		return "sample-file".equalsIgnoreCase(text(pRecord, ConnectorsPaths.Connector.TYPE));
	}

	private static final Path[] SAMPLE_DATA = { ConnectorsPaths.Connector.SAMPLE_DATA };

	/** Sample-file connector setup: the upload field itself, in place of an endpoint and credentials. */
	private static void sampleFileSetup(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Locale pLocale)
	{
		final Adaptation record = pContext.getCurrentRecord();
		if (!isSampleFile(record)) { return; }
		pWriter.add("<section class=\"ftcn-pg\"><header><span class=\"ftcn-pg-mark\">CSV</span><div><h2>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.sampleFile.title"))).add("</h2><p>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.sampleFile.help"))).add("</p></div></header>");
		fields(pWriter, pContext, SAMPLE_DATA);
		pWriter.add("</section>");
	}

	/** PostgreSQL guidance only; values remain governed by the shared EBX connector model. */
	private static void postgreSqlSetup(final UIFormPaneWriter pWriter, final Adaptation pRecord,
		final Map<String, Object> pValues, final Locale pLocale)
	{
		if (!isPostgreSql(pRecord)) { return; }
		final String url = text(pRecord, ConnectorsPaths.Connector.BASE_URL).trim();
		final String username = text(pRecord, ConnectorsPaths.Connector.USERNAME).trim();
		final boolean jndi = pValues.containsKey("jndi") && String.valueOf(pValues.get("jndi")).trim().length() > 0;
		final boolean endpointReady = jndi || url.startsWith("jdbc:postgresql://");
		final boolean identityReady = jndi || !username.isEmpty();
		pWriter.add("<section class=\"ftcn-pg\"><header><span class=\"ftcn-pg-mark\">PG</span><div><h2>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.pg.title"))).add("</h2><p>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.pg.help"))).add("</p></div></header>")
			.add("<div class=\"ftcn-pg-grid\"><div><b>").add(MappingForm.escape(text(pLocale, "connectorForm.pg.url")))
			.add("</b><code>jdbc:postgresql://host:5432/database</code><small>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.pg.url.help"))).add("</small></div><div><b>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.pg.schema"))).add("</b><code>schema = public</code><small>")
			.add(MappingForm.escape(text(pLocale, "connectorForm.pg.schema.help"))).add("</small></div></div>")
			.add("<div class=\"ftcn-readiness\"><span class=\"").add(endpointReady ? "ready" : "attention").add("\">")
			.add(endpointReady ? "&#10003; " : "! ").add(MappingForm.escape(text(pLocale, endpointReady
				? "connectorForm.pg.endpoint.ready" : "connectorForm.pg.endpoint.missing"))).add("</span><span class=\"")
			.add(identityReady ? "ready" : "attention").add("\">").add(identityReady ? "&#10003; " : "! ")
			.add(MappingForm.escape(text(pLocale, identityReady ? "connectorForm.pg.identity.ready" : "connectorForm.pg.identity.missing")))
			.add("</span><span class=\"neutral\">&#9432; ").add(MappingForm.escape(text(pLocale, "connectorForm.pg.driver")))
			.add("</span></div></section>");
	}

	/** Stored free properties plus core authentication mode for descriptor visibility evaluation. */
	private static Map<String, Object> propertyValues(final Adaptation pRecord)
	{
		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		if (pRecord == null) { return values; }
		final String authentication = text(pRecord, ConnectorsPaths.Connector.AUTH_TYPE);
		values.put("authentication", authentication);
		values.put("authType", authentication);
		final String id = text(pRecord, ConnectorsPaths.Connector.ID).replace("'", "''");
		if (id.isEmpty()) { return values; }
		final Request request = pRecord.getContainer().getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest();
		request.setXPathFilter("./connector='" + id + "'");
		final RequestResult result = request.execute();
		try
		{
			Adaptation property;
			while ((property = result.nextAdaptation()) != null)
			{
				final String key = property.getString(ConnectorsPaths.ConnectorProperty.KEY);
				if (key != null) { values.put(key, property.getString(ConnectorsPaths.ConnectorProperty.VALUE)); }
			}
		}
		finally { result.close(); }
		return values;
	}


	/**
	 * Presents the descriptor contract next to the native association editor. Values remain edited
	 * by EBX so permissions and persistence are unchanged; secret values are never emitted as HTML.
	 */
	private static void propertyDefinitions(final UIFormPaneWriter pWriter, final ConnectorDescriptor pDescriptor,
		final Map<String, Object> pValues, final Locale pLocale)
	{
		if (pDescriptor == null || pDescriptor.getConfigurationProperties().isEmpty()) { return; }
		pWriter.add("<div class=\"ftcn-flow-help\"><strong>").add(MappingForm.escape(text(pLocale, "connectorForm.expected"))).add("</strong><span>");
		boolean rendered = false;
		for (final ConfigurationPropertyDefinition property : DescriptorConfiguration.visibleProperties(pDescriptor, pValues))
		{
			if (rendered) { pWriter.add("<br/>"); }
			pWriter.add("<b>").add(MappingForm.escape(property.getLabel())).add("</b> <small>")
				.add(MappingForm.escape(property.getType().name().toLowerCase(Locale.ENGLISH)));
			if (property.isRequired()) { pWriter.add(" &middot; ").add(MappingForm.escape(text(pLocale, "connectorForm.required"))); }
			if (property.isSecret()) { pWriter.add(" &middot; ").add(MappingForm.escape(text(pLocale, "connectorForm.secret"))); }
			if (property.isAdvanced()) { pWriter.add(" &middot; ").add(MappingForm.escape(text(pLocale, "connectorForm.advanced"))); }
			if (pValues.containsKey(property.getName())) { pWriter.add(" &middot; ").add(MappingForm.escape(text(pLocale, "connectorForm.configured"))); }
			else if (property.getDefaultValue() != null && !property.isSecret())
			{
				pWriter.add(" &middot; ").add(MappingForm.escape(text(pLocale, "connectorForm.default", property.getDefaultValue())));
			}
			pWriter.add("</small>");
			if (property.getDescription() != null) { pWriter.add(" — ").add(MappingForm.escape(property.getDescription())); }
			rendered = true;
		}
		if (!rendered) { pWriter.add(MappingForm.escape(text(pLocale, "connectorForm.noProperties"))); }
		pWriter.add("</span></div>");
	}

	private static void diagnosticsAction(final UIFormPaneWriter pWriter, final UIFormContext pContext)
	{
		final Locale locale = pContext.getLocale();
		pWriter.add("<section class=\"ftcn-panel\"><header><span class=\"ftcn-icon\">&#10003;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.validate"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.validate.help"))).add("</p></div></header>");
		if (pContext.isCreatingRecord() || pContext.getCurrentRecord() == null)
		{
			pWriter.add("<div class=\"ftcn-empty\">").add(MappingForm.escape(text(locale, "connectorForm.saveDiagnostics"))).add("</div>");
		}
		else
		{
			final UIHttpManagerComponent component = UIHttpManagerComponent.createWithBaseURI(MappingForm.EBX_BASE_URI);
			component.selectInstanceOrOccurrence(pContext.getCurrentRecord());
			component.setService(ConnectorDiagnosticsServiceDeclaration.SERVICE_KEY);
			final UIButtonSpecJSAction open = pWriter.buildButtonPreview(component.getURIWithParameters(),
				message(locale, "connectorForm.openDiagnostics"));
			pWriter.addButtonJavaScript(open);
			pWriter.add("<div class=\"ftcn-flow-help\" style=\"margin-top:12px\"><span>").add(MappingForm.escape(text(locale, "connectorForm.diagnostics.help"))).add("</span></div>");
		}
		pWriter.add("</section>");
	}

	private static void connectionActions(final UIFormPaneWriter pWriter, final UIFormContext pContext)
	{
		final Locale locale = pContext.getLocale();
		if (pContext.isCreatingRecord() || pContext.getCurrentRecord() == null)
		{
			pWriter.add("<div class=\"ftcn-connection-actions ftcn-empty\">").add(MappingForm.escape(text(locale, "connectorForm.saveConnection"))).add("</div>");
			return;
		}
		final UIHttpManagerComponent component = UIHttpManagerComponent.createWithBaseURI(MappingForm.EBX_BASE_URI);
		component.selectInstanceOrOccurrence(pContext.getCurrentRecord());
		component.setService(ConnectorDiagnosticsServiceDeclaration.SERVICE_KEY);
		// Keep the manager bridge attached to this already-open record form. A redirect to
		// /ebx-ui here makes EBX render a second full workspace inside the bridge after
		// diagnostics Close, rather than returning to this form.
		final String uri = component.getURIWithParameters();
		// each button proves to the diagnostics service that its action was asked from this form, by this user, for
		// this connector: the same URL without the proof opens the page and runs nothing
		final com.orchestranetworks.service.Session user = pContext.getSession();
		final String id = pContext.getCurrentRecord().getString(ConnectorsPaths.Connector.ID);
		final UIButtonSpecJSAction test = pWriter.buildButtonPreview(ConnectorCatalogUserService.appendQuery(uri, "ftcdAction=test" + DiagnosticsLaunch.proof(user, id, "test")),
			message(locale, "connectorForm.testConnection"));
		final boolean postgreSql = isPostgreSql(pContext.getCurrentRecord());
		final ConnectorConnectionStateService.State state = connectionState(pContext);
		final UIButtonSpecJSAction connect = pWriter.buildButtonPreview(ConnectorCatalogUserService.appendQuery(uri, "ftcdAction=connect" + DiagnosticsLaunch.proof(user, id, "connect")),
			message(locale, postgreSql ? "connectorForm.connectDiscover" : "connectorForm.connect"));
		pWriter.add("<div class=\"ftcn-connection-actions\"><strong>").add(MappingForm.escape(text(locale, "connectorForm.connectionActions"))).add("</strong>");
		pWriter.addButtonJavaScript(test);
		pWriter.addButtonJavaScript(connect);
		if (state.getStatus() != ConnectorConnectionStateService.Status.NOT_CONNECTED)
		{
			final UIButtonSpecJSAction disconnect = pWriter.buildButtonPreview(ConnectorCatalogUserService.appendQuery(uri, "ftcdAction=disconnect" + DiagnosticsLaunch.proof(user, id, "disconnect")),
				message(locale, "connectorForm.disconnect"));
			pWriter.addButtonJavaScript(disconnect);
		}
		if (postgreSql)
		{
			final UIButtonSpecJSAction discover = pWriter.buildButtonPreview(ConnectorCatalogUserService.appendQuery(uri, "ftcdAction=preview" + DiagnosticsLaunch.proof(user, id, "preview")),
				message(locale, "connectorForm.discoverDatabase"));
			pWriter.addButtonJavaScript(discover);
		}
		pWriter.add("<span class=\"ftcn-state ftcn-state-").add(state.getStatus().name().toLowerCase(Locale.ENGLISH)).add("\"><b>")
			.add(MappingForm.escape(text(locale, "connectorForm.state." + state.getStatus().name()))).add("</b>");
		if (state.getVerifiedAt() != null)
		{
			pWriter.add(" <small>").add(MappingForm.escape(text(locale, "connectorForm.verifiedAt",
				DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(state.getVerifiedAt())))).add("</small>");
		}
		pWriter.add("</span><span>").add(MappingForm.escape(text(locale, "connectorForm.connectionActions.help"))).add("</span></div>");
	}

	/**
	 * Live, read-only egress-allow-list posture for this connector — computed on every render, never
	 * cached, never guessed: it reflects whatever ebx.connectors.allowedHosts resolves to right now.
	 * Distinguishes "no admin has set up the allow-list yet" from "the list exists but has gone stale
	 * relative to this connector" (the security review of September 2026, finding 07) — the human who
	 * maintains the list needs a different action in each case.
	 */
	private static void egressPosture(final UIFormPaneWriter pWriter, final UIFormContext pContext)
	{
		if (pContext.isCreatingRecord() || pContext.getCurrentRecord() == null)
		{
			return;
		}
		final ConnectorConfig config;
		try
		{
			final ConnectorRepository repository = new ConnectorRepository(pContext.getCurrentDataSet().getHome().getRepository());
			config = repository.connector(text(pContext.getCurrentRecord(), ConnectorsPaths.Connector.ID));
		}
		catch (final RuntimeException ex)
		{
			return;
		}
		if (config == null)
		{
			return;
		}
		final Locale locale = pContext.getLocale();
		final Egress.Posture posture = Egress.posture(config);
		final String css = posture == Egress.Posture.COVERED ? "covered" : posture == Egress.Posture.NOT_COVERED ? "notcovered" : "notenforced";
		final String key = posture == Egress.Posture.COVERED ? "connectorForm.egress.covered"
			: posture == Egress.Posture.NOT_COVERED ? "connectorForm.egress.notCovered" : "connectorForm.egress.notEnforced";
		pWriter.add("<div class=\"ftcn-egress ftcn-egress-").add(css).add("\"><b>").add(escapeIcon(posture)).add("</b><div><span>")
			.add(MappingForm.escape(text(locale, key))).add("</span>");
		if (posture != Egress.Posture.COVERED)
		{
			// Live, specific, copy-pasteable — not a generic "go read the docs" pointer: the admin
			// looking at THIS connector's record sees exactly where the list lives today (property,
			// file, or unset) and exactly what to add for this connector's own host(s).
			final String source = Egress.source();
			final String example = Egress.example(config);
			pWriter.add("<code class=\"ftcn-egress-code\">")
				.add(MappingForm.escape(text(locale, "connectorForm.egress.source", source == null ? text(locale, "connectorForm.egress.source.none") : source)));
			if (example != null)
			{
				pWriter.add("<br/>").add(MappingForm.escape(text(locale, "connectorForm.egress.add", example)));
			}
			pWriter.add("</code>");
		}
		pWriter.add("</div></div>");
	}

	private static String escapeIcon(final Egress.Posture pPosture)
	{
		return pPosture == Egress.Posture.COVERED ? "&#10003;" : "&#9888;";
	}

	private static ConnectorConnectionStateService.State connectionState(final UIFormContext pContext)
	{
		if (pContext.getCurrentRecord() == null)
			return new ConnectorConnectionStateService.State(ConnectorConnectionStateService.Status.NOT_CONNECTED, null, null, null);
		try
		{
			final ConnectorRepository repository = new ConnectorRepository(pContext.getCurrentDataSet().getHome().getRepository());
			final String id = text(pContext.getCurrentRecord(), ConnectorsPaths.Connector.ID);
			return new ConnectorConnectionStateService(new EbxConnectorConnectionStateStore(repository, pContext.getSession()))
				.status(repository.connector(id));
		}
		catch (final RuntimeException ex)
		{
			return new ConnectorConnectionStateService.State(ConnectorConnectionStateService.Status.NOT_CONNECTED, null, null, null);
		}
	}

	private static final class OverviewPane implements UIFormPane
	{
		@Override
		public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			stylesheet(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			final Adaptation record = pContext.getCurrentRecord();
			final String id = text(record, ConnectorsPaths.Connector.ID);
			final int properties = count(record, ConnectorsPaths.CONNECTOR_PROPERTY, "connector");
			final int mappings = count(record, ConnectorsPaths.MAPPING, "connector");

			pWriter.add("<div class=\"ftcn-grid\"><section class=\"ftcn-panel ftcn-wide\"><header><span class=\"ftcn-icon\">&#9672;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.identity"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.identity.help"))).add("</p></div></header>");
			fields(pWriter, pContext, IDENTITY);
			pWriter.add("</section><aside><section class=\"ftcn-panel\"><header><span class=\"ftcn-icon\">&#8644;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.summary"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.summary.help"))).add("</p></div></header>")
				.add("<div class=\"ftcn-summary\"><div><span>").add(MappingForm.escape(text(locale, "connectorForm.id"))).add("</span><b>").add(MappingForm.escape(id.isEmpty() ? text(locale, "connectorForm.assignedOnSave") : id)).add("</b></div>")
				.add("<div><span>").add(MappingForm.escape(text(locale, "connectorForm.coverage"))).add("</span><b>").add(MappingForm.escape(mappings == 0 ? text(locale, "connectorForm.noFlows") : text(locale, "connectorForm.flowCount", Integer.valueOf(mappings)))).add("</b></div>")
				.add("<div><span>").add(MappingForm.escape(text(locale, "connectorForm.configuration"))).add("</span><b>").add(MappingForm.escape(properties == 0 ? text(locale, "connectorForm.defaults") : text(locale, "connectorForm.propertyCount", Integer.valueOf(properties)))).add("</b></div></div></section>")
				.add("<section class=\"ftcn-panel ftcn-brand\"><header><span class=\"ftcn-icon\">&#10022;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.branding"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.branding.help"))).add("</p></div></header>");
			fields(pWriter, pContext, BRANDING);
			pWriter.add("</section></aside></div>");
		}
	}

	private static final class SettingsPane implements UIFormPane
	{
		@Override public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			stylesheet(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			final Adaptation record = pContext.getCurrentRecord();
			final ConnectorDescriptor descriptor = descriptor(record);
			final Map<String, Object> values = propertyValues(record);
			pWriter.add("<div class=\"ftcn-grid\"><section class=\"ftcn-panel ftcn-wide\"><header><span class=\"ftcn-icon\">&#128279;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.endpoint"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.endpoint.help"))).add("</p></div></header>");
			postgreSqlSetup(pWriter, record, values, locale);
			sampleFileSetup(pWriter, pContext, locale);
			/* Credential guide: the native rows keep a stable id each, the page script follows the
			 * native "Authentication" widget and shows the pane of the selected mode. */
			final ConnectorCredentialsPane credentials = new ConnectorCredentialsPane(pContext, descriptor, values);
			credentials.writeFields(pWriter);
			connectionActions(pWriter, pContext);
			pWriter.add("</section><aside>");
			credentials.writeGuide(pWriter);
			pWriter.add("<section class=\"ftcn-panel\"><header><span class=\"ftcn-icon\">&#9201;</span><div><h2>").add(MappingForm.escape(text(locale, "connectorForm.runtime"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.runtime.help"))).add("</p></div></header>");
			fields(pWriter, pContext, OPERATIONS);
			pWriter.add("</section>");
			diagnosticsAction(pWriter, pContext);
			pWriter.add("</aside></div><div class=\"ftcn-security\"><b>").add(MappingForm.escape(text(locale, "connectorForm.security"))).add("</b><span>").add(MappingForm.escape(text(locale, "connectorForm.security.help"))).add("</span></div>");
			egressPosture(pWriter, pContext);
			pWriter.add("<div class=\"ftcn-association ftcn-properties\"><header><h2>").add(MappingForm.escape(text(locale, "connectorForm.properties"))).add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.properties.help"))).add("</p></header>");
			propertyDefinitions(pWriter, descriptor, values, locale);
			if (pContext.isCreatingRecord()) { pWriter.add("<div class=\"ftcn-empty\">").add(MappingForm.escape(text(locale, "connectorForm.saveProperties"))).add("</div>"); }
			else { pWriter.addWidget(PROPERTIES); }
			pWriter.add("</div>");
			credentials.writeScript(pWriter);
		}
	}

	private static final class FlowPane implements UIFormPane
	{
		@Override public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			stylesheet(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			final Adaptation connector = pContext.getCurrentRecord();
			pWriter.add("<div class=\"ftcn-association\"><header><h2>").add(MappingForm.escape(text(locale, "connectorForm.flows.title")))
				.add("</h2><p>").add(MappingForm.escape(text(locale, "connectorForm.flows.help"))).add("</p></header>");
			pWriter.add("<div class=\"ftcn-flow-help\"><strong>").add(MappingForm.escape(text(locale, "connectorForm.manageFlow"))).add("</strong><span>").add(MappingForm.escape(text(locale, "connectorForm.manageFlow.help"))).add("</span></div>");
			if (pContext.isCreatingRecord() || connector == null)
			{
				pWriter.add("<div class=\"ftcn-empty\">").add(MappingForm.escape(text(locale, "connectorForm.saveRelated"))).add("</div></div>");
				return;
			}
			final String userId = StorageScope.userId(pContext.getSession());
			pWriter.addJS_cr(StorageScope.purgeJs(userId));
			pWriter.addJS_cr("try{sessionStorage.removeItem(" + StorageScope.jsKey(userId, FLOW_RETURN_KEY) + ");}catch(e){}");
			pWriter.add("<div class=\"ftcn-flow-create\"><div><strong>").add(MappingForm.escape(text(locale, "connectorForm.flows.create")))
				.add("</strong><span>").add(MappingForm.escape(text(locale, "connectorForm.flows.create.help")))
				.add("</span></div>");
			/* Opens the Mapping creation form directly (the route of the table's own "+", seen live
			 * 2026-09-16) and remembers this connector so the form starts with it selected
			 * (MappingForm.createPrefill); EBX offers no URL parameter to preset a field. */
			pWriter.addButtonJavaScript(workspaceButton(mappingTableURI(connector) + "?service=@creation", message(locale, "connectorForm.flows.create.action"),
				MappingForm.CREATE_CONNECTOR_KEY, connector.getOccurrencePrimaryKey().format(), userId));
			pWriter.add("</div>");
			final List<Adaptation> flows = flows(connector);
			if (flows.isEmpty()) { pWriter.add("<div class=\"ftcn-empty\">").add(MappingForm.escape(text(locale, "connectorForm.flows.empty"))).add("</div>"); }
			else
			{
				pWriter.add("<div class=\"ftcn-flow-grid\">");
				for (final Adaptation flow : flows)
				{
					final String label = text(flow, ConnectorsPaths.Mapping.LABEL);
					final String operation = text(flow, ConnectorsPaths.Mapping.OPERATION);
					final String direction = text(flow, ConnectorsPaths.Mapping.DIRECTION);
					final String dataspace = text(flow, ConnectorsPaths.Mapping.DATASPACE);
					final String dataset = text(flow, ConnectorsPaths.Mapping.DATASET);
					final String tablePath = text(flow, ConnectorsPaths.Mapping.TABLE_PATH);
					final boolean active = Boolean.TRUE.equals(flow.get(ConnectorsPaths.Mapping.ACTIVE));
					pWriter.add("<article class=\"ftcn-flow-card\"><div class=\"ftcn-flow-card-head\"><span class=\"ftcn-direction ")
						.add("OUTBOUND".equalsIgnoreCase(direction) ? "out" : "in").add("\">").add(MappingForm.escape(direction)).add("</span><span class=\"ftcn-flow-state ")
						.add(active ? "active" : "inactive").add("\">").add(MappingForm.escape(text(locale, active ? "connectorForm.flows.active" : "connectorForm.flows.inactive"))).add("</span></div><h3>")
						.add(MappingForm.escape(label.isEmpty() ? flow.getOccurrencePrimaryKey().format() : label)).add("</h3><p class=\"ftcn-flow-operation\"><b>").add(MappingForm.escape(operation)).add("</b></p>")
						.add("<dl class=\"ftcn-flow-target\"><div><dt>").add(MappingForm.escape(text(locale, "connectorForm.flows.dataspace"))).add("</dt><dd>")
						.add(MappingForm.escape(dataspace.isEmpty() ? "—" : dataspace)).add("</dd></div><div><dt>")
						.add(MappingForm.escape(text(locale, "connectorForm.flows.dataset"))).add("</dt><dd>").add(MappingForm.escape(dataset.isEmpty() ? "—" : dataset)).add("</dd></div><div><dt>")
						.add(MappingForm.escape(text(locale, "connectorForm.flows.table"))).add("</dt><dd>").add(MappingForm.escape(tablePath.isEmpty() ? "—" : tablePath)).add("</dd></div></dl><div class=\"ftcn-flow-actions\">");

					final String flowId = resolveMappingId(flow);
					pWriter.addButtonJavaScript(workspaceButton(mappingRecordURI(flow), message(locale, "connectorForm.flows.edit"), FLOW_RETURN_KEY, flowId, userId));
					pWriter.addButtonJavaScript(workspaceButton(mappingDesignerURI(flow), message(locale, "connectorForm.flows.designTest"), FLOW_RETURN_KEY, flowId, userId));
					final String runURI = flowRunURI(flow);
					if (!runURI.isEmpty()) { pWriter.addButtonJavaScript(workspaceButton(runURI, message(locale, "connectorForm.flows.run"), SERVICE_RETURN_KEY, flowId, userId)); }
					pWriter.add("<span class=\"ftcn-flow-activation\">").add(MappingForm.escape(text(locale, "connectorForm.flows.activationHint"))).add("</span></div></article>");
				}
				pWriter.add("</div>");
			}
			/* The native Mapping table retains EBX's standard Create (+) action without
			 * embedding its association widget in this custom tab. */
			pWriter.add("<div class=\"ftcn-native-flows\"><h3>").add(MappingForm.escape(text(locale, "connectorForm.flows.native"))).add("</h3><p>")
				.add(MappingForm.escape(text(locale, "connectorForm.flows.native.help"))).add("</p></div>");
			pWriter.add("</div>");
		}
	}

	/**
	 * A flow-card button that navigates the top window to a full ebx-ui workspace route. Never
	 * buildButtonPreview: its modal inner pop-up is an iframe, and an /ebx-ui/ui/advanced/data/ route
	 * loaded there renders a whole second EBX shell (header, toolbar, and the Connectors dataset's
	 * table tree on the left) inside the perspective (found live 2026-09-14 on "Edit flow"). When the
	 * connector was opened from a perspective (/ui/custom/<key>/), the route is rewritten onto that
	 * perspective so its own menu stays; outside any perspective the generic browser is kept.
	 * With a return key, the connector page is remembered in sessionStorage under that key before
	 * leaving, so the flow's own Close ({@link MappingForm}) or the run service's Back comes back here
	 * instead of stopping on the Mapping table.
	 */
	private static UIButtonSpecJSAction workspaceButton(final String pURI, final UserMessage pLabel, final String pReturnKey, final String pReturnId, final String pUserId)
	{
		final String remember = pReturnKey == null ? ""
			: "try{sessionStorage.setItem(" + StorageScope.jsKey(pUserId, pReturnKey) + ",JSON.stringify({id:" + FacetedPage.jsLiteral(pReturnId) + ",url:t.location.href,at:Date.now()}));}catch(e){}";
		return new UIButtonSpecJSAction(pLabel,
			"(function(u){var t=window.top,m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(t.location.pathname);" + remember
				+ "if(m){u=u.split('/ui/advanced/data/').join('/ui/custom/'+m[1]+'/data/');}t.location.href=u;})(" + FacetedPage.jsLiteral(pURI) + ")");
	}

	/** sessionStorage key name (stored as {@code <name>:<user>}, see {@link StorageScope}) read by {@link MappingForm} to return from a flow to its connector. */
	static final String FLOW_RETURN_KEY = "ftcnFlowReturn";
	/** sessionStorage key name (per user, see {@link StorageScope}) read by the Back button of {@link ConnectorSyncUserService}. */
	private static final String SERVICE_RETURN_KEY = "ftServiceReturn";

	/**
	 * "Create or manage a data flow": the Connectors dataset's Mapping table in the modern workspace,
	 * where EBX's own Create (+) action lives. Formerly a buildButtonPreview of the legacy /ebx/
	 * creation service -- the same modal-iframe bridge that nested a second EBX shell behind the
	 * flow-card buttons (2026-09-14).
	 */
	private static String mappingTableURI(final Adaptation pConnector)
	{
		final Adaptation dataset = pConnector.getContainer();
		return MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/"
			+ pathSegment(dataset.getHome().getKey().format()) + "/"
			+ pathSegment(dataset.getAdaptationName().getStringName()) + ConnectorsPaths.MAPPING.format();
	}
}
