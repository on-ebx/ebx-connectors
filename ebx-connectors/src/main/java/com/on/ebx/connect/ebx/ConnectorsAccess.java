package com.on.ebx.connect.ebx;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.on.ebx.connect.ebx.ui.ConnectorSyncServiceDeclaration;
import com.on.ebx.connect.ebx.ui.Messages;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.selection.EntitySelection;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/**
 * Who may do what with the connectors: the one place every entry point asks (the MCP tools, whatever the
 * transport, the REST routes and the user services). Nothing is granted by default: a caller who is neither an
 * EBX administrator nor a member of one of the three roles below is refused everything.
 * <ul>
 * <li>{@link Right#READ} - see the connections (secrets masked), the mappings, a run and the log;</li>
 * <li>{@link Right#RUN} - run what a designer configured (synchronise, enrich, replay, stop, publish), read the
 * outbound documents and the conflicts, which carry record values, and arbitrate the conflicts;</li>
 * <li>{@link Right#MANAGE} - change the configuration, and call an operation of a connection directly, which
 * runs anything the connection allows with its stored credentials.</li>
 * </ul>
 * Each right includes the ones above it in this list. An administrator gives them in the table "Access rights"
 * of the Connectors dataset ({@link AccessRightTable}): one row maps a role that already exists in the directory
 * (or "Everyone") to a right. Without any row, three role names work out of the box: {@value #DEFAULT_VIEWER},
 * {@value #DEFAULT_OPERATOR} and {@value #DEFAULT_DESIGNER}
 * ({@code -Debx.connectors.role.viewer|operator|designer=<role name>} renames them;
 * {@code -Debx.connectors.adminRole}, the older name of the designer role, is still read). A session receives
 * the widest right any of its roles brings.
 * <p>
 * The system user (a workflow script task, a scheduled task, the module's own installers) passes: what it runs
 * was configured by somebody who had the right to configure it.
 */
public final class ConnectorsAccess
{
	/** What an entry point requires; declared from the narrowest to the widest. */
	public enum Right
	{
		READ, RUN, MANAGE;

		/** True when holding this right is enough for an entry point that asks {@code pNeeded}. */
		public boolean covers(final Right pNeeded)
		{
			return pNeeded != null && this.ordinal() >= pNeeded.ordinal();
		}

		/** The right of this name as the table stores it, null for anything else. */
		public static Right named(final String pName)
		{
			for (final Right right : Right.values())
			{
				if (right.name().equals(pName))
				{
					return right;
				}
			}
			return null;
		}
	}

	/** Where the rights given by an administrator come from: role of the directory (or "Everyone") -> right. */
	public interface Grants
	{
		Map<Profile, Right> byProfile();
	}

	private static volatile Grants grants = new AccessRightTable();

	/** Replaces the source of the administrator's rights (the table of the Connectors dataset by default). */
	static void use(final Grants pGrants)
	{
		ConnectorsAccess.grants = pGrants;
	}

	public static final String DEFAULT_VIEWER = "ConnectorsViewer";
	public static final String DEFAULT_OPERATOR = "ConnectorsOperator";
	public static final String DEFAULT_DESIGNER = "ConnectorsDesigner";
	public static final String PROPERTY_VIEWER = "ebx.connectors.role.viewer";
	public static final String PROPERTY_OPERATOR = "ebx.connectors.role.operator";
	public static final String PROPERTY_DESIGNER = "ebx.connectors.role.designer";
	/** The designer role under its first name, kept so that an existing installation is not silently opened or closed. */
	public static final String PROPERTY_DESIGNER_LEGACY = "ebx.connectors.adminRole";

	/** The right each MCP tool asks when its arguments do not raise it (see {@link #required(String, JsonNode)}). */
	private static final Map<String, Right> TOOL_RIGHTS;
	static
	{
		final Map<String, Right> rights = new HashMap<String, Right>();
		rights.put(ConnectorsTools.T_HELP, Right.READ);
		rights.put(ConnectorsTools.T_LIST, Right.READ);
		rights.put(ConnectorsTools.T_DESCRIBE, Right.READ);
		rights.put(ConnectorsTools.T_MAPPINGS, Right.READ);
		rights.put(ConnectorsTools.T_MAPPING, Right.READ);
		rights.put(ConnectorsTools.T_RUN, Right.READ);
		rights.put(ConnectorsTools.T_LOG, Right.READ);
		rights.put(ConnectorsTools.T_SOURCE, Right.READ);
		rights.put(ConnectorsTools.T_ENRICH, Right.RUN);
		rights.put(ConnectorsTools.T_SYNC, Right.RUN);
		rights.put(ConnectorsTools.T_STOP, Right.RUN);
		rights.put(ConnectorsTools.T_REPLAY, Right.RUN);
		rights.put(ConnectorsTools.T_LINEAGE, Right.RUN);
		rights.put(ConnectorsTools.T_PUBLISH, Right.RUN);
		rights.put(ConnectorsTools.T_OUTBOUND, Right.RUN);
		rights.put(ConnectorsTools.T_CONFLICTS, Right.RUN);
		rights.put(ConnectorsTools.T_DECIDE, Right.RUN);
		rights.put(ConnectorsTools.T_CALL, Right.MANAGE);
		rights.put(ConnectorsTools.T_UPSERT, Right.MANAGE);
		rights.put(ConnectorsTools.T_DELETE, Right.MANAGE);
		rights.put(ConnectorsTools.T_EXPORT, Right.MANAGE);
		rights.put(ConnectorsTools.T_IMPORT, Right.MANAGE);
		rights.put(ConnectorsTools.T_OPENAPI, Right.MANAGE);
		TOOL_RIGHTS = Collections.unmodifiableMap(rights);
	}

	private ConnectorsAccess()
	{
	}

	/**
	 * The right a tool asks before its arguments are known: what decides whether the tool is listed to a caller.
	 * A tool this class does not know asks {@link Right#MANAGE}, so that a tool added without a decision here is
	 * closed rather than open.
	 */
	public static Right required(final String pTool)
	{
		final Right right = ConnectorsAccess.TOOL_RIGHTS.get(pTool);
		return right == null ? Right.MANAGE : right;
	}

	/**
	 * The right one call of a tool asks. Two tools ask more with some arguments: describing the fields of an
	 * operation calls the remote system with the stored credentials, and applying a catalog source writes the
	 * configuration; both are then a designer's action.
	 */
	public static Right required(final String pTool, final JsonNode pArguments)
	{
		final Right base = ConnectorsAccess.required(pTool);
		if (ConnectorsTools.T_DESCRIBE.equals(pTool) && ConnectorsAccess.hasText(pArguments, "operation"))
		{
			return Right.MANAGE;
		}
		if (ConnectorsTools.T_SOURCE.equals(pTool) && ConnectorsAccess.isTrue(pArguments, "apply"))
		{
			return Right.MANAGE;
		}
		return base;
	}

	private static boolean hasText(final JsonNode pArguments, final String pName)
	{
		final JsonNode value = pArguments == null ? null : pArguments.get(pName);
		return value != null && !value.isNull() && !value.asText("").trim().isEmpty();
	}

	private static boolean isTrue(final JsonNode pArguments, final String pName)
	{
		final JsonNode value = pArguments == null ? null : pArguments.get(pName);
		return value != null && !value.isNull() && (value.isBoolean() ? value.asBoolean() : "true".equalsIgnoreCase(value.asText("").trim()));
	}

	/** True when this session may use an entry point that asks {@code pNeeded}; no session, no right. */
	public static boolean has(final Session pSession, final Right pNeeded)
	{
		final Right granted = ConnectorsAccess.granted(pSession);
		return granted != null && granted.covers(pNeeded);
	}

	/** The widest right of this session, null when it has none. */
	public static Right granted(final Session pSession)
	{
		if (pSession == null)
		{
			return null;
		}
		try
		{
			if (pSession.isSystemUser() || pSession.isUserInRole(Profile.ADMINISTRATOR)
					|| ConnectorsAccess.inRole(pSession, ConnectorsAccess.designerRole()))
			{
				return Right.MANAGE;
			}
			Right widest = ConnectorsAccess.inRole(pSession, ConnectorsAccess.operatorRole()) ? Right.RUN
					: ConnectorsAccess.inRole(pSession, ConnectorsAccess.viewerRole()) ? Right.READ : null;
			// the rows of the "Access rights" table: the widest right any role of the session brings
			final Grants source = ConnectorsAccess.grants;
			final Map<Profile, Right> given = source == null ? null : source.byProfile();
			if (given != null)
			{
				for (final Map.Entry<Profile, Right> one : given.entrySet())
				{
					final Right right = one.getValue();
					if (right != null && (widest == null || !widest.covers(right)) && pSession.isUserInRole(one.getKey()))
					{
						widest = right;
					}
				}
			}
			return widest;
		}
		catch (final RuntimeException ex)
		{
			// a directory or a table that cannot answer grants nothing
			return null;
		}
	}

	private static boolean inRole(final Session pSession, final String pRole)
	{
		if (pRole == null || pRole.isEmpty())
		{
			return false;
		}
		try
		{
			return pSession.isUserInRole(Role.forSpecificRole(pRole));
		}
		catch (final RuntimeException ex)
		{
			// a role name the directory refuses is a role nobody holds
			return false;
		}
	}

	public static String viewerRole()
	{
		return ConnectorsAccess.roleName(ConnectorsAccess.PROPERTY_VIEWER, null, ConnectorsAccess.DEFAULT_VIEWER);
	}

	public static String operatorRole()
	{
		return ConnectorsAccess.roleName(ConnectorsAccess.PROPERTY_OPERATOR, null, ConnectorsAccess.DEFAULT_OPERATOR);
	}

	public static String designerRole()
	{
		return ConnectorsAccess.roleName(ConnectorsAccess.PROPERTY_DESIGNER, ConnectorsAccess.PROPERTY_DESIGNER_LEGACY, ConnectorsAccess.DEFAULT_DESIGNER);
	}

	private static String roleName(final String pProperty, final String pLegacyProperty, final String pDefault)
	{
		String name = System.getProperty(pProperty);
		if ((name == null || name.trim().isEmpty()) && pLegacyProperty != null)
		{
			name = System.getProperty(pLegacyProperty);
		}
		return name == null || name.trim().isEmpty() ? pDefault : name.trim();
	}

	/** The role that grants exactly this right (a wider role grants it too). */
	public static String roleFor(final Right pRight)
	{
		return pRight == Right.MANAGE ? ConnectorsAccess.designerRole()
				: pRight == Right.RUN ? ConnectorsAccess.operatorRole() : ConnectorsAccess.viewerRole();
	}

	/**
	 * What a refused caller reads, in English: the machine-readable text (the {@code messageEn} of an answer, the logs). The action
	 * and the narrowest role that would have allowed it; same text as {@link #refusal(String, Right, Locale)} in English.
	 */
	public static String refusal(final String pWhat, final Right pNeeded)
	{
		return ConnectorsAccess.refusal(pWhat, pNeeded, Locale.ENGLISH);
	}

	/**
	 * The refusal in the language of the reader (audit C2-17): the bundle text of the locale of the session, or of the
	 * {@code Accept-Language} header through {@link #localeOf}; English when the locale has no text. The action stays as the
	 * caller named it, the right and the role are fixed names, so nothing here carries a value of the request.
	 */
	public static String refusal(final String pWhat, final Right pNeeded, final Locale pRequested)
	{
		// the bundles exist in English and French: any other locale reads English, not the JVM's default language
		final Locale pLocale = pRequested != null && "fr".equals(pRequested.getLanguage()) ? Locale.FRENCH : Locale.ENGLISH;
		return ConnectorsAccess.text(pLocale, "access.denied", pWhat, ConnectorsAccess.text(pLocale, "access.right." + pNeeded.name()), ConnectorsAccess.roleFor(pNeeded));
	}

	/**
	 * The first language of an {@code Accept-Language} header that the module has a bundle for ({@code fr} or {@code en}); English
	 * for a missing, empty or unknown header. Quality values are honoured by order: the header's own order is kept, as browsers
	 * and HTTP clients list their languages by preference.
	 */
	public static Locale localeOf(final String pAcceptLanguage)
	{
		if (pAcceptLanguage != null)
		{
			for (final String part : pAcceptLanguage.split(","))
			{
				final String tag = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
				if (tag.equals("fr") || tag.startsWith("fr-"))
				{
					return Locale.FRENCH;
				}
				if (tag.equals("en") || tag.startsWith("en-"))
				{
					return Locale.ENGLISH;
				}
			}
		}
		return Locale.ENGLISH;
	}

	private static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(ConnectorSyncServiceDeclaration.class, pLocale, pKey, pArgs);
	}

	/** The login of a session for the log; never throws. */
	public static String userOf(final Session pSession)
	{
		try
		{
			return pSession == null || pSession.getUserReference() == null ? "(no session)" : pSession.getUserReference().getUserId();
		}
		catch (final RuntimeException ex)
		{
			return "(unknown)";
		}
	}

	/**
	 * The permission rule of a user service that asks {@code pNeeded}: the action is neither shown to nor
	 * executable by a session that does not hold the right.
	 */
	public static ServicePermissionRule<EntitySelection> rule(final Right pNeeded)
	{
		return new ServicePermissionRule<EntitySelection>()
		{
			@Override
			public UserServicePermission getPermission(final ServicePermissionRuleContext<EntitySelection> pContext)
			{
				return ConnectorsAccess.has(pContext.getSession(), pNeeded) ? UserServicePermission.getEnabled() : UserServicePermission.getDisabled();
			}
		};
	}
}
