package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.core.AwsSigV4;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Amazon S3 (and S3-compatible stores: MinIO, Ceph...) with Signature V4, JDK only.
 * {@code username} = access key id, {@code password} = secret access key (authentication BASIC),
 * properties {@code bucket}, {@code region} (eu-west-3...), optional {@code sessionToken},
 * {@code endpoint} (custom, path-style) and {@code root}. {@code baseUrl} may be left empty
 * (default {@code https://{bucket}.s3.{region}.amazonaws.com}).
 */
public final class S3Connector extends AbstractFileConnector
{
	@Override
	public String getType()
	{
		return "s3";
	}

	@Override
	public String getLabel()
	{
		return "Amazon S3 (files)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("CLOUD_STORAGE");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER).capability(com.on.ebx.connect.spi.ConnectorCapability.DISCOVER_OBJECTS).capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS).apiVersion("Amazon S3 REST / Signature Version 4").pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 1000, "NextContinuationToken")).configurationProperty(property("bucket", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true)).configurationProperty(property("endpoint", com.on.ebx.connect.spi.ConfigurationPropertyType.URL, false)).configurationProperty(property("region", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("sessionToken", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET, false));
		return b.build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c);
		for (final com.on.ebx.connect.spi.Operation op:getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String n,final com.on.ebx.connect.spi.ConfigurationPropertyType t,final boolean r){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,t).required(r).build();}

	private String bucketUrl() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final String bucket = cfg.getProperty("bucket", null);
		if (bucket == null)
		{
			throw new ConnectorException("S3: set the property bucket");
		}
		final String endpoint = cfg.getProperty("endpoint", cfg.getBaseUrl() == null || cfg.getBaseUrl().trim().isEmpty() ? null : cfg.getBaseUrl());
		if (endpoint != null && !endpoint.trim().isEmpty())
		{
			return endpoint.trim().replaceAll("/+$", "") + (endpoint.contains(bucket) ? "" : "/" + bucket);
		}
		return "https://" + bucket + ".s3." + cfg.getProperty("region", "us-east-1") + ".amazonaws.com";
	}

	private HttpJsonClient.Response request(final String pMethod, final String pKey, final Map<String, String> pQuery, final byte[] pBody, final String pContentType)
		throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (cfg.getUsername() == null || cfg.getUsername().isEmpty() || cfg.isPlaceholder(cfg.getPassword()))
		{
			throw new ConnectorException("S3: set the access key id as user name and the secret access key as password");
		}
		final StringBuilder url = new StringBuilder(this.bucketUrl());
		if (pKey != null)
		{
			url.append('/');
			for (final String segment : pKey.split("/", -1))
			{
				url.append(url.charAt(url.length() - 1) == '/' ? "" : "/").append(AwsSigV4.encode(segment));
			}
		}
		if (pQuery != null && !pQuery.isEmpty())
		{
			char separator = '?';
			for (final Map.Entry<String, String> parameter : pQuery.entrySet())
			{
				url.append(separator).append(AwsSigV4.encode(parameter.getKey())).append('=').append(AwsSigV4.encode(parameter.getValue()));
				separator = '&';
			}
		}
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		if (pContentType != null)
		{
			headers.put("Content-Type", pContentType);
		}
		try
		{
			final Map<String, String> signed = AwsSigV4.sign(pMethod, url.toString(), headers, pBody, cfg.getUsername(), cfg.getPassword(),
				cfg.getProperty("region", "us-east-1"), "s3", cfg.getProperty("sessionToken", null));
			final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (s3)");
			return client.send(pMethod, url.toString(), signed, pBody == null ? null : new String(pBody, StandardCharsets.UTF_8));
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("S3: " + ex.getMessage(), ex);
		}
	}

	@Override
	protected byte[] readBytes(final String pPath) throws ConnectorException
	{
		final HttpJsonClient.Response response = this.request("GET", pPath.replaceFirst("^/", ""), null, null, null);
		if (response.getStatus() == 404)
		{
			return null;
		}
		if (!response.isSuccess())
		{
			throw new ConnectorException("S3 GET " + pPath + " failed (HTTP " + response.getStatus() + "): " + S3Connector.message(response), response.getStatus(), null);
		}
		return response.getRawBody().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		final HttpJsonClient.Response response = this.request("PUT", pPath.replaceFirst("^/", ""), null, pContent, pContentType);
		if (!response.isSuccess())
		{
			throw new ConnectorException("S3 PUT " + pPath + " failed (HTTP " + response.getStatus() + "): " + S3Connector.message(response), response.getStatus(), null);
		}
	}

	@Override
	protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("list-type", "2");
		query.put("max-keys", String.valueOf(Math.min(1000, pMax)));
		if (!pPrefix.isEmpty())
		{
			query.put("prefix", pPrefix.replaceFirst("^/", ""));
		}
		final HttpJsonClient.Response response = this.request("GET", null, query, null, null);
		if (!response.isSuccess())
		{
			throw new ConnectorException("S3 list failed (HTTP " + response.getStatus() + "): " + S3Connector.message(response), response.getStatus(), null);
		}
		return S3Connector.parseListing(response.getRawBody());
	}

	static List<Entry> parseListing(final String pXml)
	{
		final List<Entry> entries = new ArrayList<Entry>();
		final Matcher m = Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL).matcher(pXml == null ? "" : pXml);
		while (m.find())
		{
			final String block = m.group(1);
			final String key = S3Connector.tag(block, "Key");
			if (key == null || key.endsWith("/"))
			{
				continue;
			}
			final String size = S3Connector.tag(block, "Size");
			entries.add(new Entry(key, size == null ? 0 : Long.parseLong(size), S3Connector.tag(block, "LastModified")));
		}
		return entries;
	}

	private static String tag(final String pXml, final String pTag)
	{
		final Matcher m = Pattern.compile("<" + pTag + ">(.*?)</" + pTag + ">", Pattern.DOTALL).matcher(pXml);
		return m.find() ? m.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"") : null;
	}

	private static String message(final HttpJsonClient.Response pResponse)
	{
		final String code = S3Connector.tag(pResponse.getRawBody() == null ? "" : pResponse.getRawBody(), "Message");
		return code == null ? pResponse.getRawBody() : code;
	}
}
