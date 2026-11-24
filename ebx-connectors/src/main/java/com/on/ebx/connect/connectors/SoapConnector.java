package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.OpenApiImporter;
import com.on.ebx.connect.core.SoapSupport;
import com.on.ebx.connect.core.Templates;
import com.on.ebx.connect.core.Xml;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorInspectionAdapters;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.ObjectDescription;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;

/**
 * Generic SOAP client driven by properties, no Java needed:
 * <pre>
 * soap.version            = 1.1 (default) or 1.2
 * op.validate.action      = SOAPAction header (1.1) or action parameter (1.2); may be empty
 * op.validate.path        = appended to the base URL (default: none)
 * op.validate.body        = XML written inside the SOAP Body, {input} placeholders are XML-escaped
 * op.validate.result      = local name of the element returned as the document (default: first child of Body)
 * op.validate.required    = comma list of mandatory inputs ("-" = none; default: every placeholder)
 * op.validate.fields      = catalogue for the designer: path=type;path=type
 * op.validate.description = text shown in the operation list
 * </pre>
 * The answer is converted to JSON (namespaces dropped, repeated elements as arrays), a SOAP Fault
 * becomes an error carrying the fault string.
 */
public final class SoapConnector extends AbstractRestConnector
{
	@Override
	public String getType()
	{
		return "soap";
	}

	@Override
	public String getLabel()
	{
		return "Generic SOAP web service";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "GENERIC_INTEGRATION")
			.description("Configuration-driven SOAP 1.1 and 1.2 connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("soap.version", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).defaultValue("1.1").enumerationValues("1.1", "1.2").build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.path", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.document", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.baseUrl", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.import.maxDepth", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(4)).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.import.maxDocuments", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(24)).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.import.maxBytes", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(4194304)).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsdl.import.maxMillis", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(15000)).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsse.username", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsse.password", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("wsse.password.type", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).defaultValue("text").enumerationValues("text", "digest").advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.body", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).required(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.action", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build());
		try
		{
			for (final Operation o : getOperations())
			{
				final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
				b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
			}
		}
		catch (final IllegalStateException ignored)
		{
			/* Dynamic operations require an instance configuration. */
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		final ConnectorConfig config = this.getConfig();
		final List<Operation> operations = new ArrayList<Operation>();
		if (config == null)
		{
			return operations;
		}
		for (final Map.Entry<String, String> property : config.getProperties().entrySet())
		{
			if (property.getKey().startsWith("op.") && property.getKey().endsWith(".body"))
			{
				final String name = property.getKey().substring(3, property.getKey().length() - 5);
				final String[] required = Templates.required(config.getProperty("op." + name + ".required", null), property.getValue(),
					config.getProperty("op." + name + ".path", ""));
				final String description = config.getProperty("op." + name + ".description", "SOAP operation " + name);
				final boolean write = "true".equalsIgnoreCase(config.getProperty("op." + name + ".write", "false"));
				operations.add(new Operation(name, write ? Operation.Kind.WRITE : Operation.Kind.READ, description, required, null));
			}
		}
		if (operations.isEmpty())
		{
			final String document = config.getProperty("wsdl.document", null);
			if (document != null) try
			{
				for (final SoapSupport.WsdlOperation operation : SoapSupport.parseWsdl(document).getOperations())
				{
					final List<String> required = new ArrayList<String>();
					for (final FieldInfo field : operation.getFields()) required.add(field.getPath());
					operations.add(new Operation(operation.getName(), Operation.Kind.READ, "SOAP operation " + operation.getName(),
						required.toArray(new String[required.size()]), null));
				}
			}
			catch (final ConnectorException ignored) { /* Validation/discovery reports malformed WSDL with context. */ }
		}
		return operations;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		String template = config.getProperty("op." + pOperation + ".body", null);
		SoapSupport.WsdlOperation wsdlOperation = null;
		SoapSupport.Wsdl wsdl = null;
		if (template == null && (wsdl = this.wsdl()) != null)
			for (final SoapSupport.WsdlOperation candidate : wsdl.getOperations()) if (candidate.getName().equals(pOperation))
			{
				wsdlOperation = candidate;
				break;
			}
		if (template == null && wsdlOperation != null) template = wsdlOperation.requestTemplate(wsdl.getTargetNamespace());
		if (template == null)
		{
			throw new ConnectorException(config.getId() + ": no property op." + pOperation + ".body");
		}
		final String version = wsdlOperation == null ? config.getProperty("soap.version", "1.1") : wsdlOperation.getVersion();
		final String action = config.getProperty("op." + pOperation + ".action", wsdlOperation == null ? "" : wsdlOperation.getAction());
		final String requestBody = Templates.fill(template, pInputs, Templates.XML);
		final SoapSupport.Request request;
		if ("digest".equalsIgnoreCase(config.getProperty("wsse.password.type", "text")))
		{
			final byte[] nonce = new byte[16];
			new java.security.SecureRandom().nextBytes(nonce);
			request = SoapSupport.digestRequest(version, action, requestBody, config.getProperty("wsse.username", null),
				config.getProperty("wsse.password", null), nonce, java.time.Instant.now().toString());
		}
		else request = SoapSupport.request(version, action, requestBody, config.getProperty("wsse.username", null), config.getProperty("wsse.password", null));
		final String path = Templates.fill(config.getProperty("op." + pOperation + ".path", ""), pInputs, Templates.NONE);
		final HttpJsonClient.Response response = this.sendRaw("POST", path, null, request.getEnvelope(), request.getHeaders());
		final String xml = response.getRawBody();
		if (xml == null || xml.trim().isEmpty())
		{
			if (response.isSuccess())
			{
				return null;
			}
			throw new ConnectorException(config.getId() + ": " + pOperation + " -> HTTP " + response.getStatus(), response.getStatus(), null);
		}
		final Document document;
		try
		{
			document = Xml.parse(xml);
		}
		catch (final Exception ex)
		{
			throw new ConnectorException(config.getId() + ": " + pOperation + " -> HTTP " + response.getStatus() + ", not XML: "
					+ xml.substring(0, Math.min(200, xml.length())), response.getStatus(), ex);
		}
		final Element fault = Xml.find(document, "Fault");
		if (fault != null)
		{
			throw SoapSupport.fault(config.getId(), pOperation, fault, response.getStatus());
		}
		if (!response.isSuccess())
		{
			throw new ConnectorException(config.getId() + ": " + pOperation + " -> HTTP " + response.getStatus(), response.getStatus(), null);
		}
		final String resultName = config.getProperty("op." + pOperation + ".result", null);
		Element result = resultName == null || resultName.trim().isEmpty() ? null : Xml.find(document, resultName.trim());
		if (result == null)
		{
			final Element body = Xml.find(document, "Body");
			if (body == null)
			{
				return Xml.toJson(document.getDocumentElement());
			}
			for (org.w3c.dom.Node n = body.getFirstChild(); n != null; n = n.getNextSibling())
			{
				if (n instanceof Element)
				{
					result = (Element) n;
					break;
				}
			}
			if (result == null)
			{
				return null;
			}
		}
		return Xml.toJson(result);
	}

	@Override
	public ObjectDiscoveryResult discoverObjects(final ConnectorContext pContext, final ObjectDiscoveryRequest pRequest)
		throws ConnectorException
	{
		final SoapSupport.Wsdl wsdl = this.wsdl();
		if (wsdl == null) return ConnectorInspectionAdapters.discoverOperations(getOperations(), pRequest);
		final ObjectDiscoveryRequest request = pRequest == null ? ObjectDiscoveryRequest.firstPage("", 50) : pRequest;
		final List<DiscoveredObject> objects = new ArrayList<DiscoveredObject>();
		final int offset;
		try { offset = request.getContinuationToken() == null ? 0 : Integer.parseInt(request.getContinuationToken()); }
		catch (final NumberFormatException ex) { throw new ConnectorException("Invalid WSDL continuation token", ex); }
		final String query = request.getQuery().toLowerCase();
		int matched = 0;
		for (final SoapSupport.WsdlOperation operation : wsdl.getOperations())
		{
			if (!query.isEmpty() && !operation.getName().toLowerCase().contains(query)) continue;
			if (matched++ < offset) continue;
			if (objects.size() == request.getLimit()) break;
			objects.add(new DiscoveredObject(operation.getName(), operation.getName(), "SOAP " + operation.getVersion(), true));
		}
		return ObjectDiscoveryResult.page(objects, matched > offset + objects.size() ? String.valueOf(offset + objects.size()) : null);
	}

	@Override
	public ObjectDescription describeObject(final ConnectorContext pContext, final String pObjectId) throws ConnectorException
	{
		final SoapSupport.Wsdl wsdl = this.wsdl();
		if (wsdl != null) for (final SoapSupport.WsdlOperation operation : wsdl.getOperations())
			if (operation.getName().equals(pObjectId)) return ObjectDescription.of(pObjectId, pObjectId, operation.getFields());
		final List<FieldInfo> fields = describeFields(pContext, pObjectId, java.util.Collections.<String, String>emptyMap());
		return fields.isEmpty() ? ObjectDescription.unsupported(pObjectId, "Object description is not implemented by this connector")
			: ObjectDescription.of(pObjectId, pObjectId, fields);
	}

	private SoapSupport.Wsdl wsdl() throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		String document = config.getProperty("wsdl.document", null);
		String rootUrl = config.getProperty("wsdl.baseUrl", null);
		if (document == null)
		{
			final String path = config.getProperty("wsdl.path", null);
			if (path == null) return null;
			rootUrl = HttpJsonClient.buildUrl(this.baseUrl(), path, null);
			final HttpJsonClient.Response response = this.sendRaw("GET", path, null, null,
				java.util.Collections.singletonMap("Accept", "application/wsdl+xml, text/xml, application/xml"));
			if (response.getFinalUrl() != null)
				SoapSupport.importUrl(rootUrl, response.getFinalUrl(), 0, 1, 0, 1); // Keep root redirects on the configured origin.
			if (!response.isSuccess()) throw new ConnectorException(config.getId() + ": WSDL discovery -> HTTP " + response.getStatus(), response.getStatus(), null);
			document = response.getRawBody();
		}
		if (rootUrl == null || rootUrl.trim().isEmpty()) return SoapSupport.parseWsdl(document);
		final SoapSupport.ImportLimits limits;
		try
		{
			limits = new SoapSupport.ImportLimits(
				positiveInt(config, "wsdl.import.maxDepth", 4, true), positiveInt(config, "wsdl.import.maxDocuments", 24, false),
				positiveLong(config, "wsdl.import.maxBytes", 4194304L), positiveLong(config, "wsdl.import.maxMillis", 15000L));
		}
		catch (final IllegalArgumentException ex) { throw new ConnectorException("Invalid WSDL import safety limits: " + ex.getMessage(), ex); }
		return SoapSupport.loadWsdl(rootUrl, document, new SoapSupport.DocumentFetcher()
		{
			@Override
			public String fetch(final String pUrl) throws ConnectorException
			{
				final HttpJsonClient.Response response = SoapConnector.this.getAbsolute(pUrl,
					java.util.Collections.singletonMap("Accept", "application/wsdl+xml, application/xml, text/xml"));
				if (response.getFinalUrl() != null)
					SoapSupport.importUrl(pUrl, response.getFinalUrl(), 0, 1, 0, 1); // Reject a cross-origin redirect.
				if (!response.isSuccess()) throw new ConnectorException(config.getId() + ": WSDL import " + pUrl + " -> HTTP " + response.getStatus(), response.getStatus(), null);
				return response.getRawBody();
			}
		}, limits);
	}

	private static int positiveInt(final ConnectorConfig pConfig, final String pName, final int pDefault, final boolean pAllowZero)
		throws ConnectorException
	{
		final long value = positiveLong(pConfig, pName, pDefault);
		if (value > Integer.MAX_VALUE || (!pAllowZero && value == 0)) throw new ConnectorException("Invalid " + pName + ": " + value);
		return (int) value;
	}

	private static long positiveLong(final ConnectorConfig pConfig, final String pName, final long pDefault) throws ConnectorException
	{
		final String configured = pConfig.getProperty(pName, String.valueOf(pDefault));
		try
		{
			final long value = Long.parseLong(configured);
			if (value < 1 && !("wsdl.import.maxDepth".equals(pName) && value == 0)) throw new NumberFormatException();
			return value;
		}
		catch (final NumberFormatException ex) { throw new ConnectorException("Invalid " + pName + ": " + configured, ex); }
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		for (final String[] field : OpenApiImporter.fields(this.getConfig(), "op." + pOperation + ".fields"))
		{
			fields.add(FieldInfo.of(field[0], field[0], field[1]));
		}
		return fields;
	}
}
