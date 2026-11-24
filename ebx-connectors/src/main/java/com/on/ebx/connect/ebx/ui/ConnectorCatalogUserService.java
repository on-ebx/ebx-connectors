package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorConnectionStateService;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorStatus;
import com.on.ebx.connect.core.ConnectorStatusEvaluation;
import com.on.ebx.connect.core.ConnectorStatusEvaluator;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.ebx.EbxConnectorConnectionStateStore;
import com.on.ebx.connect.ebx.Favorites;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceAjaxContext;
import com.orchestranetworks.userservice.UserServiceAjaxRequest;
import com.orchestranetworks.userservice.UserServiceAjaxResponse;
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

/**
 * Custom connector catalog. The record set comes from the filtered EBX table-view selection, so
 * native quick search, advanced criteria, saved views and permissions are respected. The facets
 * (favorites, category, readiness, connection), the advanced filters and the sort, laid out by
 * {@link FacetedPage} like the data catalog's marketplace, are an additional client-side refinement of
 * that already-filtered result. The star of a card pins the connector for the current user
 * ({@link Favorites}).
 */
public final class ConnectorCatalogUserService implements UserService<TableViewEntitySelection>
{
	private static final String UNCATEGORIZED = "uncategorized";
	private Adaptation dataset;
	private TableViewEntitySelection selection;
	private Locale locale = Locale.ENGLISH;

	/** The star: toggles the favorite of one card (see {@link Favorites#toggle}) and answers its new state. */
	private final UserServiceAjaxRequest favoriteRequest = new UserServiceAjaxRequest()
	{
		@Override
		public void processRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
		{
			final Favorites.Outcome outcome = Favorites.toggle(ConnectorCatalogUserService.this.dataset, pContext.getSession(), pContext.getParameter("type"), pContext.getParameter("key"));
			final Locale userLocale = pContext.getSession() == null ? Locale.ENGLISH : pContext.getSession().getLocale();
			pResponse.getWriter().add(Favorites.json(outcome, outcome.error == null ? null : FavoriteStars.errorText(userLocale, outcome.error)));
		}
	};

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder)
	{
		this.selection = pContext.getEntitySelection();
		this.dataset = pContext.getEntitySelection().getDataset();
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(ConnectorCatalogUserService.class, this.locale, "catalog.label"));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				ConnectorCatalogUserService.this.writeCatalog(pWriter, pPaneContext.getSession());
			}
		});
	}

	private void writeCatalog(final UserServicePaneWriter pWriter, final Session pSession)
	{
		final Map<String, Category> categories = this.categories();
		final List<Adaptation> connectors = rows(this.selection.getFilteredRecords());
		final int total = rows(this.selection.getAllRecords()).size();
		this.addFallbackCategory(categories, connectors);
		final ConnectorRepository repository = new ConnectorRepository(this.dataset.getHome().getRepository());
		final ConnectorConnectionStateService connectionStates = new ConnectorConnectionStateService(
			new EbxConnectorConnectionStateStore(repository, pSession));
		Collections.sort(connectors, Comparator.comparing(a -> text(a, ConnectorsPaths.Connector.LABEL), String.CASE_INSENSITIVE_ORDER));

		final String nativeView = MappingForm.EBX_UI_BASE_URI + "ui/advanced/data/"
			+ pathSegment(this.dataset.getHome().getKey().format()) + "/"
			+ pathSegment(this.dataset.getAdaptationName().getStringName()) + ConnectorsPaths.CONNECTOR.format();
		final String catalogView = appendQuery(nativeView,
			"service=" + ConnectorCatalogServiceDeclaration.SERVICE_NAME + "&serviceMode=TABLE_VIEW");

		/*
		 * The catalog is an overview, not a validation gate. A single connector can be in the middle of
		 * an edit, upgrade, or credential reload; it must never make every other catalog card disappear.
		 * Resolve each card independently and show a conservative setup-needed state when its runtime
		 * configuration cannot be read.
		 */
		final Map<String, Integer> mappingCounts = this.mappingCounts();
		final Set<String> favorites = Favorites.keys(this.dataset, Favorites.login(pSession), Favorites.CONNECTOR);
		final List<Card> cards = new ArrayList<Card>();
		for (final Adaptation connector : connectors)
		{
			final Card card = new Card();
			card.id = text(connector, ConnectorsPaths.Connector.ID);
			card.key = connector.getOccurrencePrimaryKey().format();
			card.category = categories.get(categoryCode(connector, categories));
			card.label = fallback(text(connector, ConnectorsPaths.Connector.LABEL), card.id, message("catalog.unnamed"));
			card.description = text(connector, ConnectorsPaths.Connector.DESCRIPTION);
			card.type = text(connector, ConnectorsPaths.Connector.TYPE);
			final String logoUrl = text(connector, ConnectorsPaths.Connector.LOGO_URL);
			card.image = logoUrl.isEmpty() ? Logos.defaultUrl() : logoUrl;
			card.auth = fallback(text(connector, ConnectorsPaths.Connector.AUTH_TYPE), "NONE");
			card.trust = number(connector.get(ConnectorsPaths.Connector.TRUST), 0);
			card.pricing = fallback(text(connector, ConnectorsPaths.Connector.PRICING), "UNKNOWN");
			card.pricingNote = text(connector, ConnectorsPaths.Connector.PRICING_NOTE);
			final Integer mappings = mappingCounts.get(card.id);
			card.mappings = mappings == null ? 0 : mappings.intValue();
			final com.on.ebx.connect.spi.ConnectorConfig config = connectorConfig(repository, card.id);
			card.readiness = readiness(config);
			card.connection = connection(connectionStates, config);
			card.favorite = favorites.contains(card.key);
			// Keep the record in the same modern workspace as the catalog. Crossing through the
			// legacy /ebx/ manager creates a separate hybrid sub-session; native Close revokes it
			// and the browser is then sent to the login page. workspaceDisplay opens the normal
			// record form via the table's own recordForm (ConnectorForm) -- no service/tableView
			// parameter here. Tried twice and both were wrong: a "tableView"-named saved view
			// (a PersistedTableViewDefinition this module never creates) makes the platform answer
			// "The view [X] was not found" (found live 2026-09-11); a "service" query parameter naming ConnectorCatalog on the
			// SAME url as workspaceDisplay=record:... makes the service win outright, so the link
			// just re-shows the catalog list instead of opening the record (found live the same
			// day, right after "fixing" the first bug). Contextual Close-return to the catalog is a
			// SEPARATE, already-working mechanism (sessionStorage + postMessage interception in
			// ConnectorForm's embedded script, see ConnectorCatalogReturnContractTest) -- it doesn't
			// need any hint on this URL at all.
			final String recordURI = appendQuery(nativeView,
				"workspaceDisplay=" + queryValue("record:./id='" + card.id.replace("'", "''") + "'"));
			card.recordURI = recordURI;
			cards.add(card);
		}

		// filter vocabularies present in the data (the page counts the cards itself)
		final String userId = StorageScope.userId(pSession);
		final FacetedPage page = new FacetedPage(this.locale, userId);
		final Map<String, String> categoryValues = new LinkedHashMap<String, String>();
		final Map<String, String> statusValues = new LinkedHashMap<String, String>();
		final Map<String, String> connectionValues = new LinkedHashMap<String, String>();
		final Map<String, String> authValues = new LinkedHashMap<String, String>();
		final Map<String, String> pricingValues = new LinkedHashMap<String, String>();
		final Map<String, String> usageValues = new LinkedHashMap<String, String>();
		for (final Category category : categories.values())
		{
			for (final Card card : cards) if (card.category == category)
			{
				categoryValues.put(category.code, category.label);
				break;
			}
		}
		for (final String[] state : new String[][] { { "ready", "catalog.status.ready" }, { "setup", "catalog.status.setup" }, { "inactive", "catalog.status.inactive" }, { "unavailable", "catalog.status.unavailable" } })
		{
			for (final Card card : cards) if (state[0].equals(card.readiness.css))
			{
				statusValues.put(state[0], message(state[1]));
				break;
			}
		}
		for (final String[] state : new String[][] { { "connected", "catalog.connection.connected" }, { "stale", "catalog.connection.stale" }, { "failed", "catalog.connection.failed" }, { "not-connected", "catalog.connection.notConnected" } })
		{
			for (final Card card : cards) if (state[0].equals(card.connection.css))
			{
				connectionValues.put(state[0], message(state[1]));
				break;
			}
		}
		for (final String auth : new String[] { "NONE", "BASIC", "API_KEY_HEADER", "BEARER", "OAUTH2" })
		{
			for (final Card card : cards) if (auth.equals(card.auth))
			{
				authValues.put(auth, message("catalog.auth." + auth));
				break;
			}
		}
		for (final String pricing : new String[] { "FREE", "FREEMIUM", "PAID", "UNKNOWN" })
		{
			for (final Card card : cards) if (pricing.equals(card.pricing))
			{
				pricingValues.put(pricing, message("catalog.pricing." + pricing));
				break;
			}
		}
		usageValues.put("used", message("catalog.usage.used"));
		usageValues.put("unused", message("catalog.usage.unused"));

		FacetedPage.stylesheets(pWriter);
		pWriter.add("<span id=\"ftcxFavEndpoint\" hidden data-url=\"").add(escape(pWriter.getURLForAjaxRequest(this.favoriteRequest))).add("\"></span>");
		pWriter.add("<div class=\"ftcc ftcx ftcx-connectors\"").add(StorageScope.attribute(userId)).add(">");
		// tools: search, sort, advanced filters; facets on the left
		pWriter.add("<div class=\"ftcc-tools\"><div class=\"ftcc-search-row\">");
		page.railToggle(pWriter);
		pWriter.add("<label class=\"ftcc-search\"><span>&#128269;</span><input id=\"ftccSearch\" type=\"search\" placeholder=\"").add(escape(message("catalog.search"))).add("\"/></label>");
		final Map<String, String> sorts = new LinkedHashMap<String, String>();
		sorts.put("name", message("catalog.sort.name"));
		sorts.put("mappings", message("catalog.sort.mappings"));
		sorts.put("trust", message("catalog.sort.trust"));
		page.sortControl(pWriter, "ftccSort", sorts);
		page.advancedToggle(pWriter);
		// "New connection": the standard creation form of the Connector table (rendered by ConnectorForm), on
		// the same ebx-ui route as the cards so the perspective rewrite below keeps it inside the perspective;
		// target="_top" for the same iframe reason as the advanced link. Same shape as the "create a
		// mapping" button of ConnectorForm (?service=@creation on the table's ebx-ui route, seen working live).
		pWriter.add("<a class=\"ftcc-create\" href=\"").add(escape(appendQuery(nativeView, "service=@creation"))).add("\" target=\"_top\" data-return-url=\"")
			.add(escape(catalogView)).add("\">+ ")
			.add(escape(message("catalog.create"))).add("</a>");
		pWriter.add("</div>");
		page.openAdvanced(pWriter);
		page.pillRow(pWriter, "auth", message("catalog.filter.auth"), authValues);
		page.pillRow(pWriter, "pricing", message("catalog.filter.pricing"), pricingValues);
		page.pillRow(pWriter, "usage", message("catalog.filter.usage"), usageValues);
		// the native table view stays one click away; when it already filters the table, say so next to the link
		final StringBuilder links = new StringBuilder();
		if (connectors.size() != total)
		{
			links.append("<span class=\"ftcc-result-count\">").append(escape(Messages.get(ConnectorCatalogUserService.class, this.locale, "catalog.countFiltered",
				Integer.valueOf(connectors.size()), Integer.valueOf(total)))).append("</span>");
		}
		// target="_top": the catalog runs in the perspective's web-component iframe, and without it this
		// link loads the raw ui/advanced/data/ route (a full, perspective-agnostic EBX shell) INSIDE that
		// iframe, nesting a second header/nav under the perspective's own -- same family of bug as the
		// buildButtonPreview trap. The .ftcc-link card links already do this; this one didn't.
		links.append("<a class=\"ftcc-advanced\" href=\"").append(escape(nativeView)).append("\" target=\"_top\">").append(escape(message("catalog.advanced"))).append("</a>");
		page.openRail(pWriter, links.toString());
		final Map<String, String> mine = new LinkedHashMap<String, String>();
		mine.put("1", message("favorite.mine"));
		page.facet(pWriter, "fav", message("favorite.filter"), mine, 1);
		page.facet(pWriter, "category", message("catalog.filter.category"), categoryValues, 8);
		page.facet(pWriter, "status", message("catalog.filter.status"), statusValues, 4);
		page.facet(pWriter, "connection", message("catalog.filter.connection"), connectionValues, 4);
		page.openMain(pWriter, message("catalog.empty"));

		int groupOrder = 0;
		for (final Category category : categories.values())
		{
			boolean opened = false;
			for (final Card card : cards)
			{
				if (card.category != category) { continue; }
				if (!opened)
				{
					page.openGroup(pWriter, category.code, groupOrder++, false);
					pWriter.add(escape(category.label));
					page.closeGroupHeading(pWriter, message("catalog.connectorsWord"), category.description);
					opened = true;
				}
				this.writeCard(pWriter, card, catalogView);
			}
			if (opened) { page.closeGroup(pWriter); }
		}
		page.closeMain(pWriter);
		pWriter.add("</div>");
		// This catalog (and the record links it generates) render on a hardcoded ui/advanced/data/
		// base -- EBX's generic, perspective-agnostic data browser, not the perspective the user
		// actually came from. No Java API exposes "which perspective is this UserService running
		// under" (found live 2026-09-11 -- there is no such concept in the perspective/menuItem
		// model, see [[ebx-perspective-automation-plan]]), so this is detected client-side instead:
		// ui/custom/<perspectiveKey>/data/... is a real, equally-valid base that keeps the record
		// (and the "back to catalog" link) inside whatever perspective's own navigation shell the
		// catalog itself was opened from -- confirmed live, both for workspaceDisplay=record:...
		// and for service=...&serviceMode=TABLE_VIEW. Falls back to the generic browser untouched
		// when this service is opened outside any perspective (e.g. Administration > Data), where
		// window.top's path has no /ui/custom/<key>/ segment to rewrite onto.
		pWriter.addJS_cr("(function(){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "document.querySelectorAll('a[href*=\"'+o+'\"]').forEach(function(a){a.setAttribute('href',a.getAttribute('href').split(o).join(n));});"
			+ "document.querySelectorAll('[data-return-url*=\"'+o+'\"]').forEach(function(el){el.setAttribute('data-return-url',el.getAttribute('data-return-url').split(o).join(n));});})();");
		pWriter.addJS_cr(page.script(".ftcx-connectors", "ftccSearch", "ftccSort"));
		// the star never opens the record (FavoriteStars stops the click), so it never leaves a return token either
		// the return token and the rail / advanced-filter preferences belong to the EBX user (StorageScope): another user's keys
		// and the unkeyed ones of the earlier versions are removed here, at opening
		pWriter.addJS_cr(StorageScope.purgeJs(userId));
		pWriter.addJS_cr("(function(){var key=" + StorageScope.jsKey(userId, "ftccRecordOrigin") + ";sessionStorage.removeItem(key);var root=document.querySelector('.ftcx-connectors'),F=root.ftFacets;"
			+ "root.querySelectorAll('.ftcc-card').forEach(function(card){card.addEventListener('click',function(){sessionStorage.setItem(key,JSON.stringify({id:card.dataset.recordId,url:card.dataset.returnUrl}));});});"
			// the creation form has no record id yet: a create token, narrowed by ConnectorForm once the record is saved
			+ "var create=root.querySelector('.ftcc-create');if(create){create.addEventListener('click',function(){sessionStorage.setItem(key,JSON.stringify({create:true,url:create.dataset.returnUrl}));});}"
			+ FavoriteStars.JS + "})();");
	}

	private void writeCard(final UserServicePaneWriter pWriter, final Card pCard, final String pCatalogView)
	{
		final String pricingLabel = message("catalog.pricing." + pCard.pricing);
		final String search = (pCard.label + " " + pCard.description + " " + pCard.type + " " + pCard.id + " " + pCard.category.label + " " + pCard.readiness.label + " "
			+ pCard.connection.label + " " + pricingLabel).toLowerCase(java.util.Locale.ENGLISH);
		// The card is an article, not a link: the star is a button, and a button inside a link is not valid. The link
		// covers the card content; one data-<filter> attribute per facet (see FacetedPage).
		pWriter.add("<article class=\"ftcc-card ftcc-state-").add(pCard.readiness.css).add(" ftcc-connection-").add(pCard.connection.css).add("\" data-category=\"")
			.add(escape(pCard.category.code)).add("\" data-fav=\"").add(pCard.favorite ? "1" : "0").add("\" data-status=\"").add(pCard.readiness.css).add("\" data-connection=\"").add(pCard.connection.css)
			.add("\" data-auth=\"").add(escape(pCard.auth)).add("\" data-pricing=\"").add(escape(pCard.pricing)).add("\" data-usage=\"").add(pCard.mappings > 0 ? "used" : "unused").add("\" data-name=\"").add(escape(pCard.label.toLowerCase(this.locale)))
			.add("\" data-mappings=\"").add(String.valueOf(pCard.mappings)).add("\" data-trust=\"").add(String.valueOf(pCard.trust)).add("\" data-search=\"").add(escape(search))
			.add("\" data-record-id=\"").add(escape(pCard.id)).add("\" data-return-url=\"").add(escape(pCatalogView)).add("\">")
			.add("<a class=\"ftcc-link\" href=\"").add(escape(pCard.recordURI)).add("\" target=\"_top\">")
			.add("<div class=\"ftcc-logo\"><img loading=\"lazy\" src=\"").add(escape(pCard.image)).add("\" onerror=\"this.onerror=null;this.src='")
			.add(escape(Logos.defaultUrl())).add("'\" alt=\"\"/><span class=\"ftcc-status\">").add(escape(pCard.readiness.label)).add("</span><span class=\"ftcc-connection-status\">")
			.add(escape(pCard.connection.label)).add("</span></div><div class=\"ftcc-body\"><div class=\"ftcc-meta\"><span>")
			.add(escape(pCard.category.label)).add("</span><code>").add(escape(fallback(pCard.type, message("catalog.unknownType")))).add("</code>");
		if (!"UNKNOWN".equals(pCard.pricing))
		{
			pWriter.add("<span class=\"ftcc-pricing ftcc-pricing-").add(pCard.pricing.toLowerCase(java.util.Locale.ENGLISH)).add("\" title=\"")
				.add(escape(pCard.pricingNote)).add("\">").add(escape(pricingLabel)).add("</span>");
		}
		pWriter.add("</div><h3>").add(escape(pCard.label)).add("</h3><p>")
			.add(escape(pCard.description)).add("</p><div class=\"ftcc-id\">").add(escape(pCard.id));
		if (pCard.mappings > 0)
		{
			pWriter.add(" &#183; ").add(escape(Messages.get(ConnectorCatalogUserService.class, this.locale, pCard.mappings == 1 ? "catalog.mapping" : "catalog.mappings", Integer.valueOf(pCard.mappings))));
		}
		pWriter.add("</div></div></a>").add(FavoriteStars.button(this.locale, Favorites.CONNECTOR, pCard.key, pCard.favorite)).add("</article>");
	}

	/** How many mappings use each connector (the "used by a mapping" filter and the sort). Empty when the table cannot be read. */
	private Map<String, Integer> mappingCounts()
	{
		final Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
		try
		{
			for (final Adaptation mapping : rows(this.dataset.getTable(ConnectorsPaths.MAPPING)))
			{
				final String connector = text(mapping, ConnectorsPaths.Mapping.CONNECTOR);
				if (connector.isEmpty()) { continue; }
				final Integer n = counts.get(connector);
				counts.put(connector, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
			}
		}
		catch (final RuntimeException unavailable)
		{
			// an overview, not a gate: without the counts every connector reads as unused
		}
		return counts;
	}

	static String appendQuery(final String pURI, final String pQuery)
	{
		return pURI + (pURI.indexOf('?') >= 0 ? '&' : '?') + pQuery;
	}

	static String queryValue(final String pValue)
	{
		try { return java.net.URLEncoder.encode(pValue, "UTF-8").replace("+", "%20"); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	static String pathSegment(final String pValue)
	{
		try { return java.net.URLEncoder.encode(pValue, "UTF-8").replace("+", "%20"); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	private Map<String, Category> categories()
	{
		final List<Adaptation> values = rows(this.dataset.getTable(ConnectorsPaths.CONNECTOR_CATEGORY));
		Collections.sort(values, Comparator.comparingInt(a -> number(a.get(ConnectorsPaths.ConnectorCategory.DISPLAY_ORDER))));
		final Map<String, Category> result = new LinkedHashMap<String, Category>();
		for (final Adaptation row : values)
		{
			final Category category = new Category(text(row, ConnectorsPaths.ConnectorCategory.CODE), text(row, ConnectorsPaths.ConnectorCategory.LABEL),
				text(row, ConnectorsPaths.ConnectorCategory.DESCRIPTION));
			result.put(category.code, category);
		}
		return result;
	}

	private void addFallbackCategory(final Map<String, Category> pCategories, final List<Adaptation> pConnectors)
	{
		for (final Adaptation connector : pConnectors)
		{
			final String code = text(connector, ConnectorsPaths.Connector.CATEGORY);
			if (code.isEmpty() || !pCategories.containsKey(code))
			{
				pCategories.put(UNCATEGORIZED, new Category(UNCATEGORIZED, message("catalog.other"), message("catalog.other.description")));
				return;
			}
		}
	}

	private static String categoryCode(final Adaptation pConnector, final Map<String, Category> pCategories)
	{
		final String code = text(pConnector, ConnectorsPaths.Connector.CATEGORY);
		return code.isEmpty() || !pCategories.containsKey(code) ? UNCATEGORIZED : code;
	}

	private ConnectorState readiness(final com.on.ebx.connect.spi.ConnectorConfig pConfig)
	{
		if (pConfig == null) { return new ConnectorState("setup", message("catalog.status.setup")); }
		try
		{
			final ConnectorStatusEvaluation evaluation = ConnectorStatusEvaluator.evaluate(pConfig, ConnectorRegistry.get());
			if (evaluation.getStatus() == ConnectorStatus.INACTIVE) { return new ConnectorState("inactive", message("catalog.status.inactive")); }
			if (evaluation.getStatus() == ConnectorStatus.UNAVAILABLE) { return new ConnectorState("unavailable", message("catalog.status.unavailable")); }
			if (evaluation.getStatus() == ConnectorStatus.MISCONFIGURED) { return new ConnectorState("setup", message("catalog.status.setup")); }
			return new ConnectorState("ready", message("catalog.status.ready"));
		}
		catch (final RuntimeException unavailable)
		{
			return new ConnectorState("setup", message("catalog.status.setup"));
		}
	}

	private ConnectorState connection(final ConnectorConnectionStateService pStates,
		final com.on.ebx.connect.spi.ConnectorConfig pConfig)
	{
		if (pConfig == null) { return new ConnectorState("not-connected", message("catalog.connection.notConnected")); }
		try
		{
			switch (pStates.status(pConfig).getStatus())
			{
			case CONNECTED: return new ConnectorState("connected", message("catalog.connection.connected"));
			case STALE: return new ConnectorState("stale", message("catalog.connection.stale"));
			case FAILED: return new ConnectorState("failed", message("catalog.connection.failed"));
			case NOT_CONNECTED:
			default: return new ConnectorState("not-connected", message("catalog.connection.notConnected"));
			}
		}
		catch (final RuntimeException unavailable)
		{
			// Keep the catalog usable while an administrator is reloading an upgraded data model.
			return new ConnectorState("not-connected", message("catalog.connection.notConnected"));
		}
	}

	private static com.on.ebx.connect.spi.ConnectorConfig connectorConfig(final ConnectorRepository pRepository, final String pId)
	{
		try { return pRepository.connector(pId); }
		catch (final RuntimeException unavailable) { return null; }
	}

	private String message(final String pKey) { return Messages.get(ConnectorCatalogUserService.class, this.locale, pKey); }

	private static List<Adaptation> rows(final AdaptationTable pTable)
	{
		return rows(pTable.createRequest());
	}

	private static List<Adaptation> rows(final Request pRequest)
	{
		final List<Adaptation> result = new ArrayList<Adaptation>();
		final RequestResult request = pRequest.execute();
		try { for (Adaptation row = request.nextAdaptation(); row != null; row = request.nextAdaptation()) { result.add(row); } }
		finally { request.close(); }
		return result;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = pRow.getString(pPath);
		return value == null ? "" : value;
	}

	private static int number(final Object pValue) { return number(pValue, 999); }
	private static int number(final Object pValue, final int pDefault) { return pValue instanceof Number ? ((Number) pValue).intValue() : pDefault; }
	private static String escape(final String pValue) { return MappingForm.escape(pValue == null ? "" : pValue); }
	private static String fallback(final String pValue, final String... pFallbacks)
	{
		if (pValue != null && !pValue.trim().isEmpty()) { return pValue; }
		for (final String fallback : pFallbacks) { if (fallback != null && !fallback.trim().isEmpty()) { return fallback; } }
		return "";
	}

	private static final class Category
	{
		final String code, label, description;
		Category(final String pCode, final String pLabel, final String pDescription)
		{
			this.code = pCode;
			this.label = pLabel;
			this.description = pDescription;
		}
	}

	/** What one card shows and is filtered on, resolved once per connector. */
	private static final class Card
	{
		String id, key, label, description, type, image, auth, pricing, pricingNote, recordURI;
		Category category;
		ConnectorState readiness, connection;
		int mappings, trust;
		boolean favorite;
	}

	private static final class ConnectorState
	{
		final String css, label;
		ConnectorState(final String pCss, final String pLabel)
		{
			this.css = pCss;
			this.label = pLabel;
		}
	}

	@Override public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome) { return pOutcome; }
	@Override public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext) { }
}
