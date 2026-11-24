package com.on.ebx.connect.ebx;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Installs the packaged "Data connector" advanced perspective ({@code DataConnector.perspective.json}) at
 * repository startup, when no perspective with that key exists. An existing perspective is never modified,
 * so the menu can be edited in Administration without being overwritten at the next restart.
 *
 * <p>A perspective is a child dataset of the {@code ebx-manager} root dataset; creating it has no REST or
 * EBX Script API, only {@link ProcedureContext#doCreateChild}. Repeatable groups of a menu item
 * ({@code label/localizedDocumentations}, {@code action/serviceParameters}) map to a {@code java.util.List}
 * of occurrences built with {@link SchemaNode#createNewOccurrence()}. When {@code ebx-manager} is not
 * available yet, installation is retried a few times in the background.
 *
 * <p>One exception to "never modified": the About entry (service {@link #ABOUT_SERVICE_KEY}). When the perspective exists
 * but has no menu item for that service, it is added at the end of the last top-level section, once, at the next start; an
 * item that is there (wherever a user moved it) is never touched or duplicated, and nothing else of the perspective is
 * read or written. A user who deletes the About entry sees it again at the next start.
 *
 * <p>{@code -Debx.connectors.perspective=false} disables the installation.
 */
public final class PerspectiveInstaller
{
	static final String RESOURCE = "DataConnector.perspective.json";
	/** The service of the About page: the menu item that is added to a perspective installed before it existed. */
	static final String ABOUT_SERVICE_KEY = "ebx-connectors@/WEB-INF/ebx/schemas/Connectors.xsd@About";

	private static final HomeKey EBX_MANAGER_HOME = HomeKey.forBranchName("ebx-manager");
	private static final AdaptationName EBX_MANAGER_ROOT = AdaptationName.forName("ebx-manager");
	private static final Path MENU_ITEM_TABLE = Path.parse("/domain/menuItem");
	private static final Path PROPERTIES_ACTIVATED = Path.parse("/domain/properties/activated");
	private static final Path PROPERTIES_DEFAULT_SELECTION = Path.parse("/domain/properties/defaultSelection");
	private static final Path MI_ID = Path.parse("id");
	private static final Path MI_TYPE = Path.parse("type");
	private static final Path MI_PARENT = Path.parse("parent");
	private static final Path MI_ORDER = Path.parse("order");
	private static final Path MI_HAS_TOP_SEPARATOR = Path.parse("hasTopSeparator");
	private static final Path MI_ICON_TYPE = Path.parse("icon/type");
	private static final Path MI_ICON_REFERENCE = Path.parse("icon/reference");
	private static final Path MI_ACTION_TYPE = Path.parse("action/type");
	private static final Path MI_ACTION_SERVICE_KEY = Path.parse("action/serviceKey");
	private static final Path MI_ACTION_SERVICE_PARAMETERS = Path.parse("action/serviceParameters");
	private static final Path MI_LABEL_DOCS = Path.parse("label/localizedDocumentations");

	private static final int ATTEMPTS = 10;
	private static final long RETRY_SECONDS = 30L;

	private PerspectiveInstaller() { }

	public static void install(final Repository pRepository, final Session pSession, final LoggingCategory pLog)
	{
		if ("false".equalsIgnoreCase(System.getProperty("ebx.connectors.perspective", "true").trim()))
		{
			pLog.info("[connectors] perspective installation disabled (ebx.connectors.perspective=false)");
			return;
		}
		final JsonNode definition;
		try
		{
			definition = PerspectiveInstaller.definition();
		}
		catch (final Exception ex)
		{
			pLog.error("[connectors] packaged perspective unreadable: " + ex);
			return;
		}
		if (PerspectiveInstaller.attempt(pRepository, pSession, pLog, definition, false))
		{
			return;
		}
		// The retries end by themselves after the last attempt; handed to BackgroundWork so that a stop of
		// the module in the meantime does not leave their thread behind.
		final ScheduledExecutorService executor = com.on.ebx.connect.core.BackgroundWork.track("perspective installation",
			new java.util.concurrent.ScheduledThreadPoolExecutor(1, com.on.ebx.connect.core.BackgroundWork.daemonThreads("ebx-connectors-perspective", false)));
		executor.schedule(new Runnable()
		{
			private int attempt = 1;

			@Override public void run()
			{
				this.attempt++;
				final boolean last = this.attempt >= PerspectiveInstaller.ATTEMPTS;
				if (PerspectiveInstaller.attempt(pRepository, pSession, pLog, definition, last) || last)
				{
					executor.shutdown();
					return;
				}
				executor.schedule(this, PerspectiveInstaller.RETRY_SECONDS, TimeUnit.SECONDS);
			}
		}, PerspectiveInstaller.RETRY_SECONDS, TimeUnit.SECONDS);
	}

	static JsonNode definition() throws java.io.IOException
	{
		final InputStream input = PerspectiveInstaller.class.getResourceAsStream(PerspectiveInstaller.RESOURCE);
		if (input == null)
		{
			throw new java.io.FileNotFoundException(PerspectiveInstaller.RESOURCE);
		}
		final Scanner scanner = new Scanner(input, "UTF-8").useDelimiter("\\A");
		try
		{
			return Json.parse(scanner.hasNext() ? scanner.next() : "");
		}
		finally
		{
			scanner.close();
		}
	}

	/** @return true when nothing is left to do (installed, already present, or failed for good). */
	private static boolean attempt(final Repository pRepository, final Session pSession, final LoggingCategory pLog,
		final JsonNode pDefinition, final boolean pLastAttempt)
	{
		final String key = pDefinition.path("key").asText();
		try
		{
			final AdaptationHome home = pRepository.lookupHome(PerspectiveInstaller.EBX_MANAGER_HOME);
			final Adaptation parent = home == null ? null : home.findAdaptationOrNull(PerspectiveInstaller.EBX_MANAGER_ROOT);
			if (parent == null)
			{
				if (pLastAttempt)
				{
					pLog.warn("[connectors] perspective " + key + " not installed: the ebx-manager dataset is unavailable");
				}
				return false;
			}
			final Adaptation existing = home.findAdaptationOrNull(AdaptationName.forName(key));
			if (existing != null)
			{
				PerspectiveInstaller.ensureAbout(home, existing, pSession, pLog, pDefinition);
				return true;
			}
			final int[] count = { 0 };
			final String[] defaultId = { null };
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
			{
				@Override public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					// parent first, then the new child (the reverse order reports the child as a missing parent)
					final Adaptation child = pContext.doCreateChild(parent.getAdaptationName(), AdaptationReference.forPersistentName(key),
						Profile.ADMINISTRATOR);
					pContext.setInstanceLabel(child, UserMessage.createInfo(pDefinition.path("labelEn").asText(key)));
					final AdaptationTable menuItems = child.getTable(PerspectiveInstaller.MENU_ITEM_TABLE);
					count[0] = PerspectiveInstaller.createNodes(pContext, menuItems, pDefinition.path("items"), null, defaultId);
					final ValueContextForUpdate properties = pContext.getContext(child.getAdaptationName());
					properties.setValue(Boolean.valueOf(pDefinition.path("activated").asBoolean(true)), PerspectiveInstaller.PROPERTIES_ACTIVATED);
					if (defaultId[0] != null)
					{
						// foreign key to domain/menuItem: the item opened when the perspective is selected
						properties.setValue(defaultId[0], PerspectiveInstaller.PROPERTIES_DEFAULT_SELECTION);
					}
					pContext.doModifyContent(child, properties);
				}
			});
			if (result.hasFailed())
			{
				pLog.error("[connectors] perspective " + key + " not installed: " + result.getExceptionFullMessage(Locale.ENGLISH));
				return true;
			}
			pLog.info("[connectors] perspective " + key + " installed (" + count[0] + " menu items)");
			return true;
		}
		catch (final RuntimeException ex)
		{
			if (pLastAttempt)
			{
				pLog.error("[connectors] perspective " + key + " not installed: " + ex);
			}
			return false;
		}
	}

	/** A menu item as far as the About check needs it. */
	static final class MenuRow
	{
		final String id;
		final String type;
		final String parent;
		final String serviceKey;
		final int order;

		MenuRow(final String pId, final String pType, final String pParent, final String pServiceKey, final int pOrder)
		{
			this.id = pId;
			this.type = pType;
			this.parent = pParent == null || pParent.trim().isEmpty() ? null : pParent.trim();
			this.serviceKey = pServiceKey;
			this.order = pOrder;
		}
	}

	/** True when an item already opens the service, wherever it sits in the menu. */
	static boolean hasService(final List<MenuRow> pRows, final String pServiceKey)
	{
		for (final MenuRow row : pRows)
		{
			if (pServiceKey.equals(row.serviceKey))
			{
				return true;
			}
		}
		return false;
	}

	/** The top-level section drawn last (highest order), null when the menu has none. */
	static MenuRow lastSection(final List<MenuRow> pRows)
	{
		MenuRow last = null;
		for (final MenuRow row : pRows)
		{
			if ("section".equals(row.type) && row.parent == null && (last == null || row.order >= last.order))
			{
				last = row;
			}
		}
		return last;
	}

	/** The order that puts an item after every child of the parent. */
	static int orderAfterChildren(final List<MenuRow> pRows, final String pParentId)
	{
		int max = 0;
		for (final MenuRow row : pRows)
		{
			if (pParentId.equals(row.parent) && row.order > max)
			{
				max = row.order;
			}
		}
		return max + 1000;
	}

	/** The item of the packaged definition that opens the service (depth first), null when there is none. */
	static JsonNode findItem(final JsonNode pNodes, final String pServiceKey)
	{
		for (final JsonNode node : pNodes)
		{
			if (pServiceKey.equals(node.path("serviceKey").asText(null)))
			{
				return node;
			}
			final JsonNode inChildren = PerspectiveInstaller.findItem(node.path("children"), pServiceKey);
			if (inChildren != null)
			{
				return inChildren;
			}
		}
		return null;
	}

	private static List<MenuRow> readRows(final AdaptationTable pMenuItems)
	{
		final List<MenuRow> rows = new ArrayList<MenuRow>();
		final com.onwbp.adaptation.RequestResult result = pMenuItems.createRequestResult(null);
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				final Object id = row.get(PerspectiveInstaller.MI_ID);
				final Object order = row.get(PerspectiveInstaller.MI_ORDER);
				rows.add(new MenuRow(id == null ? null : String.valueOf(id), String.valueOf(row.get(PerspectiveInstaller.MI_TYPE)),
					PerspectiveInstaller.stringOrNull(row.get(PerspectiveInstaller.MI_PARENT)),
					PerspectiveInstaller.stringOrNull(row.get(PerspectiveInstaller.MI_ACTION_SERVICE_KEY)), order instanceof Number ? ((Number) order).intValue() : 0));
			}
		}
		finally
		{
			result.close();
		}
		return rows;
	}

	private static String stringOrNull(final Object pValue)
	{
		return pValue == null ? null : String.valueOf(pValue);
	}

	/** Adds the About item to a perspective that has none (see the class comment); logs and gives up on any failure, the perspective stays as it was. */
	private static void ensureAbout(final AdaptationHome pHome, final Adaptation pPerspective, final Session pSession, final LoggingCategory pLog,
		final JsonNode pDefinition)
	{
		final String key = pDefinition.path("key").asText();
		try
		{
			final JsonNode about = PerspectiveInstaller.findItem(pDefinition.path("items"), PerspectiveInstaller.ABOUT_SERVICE_KEY);
			final AdaptationTable menuItems = pPerspective.getTable(PerspectiveInstaller.MENU_ITEM_TABLE);
			final List<MenuRow> rows = PerspectiveInstaller.readRows(menuItems);
			if (about == null || PerspectiveInstaller.hasService(rows, PerspectiveInstaller.ABOUT_SERVICE_KEY))
			{
				pLog.info("[connectors] perspective " + key + " already present, left unchanged");
				return;
			}
			final MenuRow section = PerspectiveInstaller.lastSection(rows);
			if (section == null)
			{
				pLog.warn("[connectors] perspective " + key + " has no section: the About entry was not added");
				return;
			}
			final int order = PerspectiveInstaller.orderAfterChildren(rows, section.id);
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pHome).execute(new Procedure()
			{
				@Override public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					PerspectiveInstaller.createNode(pContext, menuItems, about, section.id, order);
				}
			});
			if (result.hasFailed())
			{
				pLog.error("[connectors] perspective " + key + ": the About entry was not added: " + result.getExceptionFullMessage(Locale.ENGLISH));
				return;
			}
			pLog.info("[connectors] perspective " + key + " already present, About entry added at the end of its last section; nothing else changed");
		}
		catch (final RuntimeException ex)
		{
			pLog.error("[connectors] perspective " + key + ": the About entry was not added: " + ex);
		}
	}

	private static int createNodes(final ProcedureContext pContext, final AdaptationTable pMenuItems, final JsonNode pNodes, final String pParentId,
		final String[] pDefaultId) throws Exception
	{
		int count = 0;
		int order = 1000;
		for (final JsonNode node : pNodes)
		{
			final int id = PerspectiveInstaller.createNode(pContext, pMenuItems, node, pParentId, order);
			if (node.path("default").asBoolean(false))
			{
				pDefaultId[0] = String.valueOf(id);
			}
			count += 1 + PerspectiveInstaller.createNodes(pContext, pMenuItems, node.path("children"), String.valueOf(id), pDefaultId);
			order += 1000;
		}
		return count;
	}

	private static int createNode(final ProcedureContext pContext, final AdaptationTable pMenuItems, final JsonNode pNode, final String pParentId,
		final int pOrder) throws Exception
	{
		final String type = pNode.path("type").asText();
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(pMenuItems);
		row.setValue(type, PerspectiveInstaller.MI_TYPE);
		if (pParentId != null)
		{
			row.setValue(pParentId, PerspectiveInstaller.MI_PARENT);
		}
		row.setValue(Integer.valueOf(pOrder), PerspectiveInstaller.MI_ORDER);
		row.setValue(Boolean.valueOf(pNode.path("hasTopSeparator").asBoolean(false)), PerspectiveInstaller.MI_HAS_TOP_SEPARATOR);

		final String iconCss = PerspectiveInstaller.text(pNode, "iconCss");
		final String iconUrl = PerspectiveInstaller.text(pNode, "iconUrl");
		if (iconCss != null || iconUrl != null)
		{
			row.setValue(iconCss != null ? "css" : "url", PerspectiveInstaller.MI_ICON_TYPE);
			row.setValue(iconCss != null ? iconCss : iconUrl, PerspectiveInstaller.MI_ICON_REFERENCE);
		}
		final String labelEn = PerspectiveInstaller.text(pNode, "labelEn");
		final String labelFr = PerspectiveInstaller.text(pNode, "labelFr");
		if (labelEn != null || labelFr != null)
		{
			final SchemaNode docsNode = row.getNode(PerspectiveInstaller.MI_LABEL_DOCS);
			final SchemaNode localeNode = docsNode.getNode(Path.parse("locale"));
			final SchemaNode labelNode = docsNode.getNode(Path.parse("label"));
			final List<Object> docs = new ArrayList<Object>();
			// the locale field maps to java.util.Locale, not String
			docs.add(PerspectiveInstaller.occurrence(docsNode, localeNode, Locale.forLanguageTag("en-US"), labelNode, labelEn));
			docs.add(PerspectiveInstaller.occurrence(docsNode, localeNode, Locale.forLanguageTag("fr-FR"), labelNode, labelFr != null ? labelFr : labelEn));
			row.setValue(docs, PerspectiveInstaller.MI_LABEL_DOCS);
		}
		if ("action".equals(type))
		{
			row.setValue("S", PerspectiveInstaller.MI_ACTION_TYPE);
			final SchemaNode paramsNode = row.getNode(PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
			final SchemaNode nameNode = paramsNode.getNode(Path.parse("name"));
			final SchemaNode valueNode = paramsNode.getNode(Path.parse("value"));
			final List<Object> params = new ArrayList<Object>();
			final String serviceKey = PerspectiveInstaller.text(pNode, "serviceKey");
			if (serviceKey != null)
			{
				row.setValue(serviceKey, PerspectiveInstaller.MI_ACTION_SERVICE_KEY);
				for (final JsonNode param : pNode.path("parameters"))
				{
					params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, param.path("name").asText(), valueNode, PerspectiveInstaller.text(param, "value")));
				}
			}
			else
			{
				// table shorthand: "Access data" on dataspace / dataset / xpath, labelled by the table itself
				row.setValue("ebx-root-1.0@default", PerspectiveInstaller.MI_ACTION_SERVICE_KEY);
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "service", valueNode, "@default"));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "branch", valueNode, PerspectiveInstaller.text(pNode, "dataspace")));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "instance", valueNode, PerspectiveInstaller.text(pNode, "dataset")));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "xpath", valueNode, PerspectiveInstaller.text(pNode, "xpath")));
			}
			row.setValue(params, PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
		}
		final Adaptation created = pContext.doCreateOccurrence(row, pMenuItems);
		return ((Number) created.get(PerspectiveInstaller.MI_ID)).intValue();
	}

	private static Object occurrence(final SchemaNode pGroupNode, final SchemaNode pFirstNode, final Object pFirstValue, final SchemaNode pSecondNode,
		final Object pSecondValue)
	{
		final Object occurrence = pGroupNode.createNewOccurrence();
		pFirstNode.executeWrite(pFirstValue, occurrence);
		if (pSecondValue != null)
		{
			pSecondNode.executeWrite(pSecondValue, occurrence);
		}
		return occurrence;
	}

	private static String text(final JsonNode pNode, final String pField)
	{
		final JsonNode value = pNode.get(pField);
		return value == null || value.isNull() || value.asText().trim().isEmpty() ? null : value.asText();
	}
}
