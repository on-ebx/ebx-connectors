package com.on.ebx.connect.core;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded, same-origin traversal of REST collection pages. */
public final class RestPageTraversal
{
	public interface Fetcher { HttpJsonClient.Response get(String pUrl) throws IOException; }

	/**
	 * Callback for {@link #stream}: one item of one page, delivered as soon as that page is fetched -
	 * never held in one array across pages. Return {@code false} to stop fetching further pages (a
	 * batch/circuit-breaker/cancellation decision, mirrored on {@code Connector.RowSink} one layer up -
	 * {@code connectors} package, which is allowed to depend on this {@code core} one; the reverse is
	 * not, so this interface stays plain {@code JsonNode}, not {@code spi.Connector}-aware, same
	 * reasoning as {@code Csv.RecordSink}).
	 */
	public interface ItemSink { boolean accept(JsonNode pItem); }

	private RestPageTraversal() { }

	public static ObjectNode collect(final String pInitialUrl, final String pItemsRoot, final String pNextRoot,
		final int pMaximumPages, final int pMaximumRecords, final Fetcher pFetcher) throws IOException
	{
		if (pMaximumPages < 1 || pMaximumRecords < 1) throw new IllegalArgumentException("Positive page and record limits are required");
		final URI origin = URI.create(pInitialUrl);
		final ObjectNode result = Json.object();
		final ArrayNode records = result.putArray("results");
		String url = pInitialUrl;
		int pages = 0;
		String continuation = null;
		while (url != null && pages < pMaximumPages && records.size() < pMaximumRecords)
		{
			final HttpJsonClient.Response response = pFetcher.get(url);
			if (!response.isSuccess()) throw new IOException("Page " + (pages + 1) + " answered HTTP " + response.getStatus());
			final JsonNode body = response.getBody();
			final JsonNode items = RestPayload.root(body, pItemsRoot);
			if (items != null && items.isArray()) for (final JsonNode item : items)
			{
				if (records.size() >= pMaximumRecords) break;
				records.add(item);
			}
			pages++;
			continuation = RestPagination.jsonNext(body, pNextRoot);
			if (continuation == null) continuation = RestPagination.linkNext(response.getHeader("Link"), url);
			if (continuation == null)
			{
				url = null;
				continue;
			}
			final URI next = URI.create(url).resolve(continuation);
			if (!sameOrigin(origin, next)) throw new IOException("Pagination continuation changed HTTP origin");
			url = next.toString();
		}
		result.put("count", records.size());
		result.put("pages", pages);
		if (continuation != null && (pages >= pMaximumPages || records.size() >= pMaximumRecords))
		{
			result.put("truncated", true);
			result.put("nextLink", continuation);
		}
		return result;
	}

	/**
	 * Volume lot 7b: like {@link #collect}, but each page's items are pushed to {@code pSink} as soon
	 * as that page is fetched, then the page's own {@link JsonNode} array is dropped - never
	 * accumulated into one {@code results} array across pages the way {@link #collect} does. Returns a
	 * summary ({@code count}, {@code pages}, {@code truncated}, {@code nextLink} when truncated) with
	 * the same meaning as {@link #collect}'s own fields, minus {@code results} (the caller already
	 * received every item through {@code pSink}).
	 * <p>
	 * A sink-initiated stop ({@code pSink.accept} returning {@code false} - a batch/circuit-breaker/
	 * cancellation decision, never a {@code pMaximumRecords} cutoff, which is enforced here before the
	 * sink ever sees more than that many items) stops fetching further pages at once and is reported as
	 * an ordinary, non-truncated read - the caller already knows why it stopped and reports that
	 * separately, matching {@code Connector.replay}/{@code JdbcConnector}'s own convention.
	 */
	public static ObjectNode stream(final String pInitialUrl, final String pItemsRoot, final String pNextRoot,
		final int pMaximumPages, final int pMaximumRecords, final ItemSink pSink, final Fetcher pFetcher) throws IOException
	{
		if (pMaximumPages < 1 || pMaximumRecords < 1) throw new IllegalArgumentException("Positive page and record limits are required");
		final URI origin = URI.create(pInitialUrl);
		String url = pInitialUrl;
		int pages = 0;
		int delivered = 0;
		boolean sinkStopped = false;
		String continuation = null;
		while (url != null && pages < pMaximumPages && delivered < pMaximumRecords && !sinkStopped)
		{
			final HttpJsonClient.Response response = pFetcher.get(url);
			if (!response.isSuccess()) throw new IOException("Page " + (pages + 1) + " answered HTTP " + response.getStatus());
			final JsonNode body = response.getBody();
			final JsonNode items = RestPayload.root(body, pItemsRoot);
			if (items != null && items.isArray())
			{
				for (final JsonNode item : items)
				{
					if (delivered >= pMaximumRecords)
					{
						break;
					}
					delivered++;
					if (!pSink.accept(item))
					{
						sinkStopped = true;
						break;
					}
				}
			}
			pages++;
			if (sinkStopped)
			{
				break;
			}
			continuation = RestPagination.jsonNext(body, pNextRoot);
			if (continuation == null) continuation = RestPagination.linkNext(response.getHeader("Link"), url);
			if (continuation == null)
			{
				url = null;
				continue;
			}
			final URI next = URI.create(url).resolve(continuation);
			if (!sameOrigin(origin, next)) throw new IOException("Pagination continuation changed HTTP origin");
			url = next.toString();
		}
		final ObjectNode result = Json.object();
		result.put("count", delivered);
		result.put("pages", pages);
		if (!sinkStopped && continuation != null && (pages >= pMaximumPages || delivered >= pMaximumRecords))
		{
			result.put("truncated", true);
			result.put("nextLink", continuation);
		}
		return result;
	}

	private static boolean sameOrigin(final URI pA, final URI pB)
	{
		return equal(pA.getScheme(), pB.getScheme()) && equal(pA.getHost(), pB.getHost()) && port(pA) == port(pB);
	}
	private static boolean equal(final String pA, final String pB) { return pA == null ? pB == null : pA.equalsIgnoreCase(pB); }
	private static int port(final URI pUri) { return pUri.getPort() >= 0 ? pUri.getPort() : "https".equalsIgnoreCase(pUri.getScheme()) ? 443 : 80; }
}
