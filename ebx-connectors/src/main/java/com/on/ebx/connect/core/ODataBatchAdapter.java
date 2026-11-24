package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.BatchRequest;
import com.on.ebx.connect.spi.BatchResult;
import com.on.ebx.connect.spi.ConnectorException;

/** Connector-level helpers around the version-specific OData batch wire format. */
public final class ODataBatchAdapter
{
	private ODataBatchAdapter() { }

	/** Preserves request order and turns missing, duplicate and HTTP error responses into item errors. */
	public static BatchResult results(final BatchRequest pRequest, final JsonNode pResponse) throws ConnectorException
	{
		final JsonNode responses = pResponse == null ? null : pResponse.get("responses");
		if (responses == null || !responses.isArray()) throw new ConnectorException("OData batch response has no responses array");
		final Map<String, JsonNode> byId = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode response : responses)
		{
			final JsonNode idNode = response.get("id");
			if (idNode == null || idNode.asText().trim().isEmpty()) continue;
			if (byId.put(idNode.asText(), response) != null) throw new ConnectorException("OData batch response repeated id '" + idNode.asText() + "'");
		}
		final List<BatchResult.ItemResult> results = new ArrayList<BatchResult.ItemResult>();
		for (final BatchRequest.Item requested : pRequest.getItems())
		{
			final JsonNode response = byId.get(requested.getId());
			if (response == null)
			{
				results.add(BatchResult.ItemResult.error(requested.getId(), 0, "OData batch response omitted this item"));
				continue;
			}
			final int status = response.path("status").asInt(0);
			if (status >= 200 && status < 300) results.add(BatchResult.ItemResult.success(requested.getId(), status, ODataSupport.normalise(response.get("body"))));
			else
			{
				final JsonNode body = response.get("body");
				String error = body == null ? response.path("reason").asText("OData operation failed") : Json.write(body);
				if (error.length() > 500) error = error.substring(0, 500) + "...";
				results.add(BatchResult.ItemResult.error(requested.getId(), status, error));
			}
		}
		return new BatchResult(results, true);
	}

	/** Relative URL with encoded query values, suitable for an OData batch sub-request. */
	public static String relativeUrl(final String pPath, final Map<String, String> pQuery)
	{
		if (pQuery == null || pQuery.isEmpty()) return pPath;
		final StringBuilder out = new StringBuilder(pPath).append('?');
		for (final Map.Entry<String, String> entry : pQuery.entrySet())
		{
			if (out.charAt(out.length() - 1) != '?') out.append('&');
			out.append(HttpJsonClient.encode(entry.getKey())).append('=').append(HttpJsonClient.encode(entry.getValue()));
		}
		return out.toString();
	}

	public static void requireResponseBound(final String pBody, final int pMaximumBytes) throws ConnectorException
	{
		try { if (pBody != null && pBody.getBytes("UTF-8").length > pMaximumBytes) throw new ConnectorException("OData batch response exceeds byte limit " + pMaximumBytes); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}
}
