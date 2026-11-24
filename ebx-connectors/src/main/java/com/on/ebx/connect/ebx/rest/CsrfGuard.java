package com.on.ebx.connect.ebx.rest;

import java.util.Locale;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import com.on.ebx.connect.core.ConnectorService;

/**
 * Cross-site request forgery guard of the module's REST application ({@code /ebx-connectors/rest/...}, the MCP
 * endpoint included). EBX accepts its session cookie on these routes and several of them change something with
 * nothing but a path and a query string ({@code POST /v1/sync/{mapping}}, {@code /v1/replay/{run}},
 * {@code /v1/conflicts/{id}/{decision}}, {@code DELETE /v1/mappings/{mapping}}): a page of another site could
 * make the browser of a signed-in designer send one. A state-changing request (POST, PUT, PATCH, DELETE) is
 * therefore let through only when it shows it is not such a ride:
 * <ul>
 * <li>it carries an {@code Authorization} header (Basic, token: a script, an MCP client, another module);</li>
 * <li>or an {@code X-Requested-With} header (a page of another site cannot add a header of its own without a
 * CORS pre-flight, which EBX does not grant);</li>
 * <li>or its {@code Origin} (when the browser sent none, its {@code Referer}) names the host and port the
 * request itself was sent to: a page of this EBX calling with the session cookie
 * ({@code fetch(..., {method: 'POST'})} sends {@code Origin});</li>
 * </ul>
 * Anything else is answered 403 before the route runs. GET, HEAD and OPTIONS are never checked: no route of
 * this module changes anything on them.
 */
@Provider
public final class CsrfGuard implements ContainerRequestFilter
{
	static final String REFUSAL = "Cross-site request protection: a POST, PUT, PATCH or DELETE on this API must carry an Authorization header"
		+ " (send the credentials with the request itself, not after a 401), or an X-Requested-With header, or come from a page of this EBX"
		+ " (Origin or Referer naming the same host and port).";

	@Override
	public void filter(final ContainerRequestContext pContext)
	{
		final String method = pContext.getMethod();
		if (!CsrfGuard.mustCheck(method))
		{
			return;
		}
		final String path = pContext.getUriInfo() == null ? null : pContext.getUriInfo().getPath();
		final String origin = pContext.getHeaderString("Origin");
		final String referer = pContext.getHeaderString("Referer");
		if (CsrfGuard.allowed(method, pContext.getHeaderString("Authorization"), pContext.getHeaderString("X-Requested-With"), origin, referer,
			pContext.getHeaderString("Host"), pContext.getHeaderString("X-Forwarded-Host")))
		{
			return;
		}
		ConnectorService.log().warn("[connectors] cross-site request refused: " + method + " " + CsrfGuard.forLog(path) + " (Origin " + CsrfGuard.forLog(origin)
			+ ", Referer host " + CsrfGuard.forLog(CsrfGuard.authorityOf(referer)) + ")");
		pContext.abortWith(Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON).entity("{\"error\":\"" + CsrfGuard.REFUSAL + "\"}").build());
	}

	/** Pure: POST, PUT, PATCH and DELETE are checked. */
	static boolean mustCheck(final String pMethod)
	{
		final String method = pMethod == null ? "" : pMethod.toUpperCase(Locale.ROOT);
		return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
	}

	/**
	 * Pure: the decision for one request, from its method and headers. {@code pHost} is the {@code Host} header and
	 * {@code pForwardedHost} the {@code X-Forwarded-Host} a reverse proxy adds when it rewrites {@code Host} (a
	 * browser cannot set either on a cross-site request, so both say where the user's browser sent the request).
	 * When the browser sent an {@code Origin}, it alone decides: a {@code Referer} is only read without one.
	 */
	static boolean allowed(final String pMethod, final String pAuthorization, final String pRequestedWith, final String pOrigin, final String pReferer,
		final String pHost, final String pForwardedHost)
	{
		if (!CsrfGuard.mustCheck(pMethod) || CsrfGuard.present(pAuthorization) || CsrfGuard.present(pRequestedWith))
		{
			return true;
		}
		final String source = CsrfGuard.present(pOrigin) ? pOrigin : pReferer;
		if (!CsrfGuard.present(source))
		{
			return false;
		}
		if (CsrfGuard.sameHost(source, pHost))
		{
			return true;
		}
		if (pForwardedHost != null)
		{
			for (final String one : pForwardedHost.split(","))
			{
				if (CsrfGuard.sameHost(source, one))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Pure: true when {@code pSource} (an {@code Origin}, or a {@code Referer} URL) is an http(s) address on the
	 * host and port of {@code pHost} (a {@code Host} header value, {@code host[:port]}). A {@code Host} without a
	 * port was sent to the default port of the scheme the browser used, so the source must be on the default port
	 * of its own scheme; the scheme the server sees is not compared, because behind a proxy that ends TLS it is
	 * not the browser's.
	 */
	static boolean sameHost(final String pSource, final String pHost)
	{
		if (!CsrfGuard.present(pSource) || !CsrfGuard.present(pHost))
		{
			return false;
		}
		final String source = pSource.trim();
		final int schemeEnd = source.indexOf("://");
		if (schemeEnd <= 0)
		{
			// "null" (a sandboxed frame, a redirect across sites) or anything that is not a URL
			return false;
		}
		final String scheme = source.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
		final int defaultPort = "http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : -1;
		if (defaultPort < 0)
		{
			return false;
		}
		final String[] from = CsrfGuard.hostAndPort(CsrfGuard.authorityOf(source));
		final String[] to = CsrfGuard.hostAndPort(pHost.trim());
		if (from == null || to == null || !from[0].equals(to[0]))
		{
			return false;
		}
		final int fromPort = from[1] == null ? defaultPort : CsrfGuard.port(from[1]);
		if (fromPort < 0)
		{
			return false;
		}
		return to[1] == null ? fromPort == defaultPort : fromPort == CsrfGuard.port(to[1]);
	}

	/** The {@code host[:port]} of a URL, without user information; null when the text is not a URL. */
	static String authorityOf(final String pUrl)
	{
		if (pUrl == null)
		{
			return null;
		}
		final String url = pUrl.trim();
		final int schemeEnd = url.indexOf("://");
		if (schemeEnd <= 0)
		{
			return null;
		}
		int end = url.length();
		for (int i = schemeEnd + 3; i < url.length(); i++)
		{
			final char c = url.charAt(i);
			if (c == '/' || c == '?' || c == '#' || c == '\\')
			{
				end = i;
				break;
			}
		}
		final String authority = url.substring(schemeEnd + 3, end);
		final int user = authority.lastIndexOf('@');
		return user < 0 ? authority : authority.substring(user + 1);
	}

	/** {@code [host in lower case, port or null]}; null when there is no host or the port is not a number. */
	private static String[] hostAndPort(final String pAuthority)
	{
		if (pAuthority == null || pAuthority.isEmpty())
		{
			return null;
		}
		final String host;
		String port = null;
		if (pAuthority.charAt(0) == '[')
		{
			// an IPv6 literal carries colons of its own
			final int close = pAuthority.indexOf(']');
			if (close < 0)
			{
				return null;
			}
			host = pAuthority.substring(0, close + 1);
			final String rest = pAuthority.substring(close + 1);
			if (!rest.isEmpty())
			{
				if (rest.charAt(0) != ':')
				{
					return null;
				}
				port = rest.substring(1);
			}
		}
		else
		{
			final int colon = pAuthority.indexOf(':');
			host = colon < 0 ? pAuthority : pAuthority.substring(0, colon);
			port = colon < 0 ? null : pAuthority.substring(colon + 1);
		}
		if (host.isEmpty() || (port != null && CsrfGuard.port(port) < 0))
		{
			return null;
		}
		return new String[] { host.toLowerCase(Locale.ROOT), port };
	}

	private static int port(final String pPort)
	{
		if (pPort == null || pPort.isEmpty() || pPort.length() > 5)
		{
			return -1;
		}
		for (int i = 0; i < pPort.length(); i++)
		{
			if (pPort.charAt(i) < '0' || pPort.charAt(i) > '9')
			{
				return -1;
			}
		}
		return Integer.parseInt(pPort);
	}

	/** Pure: the webhook route of a feed, {@code v1/webhook/{feed}}, as the path under the application. */
	static boolean isWebhook(final String pPath)
	{
		if (pPath == null)
		{
			return false;
		}
		String path = pPath;
		while (path.startsWith("/"))
		{
			path = path.substring(1);
		}
		while (path.endsWith("/"))
		{
			path = path.substring(0, path.length() - 1);
		}
		final String prefix = "v1/webhook/";
		return path.startsWith(prefix) && path.length() > prefix.length() && path.indexOf('/', prefix.length()) < 0;
	}

	private static boolean present(final String pValue)
	{
		return pValue != null && !pValue.trim().isEmpty();
	}

	/** A header or a path as one short log token: what the caller sent is never trusted to be printable. */
	static String forLog(final String pValue)
	{
		if (pValue == null)
		{
			return "-";
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValue.length() && out.length() < 120; i++)
		{
			final char c = pValue.charAt(i);
			out.append(c < 0x20 || c == 0x7f || c == ' ' ? '_' : c);
		}
		return out.length() == 0 ? "-" : out.toString();
	}
}
