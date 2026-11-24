package com.on.ebx.connect.core;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Generic OAuth 2.0 token provider for the REST connectors (authentication {@code OAUTH2}).
 * <p>
 * Properties of the connector: {@code oauth.tokenUrl} (token endpoint), {@code oauth.flow} =
 * {@code client_credentials} (default), {@code password}, {@code refresh_token} or
 * {@code jwt_bearer}; {@code oauth.clientId} (or {@code clientId}), the client secret in the
 * {@code apiKey} field (or {@code oauth.clientSecret}); {@code oauth.scope}, {@code oauth.audience},
 * {@code oauth.resource}; {@code oauth.clientAuth} = {@code body} (default) or {@code basic};
 * {@code oauth.refreshToken}; for {@code jwt_bearer}: {@code oauth.privateKey} (PKCS#8 PEM, usually a
 * {@code vault:} or {@code file:} reference), {@code oauth.issuer} (default: client id),
 * {@code oauth.subject} (default: username), {@code oauth.jwtAudience} (default: the token URL),
 * {@code oauth.jwtLifetimeSeconds} (300), {@code oauth.claim.<name>} extra claims;
 * {@code oauth.extra.<name>} extra form parameters; {@code oauth.tokenHeader} ({@code Authorization}),
 * {@code oauth.tokenPrefix} ({@code Bearer }), {@code oauth.tokenField} ({@code access_token}),
 * {@code oauth.expiresInSeconds} when the endpoint does not say (3600).
 * Tokens are cached per connector configuration and renewed a minute before they expire; a 401
 * answer invalidates the token and the call is retried once.
 */
public final class OAuth2
{
	private static final Object[] TOKEN_LOCKS = new Object[256];
	static
	{
		for (int i = 0; i < TOKEN_LOCKS.length; i++) TOKEN_LOCKS[i] = new Object();
	}
	/** Parsed OpenID Provider metadata. It describes endpoints; it does not perform an interactive login. */
	public static final class AuthorizationMetadata
	{
		public final String issuer;
		public final String authorizationEndpoint;
		public final String tokenEndpoint;
		public final String jwksUri;
		public final JsonNode raw;

		AuthorizationMetadata(final String pIssuer, final String pAuthorizationEndpoint,
			final String pTokenEndpoint, final String pJwksUri, final JsonNode pRaw)
		{
			this.issuer = pIssuer;
			this.authorizationEndpoint = pAuthorizationEndpoint;
			this.tokenEndpoint = pTokenEndpoint;
			this.jwksUri = pJwksUri;
			this.raw = pRaw;
		}
	}
	/** A token and what the endpoint said (Salesforce adds {@code instance_url}, for instance). */
	public static final class Token
	{
		public final String accessToken;
		public final long expiry;
		public final JsonNode raw;

		Token(final String pAccessToken, final long pExpiry, final JsonNode pRaw)
		{
			this.accessToken = pAccessToken;
			this.expiry = pExpiry;
			this.raw = pRaw;
		}
	}

	/** Unchecked so that {@code headers()} can raise it; the REST base connector turns it into a ConnectorException. */
	public static final class TokenException extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		public TokenException(final String pMessage, final Throwable pCause)
		{
			super(pMessage, pCause);
		}
	}

	private static final ConcurrentHashMap<String, Token> TOKENS = new ConcurrentHashMap<String, Token>();

	private OAuth2()
	{
	}

	/**
	 * Reads an OpenID Connect discovery document. The caller may persist the returned endpoints and
	 * use an EBX user service for the browser redirect/callback; this method deliberately does not
	 * claim to implement that interactive lifecycle.
	 */
	public static AuthorizationMetadata discover(final HttpJsonClient pClient, final String pDiscoveryUrl)
	{
		if (pDiscoveryUrl == null || pDiscoveryUrl.trim().isEmpty())
		{
			throw new TokenException("OpenID Connect: discovery URL is not set", null);
		}
		try
		{
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Accept", "application/json");
			final HttpJsonClient.Response response = pClient.send("GET", pDiscoveryUrl.trim(), headers, null);
			if (!response.isSuccess())
			{
				throw new TokenException("OpenID Connect: discovery endpoint answered HTTP " + response.getStatus(), null);
			}
			final JsonNode body = response.getBody();
			final String issuer = requiredMetadata(body, "issuer");
			final String authorization = requiredMetadata(body, "authorization_endpoint");
			final String token = requiredMetadata(body, "token_endpoint");
			final JsonNode jwks = body == null ? null : body.get("jwks_uri");
			return new AuthorizationMetadata(issuer, authorization, token,
				jwks == null || jwks.isNull() ? null : jwks.asText(), body);
		}
		catch (final java.io.IOException ex)
		{
			throw new TokenException("OpenID Connect: discovery endpoint unreachable: " + ex.getMessage(), ex);
		}
	}

	private static String requiredMetadata(final JsonNode pBody, final String pName)
	{
		final JsonNode value = pBody == null ? null : pBody.get(pName);
		if (value == null || !value.isTextual() || value.asText().trim().isEmpty())
		{
			throw new TokenException("OpenID Connect: discovery document has no " + pName, null);
		}
		return value.asText().trim();
	}

	/** The value of the authorization header for a connector ({@code Bearer <token>}), from the cache when fresh. */
	public static String bearer(final ConnectorConfig pConfig, final HttpJsonClient pClient)
	{
		return pConfig.getProperty("oauth.tokenPrefix", "Bearer ") + OAuth2.token(pConfig, pClient, null, null).accessToken;
	}

	/** The token for a connector; {@code pTokenUrl} and {@code pJwtAudience} override the properties (connectors that know their endpoint). */
	public static Token token(final ConnectorConfig pConfig, final HttpJsonClient pClient, final String pTokenUrl, final String pJwtAudience)
	{
		final String key = pConfig.getId() + "|" + Hashing.sha256(pConfig.fingerprint() + "|" + pTokenUrl);
		synchronized (TOKEN_LOCKS[(key.hashCode() & Integer.MAX_VALUE) % TOKEN_LOCKS.length])
		{
		final Token cached = OAuth2.TOKENS.get(key);
		if (cached != null && System.currentTimeMillis() < cached.expiry)
		{
			return cached;
		}
		final Token fresh = OAuth2.request(pConfig, pClient, pTokenUrl, pJwtAudience);
		OAuth2.TOKENS.put(key, fresh);
		return fresh;
		}
	}

	/** Drops the cached token of a connector (after a 401). */
	public static void invalidate(final ConnectorConfig pConfig)
	{
		final String prefix = pConfig.getId() + "|";
		for (final String key : OAuth2.TOKENS.keySet())
		{
			if (key.startsWith(prefix))
			{
				OAuth2.TOKENS.remove(key);
			}
		}
	}

	/** What is missing in the configuration for the chosen flow, or null when it is complete. */
	public static String validate(final ConnectorConfig pConfig, final boolean pTokenUrlKnown)
	{
		final String flow = OAuth2.flow(pConfig);
		if ("authorization_code".equals(flow))
		{
			return "authorization_code requires an interactive EBX user-service redirect/callback; use a stored refresh_token after authorization";
		}
		if (!pTokenUrlKnown && pConfig.getProperty("oauth.tokenUrl", null) == null)
		{
			return "property oauth.tokenUrl (token endpoint) is not set";
		}
		if ("jwt_bearer".equals(flow))
		{
			return pConfig.isPlaceholder(pConfig.getProperty("oauth.privateKey", null)) ? "property oauth.privateKey (PKCS#8 PEM, or a vault:/file: reference) is not set for the jwt_bearer flow" : null;
		}
		if (OAuth2.clientId(pConfig) == null)
		{
			return "property oauth.clientId (or clientId) is not set";
		}
		if (pConfig.isPlaceholder(OAuth2.clientSecret(pConfig)) && !"password".equals(flow))
		{
			return "the client secret is not set (apiKey field, or property oauth.clientSecret)";
		}
		if ("password".equals(flow) && (pConfig.getUsername() == null || pConfig.isPlaceholder(pConfig.getPassword())))
		{
			return "username and password are required by the password flow";
		}
		if ("refresh_token".equals(flow) && pConfig.isPlaceholder(pConfig.getProperty("oauth.refreshToken", null)))
		{
			return "property oauth.refreshToken is not set for the refresh_token flow";
		}
		return null;
	}

	// ------------------------------------------------------------------ request

	private static Token request(final ConnectorConfig pConfig, final HttpJsonClient pClient, final String pTokenUrl, final String pJwtAudience)
	{
		final String tokenUrl = pTokenUrl != null ? pTokenUrl : pConfig.getProperty("oauth.tokenUrl", null);
		final String problem = OAuth2.validate(pConfig, pTokenUrl != null);
		if (problem != null)
		{
			throw new TokenException("OAuth 2.0 (" + pConfig.getId() + "): " + problem, null);
		}
		final String flow = OAuth2.flow(pConfig);
		final Map<String, String> form = new LinkedHashMap<String, String>();
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		headers.put("Accept", "application/json");
		if ("jwt_bearer".equals(flow))
		{
			form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
			form.put("assertion", OAuth2.assertion(pConfig, tokenUrl, pJwtAudience));
			if (OAuth2.clientId(pConfig) != null)
			{
				form.put("client_id", OAuth2.clientId(pConfig));
			}
		}
		else
		{
			form.put("grant_type", flow);
			if ("password".equals(flow))
			{
				form.put("username", pConfig.getUsername());
				form.put("password", pConfig.getPassword());
			}
			if ("refresh_token".equals(flow))
			{
				form.put("refresh_token", pConfig.getProperty("oauth.refreshToken", ""));
			}
			if ("basic".equalsIgnoreCase(pConfig.getProperty("oauth.clientAuth", "body")))
			{
				final String credentials = OAuth2.clientId(pConfig) + ":" + String.valueOf(OAuth2.clientSecret(pConfig));
				headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			}
			else
			{
				form.put("client_id", OAuth2.clientId(pConfig));
				if (!pConfig.isPlaceholder(OAuth2.clientSecret(pConfig)))
				{
					form.put("client_secret", OAuth2.clientSecret(pConfig));
				}
			}
		}
		for (final String optional : new String[] { "scope", "audience", "resource" })
		{
			final String value = pConfig.getProperty("oauth." + optional, null);
			if (value != null && !value.trim().isEmpty())
			{
				form.put(optional, value.trim());
			}
		}
		for (final Map.Entry<String, String> property : pConfig.getProperties().entrySet())
		{
			if (property.getKey().startsWith("oauth.extra."))
			{
				form.put(property.getKey().substring("oauth.extra.".length()), property.getValue());
			}
		}
		final StringBuilder body = new StringBuilder();
		for (final Map.Entry<String, String> e : form.entrySet())
		{
			body.append(body.length() > 0 ? "&" : "").append(HttpJsonClient.encode(e.getKey())).append('=').append(HttpJsonClient.encode(e.getValue() == null ? "" : e.getValue()));
		}
		try
		{
			final HttpJsonClient.Response response = pClient.send("POST", tokenUrl, headers, body.toString());
			final JsonNode token = Json.at(response.getBody(), pConfig.getProperty("oauth.tokenField", "access_token"));
			if (!response.isSuccess() || token == null || token.isNull())
			{
				final JsonNode description = Json.at(response.getBody(), "error_description");
				final JsonNode error = Json.at(response.getBody(), "error");
				throw new TokenException("OAuth 2.0 (" + pConfig.getId() + "): token endpoint " + tokenUrl + " answered HTTP " + response.getStatus()
						+ (error == null ? "" : " " + error.asText()) + (description == null ? "" : ": " + description.asText()), null);
			}
			final JsonNode expiresIn = Json.at(response.getBody(), "expires_in");
			final long seconds;
			try
			{
				seconds = expiresIn != null && !expiresIn.isNull() ? Long.parseLong(expiresIn.asText())
					: Long.parseLong(pConfig.getProperty("oauth.expiresInSeconds", "3600"));
			}
			catch (final NumberFormatException ex)
			{
				throw new TokenException("OAuth 2.0 (" + pConfig.getId() + "): token endpoint returned an invalid expires_in", ex);
			}
			if (seconds <= 0L)
			{
				throw new TokenException("OAuth 2.0 (" + pConfig.getId() + "): token endpoint returned a non-positive expires_in", null);
			}
			// Renew early without extending a short-lived token beyond the lifetime stated by the issuer.
			final long skew = Math.min(60L, Math.max(1L, seconds / 10L));
			return new Token(token.asText(), System.currentTimeMillis() + Math.max(1L, seconds - skew) * 1000L, response.getBody());
		}
		catch (final java.io.IOException ex)
		{
			throw new TokenException("OAuth 2.0 (" + pConfig.getId() + "): token endpoint " + tokenUrl + " unreachable: " + ex.getMessage(), ex);
		}
	}

	private static String flow(final ConnectorConfig pConfig)
	{
		return pConfig.getProperty("oauth.flow", "client_credentials").trim();
	}

	private static String clientId(final ConnectorConfig pConfig)
	{
		final String id = pConfig.getProperty("oauth.clientId", pConfig.getProperty("clientId", null));
		return id == null || id.trim().isEmpty() ? null : id.trim();
	}

	private static String clientSecret(final ConnectorConfig pConfig)
	{
		final String secret = pConfig.getProperty("oauth.clientSecret", null);
		return secret != null && !secret.trim().isEmpty() ? secret.trim() : pConfig.getApiKey();
	}

	// ------------------------------------------------------------------ JWT bearer (RS256)

	private static String assertion(final ConnectorConfig pConfig, final String pTokenUrl, final String pJwtAudience)
	{
		final long now = System.currentTimeMillis() / 1000L;
		final long lifetime = Long.parseLong(pConfig.getProperty("oauth.jwtLifetimeSeconds", "300"));
		final ObjectNode claims = Json.object();
		claims.put("iss", pConfig.getProperty("oauth.issuer", OAuth2.clientId(pConfig)));
		final String subject = pConfig.getProperty("oauth.subject", pConfig.getUsername());
		if (subject != null)
		{
			claims.put("sub", subject);
		}
		claims.put("aud", pConfig.getProperty("oauth.jwtAudience", pJwtAudience != null ? pJwtAudience : pTokenUrl));
		claims.put("iat", now);
		claims.put("exp", now + lifetime);
		claims.put("jti", java.util.UUID.randomUUID().toString());
		for (final Map.Entry<String, String> property : pConfig.getProperties().entrySet())
		{
			if (property.getKey().startsWith("oauth.claim."))
			{
				claims.put(property.getKey().substring("oauth.claim.".length()), property.getValue());
			}
		}
		return OAuth2.signRs256(pConfig.getProperty("oauth.privateKey", ""), Json.write(claims));
	}

	/** A compact JWS (RS256) of the claims, signed with a PKCS#8 PEM private key. */
	public static String signRs256(final String pPrivateKeyPem, final String pClaimsJson)
	{
		try
		{
			final String pem = pPrivateKeyPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
			final PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
			final Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
			final String header = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes("UTF-8"));
			final String payload = url.encodeToString(pClaimsJson.getBytes("UTF-8"));
			final Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(key);
			signature.update((header + "." + payload).getBytes("UTF-8"));
			return header + "." + payload + "." + url.encodeToString(signature.sign());
		}
		catch (final Exception ex)
		{
			throw new TokenException("OAuth 2.0: cannot sign the JWT assertion (oauth.privateKey must be an unencrypted PKCS#8 RSA key in PEM): " + ex.getMessage(), ex);
		}
	}
}
