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
 * Azure Blob Storage with a SAS token. {@code baseUrl} = {@code https://account.blob.core.windows.net},
 * property {@code container}, {@code apiKey} = SAS token (the query string, with or without the
 * leading {@code ?}) scoped to the container or the account. A BEARER token (Entra ID) in
 * {@code apiKey} with authentication BEARER works too.
 */
public final class AzureBlobConnector extends AbstractFileConnector
{
	@Override
	public String getType()
	{
		return "azure-blob";
	}

	@Override
	public String getLabel()
	{
		return "Azure Blob Storage (files)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("CLOUD_STORAGE");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.DISCOVER_OBJECTS)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS)
			.apiVersion("Azure Blob REST; x-ms-version configurable")
			.pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 5000, "NextMarker"))
			.configurationProperty(property("container", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true))
			.configurationProperty(property("apiVersion", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false));
		return b.build();
	}

	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String pCategory)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), pCategory);
		for (final com.on.ebx.connect.spi.Operation op : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}

	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String pName, final com.on.ebx.connect.spi.ConfigurationPropertyType pType, final boolean pRequired)
	{
		return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).build();
	}

	private HttpJsonClient.Response request(final String pMethod, final String pBlob, final Map<String, String> pQuery, final byte[] pBody, final String pContentType)
		throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final String container = cfg.getProperty("container", null);
		if (container == null)
		{
			throw new ConnectorException("Azure Blob: set the property container");
		}
		if (cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Azure Blob: set the SAS token (or a bearer token) in apiKey");
		}
		final boolean bearer = cfg.getAuthType() == ConnectorConfig.AuthType.BEARER;
		final StringBuilder url = new StringBuilder(cfg.getBaseUrl().replaceAll("/+$", "")).append('/').append(container);
		if (pBlob != null)
		{
			for (final String segment : pBlob.split("/", -1))
			{
				url.append('/').append(AwsSigV4.encode(segment));
			}
		}
		char separator = '?';
		if (pQuery != null)
		{
			for (final Map.Entry<String, String> parameter : pQuery.entrySet())
			{
				url.append(separator).append(AwsSigV4.encode(parameter.getKey())).append('=').append(AwsSigV4.encode(parameter.getValue()));
				separator = '&';
			}
		}
		if (!bearer)
		{
			url.append(separator).append(cfg.getApiKey().replaceFirst("^\\?", ""));
		}
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("x-ms-version", cfg.getProperty("apiVersion", "2021-08-06"));
		headers.put("Accept", "application/xml, */*");
		if (bearer)
		{
			headers.put("Authorization", "Bearer " + cfg.getApiKey());
		}
		if ("PUT".equals(pMethod))
		{
			headers.put("x-ms-blob-type", "BlockBlob");
			headers.put("Content-Type", pContentType == null ? "application/octet-stream" : pContentType);
		}
		try
		{
			final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (azure-blob)");
			return client.send(pMethod, url.toString(), headers, pBody == null ? null : new String(pBody, StandardCharsets.UTF_8));
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Azure Blob: " + ex.getMessage(), ex);
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
			throw new ConnectorException("Azure Blob GET " + pPath + " failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
		return response.getRawBody().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		final HttpJsonClient.Response response = this.request("PUT", pPath.replaceFirst("^/", ""), null, pContent, pContentType);
		if (!response.isSuccess())
		{
			throw new ConnectorException("Azure Blob PUT " + pPath + " failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
	}

	@Override
	protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("restype", "container");
		query.put("comp", "list");
		query.put("maxresults", String.valueOf(Math.min(5000, pMax)));
		if (!pPrefix.isEmpty())
		{
			query.put("prefix", pPrefix.replaceFirst("^/", ""));
		}
		final HttpJsonClient.Response response = this.request("GET", null, query, null, null);
		if (!response.isSuccess())
		{
			throw new ConnectorException("Azure Blob list failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
		final List<Entry> entries = new ArrayList<Entry>();
		final Matcher m = Pattern.compile("<Blob>(.*?)</Blob>", Pattern.DOTALL).matcher(response.getRawBody() == null ? "" : response.getRawBody());
		while (m.find())
		{
			final String block = m.group(1);
			final String name = AzureBlobConnector.tag(block, "Name");
			final String size = AzureBlobConnector.tag(block, "Content-Length");
			if (name != null)
			{
				entries.add(new Entry(name, size == null ? 0 : Long.parseLong(size), AzureBlobConnector.tag(block, "Last-Modified")));
			}
		}
		return entries;
	}

	private static String tag(final String pXml, final String pTag)
	{
		final Matcher m = Pattern.compile("<" + pTag + ">(.*?)</" + pTag + ">", Pattern.DOTALL).matcher(pXml);
		return m.find() ? m.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">") : null;
	}
}
