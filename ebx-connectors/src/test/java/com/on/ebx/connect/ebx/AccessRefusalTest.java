package com.on.ebx.connect.ebx;

import java.util.Locale;

import com.on.ebx.connect.core.ErrorCatalog;

/** Audit C2-17: the refusal is told in the language of the session or of the {@code Accept-Language} header. */
public final class AccessRefusalTest
{
	public static void main(final String[] pArgs)
	{
		final String en = ConnectorsAccess.refusal("the tool connectors_sync", ConnectorsAccess.Right.RUN);
		check(en.startsWith("Access denied: the tool connectors_sync requires the Connectors right RUN."), "English text kept: " + en);
		check(en.contains("table 'Access rights'") && en.contains("the role '"), "quotes rendered, not swallowed: " + en);

		final String fr = ConnectorsAccess.refusal("the tool connectors_sync", ConnectorsAccess.Right.RUN, ConnectorsAccess.localeOf("fr-FR,fr;q=0.9,en;q=0.5"));
		check(fr.startsWith("Accès refusé : the tool connectors_sync demande le droit Connectors exécution."), "French text: " + fr);
		check(fr.contains("« Droits d'accès »") && fr.contains("l'accorde"), "French apostrophes: " + fr);
		check(!fr.contains("Access denied"), "no English left: " + fr);

		check(ConnectorsAccess.localeOf(null).equals(Locale.ENGLISH) && ConnectorsAccess.localeOf("de-DE").equals(Locale.ENGLISH), "default English");
		check(ConnectorsAccess.localeOf("en-GB,fr;q=0.8").equals(Locale.ENGLISH) && ConnectorsAccess.localeOf("FR").equals(Locale.FRENCH), "first known language");
		check(ConnectorsAccess.refusal("x", ConnectorsAccess.Right.MANAGE, Locale.GERMAN).startsWith("Access denied"), "unknown locale falls back to English");

		// the engine's own refusal is not mistaken for a remote system refusing credentials, in either language
		check(ErrorCatalog.classify(en).getKind() == ErrorCatalog.Kind.ACCESS_DENIED, "English refusal classified");
		check(ErrorCatalog.classify(fr).getKind() == ErrorCatalog.Kind.ACCESS_DENIED, "French refusal classified");
		check(ErrorCatalog.classify("HTTP 403 forbidden").getKind() == ErrorCatalog.Kind.AUTH_REFUSED, "a remote refusal stays one");
		System.out.println("PASS: refusals follow the reader's language");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
