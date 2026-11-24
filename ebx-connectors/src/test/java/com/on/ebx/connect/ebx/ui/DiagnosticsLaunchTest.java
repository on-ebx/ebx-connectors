package com.on.ebx.connect.ebx.ui;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Guards the diagnostics actions started from a URL: only a URL made by the connector form, for the same user,
 * the same connector and the same action, and recent enough, starts one; and both sides are still wired to that
 * proof.
 */
public final class DiagnosticsLaunchTest
{
	private DiagnosticsLaunchTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
		final long now = 1000000000000L;
		final long expires = now + DiagnosticsLaunch.LIFETIME_MILLIS;
		final String token = DiagnosticsLaunch.mint(key, "alice", "GLEIF", "connect", expires);
		check(token != null && token.matches("[0-9]+\\.[0-9a-f]{64}"), "a token is an expiry and a signature, URL-safe: " + token);
		check(DiagnosticsLaunch.valid(key, token, "alice", "GLEIF", "connect", now), "the form's own token is accepted");
		check(DiagnosticsLaunch.valid(key, token, "alice", "GLEIF", "connect", expires - 1), "until it expires");

		check(!DiagnosticsLaunch.valid(key, token, "alice", "GLEIF", "connect", expires), "an expired token is refused");
		check(!DiagnosticsLaunch.valid(key, token, "bob", "GLEIF", "connect", now), "another user");
		check(!DiagnosticsLaunch.valid(key, token, "alice", "VIES", "connect", now), "another connector");
		check(!DiagnosticsLaunch.valid(key, token, "alice", "GLEIF", "disconnect", now), "another action");
		check(!DiagnosticsLaunch.valid("another key of the same length!!".getBytes(StandardCharsets.UTF_8), token, "alice", "GLEIF", "connect", now), "another JVM's key");
		check(!DiagnosticsLaunch.valid(key, null, "alice", "GLEIF", "connect", now) && !DiagnosticsLaunch.valid(key, "", "alice", "GLEIF", "connect", now)
			&& !DiagnosticsLaunch.valid(key, "abc", "alice", "GLEIF", "connect", now) && !DiagnosticsLaunch.valid(key, ".abc", "alice", "GLEIF", "connect", now)
			&& !DiagnosticsLaunch.valid(key, "12x.abc", "alice", "GLEIF", "connect", now), "no token, or a malformed one");
		// the expiry is signed: it cannot be pushed back, and a token cannot be made to live longer than the form gives
		final String signature = token.substring(token.indexOf('.'));
		check(!DiagnosticsLaunch.valid(key, (expires + 1) + signature, "alice", "GLEIF", "connect", now), "a changed expiry");
		final String eternal = DiagnosticsLaunch.mint(key, "alice", "GLEIF", "connect", expires + 1);
		check(!DiagnosticsLaunch.valid(key, eternal, "alice", "GLEIF", "connect", now), "a lifetime longer than the form's");
		check(!DiagnosticsLaunch.valid(key, token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0"), "alice", "GLEIF", "connect", now), "a changed signature");
		// the signed parts cannot slide into one another
		check(!DiagnosticsLaunch.mint(key, "ab", "c", "test", expires).equals(DiagnosticsLaunch.mint(key, "a", "bc", "test", expires)), "user and connector are signed apart");

		check(DiagnosticsLaunch.mint(key, null, "GLEIF", "connect", expires) == null && DiagnosticsLaunch.mint(key, "alice", "", "connect", expires) == null
			&& DiagnosticsLaunch.mint(key, "alice", "GLEIF", "drop", expires) == null && DiagnosticsLaunch.mint(null, "alice", "GLEIF", "connect", expires) == null,
			"nothing to sign, no token");
		check(DiagnosticsLaunch.isAction("test") && DiagnosticsLaunch.isAction("connect") && DiagnosticsLaunch.isAction("preview") && DiagnosticsLaunch.isAction("disconnect")
			&& !DiagnosticsLaunch.isAction("validate") && !DiagnosticsLaunch.isAction(null), "the four actions a URL may name");

		// with this JVM's key, through the session
		final Session alice = session("alice");
		final String proof = DiagnosticsLaunch.proof(alice, "GLEIF", "disconnect");
		check(proof.startsWith("&" + DiagnosticsLaunch.TOKEN_PARAMETER + "="), "what the form appends: " + proof);
		final String made = proof.substring(proof.indexOf('=') + 1);
		check(DiagnosticsLaunch.proven(made, alice, "GLEIF", "disconnect"), "the service accepts what the form made");
		check(!DiagnosticsLaunch.proven(made, session("bob"), "GLEIF", "disconnect") && !DiagnosticsLaunch.proven(made, alice, "GLEIF", "connect")
			&& !DiagnosticsLaunch.proven(null, alice, "GLEIF", "disconnect") && !DiagnosticsLaunch.proven(made, null, "GLEIF", "disconnect"),
			"and nothing else");
		check("".equals(DiagnosticsLaunch.proof(null, "GLEIF", "disconnect")), "no session, no proof: the page will ask for a click");

		// both sides are wired
		final String moduleDir = System.getProperty("ebx.connectors.moduleDir");
		check(moduleDir != null, "-Debx.connectors.moduleDir is required: the screens are read as sources");
		final File ui = new File(moduleDir, "src/main/java/com/on/ebx/connect/ebx/ui");
		final String service = new String(Files.readAllBytes(new File(ui, "ConnectorDiagnosticsUserService.java").toPath()), StandardCharsets.UTF_8);
		final int setup = service.indexOf("void setupObjectContext(");
		final int display = service.indexOf("void setupDisplay(");
		check(setup > 0 && display > setup, "the diagnostics service changed shape");
		final String onOpening = service.substring(setup, display);
		final int proven = onOpening.indexOf("DiagnosticsLaunch.proven(");
		final int firstAction = onOpening.indexOf("ConnectorDiagnosticsAdapter.");
		check(proven > 0 && firstAction > proven, "on opening, nothing may run before the proof is checked");
		check(onOpening.indexOf("return;", proven) > 0 && onOpening.indexOf("return;", proven) < firstAction, "without the proof the opening must stop before any action");
		final String form = new String(Files.readAllBytes(new File(ui, "ConnectorForm.java").toPath()), StandardCharsets.UTF_8);
		for (final String action : new String[] { "test", "connect", "preview", "disconnect" })
		{
			check(form.contains("\"ftcdAction=" + action + "\" + DiagnosticsLaunch.proof(user, id, \"" + action + "\")"),
				"the form's button '" + action + "' must carry its proof");
		}
		System.out.println("DiagnosticsLaunchTest OK");
	}

	private static Session session(final String pUser)
	{
		return (Session) Proxy.newProxyInstance(DiagnosticsLaunchTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("getUserReference".equals(pMethod.getName()))
				{
					return Profile.forUser(pUser);
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
