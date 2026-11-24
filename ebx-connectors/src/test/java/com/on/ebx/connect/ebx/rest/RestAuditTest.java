package com.on.ebx.connect.ebx.rest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import com.on.ebx.connect.ebx.ConnectorsAudit;

/**
 * Guards the audit line of the REST routes: written once, when the answer of a route that announced itself
 * leaves, with the outcome the status says, the identifiers of the request and nothing else of it.
 */
public final class RestAuditTest
{
	private RestAuditTest() { }

	public static void main(final String[] pArgs)
	{
		check(ConnectorsAudit.OK.equals(RestAudit.outcome(false, 200)) && ConnectorsAudit.OK.equals(RestAudit.outcome(false, 204))
			&& ConnectorsAudit.ERROR.equals(RestAudit.outcome(false, 404)) && ConnectorsAudit.ERROR.equals(RestAudit.outcome(false, 500))
			&& ConnectorsAudit.ERROR.equals(RestAudit.outcome(false, -1)) && ConnectorsAudit.REFUSED.equals(RestAudit.outcome(true, 403)), "outcome of a status");

		final List<String> lines = new ArrayList<String>();
		ConnectorsAudit.use(new ConnectorsAudit.Sink()
		{
			@Override
			public void write(final String pLine)
			{
				lines.add(pLine);
			}
		});
		final RestAudit audit = new RestAudit();

		// a read route never announces itself: nothing is written
		final ContainerRequestContext read = request(path(), query());
		audit.filter(read);
		audit.filter(read, response(200));
		check(lines.isEmpty(), "a route that did not announce itself is not traced");

		// POST /v1/sync/7?key=SECRET&dryRun=true&inputs=SECRET
		final ContainerRequestContext sync = request(path("mapping", "7"), query("key", "SECRET-KEY", "dryRun", "true", "inputs", "token=SECRET", "predicate", "SECRET"));
		audit.filter(sync);
		RestAudit.begin("sync", "alice", false);
		audit.filter(sync, response(200));
		check(lines.size() == 1 && lines.get(0).startsWith("[connectors] audit: user=alice rest=sync mapping=7 dryRun=true outcome=ok status=200 ms="),
			"the line of a synchronisation: " + lines);
		audit.filter(sync, response(200));
		check(lines.size() == 1, "one line per request");

		// POST /v1/conflicts/3/accept?comment=SECRET, refused
		final ContainerRequestContext decide = request(path("id", "3", "decision", "accept"), query("comment", "SECRET-COMMENT"));
		audit.filter(decide);
		RestAudit.begin("decide", "bob", true);
		audit.filter(decide, response(403));
		check(lines.size() == 2 && lines.get(1).startsWith("[connectors] audit: user=bob rest=decide id=3 decision=accept outcome=refused status=403 ms="),
			"the line of a refused decision: " + lines);

		// POST /v1/connectors/openapi?id=Wiki&url=https://...token=SECRET, failing
		final ContainerRequestContext openapi = request(path(), query("id", "Wiki", "url", "https://example.org/spec?token=SECRET", "label", "SECRET-LABEL"));
		audit.filter(openapi);
		RestAudit.begin("importOpenApi", "alice", false);
		audit.filter(openapi, response(400));
		check(lines.size() == 3 && lines.get(2).startsWith("[connectors] audit: user=alice rest=importOpenApi id=Wiki outcome=error status=400 ms="),
			"the line of a failed import: " + lines);
		for (final String line : lines)
		{
			check(!line.contains("SECRET"), "a parameter value leaked: " + line);
		}

		// an answer that never came through here must not be charged to the next request of the thread
		RestAudit.begin("replay", "alice", false);
		final ContainerRequestContext next = request(path(), query());
		audit.filter(next);
		audit.filter(next, response(200));
		check(lines.size() == 3, "a request starts clean");

		// a request whose parameters cannot be read still gets its line
		RestAudit.begin("stopRun", "alice", false);
		audit.filter(request(null, null), response(200));
		check(lines.size() == 4 && lines.get(3).startsWith("[connectors] audit: user=alice rest=stopRun outcome=ok status=200 ms="), "no parameter, no subject: " + lines);
		ConnectorsAudit.use(null);
		System.out.println("RestAuditTest OK");
	}

	private static MultivaluedMap<String, String> path(final String... pPairs)
	{
		return RestAuditTest.map(pPairs);
	}

	private static MultivaluedMap<String, String> query(final String... pPairs)
	{
		return RestAuditTest.map(pPairs);
	}

	private static MultivaluedMap<String, String> map(final String... pPairs)
	{
		final MultivaluedMap<String, String> out = new MultivaluedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2)
		{
			out.add(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	private static ContainerRequestContext request(final MultivaluedMap<String, String> pPath, final MultivaluedMap<String, String> pQuery)
	{
		final UriInfo uri = (UriInfo) Proxy.newProxyInstance(RestAuditTest.class.getClassLoader(), new Class<?>[] { UriInfo.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("getPathParameters".equals(pMethod.getName()))
				{
					return pPath;
				}
				if ("getQueryParameters".equals(pMethod.getName()))
				{
					return pQuery;
				}
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
		return (ContainerRequestContext) Proxy.newProxyInstance(RestAuditTest.class.getClassLoader(), new Class<?>[] { ContainerRequestContext.class },
			new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
				{
					if ("getUriInfo".equals(pMethod.getName()))
					{
						return uri;
					}
					throw new UnsupportedOperationException(pMethod.getName());
				}
			});
	}

	private static ContainerResponseContext response(final int pStatus)
	{
		return (ContainerResponseContext) Proxy.newProxyInstance(RestAuditTest.class.getClassLoader(), new Class<?>[] { ContainerResponseContext.class },
			new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
				{
					if ("getStatus".equals(pMethod.getName()))
					{
						return Integer.valueOf(pStatus);
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
