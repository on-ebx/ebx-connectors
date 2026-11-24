package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * Guards the audit line of the connectors: what is traced, what the line names, and above all what it never
 * carries (documents, inputs, record keys, URLs), whatever a caller sends.
 */
public final class ConnectorsAuditTest
{
	private ConnectorsAuditTest() { }

	public static void main(final String[] pArgs)
	{
		// what runs or changes something is traced, a read is not
		check(ConnectorsAudit.traced(ConnectorsAccess.Right.RUN) && ConnectorsAudit.traced(ConnectorsAccess.Right.MANAGE)
			&& !ConnectorsAudit.traced(ConnectorsAccess.Right.READ) && !ConnectorsAudit.traced(null), "RUN and MANAGE are traced, READ is not");

		// the line
		check("[connectors] audit: user=alice tool=connectors_sync mapping=12 dryRun=true outcome=ok ms=532"
			.equals(ConnectorsAudit.line("alice", "tool", "connectors_sync", "mapping=12 dryRun=true", ConnectorsAudit.OK, -1, 532L)), "a tool line");
		check("[connectors] audit: user=alice rest=deleteMapping mapping=7 outcome=error status=404 ms=3"
			.equals(ConnectorsAudit.line("alice", "rest", "deleteMapping", "mapping=7", ConnectorsAudit.ERROR, 404, 3L)), "a REST line carries the status");
		check("[connectors] audit: user=- tool=connectors_conflicts_list outcome=refused ms=0"
			.equals(ConnectorsAudit.line(null, "tool", "connectors_conflicts_list", "", ConnectorsAudit.REFUSED, -1, -5L)), "no user, no subject, no negative duration");

		// identifiers and mode flags only
		final ObjectNode call = Json.object();
		call.put("connector", "GLEIF");
		call.put("operation", "lei");
		call.putObject("inputs").put("lei", "SECRET-INPUT");
		call.putObject("document").put("iban", "SECRET-DOCUMENT");
		call.put("allowWrite", true);
		final String callSubject = ConnectorsAudit.subject(ConnectorsTools.T_CALL, call);
		check("connector=GLEIF operation=lei allowWrite=true".equals(callSubject), "a raw call names the connection and the operation: " + callSubject);

		final ObjectNode sync = Json.object();
		sync.put("mapping", 12);
		sync.put("key", "SECRET-KEY");
		sync.put("predicate", "./name='SECRET-PREDICATE'");
		sync.put("inputs", "token=SECRET-INPUT");
		sync.put("dryRun", "TRUE");
		sync.put("async", true);
		check("mapping=12 dryRun=true".equals(ConnectorsAudit.subject(ConnectorsTools.T_SYNC, sync)), "a sync names its mapping, never the record nor the inputs");

		final ObjectNode enrich = Json.object();
		enrich.put("mappings", "30, 25");
		enrich.putArray("connectors").add("GLEIF/lei").add("VIES").addObject().put("x", "SECRET");
		enrich.put("key", "SECRET-KEY");
		enrich.put("dryRun", false);
		check("mappings=30,_25 connectors=GLEIF/lei,VIES".equals(ConnectorsAudit.subject(ConnectorsTools.T_ENRICH, enrich)), "lists of identifiers, no false flag");

		final ObjectNode upsert = Json.object();
		final ObjectNode document = upsert.putObject("mapping");
		document.put("connector", "Wikidata");
		document.put("operation", "search");
		document.put("label", "SECRET-LABEL");
		document.putArray("rules").addObject().put("sourcePath", "SECRET-RULE");
		check("mapping=(new) connector=Wikidata operation=search".equals(ConnectorsAudit.subject(ConnectorsTools.T_UPSERT, upsert)), "a new mapping: its connection and operation");
		document.put("id", 37);
		check("mapping=37 connector=Wikidata operation=search".equals(ConnectorsAudit.subject(ConnectorsTools.T_UPSERT, upsert)), "an updated mapping: its id");
		final ObjectNode upsertText = Json.object();
		upsertText.put("mapping", Json.write(document));
		check("mapping=37 connector=Wikidata operation=search".equals(ConnectorsAudit.subject(ConnectorsTools.T_UPSERT, upsertText)), "a mapping document sent as text");
		final ObjectNode documentAsIdentifier = Json.object();
		documentAsIdentifier.put("mapping", "{\"password\":\"SECRET\"}");
		documentAsIdentifier.put("runId", "[\"SECRET\"]");
		check("".equals(ConnectorsAudit.subject(ConnectorsTools.T_DELETE, documentAsIdentifier)), "a document where an identifier is expected is not written");

		final ObjectNode imported = Json.object();
		imported.put("config", "{\"connectors\":[{\"password\":\"SECRET\"}]}");
		imported.put("url", "https://example.org/spec?token=SECRET");
		imported.put("spec", "SECRET-SPEC");
		imported.put("comment", "SECRET-COMMENT");
		check("".equals(ConnectorsAudit.subject(ConnectorsTools.T_IMPORT, imported)), "a configuration, a URL, a comment are never written");
		for (final ObjectNode one : new ObjectNode[] { call, sync, enrich, upsert, upsertText, documentAsIdentifier, imported })
		{
			for (final ConnectorsTools.Tool tool : ConnectorsTools.CATALOG)
			{
				check(!ConnectorsAudit.subject(tool.name, one).contains("SECRET"), "an argument value leaked through " + tool.name);
			}
		}
		check("".equals(ConnectorsAudit.subject(ConnectorsTools.T_SYNC, null)) && "".equals(ConnectorsAudit.subject(ConnectorsTools.T_SYNC, Json.object().arrayNode())),
			"no arguments, no subject");

		// a caller cannot forge or flood the log
		final ObjectNode forged = Json.object();
		forged.put("mapping", "12 outcome=ok\n[connectors] audit: user=admin");
		final String forgedSubject = ConnectorsAudit.subject(ConnectorsTools.T_SYNC, forged);
		check(forgedSubject.indexOf('\n') < 0 && forgedSubject.indexOf(' ') < 0, "an identifier is one token: " + forgedSubject);
		final StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			longName.append('x');
		}
		check(ConnectorsAudit.token(longName.toString()).length() == 83 && ConnectorsAudit.token(longName.toString()).endsWith("..."), "a long identifier is cut");
		check("-".equals(ConnectorsAudit.token(null)) && "-".equals(ConnectorsAudit.token("  ")) && "a_b".equals(ConnectorsAudit.token(" a\tb ")), "tokens");

		// through the tools' one entry point: a refused call that would have run something is traced, a refused read is not
		final List<String> lines = new ArrayList<String>();
		ConnectorsAudit.use(new ConnectorsAudit.Sink()
		{
			@Override
			public void write(final String pLine)
			{
				lines.add(pLine);
			}
		});
		ConnectorsAccess.use(null);
		final ConnectorsTools viewer = new ConnectorsTools(session("alice", Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER)), null, Locale.ENGLISH);
		check(viewer.invoke(ConnectorsTools.T_SYNC, sync).has("error"), "a viewer cannot synchronise");
		check(lines.size() == 1 && "[connectors] audit: user=alice tool=connectors_sync mapping=12 dryRun=true outcome=refused ms=0".equals(lines.get(0)),
			"the refused synchronisation leaves its line: " + lines);
		final ConnectorsTools nobody = new ConnectorsTools(session("bob"), null, Locale.ENGLISH);
		check(nobody.invoke(ConnectorsTools.T_LIST, Json.object()).has("error") && lines.size() == 1, "a refused read is not traced (the access-denied warning says it)");
		check(nobody.invoke("connectors_not_a_tool", Json.object()).has("error") && lines.size() == 2 && lines.get(1).contains("tool=connectors_not_a_tool")
			&& lines.get(1).contains("outcome=refused"), "a tool nobody classified is closed, and traced");

		// a sink that fails never fails the call
		ConnectorsAudit.use(new ConnectorsAudit.Sink()
		{
			@Override
			public void write(final String pLine)
			{
				throw new IllegalStateException("log unavailable");
			}
		});
		check(viewer.invoke(ConnectorsTools.T_SYNC, sync).has("error"), "the answer does not depend on the trace");
		ConnectorsAudit.rest("alice", "sync", "mapping=1", ConnectorsAudit.OK, 200, 1L);
		ConnectorsAudit.use(null);
		System.out.println("ConnectorsAuditTest OK");
	}

	/** A session that only answers what the access check and the audit ask: its user and its roles. */
	private static Session session(final String pUser, final Profile... pRoles)
	{
		final Set<Profile> roles = new HashSet<Profile>(Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(ConnectorsAuditTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName()))
				{
					return Boolean.FALSE;
				}
				if ("isUserInRole".equals(pMethod.getName()))
				{
					return Boolean.valueOf(roles.contains(pArguments[0]));
				}
				if ("getUserReference".equals(pMethod.getName()))
				{
					return Profile.forUser(pUser);
				}
				if ("getLocale".equals(pMethod.getName()))
				{
					return null;
				}
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
