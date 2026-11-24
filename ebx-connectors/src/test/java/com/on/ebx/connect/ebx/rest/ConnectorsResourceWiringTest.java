package com.on.ebx.connect.ebx.rest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import com.orchestranetworks.rest.inject.SessionContext;

/**
 * Wiring left by the provider lots (audit C2-03, C2-16, C2-17, C2-10): the callers pass the session to the reader overloads, a refusal
 * speaks the language of Accept-Language, the webhook information route and the catalog preview go through the common right check,
 * and the cross-site guard has no webhook exemption any more.
 */
public final class ConnectorsResourceWiringTest
{
	private ConnectorsResourceWiringTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String rest = source("ebx/rest/ConnectorsResource.java");
		final String tools = source("ebx/mcp/ConnectorsTools.java");
		final String ui = source("ebx/ui/ConflictManagerUserService.java");
		check(rest.contains("ConnectorsTools.outboundPage(repo, session, mapping.getId()") && !rest.contains("repo.outboundStates(mapping.getId()"), "REST outbound passes the session to the shared paged answer (C2-07)");
		check(rest.contains("resolver.page(session, pStatus") && !rest.contains("resolver.page(pStatus"), "REST conflicts pass the session");
		check(tools.contains("outboundPage(this.repo(), this.session, mapping.getId()") && tools.contains("pRepo.pageOutboundStates(pSession, pMappingId") && tools.contains("resolver.page(this.session, status"), "MCP passes the session");
		check(tools.contains("this.session.getLocale()"), "MCP refusal in the session's locale");
		check(ui.contains("pResolver.page(pSession,") && ui.contains("pResolver.pageOfIds(pSession,"), "the conflict screen passes the session");
		check(rest.contains("this.refused(\"webhookInfo\"") && rest.contains("this.refused(\"catalogPreview\""), "webhookInfo and catalogPreview are checked");
		final String csrf = source("ebx/rest/CsrfGuard.java");
		check(!csrf.contains("X-Webhook-Secret") && !csrf.contains("feedProof") && !csrf.contains("SIGNATURE_HEADER"), "no webhook exemption in the cross-site guard");
		check(!source("ebx/ConnectorsInstaller.java").contains("expected in X-Webhook-Secret"), "the webhook seed names no secret");

		// a caller without session: refused in French with Accept-Language fr, English otherwise, on the webhook information route
		final String fr = refusalOf("fr-FR,fr;q=0.9");
		check(fr.indexOf("Accès refusé") >= 0 || fr.toLowerCase().indexOf("refus") >= 0, "French refusal: " + fr);
		final String en = refusalOf(null);
		check(en.indexOf("Access denied") >= 0, "English refusal: " + en);
		System.out.println("ConnectorsResourceWiringTest OK");
	}

	private static String refusalOf(final String pAcceptLanguage) throws Exception
	{
		final SessionContext context = (SessionContext) Proxy.newProxyInstance(ConnectorsResourceWiringTest.class.getClassLoader(), new Class<?>[] { SessionContext.class },
			new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					return null;
				}
			});
		final HttpHeaders headers = (HttpHeaders) Proxy.newProxyInstance(ConnectorsResourceWiringTest.class.getClassLoader(), new Class<?>[] { HttpHeaders.class },
			new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					return "getHeaderString".equals(pMethod.getName()) && "Accept-Language".equals(pArgs[0]) ? pAcceptLanguage : null;
				}
			});
		final Response response = new ConnectorsResource(context, headers).webhookInfo("x");
		check(response.getStatus() == 403, "webhookInfo without a session is refused: " + response.getStatus());
		return String.valueOf(response.getEntity());
	}

	private static String source(final String pPath) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get("src/main/java/com/on/ebx/connect/" + pPath)), StandardCharsets.UTF_8);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
