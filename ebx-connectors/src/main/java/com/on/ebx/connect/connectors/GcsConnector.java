package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.GoogleAuth;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Google Cloud Storage (JSON API). Property {@code bucket}; authentication through a service
 * account key ({@code serviceAccountJson} / {@code serviceAccountFile}) or a bearer token in
 * {@code apiKey}. {@code baseUrl} defaults to {@code https://storage.googleapis.com}.
 */
public final class GcsConnector extends AbstractFileConnector
{
	private final GoogleAuth auth = new GoogleAuth();

	@Override
	public String getType()
	{
		return "gcs";
	}

	@Override
	public String getLabel()
	{
		return "Google Cloud Storage (files)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("CLOUD_STORAGE");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.SERVICE_ACCOUNT).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.DISCOVER_OBJECTS).capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS)
			.apiVersion("Google Cloud Storage JSON API v1")
			.pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 1000, "nextPageToken"))
			.configurationProperty(property("bucket", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true))
			.configurationProperty(property("serviceAccountJson", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET, false))
			.configurationProperty(property("serviceAccountFile", com.on.ebx.connect.spi.ConfigurationPropertyType.FILE, false));
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

	private String base()
	{
		final String url = this.getConfig().getBaseUrl();
		return url == null || url.trim().isEmpty() ? "https://storage.googleapis.com" : url.trim().replaceAll("/+$", "");
	}

	private String bucket() throws ConnectorException
	{
		final String bucket = this.getConfig().getProperty("bucket", null);
		if (bucket == null)
		{
			throw new ConnectorException("GCS: set the property bucket");
		}
		return bucket;
	}

	private HttpJsonClient.Response request(final String pMethod, final String pUrl, final byte[] pBody, final String pContentType) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Authorization", "Bearer " + this.auth.token(cfg, "https://www.googleapis.com/auth/devstorage.read_write"));
		if (pContentType != null)
		{
			headers.put("Content-Type", pContentType);
		}
		try
		{
			return new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (gcs)").send(pMethod, pUrl, headers,
				pBody == null ? null : new String(pBody, StandardCharsets.UTF_8));
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("GCS: " + ex.getMessage(), ex);
		}
	}

	@Override
	protected byte[] readBytes(final String pPath) throws ConnectorException
	{
		final String url = this.base() + "/storage/v1/b/" + HttpJsonClient.encode(this.bucket()) + "/o/" + HttpJsonClient.encode(pPath.replaceFirst("^/", "")) + "?alt=media";
		final HttpJsonClient.Response response = this.request("GET", url, null, null);
		if (response.getStatus() == 404)
		{
			return null;
		}
		if (!response.isSuccess())
		{
			throw new ConnectorException("GCS GET " + pPath + " failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
		return response.getRawBody().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		final String url = this.base() + "/upload/storage/v1/b/" + HttpJsonClient.encode(this.bucket()) + "/o?uploadType=media&name="
				+ HttpJsonClient.encode(pPath.replaceFirst("^/", ""));
		final HttpJsonClient.Response response = this.request("POST", url, pContent, pContentType);
		if (!response.isSuccess())
		{
			throw new ConnectorException("GCS upload " + pPath + " failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
	}

	@Override
	protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("maxResults", String.valueOf(Math.min(1000, pMax)));
		query.put("fields", "items(name,size,updated),nextPageToken");
		if (!pPrefix.isEmpty())
		{
			query.put("prefix", pPrefix.replaceFirst("^/", ""));
		}
		final HttpJsonClient.Response response = this.request("GET",
			HttpJsonClient.buildUrl(this.base(), "/storage/v1/b/" + HttpJsonClient.encode(this.bucket()) + "/o", query), null, null);
		if (!response.isSuccess())
		{
			throw new ConnectorException("GCS list failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
		}
		final List<Entry> entries = new ArrayList<Entry>();
		final JsonNode items = Json.at(response.getBody(), "items");
		if (items != null)
		{
			for (final JsonNode item : items)
			{
				final String name = item.get("name").asText();
				if (!name.endsWith("/"))
				{
					entries.add(new Entry(name, item.get("size") == null ? 0 : item.get("size").asLong(), item.get("updated") == null ? null : item.get("updated").asText()));
				}
			}
		}
		return entries;
	}
}
