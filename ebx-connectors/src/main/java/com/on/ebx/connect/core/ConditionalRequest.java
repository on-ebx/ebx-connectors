package com.on.ebx.connect.core;

import java.util.LinkedHashMap;

/** HTTP precondition headers used by cache refreshes and optimistic writes. */
public final class ConditionalRequest
{
	private ConditionalRequest() { }
	public static java.util.Map<String, String> ifNoneMatch(final String pEtag)
	{
		final java.util.Map<String, String> headers = new LinkedHashMap<String, String>();
		if (pEtag != null && !pEtag.trim().isEmpty()) headers.put("If-None-Match", pEtag.trim());
		return headers;
	}
	public static java.util.Map<String, String> ifMatch(final String pEtag)
	{
		final java.util.Map<String, String> headers = new LinkedHashMap<String, String>();
		if (pEtag != null && !pEtag.trim().isEmpty()) headers.put("If-Match", pEtag.trim());
		return headers;
	}
	public static boolean notModified(final HttpJsonClient.Response pResponse) { return pResponse != null && pResponse.getStatus() == 304; }
}
