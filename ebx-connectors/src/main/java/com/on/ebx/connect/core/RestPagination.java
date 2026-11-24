package com.on.ebx.connect.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Stateless helpers for the common offset, token, JSON-link and HTTP Link pagination styles. */
public final class RestPagination
{
	private RestPagination() { }

	public static Map<String, String> offset(final Map<String, String> pQuery, final String pOffsetName,
		final String pLimitName, final long pOffset, final int pLimit)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pQuery != null) query.putAll(pQuery);
		query.put(pOffsetName, String.valueOf(Math.max(0L, pOffset)));
		query.put(pLimitName, String.valueOf(Math.max(1, pLimit)));
		return query;
	}

	public static Map<String, String> token(final Map<String, String> pQuery, final String pTokenName,
		final String pToken, final String pLimitName, final int pLimit)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pQuery != null) query.putAll(pQuery);
		if (pToken != null && !pToken.isEmpty()) query.put(pTokenName, pToken);
		if (pLimitName != null) query.put(pLimitName, String.valueOf(Math.max(1, pLimit)));
		return query;
	}

	public static String jsonNext(final JsonNode pBody, final String pPath)
	{
		final JsonNode value = Json.at(pBody, pPath);
		return value == null || value.isNull() || value.asText().trim().isEmpty() ? null : value.asText();
	}

	public static String linkNext(final String pLinkHeader, final String pRequestUrl)
	{
		if (pLinkHeader == null) return null;
		for (final String part : pLinkHeader.split(","))
		{
			final int left = part.indexOf('<');
			final int right = part.indexOf('>', left + 1);
			if (left >= 0 && right > left && part.substring(right + 1).toLowerCase().contains("rel=\"next\""))
			{
				return URI.create(pRequestUrl).resolve(part.substring(left + 1, right)).toString();
			}
		}
		return null;
	}
}
