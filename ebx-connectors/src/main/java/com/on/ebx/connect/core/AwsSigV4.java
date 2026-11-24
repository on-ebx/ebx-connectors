package com.on.ebx.connect.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for a single request (S3 and other AWS services), JDK only. */
public final class AwsSigV4
{
	private AwsSigV4()
	{
	}

	/**
	 * Adds {@code x-amz-date}, {@code x-amz-content-sha256}, {@code host} and {@code Authorization}
	 * to the headers for the given request; query parameters must already be in the URL.
	 */
	public static Map<String, String> sign(final String pMethod, final String pUrl, final Map<String, String> pHeaders, final byte[] pBody,
		final String pAccessKey, final String pSecretKey, final String pRegion, final String pService, final String pSessionToken) throws Exception
	{
		final URI uri = URI.create(pUrl);
		final SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
		stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
		final String amzDate = stamp.format(new Date());
		final String date = amzDate.substring(0, 8);
		final String payloadHash = AwsSigV4.hex(AwsSigV4.sha256(pBody == null ? new byte[0] : pBody));

		final Map<String, String> headers = new LinkedHashMap<String, String>(pHeaders == null ? new LinkedHashMap<String, String>() : pHeaders);
		headers.put("host", uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost());
		headers.put("x-amz-date", amzDate);
		headers.put("x-amz-content-sha256", payloadHash);
		if (pSessionToken != null && !pSessionToken.isEmpty())
		{
			headers.put("x-amz-security-token", pSessionToken);
		}
		final TreeMap<String, String> canonicalHeaders = new TreeMap<String, String>();
		for (final Map.Entry<String, String> header : headers.entrySet())
		{
			canonicalHeaders.put(header.getKey().toLowerCase(), header.getValue().trim().replaceAll("\\s+", " "));
		}
		final StringBuilder canonicalHeaderText = new StringBuilder();
		final StringBuilder signedHeaders = new StringBuilder();
		for (final Map.Entry<String, String> header : canonicalHeaders.entrySet())
		{
			canonicalHeaderText.append(header.getKey()).append(':').append(header.getValue()).append('\n');
			signedHeaders.append(signedHeaders.length() > 0 ? ";" : "").append(header.getKey());
		}
		final String canonicalRequest = pMethod + "\n" + AwsSigV4.canonicalPath(uri.getRawPath()) + "\n" + AwsSigV4.canonicalQuery(uri.getRawQuery()) + "\n"
				+ canonicalHeaderText + "\n" + signedHeaders + "\n" + payloadHash;
		final String scope = date + "/" + pRegion + "/" + pService + "/aws4_request";
		final String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + AwsSigV4.hex(AwsSigV4.sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
		byte[] key = AwsSigV4.hmac(("AWS4" + pSecretKey).getBytes(StandardCharsets.UTF_8), date);
		key = AwsSigV4.hmac(key, pRegion);
		key = AwsSigV4.hmac(key, pService);
		key = AwsSigV4.hmac(key, "aws4_request");
		final String signature = AwsSigV4.hex(AwsSigV4.hmac(key, stringToSign));
		headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + pAccessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
		headers.remove("host"); // set by the HTTP client itself
		return headers;
	}

	private static String canonicalPath(final String pRawPath)
	{
		if (pRawPath == null || pRawPath.isEmpty())
		{
			return "/";
		}
		// S3 expects the path encoded once; encode what URI left raw (spaces are already %20)
		final StringBuilder sb = new StringBuilder();
		for (final String segment : pRawPath.split("/", -1))
		{
			sb.append(sb.length() > 0 || pRawPath.startsWith("/") ? "/" : "").append(AwsSigV4.encode(AwsSigV4.decode(segment)));
		}
		return sb.length() == 0 ? "/" : sb.toString().replaceFirst("^//", "/");
	}

	private static String canonicalQuery(final String pRawQuery)
	{
		if (pRawQuery == null || pRawQuery.isEmpty())
		{
			return "";
		}
		final TreeMap<String, String> sorted = new TreeMap<String, String>();
		for (final String pair : pRawQuery.split("&"))
		{
			final int eq = pair.indexOf('=');
			final String name = eq < 0 ? pair : pair.substring(0, eq);
			final String value = eq < 0 ? "" : pair.substring(eq + 1);
			sorted.put(AwsSigV4.encode(AwsSigV4.decode(name)), AwsSigV4.encode(AwsSigV4.decode(value)));
		}
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<String, String> e : sorted.entrySet())
		{
			sb.append(sb.length() > 0 ? "&" : "").append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	private static String decode(final String pValue)
	{
		try
		{
			return java.net.URLDecoder.decode(pValue.replace("+", "%2B"), "UTF-8");
		}
		catch (final Exception ex)
		{
			return pValue;
		}
	}

	/** RFC 3986 encoding (unreserved characters kept, everything else percent-encoded). */
	public static String encode(final String pValue)
	{
		final StringBuilder sb = new StringBuilder();
		for (final byte b : pValue.getBytes(StandardCharsets.UTF_8))
		{
			final int c = b & 0xFF;
			if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~')
			{
				sb.append((char) c);
			}
			else
			{
				sb.append('%').append(String.format("%02X", c));
			}
		}
		return sb.toString();
	}

	static byte[] sha256(final byte[] pData) throws Exception
	{
		return MessageDigest.getInstance("SHA-256").digest(pData);
	}

	static byte[] hmac(final byte[] pKey, final String pData) throws Exception
	{
		final Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(pKey, "HmacSHA256"));
		return mac.doFinal(pData.getBytes(StandardCharsets.UTF_8));
	}

	public static String hex(final byte[] pBytes)
	{
		final StringBuilder sb = new StringBuilder();
		for (final byte b : pBytes)
		{
			sb.append(String.format("%02x", b & 0xFF));
		}
		return sb.toString();
	}
}
