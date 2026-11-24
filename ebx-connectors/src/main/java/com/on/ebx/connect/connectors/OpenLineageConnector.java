package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.OpenLineageEmitter;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * An <a href="https://openlineage.io">OpenLineage</a> sink: the base URL is the endpoint that
 * accepts {@code RunEvent}s ({@code http://host/ebx-data-catalog/rest/v1/openlineage} for the ON EBX
 * data catalog, {@code http://marquez:5000/api/v1/lineage} for Marquez, the ingest endpoint of
 * DataHub...), with its authentication (EBX Basic for the catalog).
 * <p>
 * Declaring one active connector of this type is enough for <b>every outbound run</b> of the module
 * to be described there automatically — the EBX table as input, the connector's object as output,
 * one column-lineage entry per rule, the counts of the run — so that the catalog shows where the
 * master data goes. Every mapping is also described <b>as soon as it is declared</b>, before any
 * run, as an OpenLineage {@code JobEvent} ({@code DeclaredLineage}): the catalog shows the flow as
 * DECLARED, then as observed after its first run, and retires it when the mapping is deactivated or
 * deleted. Properties: {@code ebx.namespace} (the OpenLineage namespace of this EBX
 * instance, the {@code lineageNamespace} of its Source in the catalog; default
 * {@code ebx://localhost:8080}), {@code job.namespace} (default {@code ebx-connectors}),
 * {@code emit.outbound} (default true), {@code emit.inbound} (default false: imports and
 * enrichments are described too, EBX becoming the output), {@code emit.declared} (default true:
 * mappings described when declared, not only when run), {@code exclude.types} (connector types
 * never described, default {@code chat-webhook,slack,openlineage,llm}), {@code exclude.connectors}
 * (connector ids never described), {@code producer}.
 * <p>
 * Operation {@code emit}: posts the document (one event or an array of events) as is, so that an
 * outbound mapping can also send hand-built events.
 */
public final class OpenLineageConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(Operation.write("emit", "Post one OpenLineage RunEvent (or an array of events) as is"),
		Operation.read("contract", "What the endpoint announces on GET: the ON EBX data catalog answers its module, contract version, features and dataset state"));

	@Override
	public String getType()
	{
		return OpenLineageEmitter.TYPE;
	}

	@Override
	public String getLabel()
	{
		return "OpenLineage (data catalog, Marquez, DataHub...)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(this.getType(), this.getLabel(), "DATA_PLATFORMS")
			.description("OpenLineage sink: every outbound run of the module is described there (EBX table -> connector object, column lineage, counts); "
				+ "the ON EBX data catalog, Marquez and DataHub accept these events.")
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.apiVersion("OpenLineage 2.0")
			.configurationProperty(ConfigurationPropertyDefinition.builder("ebx.namespace", ConfigurationPropertyType.STRING).label("EBX namespace")
				.description("OpenLineage namespace of this EBX instance: the lineageNamespace of its Source in the data catalog").defaultValue("ebx://localhost:8080").build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("job.namespace", ConfigurationPropertyType.STRING).label("Job namespace")
				.description("Namespace of the jobs (one per mapping)").defaultValue("ebx-connectors").build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("emit.outbound", ConfigurationPropertyType.BOOLEAN).label("Describe outbound runs")
				.defaultValue(Boolean.TRUE).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("emit.inbound", ConfigurationPropertyType.BOOLEAN).label("Describe inbound runs (imports, enrichments)")
				.defaultValue(Boolean.FALSE).advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("emit.declared", ConfigurationPropertyType.BOOLEAN).label("Describe mappings as soon as they are declared")
				.description("Each mapping is sent as an OpenLineage JobEvent when it is created or changed, before any run, and retired when deactivated or deleted")
				.defaultValue(Boolean.TRUE).advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("exclude.types", ConfigurationPropertyType.STRING).label("Connector types never described")
				.defaultValue("chat-webhook,slack,openlineage,llm").advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("exclude.connectors", ConfigurationPropertyType.STRING).label("Connector ids never described")
				.advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("exclude.catalogFlows", ConfigurationPropertyType.BOOLEAN).label("Leave out the data catalog's own flows")
				.description("Mappings on the ON EBX data catalog dataset (scan, profile, sample, access, alerts) feed the catalog: they are not described, and what was declared for them is retired")
				.defaultValue(Boolean.TRUE).advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("producer", ConfigurationPropertyType.STRING).label("Producer URI of the events")
				.defaultValue(OpenLineageEmitter.PRODUCER).advanced(true).build());
		for (final Operation o : this.getOperations())
		{
			// emit writes to the endpoint; contract only reads what it announces
			final com.on.ebx.connect.spi.ConnectorDirection direction = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND
				: com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(),
				o.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OpenLineageConnector.OPERATIONS;
	}

	@Override
	public void configure(final com.on.ebx.connect.spi.ConnectorConfig pConfig)
	{
		super.configure(pConfig);
		OpenLineageEmitter.invalidate(); // a sink was created or edited: re-read the list at the next run
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		if ("contract".equals(pOperation))
		{
			final HttpJsonClient.Response response = this.send("GET", "", null, null);
			if (!response.isSuccess())
			{
				throw new ConnectorException(this.getConfig().getId() + ": contract -> HTTP " + response.getStatus(), response.getStatus(), null);
			}
			final JsonNode body = response.getBody();
			return body != null && body.isObject() ? body : Json.object();
		}
		if (!"emit".equals(pOperation))
		{
			throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
		}
		if (pPayload == null || pPayload.isNull() || !(pPayload.isObject() || pPayload.isArray()))
		{
			throw new ConnectorException(this.getConfig().getId() + ": emit needs a RunEvent (or an array of events) as document");
		}
		if (pPayload.isObject() && !pPayload.has("job"))
		{
			throw new ConnectorException(this.getConfig().getId() + ": not a RunEvent (no 'job')");
		}
		final HttpJsonClient.Response response = this.send("POST", "", null, pPayload);
		if (!response.isSuccess())
		{
			throw new ConnectorException(this.getConfig().getId() + ": emit -> HTTP " + response.getStatus() + " " + String.valueOf(response.getRawBody()),
				response.getStatus(), null);
		}
		final ObjectNode out = Json.object();
		out.put("status", "sent");
		out.put("http", response.getStatus());
		out.put("events", pPayload.isArray() ? pPayload.size() : 1);
		final JsonNode body = response.getBody();
		if (body != null && body.isObject())
		{
			out.set("reply", body);
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("contract".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("module", "ebx-data-catalog when the endpoint is the ON EBX data catalog", "string"),
				FieldInfo.of("contractVersion", "Version of the catalog's contract", "number"), FieldInfo.of("features", "What the catalog accepts (runEvents, jobEvents, sources)", "array"),
				FieldInfo.of("datasetState", "VERIFIED, ABSENT or FOREIGN (a same-named dataset of another model)", "string"));
		}
		return Arrays.asList(FieldInfo.of("status", "sent", "string"), FieldInfo.of("http", "HTTP status", "number"), FieldInfo.of("events", "Number of events posted", "number"),
			FieldInfo.of("reply", "The endpoint's answer (the catalog returns its import report)", "object"));
	}
}
