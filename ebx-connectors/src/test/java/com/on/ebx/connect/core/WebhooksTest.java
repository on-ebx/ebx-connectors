package com.on.ebx.connect.core;

/**
 * C2-10: the webhook has one mode, an EBX session holding the RUN right. No session is 401, a session without the right
 * is 403, and the refusal says what to do. (The former secret and signature modes were unreachable behind the REST
 * Toolkit and fell back to a stored password.)
 */
public final class WebhooksTest
{
	public static void main(final String[] pArgs)
	{
		check(Webhooks.access(false, false) == 401, "no session: 401");
		check(Webhooks.access(false, true) == 401, "a right cannot replace the session: 401");
		check(Webhooks.access(true, false) == 403, "a session without the RUN right: 403");
		check(Webhooks.access(true, true) == 200, "a session with the RUN right passes");
		final String unauthenticated = Webhooks.refusal(Webhooks.UNAUTHENTICATED);
		check(unauthenticated.contains("Authorization") && unauthenticated.contains("removed"), "401 tells to authenticate and that the old modes are gone: " + unauthenticated);
		check(Webhooks.refusal(Webhooks.FORBIDDEN).contains("right to run"), "403 names the missing right");
		System.out.println("PASS: the webhook accepts an EBX session with the RUN right only (401 without a session, 403 without the right)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
