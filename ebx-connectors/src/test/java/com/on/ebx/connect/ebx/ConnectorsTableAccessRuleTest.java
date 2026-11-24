package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.ebx.ConnectorsTableAccessRule.Decision;
import com.on.ebx.connect.ebx.ConnectorsTableAccessRule.Level;
import com.on.ebx.connect.ebx.ConnectorsTableAccessRule.Policy;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRuleForCreateContext;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * Audit C-01 (tables): every table of the Connectors model has a decided rule, the decision follows the Connectors right
 * of the session (no right: hidden; READ: read only; the right that writes the table: read-write), the administrator and
 * the system user always write, creation follows the same decision, and the engine paths a RUN holder triggers (checkpoint,
 * conflict decision) do not depend on the table rule.
 */
public final class ConnectorsTableAccessRuleTest
{
	private ConnectorsTableAccessRuleTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String root = System.getProperty("ebx.connectors.moduleDir", ".");
		everyTableIsDecided(root);
		decisions();
		throughTheRule();
		wiring(root);
		System.out.println("PASS: the Connectors rights apply to every table of the Connectors dataset");
	}

	/** The tables of the XSD, as {@code /root/<name>}: an element whose own annotation declares {@code osd:table}. */
	static Set<Path> tablesOfTheModel(final String pRoot) throws Exception
	{
		final String xsd = new String(Files.readAllBytes(Paths.get(pRoot, "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd")), StandardCharsets.UTF_8);
		final Set<Path> tables = new LinkedHashSet<Path>();
		final Matcher table = Pattern.compile("<osd:table>").matcher(xsd);
		final Pattern element = Pattern.compile("<xs:element name=\"(\\w+)\"");
		while (table.find())
		{
			// the nearest element opened before this osd:table is the table
			final Matcher name = element.matcher(xsd.substring(0, table.start()));
			String last = null;
			while (name.find())
			{
				last = name.group(1);
			}
			check(last != null, "an osd:table without an element");
			tables.add(Path.parse("/root/" + last));
		}
		return tables;
	}

	private static void everyTableIsDecided(final String pRoot) throws Exception
	{
		final Set<Path> tables = ConnectorsTableAccessRuleTest.tablesOfTheModel(pRoot);
		check(tables.size() >= 26, "the XSD must hold the Connectors tables, found " + tables.size());
		for (final Path table : tables)
		{
			check(ConnectorsTableAccessRule.TABLES.containsKey(table), "table " + table.format() + " has no decided access rule in ConnectorsTableAccessRule.TABLES");
		}
		for (final Path decided : ConnectorsTableAccessRule.TABLES.keySet())
		{
			check(tables.contains(decided), "decided table " + decided.format() + " is not in the model");
		}
		for (final Map.Entry<Path, Policy> one : ConnectorsTableAccessRule.TABLES.entrySet())
		{
			final Policy policy = one.getValue();
			check(policy.read() != null && policy.write() != null && policy.write().covers(policy.read()), "a table must be readable by whoever writes it: " + one.getKey().format());
			check(policy.reason() != null && !policy.reason().trim().isEmpty(), "every decision says why: " + one.getKey().format());
			check(policy.read() != Level.ADMIN, "every table is readable with a Connectors right: " + one.getKey().format());
		}
		// the two tables that keep their own record rule (registered by ConnectorsSchemaExtensions), and only those
		final Set<Path> own = new HashSet<Path>();
		for (final Map.Entry<Path, Policy> one : ConnectorsTableAccessRule.TABLES.entrySet())
		{
			if (one.getValue().hasOwnRecordRule()) own.add(one.getKey());
		}
		check(own.equals(new HashSet<Path>(Arrays.asList(ConnectorsPaths.ACCESS_RIGHT, ConnectorsPaths.FAVORITE))), "only AccessRight and Favorite keep their own record rule: " + own);
	}

	private static void decisions()
	{
		// a table nobody decided: hidden without a right, read only for READ and RUN, written by MANAGE
		final Policy unknown = ConnectorsTableAccessRule.policy(Path.parse("/root/SomeTableAddedLater"));
		check(unknown == ConnectorsTableAccessRule.DEFAULT, "an unknown table gets the default");
		expect(unknown, Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_ONLY, Decision.READ_WRITE, "unknown table");
		check(ConnectorsTableAccessRule.policy(null) == ConnectorsTableAccessRule.DEFAULT, "no path: the default");

		// configuration: a READ (or RUN) holder can no longer change a connection - the live finding of 2026-10-06
		for (final Path table : new Path[] { ConnectorsPaths.CONNECTOR, ConnectorsPaths.CONNECTOR_PROPERTY, ConnectorsPaths.CONNECTOR_CATEGORY,
			ConnectorsPaths.CONNECTOR_TEMPLATE, ConnectorsPaths.MAPPING, ConnectorsPaths.MAPPING_INPUT, ConnectorsPaths.MAPPING_RULE,
			ConnectorsPaths.SCHEMA_REFRESH_STATE, ConnectorsPaths.CONNECTOR_GOVERNANCE, ConnectorsPaths.CONNECTOR_GOVERNANCE_AUDIT })
		{
			expect(ConnectorsTableAccessRule.policy(table), Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_ONLY, Decision.READ_WRITE, table.format());
		}
		// run time, cleaned by a designer's mapping deletion in the designer's own transaction
		for (final Path table : new Path[] { ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT, ConnectorsPaths.FIELD_PROVENANCE })
		{
			expect(ConnectorsTableAccessRule.policy(table), Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_ONLY, Decision.READ_WRITE, table.format());
		}
		// run time, carrying record values: RUN reads them (as the tools that list them ask), a reader does not see them
		for (final Path table : new Path[] { ConnectorsPaths.CONFLICT, ConnectorsPaths.OUTBOUND_STATE })
		{
			expect(ConnectorsTableAccessRule.policy(table), Decision.HIDDEN, Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_WRITE, table.format());
		}
		// written only by the engine or the installer: nobody but an administrator rewrites a log line by hand
		for (final Path table : new Path[] { ConnectorsPaths.SYNC_LOG, ConnectorsPaths.EXECUTION_RUN, ConnectorsPaths.PUBLICATION,
			ConnectorsPaths.CONNECTOR_OPERATIONAL_METRICS, ConnectorsPaths.RELEASE, ConnectorsPaths.RELEASE_ITEM, ConnectorsPaths.RELEASE_METRIC,
			ConnectorsPaths.RELEASE_PASS_STATUS, ConnectorsPaths.RELEASE_PASS_STEP, ConnectorsPaths.SEED_MARKER, ConnectorsPaths.ACCESS_RIGHT,
			ConnectorsPaths.FAVORITE })
		{
			expect(ConnectorsTableAccessRule.policy(table), Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_ONLY, Decision.READ_ONLY, table.format());
		}
		// wave 12: the module's settings (the default language-model connector) - read with a right, changed by an administrator only
		expect(ConnectorsTableAccessRule.SETTINGS, Decision.HIDDEN, Decision.READ_ONLY, Decision.READ_ONLY, Decision.READ_ONLY, "settings");
		check(ConnectorsTableAccessRule.decide(Level.ADMIN, ConnectorsTableAccessRule.SETTINGS) == Decision.READ_WRITE, "ADMIN changes the settings");
		check(!ConnectorsTableAccessRule.TABLES.containsKey(ConnectorsPaths.SETTINGS), "the settings are a group, not a table");
		// the administrator and the system user write every table
		for (final Policy policy : ConnectorsTableAccessRule.TABLES.values())
		{
			check(ConnectorsTableAccessRule.decide(Level.ADMIN, policy) == Decision.READ_WRITE, "ADMIN writes every table");
		}
		check(ConnectorsTableAccessRule.decide(Level.ADMIN, ConnectorsTableAccessRule.DEFAULT) == Decision.READ_WRITE, "ADMIN writes an unknown table");
		// rights to levels
		check(ConnectorsTableAccessRule.levelOf((ConnectorsAccess.Right) null) == null, "no right, no level");
		check(ConnectorsTableAccessRule.levelOf(ConnectorsAccess.Right.READ) == Level.READ, "READ");
		check(ConnectorsTableAccessRule.levelOf(ConnectorsAccess.Right.RUN) == Level.RUN, "RUN");
		check(ConnectorsTableAccessRule.levelOf(ConnectorsAccess.Right.MANAGE) == Level.MANAGE, "MANAGE");
	}

	private static void expect(final Policy pPolicy, final Decision pNone, final Decision pRead, final Decision pRun, final Decision pManage, final String pWhat)
	{
		check(ConnectorsTableAccessRule.decide(null, pPolicy) == pNone, pWhat + ": no right gets " + pNone);
		check(ConnectorsTableAccessRule.decide(Level.READ, pPolicy) == pRead, pWhat + ": READ gets " + pRead);
		check(ConnectorsTableAccessRule.decide(Level.RUN, pPolicy) == pRun, pWhat + ": RUN gets " + pRun);
		check(ConnectorsTableAccessRule.decide(Level.MANAGE, pPolicy) == pManage, pWhat + ": MANAGE gets " + pManage);
	}

	private static void throughTheRule()
	{
		final Role partner = Role.forSpecificRole("PartnerAuditors");
		ConnectorsAccess.use(new ConnectorsAccess.Grants()
		{
			@Override
			public Map<Profile, ConnectorsAccess.Right> byProfile()
			{
				return java.util.Collections.<Profile, ConnectorsAccess.Right> singletonMap(partner, ConnectorsAccess.Right.READ);
			}
		});
		try
		{
			final Session designer = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_DESIGNER));
			final Session operator = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR));
			final Session viewer = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER));
			final Session mappedReader = session(false, partner);
			final Session stranger = session(false);
			final Session admin = session(false, Profile.ADMINISTRATOR);
			final Session system = session(true);
			final ConnectorsTableAccessRule connector = new ConnectorsTableAccessRule(ConnectorsTableAccessRule.policy(ConnectorsPaths.CONNECTOR));
			final ConnectorsTableAccessRule log = new ConnectorsTableAccessRule(ConnectorsTableAccessRule.policy(ConnectorsPaths.SYNC_LOG));
			final ConnectorsTableAccessRule conflict = new ConnectorsTableAccessRule(ConnectorsTableAccessRule.policy(ConnectorsPaths.CONFLICT));

			// a row: through getPermission(record, session, node)
			for (final Session writer : new Session[] { designer, admin, system })
			{
				check(connector.getPermission(null, writer, null).isReadWrite(), "MANAGE, an administrator and the system user edit a connection");
			}
			for (final Session reader : new Session[] { viewer, mappedReader, operator })
			{
				final AccessPermission permission = connector.getPermission(null, reader, null);
				check(permission.isReadOnly(), "READ and RUN see a connection read only");
			}
			check(connector.getPermission(null, stranger, null).isHidden(), "no right: the connections are hidden");
			check(connector.getPermission(null, null, null).isReadWrite(), "no session is the platform itself");
			check(log.getPermission(null, designer, null).isReadOnly() && log.getPermission(null, admin, null).isReadWrite()
				&& log.getPermission(null, system, null).isReadWrite(), "the log: read only to a designer, written by the administrator and the system user");
			check(conflict.getPermission(null, viewer, null).isHidden() && conflict.getPermission(null, operator, null).isReadOnly()
				&& conflict.getPermission(null, designer, null).isReadWrite(), "conflicts: hidden to READ, read only to RUN, written by MANAGE");

			// a creation: through getPermission(AccessRuleForCreateContext)
			check(connector.getPermission(createContext(designer)).isReadWrite(), "MANAGE creates a connection");
			check(connector.getPermission(createContext(system)).isReadWrite(), "the system user (installers) creates a connection");
			check(connector.getPermission(createContext(viewer)).isReadOnly(), "READ cannot create a connection");
			check(connector.getPermission(createContext(operator)).isReadOnly(), "RUN cannot create a connection");
			check(connector.getPermission(createContext(stranger)).isHidden(), "no right cannot create a connection");
			check(log.getPermission(createContext(designer)).isReadOnly(), "a designer cannot forge a log line");
			check(connector.getPermission((AccessRuleForCreateContext) null).isReadWrite(), "no context is the platform itself");
		}
		finally
		{
			ConnectorsAccess.use(null);
		}
	}

	/** The registration and the engine paths a RUN holder triggers, read in the sources. */
	private static void wiring(final String pRoot) throws Exception
	{
		final String extensions = read(pRoot, "src/main/java/com/on/ebx/connect/ebx/ConnectorsSchemaExtensions.java");
		final int favorite = extensions.indexOf("setAccessRuleOnOccurrence(com.on.ebx.connect.core.ConnectorsPaths.FAVORITE");
		final int accessRight = extensions.indexOf("setAccessRuleOnOccurrence(com.on.ebx.connect.core.ConnectorsPaths.ACCESS_RIGHT");
		final int tables = extensions.indexOf("ConnectorsTableAccessRule.register(pContext)");
		final int secret = extensions.indexOf("setAccessRuleOnNode(ConnectorPropertyValueAccessRule.VALUE");
		check(tables > 0, "the schema extensions must register the table rule");
		check(favorite > 0 && accessRight > 0 && tables > favorite && tables > accessRight, "the table rule is registered after the Favorite and AccessRight record rules");
		check(secret > 0, "the credential value rule (audit C-05) must stay registered");
		check(extensions.contains("setAccessRuleOnNodeAndAllDescendants(com.on.ebx.connect.core.ConnectorsPaths.SETTINGS, true,\n\t\t\tnew ConnectorsTableAccessRule(ConnectorsTableAccessRule.SETTINGS))"),
			"the settings group carries the administrator-only rule (wave 12)");
		final String xsd = read(pRoot, "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd");
		final int settings = xsd.indexOf("<xs:element name=\"Settings\" minOccurs=\"1\" maxOccurs=\"1\">");
		check(settings > 0 && xsd.indexOf("<xs:element name=\"defaultLlmConnector\"", settings) > settings
			&& xsd.indexOf("<tablePath>/root/Connector</tablePath>", settings) > settings && xsd.indexOf("<predicate>./type = 'llm'</predicate>", settings) > settings,
			"the setting defaultLlmConnector is a foreign key to the llm connectors, inside the Settings group");
		check(extensions.indexOf("setAccessRuleOnOccurrence", tables) < 0, "no other record rule may replace the table rule after it");
		final String rule = read(pRoot, "src/main/java/com/on/ebx/connect/ebx/ConnectorsTableAccessRule.java");
		check(rule.contains("setAccessRuleForCreateOnNode(child.getPathInSchema()") && !rule.contains("setAccessRuleForCreateOnNode(table"),
			"creation is closed field by field, never on the table node (a model compilation error)");
		final String resolver = read(pRoot, "src/main/java/com/on/ebx/connect/core/ConflictResolver.java");
		check(count(resolver, "ConnectorsAccess.has(pSession, com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN)") == 2, "accept and reject check RUN before writing the conflict");
		check(resolver.contains("pContext.setAllPrivileges(true);\n\t\t\t\tfinal ValueContextForUpdate update = pContext.getContext(pConflict.getAdaptationName());"),
			"the conflict row is written with all privileges by the decision");
		final String repository = read(pRoot, "src/main/java/com/on/ebx/connect/core/ConnectorRepository.java");
		final int save = repository.indexOf("public SynchronizationCheckpoint saveCheckpoint(");
		check(save > 0 && repository.indexOf("pContext.setAllPrivileges(true);", save) > 0
			&& repository.indexOf("pContext.setAllPrivileges(true);", save) < repository.indexOf("doCreateOccurrence(values, table)", save),
			"a feed run saves its checkpoint with all privileges");
	}

	private static int count(final String pText, final String pPart)
	{
		int found = 0;
		for (int at = pText.indexOf(pPart); at >= 0; at = pText.indexOf(pPart, at + 1)) found++;
		return found;
	}

	private static String read(final String pRoot, final String pFile) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(pRoot, pFile)), StandardCharsets.UTF_8);
	}

	private static AccessRuleForCreateContext createContext(final Session pSession)
	{
		return (AccessRuleForCreateContext) Proxy.newProxyInstance(ConnectorsTableAccessRuleTest.class.getClassLoader(),
			new Class<?>[] { AccessRuleForCreateContext.class }, new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
				{
					if ("getSession".equals(pMethod.getName())) return pSession;
					throw new UnsupportedOperationException(pMethod.getName());
				}
			});
	}

	private static Session session(final boolean pSystem, final Profile... pRoles)
	{
		final Set<Profile> roles = new HashSet<Profile>(Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(ConnectorsTableAccessRuleTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName())) return Boolean.valueOf(pSystem);
				if ("isUserInRole".equals(pMethod.getName())) return Boolean.valueOf(roles.contains(pArguments[0]));
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass) throw new AssertionError(pMessage);
	}
}
