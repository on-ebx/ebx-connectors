package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** RS256 JSON Web Tokens signed with a PKCS#8 private key (Google service accounts, Snowflake key pairs...). */
public final class Jwt
{
	private Jwt()
	{
	}

	public static PrivateKey privateKey(final String pPem) throws Exception
	{
		final String body = pPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\\\n", "").replaceAll("\\s", "");
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
	}

	/** {@code header.payload.signature}; the payload is given as JSON text. */
	public static String sign(final String pPayloadJson, final PrivateKey pKey, final String pKeyId) throws Exception
	{
		final String header = pKeyId == null ? "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
				: "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + pKeyId + "\"}";
		final String signingInput = Jwt.base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ Jwt.base64Url(pPayloadJson.getBytes(StandardCharsets.UTF_8));
		final Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(pKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
		return signingInput + "." + Jwt.base64Url(signature.sign());
	}

	public static String base64Url(final byte[] pBytes)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(pBytes);
	}

	/**
	 * Google OAuth 2.0 for a service account: a signed assertion exchanged for an access token.
	 *
	 * @param pServiceAccountJson the JSON key file content ({@code client_email}, {@code private_key}).
	 */
	public static String googleAccessToken(final String pServiceAccountJson, final String pScope, final int pTimeoutMs) throws Exception
	{
		return Jwt.googleAccessToken(pServiceAccountJson, pScope, pTimeoutMs,
			"https://oauth2.googleapis.com/token");
	}

	/** Testable/service-account exchange variant with an explicitly governed token endpoint. */
	static String googleAccessToken(final String pServiceAccountJson, final String pScope,
		final int pTimeoutMs, final String pTokenEndpoint) throws Exception
	{
		final com.fasterxml.jackson.databind.JsonNode key = Json.parse(pServiceAccountJson);
		if (key == null || key.get("client_email") == null || key.get("private_key") == null)
		{
			throw new IllegalArgumentException("Google: the service account JSON key (client_email, private_key) is required");
		}
		final long now = System.currentTimeMillis() / 1000L;
		final String payload = "{\"iss\":\"" + key.get("client_email").asText() + "\",\"scope\":\"" + pScope
				+ "\",\"aud\":\"" + pTokenEndpoint + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600L) + "}";
		final String assertion = Jwt.sign(payload, Jwt.privateKey(key.get("private_key").asText()),
			key.get("private_key_id") == null ? null : key.get("private_key_id").asText());
		final HttpJsonClient client = new HttpJsonClient(pTimeoutMs, pTimeoutMs, "EBX-Connectors/1.0 (google)");
		final java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		final String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
			+ HttpJsonClient.encode(assertion);
		final HttpJsonClient.Response response = client.sendBytesWithoutRedirects("POST", pTokenEndpoint,
			headers, form.getBytes(StandardCharsets.UTF_8), 65536);
		final com.fasterxml.jackson.databind.JsonNode token = Json.at(response.getBody(), "access_token");
		if (!response.isSuccess() || token == null)
		{
			throw new IllegalStateException("Google token exchange failed (HTTP " + response.getStatus() + ")");
		}
		return token.asText();
	}
}
