package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Inbound webhook: external applications POST documents to
 * {@code /ebx-connectors/rest/v1/webhook/{mapping id or label}} and the feed mapping
 * imports them. The sender authenticates as an EBX user holding the Connectors RUN right (audit C2-10); the
 * connector row stores no secret and no account.
 * Property {@code sample} = a sample JSON document for the mapping designer.
 * <p>
 * Operation {@code receive} returns the pushed document; used from a user service with no
 * document it returns nothing.
 */
public final class WebhookConnector implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("receive", "Document pushed by the caller (items at itemsPath of the feed mapping)", new String[0]));

	private ConnectorConfig config;

	@Override
	public String getType()
	{
		return "webhook";
	}

	@Override
	public String getLabel()
	{
		return "Inbound webhook (HTTP POST)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("MESSAGING");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).apiVersion("Internal EBX webhook endpoint").capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS).configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("sample", com.on.ebx.connect.spi.ConfigurationPropertyType.JSON).build());
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

	@Override
	public List<Operation> getOperations()
	{
		return WebhookConnector.OPERATIONS;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
	}

	public ConnectorConfig getConfig()
	{
		return this.config;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (!"receive".equals(pOperation))
		{
			throw new ConnectorException("Webhook: unknown operation '" + pOperation + "'");
		}
		return pPayload;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String sample = this.config == null ? null : this.config.getProperty("sample", null);
		if (sample == null || sample.trim().isEmpty())
		{
			return fields;
		}
		try
		{
			for (final String[] flat : Json.flatten(Json.parse(sample), 200))
			{
				fields.add(new FieldInfo(flat[0], flat[0], flat[2], flat[1]) /* type, sample: swap fixed (2026-10-17) */);
			}
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Webhook: the property sample is not valid JSON: " + ex.getMessage(), ex);
		}
		return fields;
	}
}
