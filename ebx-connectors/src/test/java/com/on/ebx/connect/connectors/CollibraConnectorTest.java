package com.on.ebx.connect.connectors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.sun.net.httpserver.HttpServer;

/**
 * The Collibra connector against an in-memory fake of the REST API 2.0 (communities, domains,
 * types, statuses, assets, attributes, relations, import jobs): names resolved to ids and cached,
 * domain created on demand, upsert idempotent on name + domain, attributes created then patched,
 * parent and term relations, column naming {@code table > column}, business terms flattened for a
 * glossary feed, relation type resolved from {@code Source>role>Target} with an approximate
 * fallback, import job polled to completion. No instance, no network.
 */
public final class CollibraConnectorTest
{
	private CollibraConnectorTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Fake fake = new Fake();
		server.createContext("/", fake);
		server.start();
		try
		{
			final int port = server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + port);
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("Collibra");
			config.setType("collibra");
			config.setBaseUrl("http://127.0.0.1:" + port);
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("api");
			config.setPassword("pw");
			config.setConnectTimeoutMs(5000);
			config.setReadTimeoutMs(10000);
			config.setProperty("community", "Data Governance Council");
			final CollibraConnector connector = new CollibraConnector();
			connector.configure(config);

			final ConnectionTestResult probe = connector.testConnection(null);
			check(probe.isSuccessful() && probe.getMessage().contains("community 'Data Governance Council' found"), "connection test: " + probe.getMessage());
			check(fake.lastAuthorization != null && fake.lastAuthorization.startsWith("Basic "), "basic authentication sent");

			// glossary feed
			final Map<String, String> terms = new LinkedHashMap<String, String>();
			terms.put("source", "COLLIBRA");
			final JsonNode feed = connector.execute(null, "terms", terms, null);
			check(feed != null && feed.get("count").asInt() == 2, "2 business terms (got " + (feed == null ? null : feed.get("count")) + ")");
			final JsonNode customer = feed.get("items").get(0);
			check("Customer".equals(customer.get("name").asText()) && customer.get("definition").asText().startsWith("A party") && "Accepted".equals(customer.get("status").asText())
				&& "Business Glossary".equals(customer.get("domain").asText()) && customer.get("code").asText().equals(customer.get("id").asText()), "term flattened with definition, status, domain, code = id");
			check(fake.assetQueries.get(0).contains("typeIds=" + fake.ids.get("assetType|Business Term")), "terms filtered on the Business Term type id");

			// upsert: domain created on demand, asset created, attributes set
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			final ObjectNode table = Json.object();
			table.put("name", "SALES.ORDERS");
			table.put("displayName", "Orders");
			table.put("type", "Table");
			table.put("domain", "DWH");
			table.putObject("attributes").put("description", "Sales orders").put("url", "http://ebx/asset/1");
			final JsonNode created = connector.execute(null, "upsert", inputs, table);
			check(created.get("created").asBoolean() && created.get("attributesSet").asInt() == 2, "table asset created with 2 attributes: " + created);
			check(fake.domains.containsKey("DWH") && fake.assets.size() == 3, "domain DWH created on demand, asset stored");
			check(created.get("url").asText().endsWith("/asset/" + created.get("id").asText()), "asset URL built from the base URL");

			// second upsert: no duplicate, one attribute patched, one unchanged
			table.putObject("attributes").put("description", "Sales orders (daily)").put("url", "http://ebx/asset/1");
			final JsonNode updated = connector.execute(null, "upsert", inputs, table);
			check(!updated.get("created").asBoolean() && updated.get("id").asText().equals(created.get("id").asText()) && updated.get("attributesSet").asInt() == 1, "upsert idempotent, attribute patched: " + updated);
			check(fake.assets.size() == 3, "no duplicate asset");

			// column: name qualified by the parent, contains relation, term relation, data type attribute
			final Map<String, String> columnInputs = new LinkedHashMap<String, String>();
			columnInputs.put("attributes.dataType", "DECIMAL(18,2)");
			final ObjectNode column = Json.object();
			column.put("name", "GROSS_PRICE");
			column.put("type", "Column");
			column.put("domain", "DWH");
			column.put("parent", "SALES.ORDERS");
			column.put("term", "Net revenue");
			final JsonNode col = connector.execute(null, "upsert", columnInputs, column);
			check("SALES.ORDERS > GROSS_PRICE".equals(col.get("name").asText()) && col.get("relationsCreated").asInt() == 2 && col.get("attributesSet").asInt() == 1, "column named table > column, 2 relations, technical data type: " + col);
			final JsonNode colAsset = fake.assets.get(col.get("id").asText());
			check("GROSS_PRICE".equals(colAsset.get("displayName").asText()), "display name = column name");
			check(fake.relations.size() == 2 && fake.relationTypeOf(fake.relations.get(0)).equals(fake.ids.get("relationType|Table>contains>Column")), "contains relation typed exactly");
			check(fake.relationTypeOf(fake.relations.get(1)).equals(fake.ids.get("relationType|Data Element>represents>Business Term")), "term relation matched approximately (Data Element for Column)");
			final JsonNode again = connector.execute(null, "upsert", columnInputs, column);
			check(again.get("relationsCreated").asInt() == 0 && fake.relations.size() == 2, "relations idempotent");

			// lineage relation between two columns of two tables (second table upserted first)
			final ObjectNode staging = Json.object();
			staging.put("name", "STG.ORDERS_RAW");
			staging.put("type", "Table");
			staging.put("domain", "DWH");
			connector.execute(null, "upsert", inputs, staging);
			final ObjectNode rawColumn = Json.object();
			rawColumn.put("name", "PRICE");
			rawColumn.put("type", "Column");
			rawColumn.put("domain", "DWH");
			rawColumn.put("parent", "STG.ORDERS_RAW");
			connector.execute(null, "upsert", inputs, rawColumn);
			final Map<String, String> relate = new LinkedHashMap<String, String>();
			relate.put("source", "PRICE");
			relate.put("sourceParent", "STG.ORDERS_RAW");
			relate.put("sourceDomain", "DWH");
			relate.put("target", "GROSS_PRICE");
			relate.put("targetParent", "SALES.ORDERS");
			relate.put("targetDomain", "DWH");
			final JsonNode edge = connector.execute(null, "relate", relate, null);
			check(edge.get("created").asBoolean() && fake.relationTypeOf(fake.relations.get(fake.relations.size() - 1)).equals(fake.ids.get("relationType|Column>is source for>Column")), "column lineage relation: " + edge);
			relate.remove("source");
			relate.remove("target");
			final JsonNode tableEdge = connector.execute(null, "relate", relate, null);
			check(tableEdge.get("created").asBoolean() && fake.relationTypeOf(fake.relations.get(fake.relations.size() - 1)).equals(fake.ids.get("relationType|Table>is source for>Table")), "table-level lineage relation: " + tableEdge);
			relate.put("target", "NOPE");
			final JsonNode missing = connector.execute(null, "relate", relate, null);
			check(!missing.get("created").asBoolean() && missing.get("skipped").asText().contains("target"), "missing endpoint skipped, not failed");

			// get with attributes and relations; search
			final Map<String, String> get = new LinkedHashMap<String, String>();
			get.put("name", "SALES.ORDERS > GROSS_PRICE");
			get.put("domain", "DWH");
			final JsonNode one = connector.execute(null, "get", get, null);
			check(one != null && "DECIMAL(18,2)".equals(one.get("attributes").get("Technical Data Type").asText()) && one.get("incoming").size() == 2 && one.get("outgoing").size() == 1, "get: attributes and both relation directions: " + one);
			final Map<String, String> search = new LinkedHashMap<String, String>();
			search.put("name", "ORDERS");
			search.put("domain", "DWH");
			search.put("type", "Table");
			final JsonNode found = connector.execute(null, "search", search, null);
			check(found.get("count").asInt() == 2, "search by name fragment, domain and type");

			// types and import job
			final Map<String, String> kinds = new LinkedHashMap<String, String>();
			kinds.put("kind", "relationTypes");
			final JsonNode types = connector.execute(null, "types", kinds, null);
			check(types.get("count").asInt() == 5 && "Table>contains>Column".equals(types.get("items").get(0).get("spec").asText()), "relation types listed with their spec");
			final ArrayNode commands = Json.mapper().createArrayNode();
			commands.addObject().put("resourceType", "Asset").putObject("identifier").put("name", "X");
			final Map<String, String> wait = new LinkedHashMap<String, String>();
			wait.put("wait", "true");
			final JsonNode job = connector.execute(null, "import", wait, commands);
			check("COMPLETED".equals(job.get("state").asText()) && fake.importBody.contains("\"resourceType\":\"Asset\"") && fake.importContentType.startsWith("multipart/form-data"), "import job sent as multipart and polled to completion: " + job);
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("PASS: Collibra connector against a fake REST API 2.0 (terms feed, upsert with attributes and relations, lineage relations, get/search/types, import job)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	/** In-memory Collibra. */
	private static final class Fake implements com.sun.net.httpserver.HttpHandler
	{
		final Map<String, String> ids = new LinkedHashMap<String, String>();
		final Map<String, ObjectNode> domains = new LinkedHashMap<String, ObjectNode>(); // name → domain
		final Map<String, ObjectNode> assets = new LinkedHashMap<String, ObjectNode>(); // id → asset
		final List<ObjectNode> attributes = new ArrayList<ObjectNode>();
		final List<ObjectNode> relations = new ArrayList<ObjectNode>();
		final List<String> assetQueries = new ArrayList<String>();
		final ArrayNode relationTypes = Json.mapper().createArrayNode();
		String lastAuthorization;
		String importBody = "";
		String importContentType = "";
		int jobPolls;

		Fake()
		{
			this.ids.put("community|Data Governance Council", UUID.randomUUID().toString());
			for (final String t : new String[] { "Table", "Column", "Business Term", "Data Set" }) this.ids.put("assetType|" + t, UUID.randomUUID().toString());
			for (final String t : new String[] { "Description", "Definition", "Technical Data Type", "URL", "Note" }) this.ids.put("attributeType|" + t, UUID.randomUUID().toString());
			for (final String t : new String[] { "Candidate", "Accepted" }) this.ids.put("status|" + t, UUID.randomUUID().toString());
			for (final String t : new String[] { "Physical Data Dictionary", "Glossary" }) this.ids.put("domainType|" + t, UUID.randomUUID().toString());
			for (final String[] r : new String[][] { { "Table", "contains", "Column" }, { "Data Element", "represents", "Business Term" }, { "Column", "is source for", "Column" }, { "Table", "is source for", "Table" }, { "Business Term", "is related to", "Business Term" } })
			{
				final String id = UUID.randomUUID().toString();
				this.ids.put("relationType|" + r[0] + ">" + r[1] + ">" + r[2], id);
				final ObjectNode type = this.relationTypes.addObject();
				type.put("id", id);
				type.putObject("sourceType").put("name", r[0]);
				type.put("role", r[1]);
				type.put("coRole", "is " + r[1] + " of");
				type.putObject("targetType").put("name", r[2]);
			}
			final ObjectNode glossary = this.domain("Business Glossary", "Glossary");
			this.asset("Customer", glossary, "Business Term", "Accepted", "A party buying our products");
			this.asset("Net revenue", glossary, "Business Term", "Candidate", "Gross price minus discount");
		}

		ObjectNode domain(final String pName, final String pType)
		{
			final ObjectNode d = Json.object();
			d.put("id", UUID.randomUUID().toString());
			d.put("name", pName);
			d.putObject("type").put("name", pType).put("id", this.ids.get("domainType|" + pType));
			d.putObject("community").put("name", "Data Governance Council").put("id", this.ids.get("community|Data Governance Council"));
			this.domains.put(pName, d);
			return d;
		}

		ObjectNode asset(final String pName, final ObjectNode pDomain, final String pType, final String pStatus, final String pDefinition)
		{
			final ObjectNode a = Json.object();
			a.put("id", UUID.randomUUID().toString());
			a.put("name", pName);
			a.put("displayName", pName);
			a.putObject("type").put("id", this.ids.get("assetType|" + pType)).put("name", pType);
			a.set("domain", pDomain);
			a.putObject("status").put("id", this.ids.get("status|" + pStatus)).put("name", pStatus);
			a.put("createdOn", 1700000000000L);
			this.assets.put(a.get("id").asText(), a);
			if (pDefinition != null)
			{
				final ObjectNode attr = Json.object();
				attr.put("id", UUID.randomUUID().toString());
				attr.putObject("asset").put("id", a.get("id").asText());
				attr.putObject("type").put("id", this.ids.get("attributeType|Definition")).put("name", "Definition");
				attr.put("value", pDefinition);
				this.attributes.add(attr);
			}
			return a;
		}

		String relationTypeOf(final ObjectNode pRelation)
		{
			return pRelation.path("type").path("id").asText();
		}

		@Override
		public void handle(final com.sun.net.httpserver.HttpExchange pExchange) throws IOException
		{
			final String path = pExchange.getRequestURI().getPath();
			final String query = pExchange.getRequestURI().getQuery() == null ? "" : pExchange.getRequestURI().getQuery();
			final Map<String, String> q = Fake.params(query);
			final String method = pExchange.getRequestMethod();
			final byte[] raw = PowerBiConnectorTest.readAll(pExchange);
			final String body = new String(raw, StandardCharsets.UTF_8);
			this.lastAuthorization = pExchange.getRequestHeaders().getFirst("Authorization");
			try
			{
				if (!path.startsWith("/rest/2.0/")) { this.reply(pExchange, 404, "{}"); return; }
				final String route = path.substring("/rest/2.0".length());
				if (route.equals("/communities")) { this.list(pExchange, this.named("community", q.get("name"))); return; }
				if (route.equals("/assetTypes")) { this.list(pExchange, this.named("assetType", q.get("name"))); return; }
				if (route.equals("/attributeTypes")) { this.list(pExchange, this.named("attributeType", q.get("name"))); return; }
				if (route.equals("/statuses")) { this.list(pExchange, this.named("status", q.get("name"))); return; }
				if (route.equals("/domainTypes")) { this.list(pExchange, this.named("domainType", q.get("name"))); return; }
				if (route.equals("/relationTypes")) { this.list(pExchange, this.relationTypes); return; }
				if (route.equals("/domains") && "GET".equals(method))
				{
					final ArrayNode out = Json.mapper().createArrayNode();
					for (final ObjectNode d : this.domains.values()) if (q.get("name") == null || q.get("name").equalsIgnoreCase(d.get("name").asText())) out.add(d);
					this.list(pExchange, out);
					return;
				}
				if (route.equals("/domains") && "POST".equals(method))
				{
					final JsonNode in = Json.parse(body);
					check(this.ids.get("community|Data Governance Council").equals(in.get("communityId").asText()) && this.ids.get("domainType|Physical Data Dictionary").equals(in.get("typeId").asText()), "domain created in the community with the configured type");
					this.reply(pExchange, 201, Json.write(this.domain(in.get("name").asText(), "Physical Data Dictionary")));
					return;
				}
				if (route.equals("/assets") && "GET".equals(method))
				{
					this.assetQueries.add(query);
					final ArrayNode out = Json.mapper().createArrayNode();
					for (final ObjectNode a : this.assets.values())
					{
						final String name = q.get("name");
						final String mode = q.get("nameMatchMode") == null ? "ANYWHERE" : q.get("nameMatchMode");
						if (name != null && ("EXACT".equals(mode) ? !name.equals(a.get("name").asText()) : !a.get("name").asText().toUpperCase().contains(name.toUpperCase()))) continue;
						if (q.get("domainId") != null && !q.get("domainId").equals(a.get("domain").get("id").asText())) continue;
						if (q.get("typeIds") != null && !q.get("typeIds").equals(a.get("type").get("id").asText())) continue;
						out.add(a);
					}
					this.list(pExchange, out);
					return;
				}
				if (route.equals("/assets") && "POST".equals(method))
				{
					final JsonNode in = Json.parse(body);
					ObjectNode domain = null;
					for (final ObjectNode d : this.domains.values()) if (d.get("id").asText().equals(in.get("domainId").asText())) domain = d;
					check(domain != null, "asset created in an existing domain");
					String type = null;
					for (final Map.Entry<String, String> e : this.ids.entrySet()) if (e.getKey().startsWith("assetType|") && e.getValue().equals(in.get("typeId").asText())) type = e.getKey().substring("assetType|".length());
					final ObjectNode a = this.asset(in.get("name").asText(), domain, type, "Candidate", null);
					if (in.has("displayName")) a.put("displayName", in.get("displayName").asText());
					this.reply(pExchange, 201, Json.write(a));
					return;
				}
				if (route.startsWith("/assets/") && "GET".equals(method))
				{
					final ObjectNode a = this.assets.get(route.substring("/assets/".length()));
					if (a == null) this.reply(pExchange, 404, "{}");
					else this.reply(pExchange, 200, Json.write(a));
					return;
				}
				if (route.startsWith("/assets/") && "PATCH".equals(method))
				{
					final ObjectNode a = this.assets.get(route.substring("/assets/".length()));
					final JsonNode in = Json.parse(body);
					if (in.has("displayName")) a.put("displayName", in.get("displayName").asText());
					this.reply(pExchange, 200, Json.write(a));
					return;
				}
				if (route.equals("/attributes") && "GET".equals(method))
				{
					final ArrayNode out = Json.mapper().createArrayNode();
					for (final ObjectNode attr : this.attributes) if (q.get("assetId").equals(attr.get("asset").get("id").asText())) out.add(attr);
					this.list(pExchange, out);
					return;
				}
				if (route.equals("/attributes") && "POST".equals(method))
				{
					final JsonNode in = Json.parse(body);
					final ObjectNode attr = Json.object();
					attr.put("id", UUID.randomUUID().toString());
					attr.putObject("asset").put("id", in.get("assetId").asText());
					String type = null;
					for (final Map.Entry<String, String> e : this.ids.entrySet()) if (e.getKey().startsWith("attributeType|") && e.getValue().equals(in.get("typeId").asText())) type = e.getKey().substring("attributeType|".length());
					attr.putObject("type").put("id", in.get("typeId").asText()).put("name", type);
					attr.put("value", in.get("value").asText());
					this.attributes.add(attr);
					this.reply(pExchange, 201, Json.write(attr));
					return;
				}
				if (route.startsWith("/attributes/") && "PATCH".equals(method))
				{
					final String id = route.substring("/attributes/".length());
					for (final ObjectNode attr : this.attributes) if (attr.get("id").asText().equals(id)) { attr.put("value", Json.parse(body).get("value").asText()); this.reply(pExchange, 200, Json.write(attr)); return; }
					this.reply(pExchange, 404, "{}");
					return;
				}
				if (route.equals("/relations") && "GET".equals(method))
				{
					final ArrayNode out = Json.mapper().createArrayNode();
					for (final ObjectNode r : this.relations)
					{
						if (q.get("sourceId") != null && !q.get("sourceId").equals(r.get("source").get("id").asText())) continue;
						if (q.get("targetId") != null && !q.get("targetId").equals(r.get("target").get("id").asText())) continue;
						if (q.get("relationTypeId") != null && !q.get("relationTypeId").equals(r.get("type").get("id").asText())) continue;
						out.add(r);
					}
					this.list(pExchange, out);
					return;
				}
				if (route.equals("/relations") && "POST".equals(method))
				{
					final JsonNode in = Json.parse(body);
					final ObjectNode r = Json.object();
					r.put("id", UUID.randomUUID().toString());
					r.putObject("source").put("id", in.get("sourceId").asText()).put("name", this.assets.get(in.get("sourceId").asText()).get("name").asText());
					r.putObject("target").put("id", in.get("targetId").asText()).put("name", this.assets.get(in.get("targetId").asText()).get("name").asText());
					r.putObject("type").put("id", in.get("typeId").asText());
					this.relations.add(r);
					this.reply(pExchange, 201, Json.write(r));
					return;
				}
				if (route.equals("/import/json-job"))
				{
					this.importBody = body;
					this.importContentType = pExchange.getRequestHeaders().getFirst("Content-Type");
					this.reply(pExchange, 200, "{\"id\":\"job-1\",\"state\":\"WAITING\"}");
					return;
				}
				if (route.equals("/jobs/job-1"))
				{
					this.jobPolls++;
					this.reply(pExchange, 200, this.jobPolls < 2 ? "{\"id\":\"job-1\",\"state\":\"RUNNING\"}" : "{\"id\":\"job-1\",\"state\":\"COMPLETED\",\"message\":\"1 asset\"}");
					return;
				}
				this.reply(pExchange, 404, "{\"error\":\"no route " + method + " " + route + "\"}");
			}
			finally
			{
				pExchange.close();
			}
		}

		private ArrayNode named(final String pKind, final String pName)
		{
			final ArrayNode out = Json.mapper().createArrayNode();
			for (final Map.Entry<String, String> e : this.ids.entrySet())
			{
				if (!e.getKey().startsWith(pKind + "|")) continue;
				final String name = e.getKey().substring(pKind.length() + 1);
				if (pName != null && !pName.equalsIgnoreCase(name)) continue;
				out.addObject().put("id", e.getValue()).put("name", name);
			}
			return out;
		}

		private void list(final com.sun.net.httpserver.HttpExchange pExchange, final ArrayNode pResults) throws IOException
		{
			final ObjectNode page = Json.object();
			page.put("total", pResults.size());
			page.set("results", pResults);
			this.reply(pExchange, 200, Json.write(page));
		}

		private void reply(final com.sun.net.httpserver.HttpExchange pExchange, final int pStatus, final String pBody) throws IOException
		{
			final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().add("Content-Type", "application/json");
			pExchange.sendResponseHeaders(pStatus, bytes.length);
			final OutputStream out = pExchange.getResponseBody();
			out.write(bytes);
			out.close();
		}

		static Map<String, String> params(final String pQuery)
		{
			final Map<String, String> map = new LinkedHashMap<String, String>();
			for (final String pair : pQuery.split("&"))
			{
				final int eq = pair.indexOf('=');
				if (eq > 0)
				{
					try { map.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")); }
					catch (final java.io.UnsupportedEncodingException ex) { throw new IllegalStateException(ex); }
				}
			}
			return map;
		}
	}
}
