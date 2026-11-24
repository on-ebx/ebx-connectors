package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.core.AwsCredentials;
import com.on.ebx.connect.core.AwsSigV4;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/** Shared bounded AWS Query API transport for SQS and SNS. */
abstract class AwsQueryConnectorSupport
{
	static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private ConnectorConfig config;

	final void setConfig(final ConnectorConfig pConfig) { this.config = pConfig; }
	final ConnectorConfig config() { return this.config; }

	final HttpJsonClient.Response query(final String pService, final String pEndpoint,
		final Map<String, String> pParameters) throws ConnectorException
	{
		try
		{
			final StringBuilder body = new StringBuilder();
			for (final Map.Entry<String, String> entry : new TreeMap<String, String>(pParameters).entrySet())
			{
				if (entry.getValue() == null) continue;
				if (body.length() > 0) body.append('&');
				body.append(AwsSigV4.encode(entry.getKey())).append('=').append(AwsSigV4.encode(entry.getValue()));
			}
			final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
			final AwsCredentials credentials = AwsCredentials.load();
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
			final String region = this.config.getProperty("region", "us-east-1");
			final Map<String, String> signed = AwsSigV4.sign("POST", pEndpoint, headers, bytes,
				credentials.getAccessKeyId(), credentials.getSecretAccessKey(), region, pService,
				credentials.getSessionToken());
			return new HttpJsonClient(this.config.getConnectTimeoutMs(), this.config.getReadTimeoutMs(),
				"EBX-Connectors/1.0 (" + pService + ")").sendBytesWithoutRedirects("POST", pEndpoint,
				signed, bytes, MAX_RESPONSE_BYTES);
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("AWS " + pService + ": " + ex.getMessage(), ex); }
	}

	static String element(final String pXml, final String pName)
	{
		final Matcher matcher = Pattern.compile("<(?:[A-Za-z0-9_]+:)?" + Pattern.quote(pName)
			+ "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?" + Pattern.quote(pName) + ">", Pattern.DOTALL).matcher(pXml);
		return matcher.find() ? unescape(matcher.group(1).trim()) : null;
	}
	static String unescape(final String pText)
	{
		return pText.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
			.replace("&apos;", "'").replace("&amp;", "&");
	}
	static int bounded(final String pValue, final int pDefault, final int pMin, final int pMax,
		final String pName) throws ConnectorException
	{
		try { final int value = pValue == null ? pDefault : Integer.parseInt(pValue);
			if (value < pMin || value > pMax) throw new ConnectorException(pName + " must be between " + pMin + " and " + pMax);
			return value;
		}
		catch (final NumberFormatException ex) { throw new ConnectorException(pName + " must be an integer"); }
	}
}
