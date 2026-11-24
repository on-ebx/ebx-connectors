package com.on.ebx.connect.core;

/**
 * Who may post to {@code POST /v1/webhook/{feed}} (audit C2-10). Only an authenticated EBX user holding the RUN right
 * of the module: the route is served by the EBX REST Toolkit, which refuses every request without EBX credentials
 * before the module's code runs, so the former shared-secret ({@code X-Webhook-Secret}) and HMAC-signature modes could
 * never be reached by a third party without an account, and when they were they ran under the EBX login and password
 * stored on the connection. Both modes are removed: the sender holds an EBX account (a technical user, with a
 * token or a password of its own) that is given the RUN right, and the import runs under that account.
 */
public final class Webhooks
{
	/**
	 * Kept only because the cross-site guard names the header; no code verifies it any more.
	 *
	 * @deprecated the signed mode was removed (C2-10)
	 */
	@Deprecated
	public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

	/** The answer to a post: let it through. */
	public static final int OK = 200;
	/** No EBX session: the sender must authenticate as an EBX user. */
	public static final int UNAUTHENTICATED = 401;
	/** An EBX session without the RUN right. */
	public static final int FORBIDDEN = 403;

	private Webhooks()
	{
	}

	/**
	 * The HTTP status of the access decision: {@link #UNAUTHENTICATED} without a session, {@link #FORBIDDEN} when the
	 * session lacks the RUN right, {@link #OK} otherwise.
	 */
	public static int access(final boolean pAuthenticated, final boolean pMayRun)
	{
		if (!pAuthenticated)
		{
			return Webhooks.UNAUTHENTICATED;
		}
		return pMayRun ? Webhooks.OK : Webhooks.FORBIDDEN;
	}

	/** What a refused sender is told to do. */
	public static String refusal(final int pStatus)
	{
		if (pStatus == Webhooks.UNAUTHENTICATED)
		{
			return "Authentication required: post with the credentials of an EBX user (Authorization header). The shared-secret and signature modes of the webhook were removed.";
		}
		return "The EBX user posting to the webhook needs the Connectors right to run (see Administration > Connectors access).";
	}
}
