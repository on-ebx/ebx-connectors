package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Turns an OpenAPI 3 specification (JSON, or YAML when jackson-dataformat-yaml is on the
 * classpath) into the configuration of a connector of type {@code rest}: base URL from
 * {@code servers}, authentication from {@code securitySchemes}, one operation per path and
 * method ({@code op.<name>.method / .path / .query / .required / .description}) and the flattened
 * response and request-body schemas ({@code op.<name>.fields}, {@code op.<name>.bodyFields}) so
 * that the mapping designer knows the fields before any call.
 */
public final class OpenApiImporter
{
	/** Result: the connector row and its properties, plus what was understood. */
	public static final class Draft
	{
		public String id;
		public String label;
		public String baseUrl = "";
		public ConnectorConfig.AuthType authType = ConnectorConfig.AuthType.NONE;
		public String apiKeyHeader;
		public String description = "";
		public final Map<String, String> properties = new LinkedHashMap<String, String>();
		public final List<String> operations = new ArrayList<String>();
		public final List<String> notes = new ArrayList<String>();
	}

	private static final int PROPERTY_MAX = 1900;
	private static final int FIELDS_MAX = 300;
	private static final int DEPTH_MAX = 6;

	private OpenApiImporter()
	{
	}

	/** JSON first; otherwise YAML through jackson-dataformat-yaml, reached by reflection so the module loads without it. */
	public static JsonNode parse(final String pText) throws ConnectorException
	{
		final String text = pText == null ? "" : pText.trim();
		if (text.isEmpty())
		{
			throw new ConnectorException("OpenAPI: the specification is empty");
		}
		if (text.startsWith("{") || text.startsWith("["))
		{
			try
			{
				return Json.parse(text);
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException("OpenAPI: invalid JSON: " + ex.getMessage(), ex);
			}
		}
		try
		{
			final Class<?> factoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
			final Object factory = factoryClass.getConstructor().newInstance();
			final ObjectMapper mapper = new ObjectMapper((com.fasterxml.jackson.core.JsonFactory) factory);
			return mapper.readTree(text);
		}
		catch (final ClassNotFoundException ex)
		{
			throw new ConnectorException("OpenAPI: YAML needs jackson-dataformat-yaml.jar and snakeyaml.jar (the war carries them in WEB-INF/lib; they are missing from this installation); send JSON instead");
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("OpenAPI: invalid YAML: " + ex.getMessage(), ex);
		}
	}

	public static Draft build(final JsonNode pSpec, final String pId, final String pLabel) throws ConnectorException
	{
		if (pSpec == null || pSpec.get("paths") == null)
		{
			throw new ConnectorException("OpenAPI: no 'paths' in the specification (OpenAPI 3 expected)");
		}
		final Draft draft = new Draft();
		final JsonNode info = pSpec.get("info");
		draft.label = pLabel != null && !pLabel.trim().isEmpty() ? pLabel.trim() : OpenApiImporter.text(info, "title", pId);
		draft.id = pId != null && !pId.trim().isEmpty() ? pId.trim() : draft.label.replaceAll("[^A-Za-z0-9]", "");
		draft.description = ("Imported from OpenAPI " + OpenApiImporter.text(pSpec, "openapi", "3") + " - " + draft.label
				+ (info != null && info.get("version") != null ? " v" + info.get("version").asText() : "") + ". "
				+ OpenApiImporter.text(info, "description", "")).trim();
		if (draft.description.length() > 1900)
		{
			draft.description = draft.description.substring(0, 1900);
		}
		// servers
		final JsonNode servers = pSpec.get("servers");
		if (servers != null && servers.isArray() && servers.size() > 0)
		{
			String url = OpenApiImporter.text(servers.get(0), "url", "");
			final JsonNode variables = servers.get(0).get("variables");
			if (variables != null)
			{
				final Iterator<Map.Entry<String, JsonNode>> it = variables.fields();
				while (it.hasNext())
				{
					final Map.Entry<String, JsonNode> v = it.next();
					url = url.replace("{" + v.getKey() + "}", OpenApiImporter.text(v.getValue(), "default", ""));
				}
			}
			draft.baseUrl = url.replaceAll("/+$", "");
			if (!draft.baseUrl.startsWith("http"))
			{
				draft.notes.add("servers[0].url is relative (" + draft.baseUrl + "): set the base URL by hand");
			}
		}
		else
		{
			draft.notes.add("no servers: set the base URL by hand");
		}
		OpenApiImporter.security(pSpec, draft);
		// operations
		final Set<String> names = new LinkedHashSet<String>();
		final Iterator<Map.Entry<String, JsonNode>> paths = pSpec.get("paths").fields();
		while (paths.hasNext())
		{
			final Map.Entry<String, JsonNode> path = paths.next();
			final JsonNode pathParameters = path.getValue().get("parameters");
			for (final String method : new String[] { "get", "post", "put", "patch", "delete" })
			{
				final JsonNode operation = path.getValue().get(method);
				if (operation == null || !operation.isObject())
				{
					continue;
				}
				String name = OpenApiImporter.text(operation, "operationId", "");
				if (name.isEmpty())
				{
					name = (method.equals("get") ? "" : method + "_") + path.getKey().replaceAll("[{}]", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
				}
				name = name.replaceAll("[^A-Za-z0-9_]", "_");
				String unique = name;
				for (int i = 2; names.contains(unique); i++)
				{
					unique = name + i;
				}
				names.add(unique);
				draft.properties.put("op." + unique + ".method", method.toUpperCase());
				draft.properties.put("op." + unique + ".path", path.getKey());
				final List<String> query = new ArrayList<String>();
				final List<String> required = new ArrayList<String>();
				for (final JsonNode parameters : new JsonNode[] { pathParameters, operation.get("parameters") })
				{
					if (parameters == null)
					{
						continue;
					}
					for (JsonNode parameter : parameters)
					{
						parameter = OpenApiImporter.resolve(pSpec, parameter);
						final String pname = OpenApiImporter.text(parameter, "name", "");
						final String in = OpenApiImporter.text(parameter, "in", "query");
						if (pname.isEmpty())
						{
							continue;
						}
						if ("query".equals(in))
						{
							query.add(pname + "={" + pname + "}");
						}
						if ("path".equals(in) || (parameter.get("required") != null && parameter.get("required").asBoolean()))
						{
							required.add(pname);
						}
						if ("header".equals(in)) draft.properties.put("op." + unique + ".header." + pname, "{" + pname + "}");
						if ("cookie".equals(in)) draft.properties.put("op." + unique + ".cookie." + pname, "{" + pname + "}");
					}
				}
				if (!query.isEmpty())
				{
					draft.properties.put("op." + unique + ".query", OpenApiImporter.join(query, "&"));
				}
				if (!required.isEmpty())
				{
					draft.properties.put("op." + unique + ".required", OpenApiImporter.join(required, ","));
				}
				else if (!query.isEmpty())
				{
					draft.properties.put("op." + unique + ".required", "-"); // parameters exist, none is mandatory
				}
				final String summary = OpenApiImporter.text(operation, "summary", OpenApiImporter.text(operation, "description", ""));
				if (!summary.isEmpty())
				{
					draft.properties.put("op." + unique + ".description", summary.length() > 500 ? summary.substring(0, 500) : summary);
				}
				for (final String extension : new String[] { "response-root", "pagination-next" })
				{
					final JsonNode value = operation.get("x-ebx-" + extension);
					if (value != null && value.isTextual()) draft.properties.put("op." + unique + "." + ("response-root".equals(extension) ? "responseRoot" : "pagination.next"), value.asText());
				}
				final JsonNode requestBody = OpenApiImporter.resolve(pSpec, operation.get("requestBody"));
				final JsonNode content = requestBody == null ? null : requestBody.get("content");
				if (content != null && content.has("multipart/form-data")) draft.properties.put("op." + unique + ".contentType", "multipart/form-data");
				// response schema -> fields of the input tree; request body -> fields of the outbound document
				final JsonNode response = OpenApiImporter.jsonSchema(pSpec, OpenApiImporter.firstResponse(operation));
				if (response != null)
				{
					OpenApiImporter.putChunked(draft.properties, "op." + unique + ".fields", OpenApiImporter.flatten(pSpec, response));
					if (!draft.properties.containsKey("op." + unique + ".pagination.next"))
					{
						final String inferredNext = OpenApiImporter.inferNextPath(pSpec, response);
						if (inferredNext != null)
						{
							draft.properties.put("op." + unique + ".pagination.next", inferredNext);
							draft.notes.add("Inferred JSON pagination link '" + inferredNext + "' for operation " + unique + "; verify it against the API");
						}
					}
				}
				final JsonNode body = OpenApiImporter.jsonSchema(pSpec, operation.get("requestBody"));
				if (body != null)
				{
					OpenApiImporter.putChunked(draft.properties, "op." + unique + ".bodyFields", OpenApiImporter.flatten(pSpec, body));
				}
				draft.operations.add(unique + " (" + method.toUpperCase() + " " + path.getKey() + (required.isEmpty() ? "" : ", required: " + OpenApiImporter.join(required, ", ")) + ")");
			}
		}
		if (draft.operations.isEmpty())
		{
			throw new ConnectorException("OpenAPI: no GET/POST/PUT/DELETE operation found");
		}
		return draft;
	}

	private static void security(final JsonNode pSpec, final Draft pDraft)
	{
		final JsonNode schemes = pSpec.get("components") == null ? null : pSpec.get("components").get("securitySchemes");
		if (schemes == null || !schemes.isObject() || schemes.size() == 0)
		{
			return;
		}
		// the scheme named by the global security requirement wins, else the first one
		String chosen = null;
		final JsonNode security = pSpec.get("security");
		if (security != null && security.isArray() && security.size() > 0 && security.get(0).isObject() && security.get(0).size() > 0)
		{
			chosen = security.get(0).fieldNames().next();
		}
		final JsonNode scheme = chosen != null && schemes.get(chosen) != null ? schemes.get(chosen) : schemes.elements().next();
		final String type = OpenApiImporter.text(scheme, "type", "");
		if ("apiKey".equals(type))
		{
			final String in = OpenApiImporter.text(scheme, "in", "header");
			final String name = OpenApiImporter.text(scheme, "name", "X-API-Key");
			pDraft.authType = ConnectorConfig.AuthType.API_KEY_HEADER;
			if ("query".equals(in))
			{
				pDraft.properties.put("auth.apiKeyQuery", name);
				pDraft.notes.add("API key expected as query parameter '" + name + "' (property auth.apiKeyQuery); enter the key in apiKey");
			}
			else
			{
				pDraft.apiKeyHeader = name;
				pDraft.notes.add("API key expected in header '" + name + "': enter the key in apiKey");
			}
		}
		else if ("http".equals(type))
		{
			final String scheme2 = OpenApiImporter.text(scheme, "scheme", "basic").toLowerCase();
			pDraft.authType = "bearer".equals(scheme2) ? ConnectorConfig.AuthType.BEARER : ConnectorConfig.AuthType.BASIC;
			pDraft.notes.add("bearer".equals(scheme2) ? "bearer token expected: enter it in apiKey" : "HTTP basic: enter username and password");
		}
		else if ("oauth2".equals(type) || "openIdConnect".equals(type))
		{
			if ("oauth2".equals(type))
			{
				final JsonNode flows = scheme.get("flows");
				JsonNode flow = flows == null ? null : flows.get("clientCredentials");
				String flowName = "client_credentials";
				if (flow == null && flows != null)
				{
					flow = flows.get("password");
					flowName = "password";
				}
				if (flow == null && flows != null)
				{
					flow = flows.get("authorizationCode");
					flowName = "authorization_code";
				}
				if (flow != null && flow.get("tokenUrl") != null)
				{
					pDraft.authType = "authorization_code".equals(flowName) ? ConnectorConfig.AuthType.BEARER : ConnectorConfig.AuthType.OAUTH2;
					pDraft.properties.put("oauth.flow", flowName);
					pDraft.properties.put("oauth.tokenUrl", flow.get("tokenUrl").asText());
					if (flow.get("authorizationUrl") != null) pDraft.properties.put("oauth.authorizationUrl", flow.get("authorizationUrl").asText());
					if ("authorization_code".equals(flowName))
						pDraft.notes.add("OAuth 2.0 authorization_code metadata imported; interactive browser authorization is not automatic, so obtain a token or configure a refresh_token lifecycle");
					else pDraft.notes.add("OAuth 2.0 " + flowName + ": configure the client identifier and secret");
				}
				else
				{
					pDraft.authType = ConnectorConfig.AuthType.BEARER;
					pDraft.notes.add("OAuth 2.0 has no non-interactive supported token flow: obtain a token and enter it in apiKey");
				}
			}
			else
			{
				pDraft.authType = ConnectorConfig.AuthType.BEARER;
				final String discovery = OpenApiImporter.text(scheme, "openIdConnectUrl", "");
				if (!discovery.isEmpty()) pDraft.properties.put("oauth.discoveryUrl", discovery);
				pDraft.properties.put("oauth.flow", "authorization_code");
				pDraft.notes.add("OpenID Connect discovery metadata imported; resolve it during configuration, then use an EBX user service for interactive authorization or enter an issued token in apiKey");
			}
		}
	}

	/** Conservative inference: only conventional response fields whose value is a URL/link. */
	private static String inferNextPath(final JsonNode pSpec, final JsonNode pSchemaRef)
	{
		final JsonNode schema = OpenApiImporter.resolve(pSpec, pSchemaRef);
		if (schema == null) return null;
		final JsonNode properties = schema.get("properties");
		if (properties == null || !properties.isObject()) return null;
		for (final String direct : new String[] { "next", "nextLink", "next_link", "nextPage", "next_page" })
		{
			if (isLinkProperty(OpenApiImporter.resolve(pSpec, properties.get(direct)))) return direct;
		}
		for (final String holder : new String[] { "links", "pagination", "paging" })
		{
			final JsonNode nested = OpenApiImporter.resolve(pSpec, properties.get(holder));
			final JsonNode nestedProperties = nested == null ? null : nested.get("properties");
			if (nestedProperties != null && nestedProperties.isObject())
			{
				for (final String next : new String[] { "next", "nextLink", "next_link", "nextPage", "next_page" })
				{
					if (isLinkProperty(OpenApiImporter.resolve(pSpec, nestedProperties.get(next)))) return holder + "." + next;
				}
			}
		}
		return null;
	}

	private static boolean isLinkProperty(final JsonNode pSchema)
	{
		if (pSchema == null) return false;
		final String type = OpenApiImporter.text(pSchema, "type", "string");
		final String format = OpenApiImporter.text(pSchema, "format", "");
		return "string".equals(type) && (format.isEmpty() || "uri".equals(format) || "url".equals(format));
	}

	private static JsonNode firstResponse(final JsonNode pOperation)
	{
		final JsonNode responses = pOperation.get("responses");
		if (responses == null)
		{
			return null;
		}
		for (final String code : new String[] { "200", "201", "2XX", "default" })
		{
			if (responses.get(code) != null)
			{
				return responses.get(code);
			}
		}
		return responses.size() > 0 ? responses.elements().next() : null;
	}

	/** The JSON schema of a response or request body ({@code content['application/json'].schema}), resolved. */
	private static JsonNode jsonSchema(final JsonNode pSpec, final JsonNode pHolder)
	{
		if (pHolder == null)
		{
			return null;
		}
		final JsonNode holder = OpenApiImporter.resolve(pSpec, pHolder);
		final JsonNode content = holder.get("content");
		if (content == null || !content.isObject())
		{
			return null;
		}
		JsonNode media = content.get("application/json");
		if (media == null)
		{
			final Iterator<Map.Entry<String, JsonNode>> it = content.fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> e = it.next();
				if (e.getKey().contains("json"))
				{
					media = e.getValue();
					break;
				}
			}
		}
		return media == null || media.get("schema") == null ? null : OpenApiImporter.resolve(pSpec, media.get("schema"));
	}

	/** Local {@code $ref} resolution ({@code #/components/schemas/Pet}). */
	static JsonNode resolve(final JsonNode pSpec, final JsonNode pNode)
	{
		JsonNode node = pNode;
		for (int hops = 0; node != null && node.get("$ref") != null && hops < 20; hops++)
		{
			final String ref = node.get("$ref").asText();
			if (!ref.startsWith("#/"))
			{
				return node;
			}
			JsonNode target = pSpec;
			for (final String step : ref.substring(2).split("/"))
			{
				target = target == null ? null : target.get(step.replace("~1", "/").replace("~0", "~"));
			}
			if (target == null)
			{
				return node;
			}
			node = target;
		}
		return node;
	}

	/** Flattens a schema into {@code path=type} pairs: objects recurse, arrays become {@code name[0]}. */
	static List<String> flatten(final JsonNode pSpec, final JsonNode pSchema)
	{
		final List<String> out = new ArrayList<String>();
		OpenApiImporter.flatten(pSpec, pSchema, "", out, 0);
		return out;
	}

	private static void flatten(final JsonNode pSpec, final JsonNode pSchemaRef, final String pPrefix, final List<String> pOut, final int pDepth)
	{
		if (pSchemaRef == null || pOut.size() >= OpenApiImporter.FIELDS_MAX || pDepth > OpenApiImporter.DEPTH_MAX)
		{
			return;
		}
		final JsonNode schema = OpenApiImporter.resolve(pSpec, pSchemaRef);
		JsonNode properties = schema.get("properties");
		final JsonNode allOf = schema.get("allOf");
		if (allOf != null && allOf.isArray())
		{
			for (final JsonNode part : allOf)
			{
				OpenApiImporter.flatten(pSpec, part, pPrefix, pOut, pDepth + 1);
			}
			if (properties == null)
			{
				return;
			}
		}
		for (final String alt : new String[] { "oneOf", "anyOf" })
		{
			if (schema.get(alt) != null && schema.get(alt).isArray() && schema.get(alt).size() > 0 && properties == null)
			{
				OpenApiImporter.flatten(pSpec, schema.get(alt).get(0), pPrefix, pOut, pDepth + 1);
				return;
			}
		}
		final String type = OpenApiImporter.text(schema, "type", properties != null ? "object" : (schema.get("items") != null ? "array" : "string"));
		if ("object".equals(type) && properties != null)
		{
			final Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> property = it.next();
				final JsonNode child = OpenApiImporter.resolve(pSpec, property.getValue());
				final String path = pPrefix.isEmpty() ? property.getKey() : pPrefix + "." + property.getKey();
				final String childType = OpenApiImporter.text(child, "type", child.get("properties") != null ? "object" : child.get("items") != null ? "array" : "string");
				if ("object".equals(childType) && (child.get("properties") != null || child.get("allOf") != null))
				{
					OpenApiImporter.flatten(pSpec, child, path, pOut, pDepth + 1);
				}
				else if ("array".equals(childType))
				{
					OpenApiImporter.flatten(pSpec, child, path, pOut, pDepth + 1);
				}
				else if (pOut.size() < OpenApiImporter.FIELDS_MAX)
				{
					pOut.add(path + "=" + OpenApiImporter.typeOf(child));
				}
			}
			return;
		}
		if ("array".equals(type))
		{
			final JsonNode items = OpenApiImporter.resolve(pSpec, schema.get("items"));
			final String path = pPrefix.isEmpty() ? "[0]" : pPrefix + "[0]";
			if (items == null)
			{
				pOut.add(path + "=string");
			}
			else if (items.get("properties") != null || items.get("allOf") != null || items.get("items") != null)
			{
				OpenApiImporter.flatten(pSpec, items, path, pOut, pDepth + 1);
			}
			else
			{
				pOut.add(path + "=" + OpenApiImporter.typeOf(items));
			}
			return;
		}
		if (!pPrefix.isEmpty())
		{
			pOut.add(pPrefix + "=" + OpenApiImporter.typeOf(schema));
		}
	}

	private static String typeOf(final JsonNode pSchema)
	{
		final String type = OpenApiImporter.text(pSchema, "type", "string");
		final String format = OpenApiImporter.text(pSchema, "format", "");
		if ("string".equals(type) && "date".equals(format))
		{
			return "date";
		}
		if ("string".equals(type) && "date-time".equals(format))
		{
			return "datetime";
		}
		if ("integer".equals(type) || "number".equals(type))
		{
			return "number";
		}
		return type;
	}

	/** {@code op.x.fields}, {@code op.x.fields.2}, ... each under the property value limit. */
	static void putChunked(final Map<String, String> pProperties, final String pKey, final List<String> pEntries)
	{
		if (pEntries.isEmpty())
		{
			return;
		}
		final StringBuilder chunk = new StringBuilder();
		int index = 1;
		for (final String entry : pEntries)
		{
			if (chunk.length() + entry.length() + 1 > OpenApiImporter.PROPERTY_MAX)
			{
				pProperties.put(index == 1 ? pKey : pKey + "." + index, chunk.toString());
				chunk.setLength(0);
				index++;
			}
			chunk.append(chunk.length() > 0 ? ";" : "").append(entry);
		}
		if (chunk.length() > 0)
		{
			pProperties.put(index == 1 ? pKey : pKey + "." + index, chunk.toString());
		}
	}

	/** Reads the chunked field list back ({@code path=type} pairs). */
	public static List<String[]> fields(final ConnectorConfig pConfig, final String pKey)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (int index = 1; index < 100; index++)
		{
			final String value = pConfig.getProperty(index == 1 ? pKey : pKey + "." + index, null);
			if (value == null)
			{
				break;
			}
			for (final String entry : value.split(";"))
			{
				final int eq = entry.lastIndexOf('=');
				if (eq > 0)
				{
					out.add(new String[] { entry.substring(0, eq), entry.substring(eq + 1) });
				}
			}
		}
		return out;
	}

	private static String text(final JsonNode pNode, final String pKey, final String pDefault)
	{
		return pNode == null || pNode.get(pKey) == null || pNode.get(pKey).isNull() ? pDefault : pNode.get(pKey).asText();
	}

	private static String join(final List<String> pList, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String s : pList)
		{
			sb.append(sb.length() > 0 ? pSeparator : "").append(s);
		}
		return sb.toString();
	}
}
