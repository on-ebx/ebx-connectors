package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Response-root and schema helpers shared by REST and OpenAPI connectors. */
public final class RestPayload
{
	private RestPayload() { }
	/** Selects a configured dotted/JSON-pointer root; an empty root preserves the response. */
	public static JsonNode root(final JsonNode pBody, final String pRoot)
	{
		if (pBody == null || pRoot == null || pRoot.trim().isEmpty()) return pBody;
		if (pRoot.startsWith("/")) return pBody.at(pRoot);
		return Json.at(pBody, pRoot);
	}
	/** A stable schema fingerprint suitable for detecting a refreshed OpenAPI/schema document. */
	public static String schemaFingerprint(final JsonNode pSchema)
	{
		return Hashing.sha256(pSchema == null ? "null" : Json.write(pSchema));
	}
}
