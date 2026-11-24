package com.on.ebx.connect.ebx.rest;

/**
 * Guards the cross-site request rule of the REST application: which requests are checked, and that every caller
 * known today passes it (a script with Basic authentication, an MCP client, a page of this EBX, a webhook sender with its EBX credentials)
 * while a form or a script of another site does not.
 */
public final class CsrfGuardTest
{
	private CsrfGuardTest() { }

	public static void main(final String[] pArgs)
	{
		for (final String method : new String[] { "POST", "put", "PATCH", "DELETE" })
		{
			check(CsrfGuard.mustCheck(method), method + " must be checked");
		}
		for (final String method : new String[] { "GET", "HEAD", "OPTIONS", null })
		{
			check(!CsrfGuard.mustCheck(method), method + " must not be checked");
			check(CsrfGuard.allowed(method, null, null, "https://evil.example", null, "localhost:8080", null), method + " is never refused");
		}

		// callers that are not a browser riding a cookie
		check(CsrfGuard.allowed("POST", "Basic YWRtaW46YWRtaW4=", null, null, null, "localhost:8080", null), "curl -u, an MCP client, another module: Authorization");
		check(CsrfGuard.allowed("DELETE", "EBX token", null, null, null, "localhost:8080", null), "a token in Authorization");
		check(CsrfGuard.allowed("POST", null, "XMLHttpRequest", null, null, "localhost:8080", null), "a custom header cannot be added across sites");
		check(!CsrfGuard.allowed("POST", " ", "", null, null, "localhost:8080", null), "blank headers prove nothing");

		// a page of this EBX: fetch(..., {method:'POST', credentials:'same-origin'}) sends Origin
		check(CsrfGuard.allowed("POST", null, null, "http://localhost:8080", null, "localhost:8080", null), "same-origin fetch on the demo instance");
		check(CsrfGuard.allowed("POST", null, null, "https://mdm.example.com", null, "mdm.example.com", null), "default https port on both sides");
		check(CsrfGuard.allowed("POST", null, null, "https://MDM.example.com:443", null, "mdm.example.com", null), "explicit default port, host case");
		check(CsrfGuard.allowed("POST", null, null, "https://mdm.example.com", null, "mdm.example.com:443", null), "Host carrying the default port");
		check(CsrfGuard.allowed("POST", null, null, "http://[::1]:8080", null, "[::1]:8080", null), "IPv6 loopback");
		check(CsrfGuard.allowed("POST", null, null, null, "http://localhost:8080/ebx-ui/ui/advanced/data/BConnectors?x=[a|b]", "localhost:8080", null),
			"no Origin: the Referer decides, whatever its path and query hold");
		check(CsrfGuard.allowed("POST", null, null, "https://mdm.example.com", null, "tomcat-internal:8080", "mdm.example.com"),
			"behind a proxy that rewrites Host: X-Forwarded-Host is where the browser sent the request");
		check(CsrfGuard.allowed("POST", null, null, "https://mdm.example.com", null, "tomcat-internal:8080", "other.example.com, mdm.example.com"),
			"a list of forwarded hosts");

		// a page of another site
		check(!CsrfGuard.allowed("POST", null, null, null, null, "localhost:8080", null), "no proof at all (an old form post without Origin nor Referer)");
		check(!CsrfGuard.allowed("POST", null, null, "https://evil.example", null, "localhost:8080", null), "another site");
		check(!CsrfGuard.allowed("POST", null, null, "http://localhost:3000", null, "localhost:8080", null), "same host, another port");
		check(!CsrfGuard.allowed("POST", null, null, "http://mdm.example.com:8081", null, "mdm.example.com", null), "another port against a default one");
		check(!CsrfGuard.allowed("POST", null, null, "http://127.0.0.1:8080", null, "localhost:8080", null), "another name of the same machine is another origin");
		check(!CsrfGuard.allowed("POST", null, null, "null", "http://localhost:8080/ebx/", "localhost:8080", null),
			"an opaque Origin is not rescued by a Referer");
		check(!CsrfGuard.allowed("POST", null, null, "https://evil.example", "http://localhost:8080/ebx/", "localhost:8080", null),
			"a foreign Origin is not rescued by a Referer");
		check(!CsrfGuard.allowed("POST", null, null, null, "https://evil.example/localhost:8080", "localhost:8080", null), "the Referer's host decides, not its path");
		check(!CsrfGuard.allowed("POST", null, null, null, "http://localhost:8080@evil.example/", "localhost:8080", null), "user information is not the host");
		check(!CsrfGuard.allowed("POST", null, null, null, "http://evil.example\\@localhost:8080/", "localhost:8080", null), "a backslash ends the host as a slash does");
		check(!CsrfGuard.allowed("POST", null, null, "http://localhost.evil.example:8080", null, "localhost:8080", null), "a longer host name");
		check(!CsrfGuard.allowed("POST", null, null, "file://localhost:8080", null, "localhost:8080", null), "only http and https origins");
		check(!CsrfGuard.allowed("POST", null, null, "http://localhost:80a", null, "localhost:80a", null), "a port that is not a number");
		check(!CsrfGuard.allowed("POST", null, null, "http://localhost:8080", null, null, null), "no Host to compare with");
		check(!CsrfGuard.allowed("POST", null, null, "https://evil.example", null, "localhost:8080", "mdm.example.com"), "a forwarded host does not open other sites");

		// C2-10: the webhook secret and signature headers prove nothing any more: allowed() has no parameter for them
		check(!CsrfGuard.allowed("POST", null, null, null, null, "localhost:8080", null), "a webhook post without Authorization is a plain post");

		// the webhook route of a feed, and only it
		check(CsrfGuard.isWebhook("v1/webhook/34") && CsrfGuard.isWebhook("/v1/webhook/Orders feed/"), "the webhook route");
		for (final String other : new String[] { null, "", "v1/webhook", "v1/webhook/", "v1/webhook/34/x", "v1/sync/webhook", "mcp", "v1/config" })
		{
			check(!CsrfGuard.isWebhook(other), "not the webhook route: " + other);
		}

		check("localhost:8080".equals(CsrfGuard.authorityOf("http://localhost:8080/a?b#c")) && CsrfGuard.authorityOf("null") == null, "authority of a URL");
		check("a_b_".equals(CsrfGuard.forLog("a b\n")) && "-".equals(CsrfGuard.forLog(null)) && CsrfGuard.forLog(new String(new char[500])).length() == 120,
			"what is logged of a caller's header is one short printable token");
		check(CsrfGuard.REFUSAL.indexOf('"') < 0 && CsrfGuard.REFUSAL.indexOf('\\') < 0, "the refusal is written into a JSON string as is");
		System.out.println("CsrfGuardTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
