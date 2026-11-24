package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.orchestranetworks.service.Session;

/**
 * Proof that a diagnostics action asked in a URL was started by a button of the connector form, not by a link
 * somebody made the user follow. The form's buttons open the diagnostics service with the action to run on
 * opening ({@value #ACTION_PARAMETER}); a plain GET would otherwise be enough to test, connect or disconnect a
 * connection with its stored credentials. The form therefore adds a token only it can produce
 * ({@value #TOKEN_PARAMETER}): an HMAC, under a key drawn when this class loads and never leaving the JVM, of the
 * user, the connector, the action and an expiry. The service runs the action on opening only with a valid token;
 * without one it runs nothing and leaves the choice to the buttons of its own page.
 * <p>
 * A token is useless to another user, for another connector or for another action. It dies with the JVM (a
 * restart, another node of a cluster) and after {@link #LIFETIME_MILLIS}: the page then asks for one more click.
 */
final class DiagnosticsLaunch
{
	static final String ACTION_PARAMETER = "ftcdAction";
	static final String TOKEN_PARAMETER = "ftcdToken";
	/** A working day: a connector form left open longer asks for one more click in the diagnostics page. */
	static final long LIFETIME_MILLIS = 8L * 60L * 60L * 1000L;

	private static final String[] ACTIONS = { "test", "connect", "preview", "disconnect" };
	private static final byte[] KEY = DiagnosticsLaunch.newKey();

	private DiagnosticsLaunch()
	{
	}

	private static byte[] newKey()
	{
		final byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	/** True for an action the service can run on opening. */
	static boolean isAction(final String pAction)
	{
		for (final String action : DiagnosticsLaunch.ACTIONS)
		{
			if (action.equals(pAction))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * What the form appends after {@code ftcdAction=<action>} in the URL of a button: {@code &ftcdToken=...};
	 * empty when no token can be made (the page then asks for one more click).
	 */
	static String proof(final Session pSession, final String pConnectorId, final String pAction)
	{
		final String token = DiagnosticsLaunch.mint(DiagnosticsLaunch.KEY, DiagnosticsLaunch.userOf(pSession), pConnectorId, pAction,
			System.currentTimeMillis() + DiagnosticsLaunch.LIFETIME_MILLIS);
		return token == null ? "" : "&" + DiagnosticsLaunch.TOKEN_PARAMETER + "=" + token;
	}

	/** True when {@code pToken} was made by {@link #proof} for this user, this connector and this action, and has not expired. */
	static boolean proven(final String pToken, final Session pSession, final String pConnectorId, final String pAction)
	{
		return DiagnosticsLaunch.valid(DiagnosticsLaunch.KEY, pToken, DiagnosticsLaunch.userOf(pSession), pConnectorId, pAction, System.currentTimeMillis());
	}

	/** Pure: {@code <expiry in milliseconds>.<HMAC-SHA256 in hexadecimal>}, made of URL-safe characters only; null when something is missing. */
	static String mint(final byte[] pKey, final String pUser, final String pConnectorId, final String pAction, final long pExpiresAt)
	{
		if (pKey == null || pUser == null || pUser.isEmpty() || pConnectorId == null || pConnectorId.isEmpty() || !DiagnosticsLaunch.isAction(pAction))
		{
			return null;
		}
		final byte[] signature = DiagnosticsLaunch.sign(pKey, pUser, pConnectorId, pAction, pExpiresAt);
		return signature == null ? null : pExpiresAt + "." + DiagnosticsLaunch.hex(signature);
	}

	/** Pure: the token is well formed, not expired, not valid for longer than a token can be, and signed for exactly these values. */
	static boolean valid(final byte[] pKey, final String pToken, final String pUser, final String pConnectorId, final String pAction, final long pNow)
	{
		if (pToken == null || pToken.length() > 100)
		{
			return false;
		}
		final int dot = pToken.indexOf('.');
		if (dot <= 0 || dot > 15)
		{
			return false;
		}
		final long expiresAt;
		try
		{
			expiresAt = Long.parseLong(pToken.substring(0, dot));
		}
		catch (final NumberFormatException ex)
		{
			return false;
		}
		if (expiresAt <= pNow || expiresAt - pNow > DiagnosticsLaunch.LIFETIME_MILLIS)
		{
			return false;
		}
		final String expected = DiagnosticsLaunch.mint(pKey, pUser, pConnectorId, pAction, expiresAt);
		// compared in constant time: the answer must not tell how many characters of a guess were right
		return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), pToken.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] sign(final byte[] pKey, final String pUser, final String pConnectorId, final String pAction, final long pExpiresAt)
	{
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pKey, "HmacSHA256"));
			// each part is preceded by its length, so that no two different triples sign the same text
			for (final String part : new String[] { pUser, pConnectorId, pAction, String.valueOf(pExpiresAt) })
			{
				final byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
				mac.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
				mac.update(bytes);
			}
			return mac.doFinal();
		}
		catch (final java.security.GeneralSecurityException ex)
		{
			return null;
		}
	}

	private static String hex(final byte[] pBytes)
	{
		final StringBuilder out = new StringBuilder(pBytes.length * 2);
		for (final byte one : pBytes)
		{
			out.append(Character.forDigit((one >> 4) & 0xf, 16)).append(Character.forDigit(one & 0xf, 16));
		}
		return out.toString();
	}

	private static String userOf(final Session pSession)
	{
		try
		{
			return pSession == null || pSession.getUserReference() == null ? null : pSession.getUserReference().getUserId();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
