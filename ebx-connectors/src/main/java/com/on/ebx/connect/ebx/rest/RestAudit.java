package com.on.ebx.connect.ebx.rest;

import java.util.List;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import com.on.ebx.connect.ebx.ConnectorsAudit;

/**
 * Writes the audit line of a REST route that runs or changes something ({@link ConnectorsAudit}) when its answer
 * leaves, so that the line carries the outcome and the duration. The route announces itself where it checks the
 * caller's right ({@code ConnectorsResource.refused}, which every such route calls first); the answer is seen
 * here. Both happen on the thread that serves the request, which is what links them: a resource of this module
 * answers synchronously.
 */
@Provider
public final class RestAudit implements ContainerRequestFilter, ContainerResponseFilter
{
	/** The path parameters that designate something. */
	private static final String[] PATH_IDENTIFIERS = { "mapping", "runId", "template", "feed", "id", "decision" };
	/** The query parameters that designate something; never a key, a predicate, a URL, an input or a comment. */
	private static final String[] QUERY_IDENTIFIERS = { "connector", "mapping", "mappings", "connectors", "id" };
	private static final String[] QUERY_FLAGS = { "dryRun", "apply" };

	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<Pending>();

	private static final class Pending
	{
		private final String route;
		private final String user;
		private final boolean refused;
		private final long started = System.nanoTime();

		private Pending(final String pRoute, final String pUser, final boolean pRefused)
		{
			this.route = pRoute;
			this.user = pUser;
			this.refused = pRefused;
		}
	}

	/** Called by a route that asks RUN or MANAGE, before it does anything: its answer will be traced. */
	static void begin(final String pRoute, final String pUser, final boolean pRefused)
	{
		RestAudit.PENDING.set(new Pending(pRoute, pUser, pRefused));
	}

	/** A request starts: whatever an earlier request left on this thread (its answer never came through here) is not this one's. */
	@Override
	public void filter(final ContainerRequestContext pRequest)
	{
		RestAudit.PENDING.remove();
	}

	@Override
	public void filter(final ContainerRequestContext pRequest, final ContainerResponseContext pResponse)
	{
		final Pending pending = RestAudit.PENDING.get();
		if (pending == null)
		{
			return;
		}
		RestAudit.PENDING.remove();
		try
		{
			final int status = pResponse == null ? -1 : pResponse.getStatus();
			ConnectorsAudit.rest(pending.user, pending.route, RestAudit.subject(pRequest == null ? null : pRequest.getUriInfo()),
				RestAudit.outcome(pending.refused, status), status, (System.nanoTime() - pending.started) / 1000000L);
		}
		catch (final RuntimeException ex)
		{
			// the answer matters more than its trace
		}
	}

	/** Pure: refused when the caller lacked the right, otherwise what the HTTP status says. */
	static String outcome(final boolean pRefused, final int pStatus)
	{
		return pRefused ? ConnectorsAudit.REFUSED : pStatus >= 200 && pStatus < 400 ? ConnectorsAudit.OK : ConnectorsAudit.ERROR;
	}

	/** What the request designates, from its path and query parameters. */
	static String subject(final UriInfo pUri)
	{
		final StringBuilder out = new StringBuilder();
		if (pUri == null)
		{
			return "";
		}
		RestAudit.append(out, pUri.getPathParameters(), RestAudit.PATH_IDENTIFIERS, false);
		final MultivaluedMap<String, String> query = pUri.getQueryParameters();
		RestAudit.append(out, query, RestAudit.QUERY_IDENTIFIERS, false);
		RestAudit.append(out, query, RestAudit.QUERY_FLAGS, true);
		return out.toString();
	}

	private static void append(final StringBuilder pSubject, final MultivaluedMap<String, String> pParameters, final String[] pNames, final boolean pFlags)
	{
		if (pParameters == null)
		{
			return;
		}
		for (final String name : pNames)
		{
			final List<String> values = pParameters.get(name);
			final String value = values == null || values.isEmpty() ? null : values.get(0);
			if (pFlags)
			{
				if (value != null && "true".equalsIgnoreCase(value.trim()))
				{
					ConnectorsAudit.append(pSubject, name, "true");
				}
			}
			else
			{
				ConnectorsAudit.append(pSubject, name, value);
			}
		}
	}
}
