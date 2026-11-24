package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * Guards the authorization of the connectors: nothing without a role, each role opens exactly its tools, a raw
 * call and every configuration change stay with the designer, and a tool nobody classified is closed.
 */
public final class ConnectorsAccessTest
{
	private ConnectorsAccessTest() { }

	public static void main(final String[] pArgs)
	{
		// no row in the "Access rights" table to begin with (the real table needs a repository)
		ConnectorsAccess.use(table());
		// every tool of the catalogue has been given a right on purpose (an unknown tool falls back to MANAGE)
		final Set<String> read = new HashSet<String>(Arrays.asList(ConnectorsTools.T_HELP, ConnectorsTools.T_LIST, ConnectorsTools.T_DESCRIBE,
			ConnectorsTools.T_MAPPINGS, ConnectorsTools.T_MAPPING, ConnectorsTools.T_RUN, ConnectorsTools.T_LOG, ConnectorsTools.T_SOURCE));
		final Set<String> run = new HashSet<String>(Arrays.asList(ConnectorsTools.T_ENRICH, ConnectorsTools.T_SYNC, ConnectorsTools.T_STOP,
			ConnectorsTools.T_REPLAY, ConnectorsTools.T_LINEAGE, ConnectorsTools.T_PUBLISH, ConnectorsTools.T_OUTBOUND, ConnectorsTools.T_CONFLICTS,
			ConnectorsTools.T_DECIDE));
		final Set<String> manage = new HashSet<String>(Arrays.asList(ConnectorsTools.T_CALL, ConnectorsTools.T_UPSERT, ConnectorsTools.T_DELETE,
			ConnectorsTools.T_EXPORT, ConnectorsTools.T_IMPORT, ConnectorsTools.T_OPENAPI));
		check(read.size() + run.size() + manage.size() == ConnectorsTools.CATALOG.size(), "a tool of the catalogue has no decided right");
		for (final ConnectorsTools.Tool tool : ConnectorsTools.CATALOG)
		{
			final ConnectorsAccess.Right right = ConnectorsAccess.required(tool.name);
			check(right == (read.contains(tool.name) ? ConnectorsAccess.Right.READ
				: run.contains(tool.name) ? ConnectorsAccess.Right.RUN : manage.contains(tool.name) ? ConnectorsAccess.Right.MANAGE : null),
				"unexpected right for " + tool.name + ": " + right);
		}
		check(ConnectorsAccess.required("connectors_not_a_tool") == ConnectorsAccess.Right.MANAGE, "an unclassified tool must be closed, not open");

		// arguments that turn a read into a designer's action
		final ObjectNode describeOperation = Json.object();
		describeOperation.put("connector", "gleif");
		describeOperation.put("operation", "getByLei");
		check(ConnectorsAccess.required(ConnectorsTools.T_DESCRIBE, describeOperation) == ConnectorsAccess.Right.MANAGE,
			"describing an operation samples the remote system: designer only");
		final ObjectNode describeOnly = Json.object();
		describeOnly.put("connector", "gleif");
		describeOnly.put("operation", " ");
		check(ConnectorsAccess.required(ConnectorsTools.T_DESCRIBE, describeOnly) == ConnectorsAccess.Right.READ, "describing a connection is a read");
		final ObjectNode apply = Json.object();
		apply.put("apply", true);
		check(ConnectorsAccess.required(ConnectorsTools.T_SOURCE, apply) == ConnectorsAccess.Right.MANAGE, "applying a catalog source writes the configuration");
		final ObjectNode applyText = Json.object();
		applyText.put("apply", "TRUE");
		check(ConnectorsAccess.required(ConnectorsTools.T_SOURCE, applyText) == ConnectorsAccess.Right.MANAGE, "apply sent as text must count too");
		check(ConnectorsAccess.required(ConnectorsTools.T_SOURCE, Json.object()) == ConnectorsAccess.Right.READ, "planning a catalog source is a read");
		check(ConnectorsAccess.required(ConnectorsTools.T_CALL, null) == ConnectorsAccess.Right.MANAGE, "a raw call is a designer's action whatever its arguments");

		// each right includes the narrower ones, never the wider
		check(ConnectorsAccess.Right.MANAGE.covers(ConnectorsAccess.Right.RUN) && ConnectorsAccess.Right.RUN.covers(ConnectorsAccess.Right.READ)
			&& ConnectorsAccess.Right.READ.covers(ConnectorsAccess.Right.READ), "a right must include the narrower ones");
		check(!ConnectorsAccess.Right.READ.covers(ConnectorsAccess.Right.RUN) && !ConnectorsAccess.Right.RUN.covers(ConnectorsAccess.Right.MANAGE)
			&& !ConnectorsAccess.Right.MANAGE.covers(null), "a right must not include a wider one");

		// sessions: nothing by default
		check(ConnectorsAccess.granted(null) == null && !ConnectorsAccess.has(null, ConnectorsAccess.Right.READ), "no session, no right");
		check(ConnectorsAccess.granted(session(false)) == null, "an authenticated user without a role must get nothing");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole("SomeOtherRole"))) == null, "an unrelated role grants nothing");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER))) == ConnectorsAccess.Right.READ, "viewer reads");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR))) == ConnectorsAccess.Right.RUN, "operator runs");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_DESIGNER))) == ConnectorsAccess.Right.MANAGE, "designer manages");
		check(ConnectorsAccess.granted(session(false, Profile.ADMINISTRATOR)) == ConnectorsAccess.Right.MANAGE, "the administrator manages");
		check(ConnectorsAccess.granted(session(true)) == ConnectorsAccess.Right.MANAGE, "the system user (workflow, scheduler) passes");
		final Session viewer = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER));
		check(ConnectorsAccess.has(viewer, ConnectorsAccess.Right.READ) && !ConnectorsAccess.has(viewer, ConnectorsAccess.Right.RUN)
			&& !ConnectorsAccess.has(viewer, ConnectorsAccess.Right.MANAGE), "a viewer must not run or manage");
		final Session operator = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR));
		check(ConnectorsAccess.has(operator, ConnectorsAccess.required(ConnectorsTools.T_SYNC))
			&& !ConnectorsAccess.has(operator, ConnectorsAccess.required(ConnectorsTools.T_CALL))
			&& !ConnectorsAccess.has(operator, ConnectorsAccess.required(ConnectorsTools.T_UPSERT)), "an operator runs mappings but neither calls raw nor configures");
		check(ConnectorsAccess.granted(failing()) == null, "a directory that throws grants nothing");

		// the "Access rights" table: an administrator maps roles that already exist to a right
		final Role stewards = Role.forSpecificRole("DataStewards");
		final Role integration = Role.forSpecificRole("IntegrationTeam");
		ConnectorsAccess.use(table(stewards, ConnectorsAccess.Right.RUN, integration, ConnectorsAccess.Right.MANAGE));
		check(ConnectorsAccess.granted(session(false, stewards)) == ConnectorsAccess.Right.RUN, "a role mapped in the table receives its right");
		check(ConnectorsAccess.granted(session(false, integration)) == ConnectorsAccess.Right.MANAGE, "a role mapped to designer manages");
		check(ConnectorsAccess.granted(session(false, stewards, integration)) == ConnectorsAccess.Right.MANAGE, "the widest right of a session's roles wins");
		check(ConnectorsAccess.granted(session(false, integration, stewards)) == ConnectorsAccess.Right.MANAGE, "whatever the order of the rows");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole("SomeOtherRole"))) == null, "a role absent from the table still gets nothing");
		check(ConnectorsAccess.granted(session(false, stewards, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER))) == ConnectorsAccess.Right.RUN,
			"a default role and a row add up to the widest right");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR), Role.forSpecificRole("Readers"))) == ConnectorsAccess.Right.RUN,
			"a narrower row must not lower a default role");
		ConnectorsAccess.use(table(Profile.EVERYONE, ConnectorsAccess.Right.READ));
		check(ConnectorsAccess.granted(session(false, Profile.EVERYONE)) == ConnectorsAccess.Right.READ, "'Everyone' mapped to viewer lets every user read");
		check(!ConnectorsAccess.has(session(false, Profile.EVERYONE), ConnectorsAccess.Right.RUN), "and nothing more");
		ConnectorsAccess.use(table(stewards, null));
		check(ConnectorsAccess.granted(session(false, stewards)) == null, "a row without a right gives nothing");
		ConnectorsAccess.use(null);
		check(ConnectorsAccess.granted(session(false, stewards)) == null
			&& ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER))) == ConnectorsAccess.Right.READ,
			"without a table the default roles still work and nobody else passes");
		ConnectorsAccess.use(new ConnectorsAccess.Grants()
		{
			@Override
			public java.util.Map<Profile, ConnectorsAccess.Right> byProfile()
			{
				throw new IllegalStateException("table unavailable");
			}
		});
		check(ConnectorsAccess.granted(session(false, stewards)) == null, "a table that cannot be read gives nothing");
		check(ConnectorsAccess.granted(session(false, Profile.ADMINISTRATOR)) == ConnectorsAccess.Right.MANAGE, "the administrator never depends on the table");
		ConnectorsAccess.use(table());
		check(ConnectorsAccess.Right.named("RUN") == ConnectorsAccess.Right.RUN && ConnectorsAccess.Right.named("run") == null
			&& ConnectorsAccess.Right.named(null) == null, "only the stored names READ, RUN, MANAGE are rights");
		check(ConnectorsRoleEnumeration.eligible(stewards) && ConnectorsRoleEnumeration.eligible(Profile.EVERYONE)
			&& !ConnectorsRoleEnumeration.eligible(Profile.ADMINISTRATOR) && !ConnectorsRoleEnumeration.eligible(Profile.OWNER)
			&& !ConnectorsRoleEnumeration.eligible(Profile.READ_ONLY) && !ConnectorsRoleEnumeration.eligible(Profile.forUser("alice"))
			&& !ConnectorsRoleEnumeration.eligible(null), "only a role of the directory or 'Everyone' can receive a right");

		// role names: the property wins, the older designer property is still read
		System.setProperty(ConnectorsAccess.PROPERTY_DESIGNER_LEGACY, "LegacyAdmins");
		check("LegacyAdmins".equals(ConnectorsAccess.designerRole()), "the older designer property must still be read");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole("LegacyAdmins"))) == ConnectorsAccess.Right.MANAGE, "the legacy role manages");
		check(ConnectorsAccess.granted(session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_DESIGNER))) == null, "a replaced default role grants nothing");
		System.setProperty(ConnectorsAccess.PROPERTY_DESIGNER, " Designers ");
		check("Designers".equals(ConnectorsAccess.designerRole()), "the designer property wins over the older one, trimmed");
		System.clearProperty(ConnectorsAccess.PROPERTY_DESIGNER);
		System.clearProperty(ConnectorsAccess.PROPERTY_DESIGNER_LEGACY);
		check(ConnectorsAccess.DEFAULT_DESIGNER.equals(ConnectorsAccess.designerRole()), "without a property the default role name applies");

		check(ConnectorsAccess.refusal("'connectors_call'", ConnectorsAccess.Right.MANAGE).contains(ConnectorsAccess.DEFAULT_DESIGNER),
			"a refusal must name the role that would allow the action");
		System.out.println("PASS: connectors access is closed by default, each role opens exactly its tools, raw calls and configuration stay with the designer, "
			+ "and the administrator's table maps existing roles to rights");
	}

	/** A session that only answers what the access check asks: system user or not, and the roles it holds. */
	private static Session session(final boolean pSystem, final Profile... pRoles)
	{
		final Set<Profile> roles = new HashSet<Profile>(Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(ConnectorsAccessTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName()))
				{
					return Boolean.valueOf(pSystem);
				}
				if ("isUserInRole".equals(pMethod.getName()))
				{
					return Boolean.valueOf(roles.contains(pArguments[0]));
				}
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	/** The rows of the "Access rights" table: profile, right, profile, right... */
	private static ConnectorsAccess.Grants table(final Object... pRows)
	{
		final java.util.Map<Profile, ConnectorsAccess.Right> rows = new java.util.LinkedHashMap<Profile, ConnectorsAccess.Right>();
		for (int i = 0; i + 1 < pRows.length; i += 2)
		{
			rows.put((Profile) pRows[i], (ConnectorsAccess.Right) pRows[i + 1]);
		}
		return new ConnectorsAccess.Grants()
		{
			@Override
			public java.util.Map<Profile, ConnectorsAccess.Right> byProfile()
			{
				return rows;
			}
		};
	}

	private static Session failing()
	{
		return (Session) Proxy.newProxyInstance(ConnectorsAccessTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				throw new IllegalStateException("directory unavailable");
			}
		});
	}

	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
