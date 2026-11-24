package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/** Offline protocol fixtures for the shared OData v2/v4 foundation. */
public final class ODataSupportTest
{
	private ODataSupportTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		queryOptionsAreVersionAware();
		v2AndV4PagesNormalizeIdentically();
		metadataDiscoverySupportsV2AndV4Namespaces();
		continuationsStayOnOrigin();
		v4BatchPrimitivesPreserveProtocolFields();
		boundedPagingPreservesDeltaTombstones();
		v2BatchUsesMultipartChangeset();
		v2MultipartResponseIsBoundedAndNormalized();
		System.out.println("PASS: OData v2/v4 metadata, query, paging, delta and batch foundations");
	}

	private static void queryOptionsAreVersionAware()
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("select", "Id,Name");
		inputs.put("$filter", "Active eq true");
		inputs.put("skiptoken", "opaque-token");
		final Map<String, String> v2 = ODataSupport.query(ODataSupport.Version.V2, inputs, true, "50", "Addresses");
		check("json".equals(v2.get("$format")), "v2 format missing");
		check("allpages".equals(v2.get("$inlinecount")), "v2 count option missing");
		check("opaque-token".equals(v2.get("$skiptoken")), "skip token missing");
		check("Addresses".equals(v2.get("$expand")), "default expand missing");
		final Map<String, String> v4 = ODataSupport.query(ODataSupport.Version.V4, inputs, true, "50", null);
		check(!v4.containsKey("$format") && "true".equals(v4.get("$count")), "v4 options incorrect");
	}

	private static void v2AndV4PagesNormalizeIdentically() throws Exception
	{
		final ObjectNode v2 = ODataSupport.collection(Json.parse("{\"d\":{\"results\":[{\"Id\":\"1\",\"When\":\"/Date(0)/\",\"__metadata\":{}}],\"__count\":\"9\",\"__next\":\"People?$skiptoken=a\"}}"));
		check(v2.get("results").size() == 1 && v2.get("totalCount").asInt() == 9, "v2 page not normalized");
		check("1970-01-01T00:00:00Z".equals(Json.at(v2, "results[0].When").asText()), "v2 date not normalized");
		check("People?$skiptoken=a".equals(v2.get("nextLink").asText()), "v2 next link missing");
		final ObjectNode v4 = ODataSupport.collection(Json.parse("{\"value\":[],\"@odata.count\":0,\"@odata.deltaLink\":\"People?$deltatoken=z\"}"));
		check(v4.get("results").isArray() && v4.get("results").size() == 0, "empty v4 page was lost");
		check("People?$deltatoken=z".equals(v4.get("deltaLink").asText()), "v4 delta link missing");
	}

	private static void metadataDiscoverySupportsV2AndV4Namespaces() throws Exception
	{
		final String v2 = "<?xml version=\"1.0\"?><edmx:Edmx xmlns:edmx=\"urn:edmx\" xmlns:m=\"urn:m\" xmlns:sap=\"http://www.sap.com/Protocols/SAPData\"><edmx:DataServices><Schema xmlns=\"urn:edm\" Namespace=\"Demo\"><EntityType Name=\"Person\"><Key><PropertyRef Name=\"Id\"/></Key><Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\" MaxLength=\"40\" sap:label=\"Identifier\"/><NavigationProperty Name=\"Orders\" Relationship=\"Demo.Rel\"/></EntityType><EntityContainer Name=\"C\"><EntitySet Name=\"People\" EntityType=\"Demo.Person\"/></EntityContainer></Schema></edmx:DataServices></edmx:Edmx>";
		final List<ODataSupport.MetadataField> fields = ODataSupport.parseMetadata(v2, "People");
		check(fields.size() == 2 && "Identifier".equals(fields.get(0).getLabel()), "v2 metadata fields missing");
		check(!fields.get(0).isNullable() && fields.get(1).isNavigation(), "metadata facets missing");
		check(fields.get(0).isKey() && "40".equals(fields.get(0).getMaxLength()), "metadata key/length facets missing");
		final String v4 = "<edmx:Edmx xmlns:edmx=\"urn:edmx\"><edmx:DataServices><Schema xmlns=\"urn:edm\" Namespace=\"N\"><EntityType Name=\"Thing\"><Property Name=\"Amount\" Type=\"Edm.Decimal\"/></EntityType><EntityContainer Name=\"C\"><EntitySet Name=\"Things\" EntityType=\"N.Thing\"/></EntityContainer></Schema></edmx:DataServices></edmx:Edmx>";
		check("Decimal".equals(ODataSupport.parseMetadata(v4, "Things").get(0).getType()), "v4 metadata type missing");
	}

	private static void continuationsStayOnOrigin() throws Exception
	{
		check("https://example.test/odata/People?$skiptoken=x".equals(ODataSupport.continuationUrl("https://example.test/odata/", "People?$skiptoken=x")), "relative continuation failed");
		try
		{
			ODataSupport.continuationUrl("https://example.test/odata/", "https://evil.test/steal");
			throw new AssertionError("cross-origin continuation accepted");
		}
		catch (final ConnectorException expected) { }
	}

	private static void v4BatchPrimitivesPreserveProtocolFields() throws Exception
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("If-Match", "*");
		final ODataSupport.BatchOperation operation = new ODataSupport.BatchOperation("1", "patch", "People('1')", headers,
			Json.parse("{\"Name\":\"Ada\"}"), Arrays.asList("0"));
		final ObjectNode batch = ODataSupport.v4Batch(Arrays.asList(operation));
		check("PATCH".equals(Json.at(batch, "requests[0].method").asText()), "batch method not normalized");
		check("*".equals(Json.at(batch, "requests[0].headers.If-Match").asText()), "batch headers missing");
		final JsonNode response = ODataSupport.v4BatchResponse(Json.parse("{\"responses\":[{\"id\":\"1\",\"status\":200,\"body\":{\"@odata.context\":\"x\",\"Name\":\"Ada\"}}]}"));
		check(Json.at(response, "responses[0].body").get("@odata.context") == null, "batch body annotation retained");
		check("Ada".equals(Json.at(response, "responses[0].body.Name").asText()), "batch body lost");
	}

	private static void boundedPagingPreservesDeltaTombstones() throws Exception
	{
		final JsonNode first = Json.parse("{\"value\":[{\"id\":\"1\"}],\"@odata.nextLink\":\"Things?$skiptoken=x\"}");
		final ObjectNode merged = ODataSupport.collectPages("https://example.test/odata/", first, 3, 10,
			new ODataSupport.PageFetcher() { public JsonNode get(final String pUrl) throws ConnectorException {
				try { return Json.parse("{\"value\":[{\"id\":\"2\",\"@odata.removed\":{\"reason\":\"deleted\"}}],\"@odata.deltaLink\":\"Things?$deltatoken=z\"}"); }
				catch (final java.io.IOException ex) { throw new ConnectorException(ex.getMessage(), ex); }
			} });
		check(merged.get("results").size() == 2 && Json.at(merged, "results[1]._deleted").asBoolean(), "delta tombstone lost");
		check("Things?$deltatoken=z".equals(merged.get("deltaLink").asText()), "final delta link lost");
	}

	private static void v2BatchUsesMultipartChangeset() throws Exception
	{
		final ODataSupport.V2Batch batch = ODataSupport.v2Batch(Arrays.asList(
			new ODataSupport.BatchOperation("1", "get", "People('1')", null, null, null),
			new ODataSupport.BatchOperation("2", "patch", "People('2')", null, Json.parse("{\"Name\":\"Ada\"}"), null)), "fixed");
		final String body = new String(batch.getBody(), "UTF-8");
		check(batch.getContentType().contains("boundary=fixed"), "v2 batch content type missing");
		check(body.contains("multipart/mixed; boundary=changeset_fixed") && body.contains("PATCH People('2') HTTP/1.1"), "v2 changeset missing");
	}

	private static void v2MultipartResponseIsBoundedAndNormalized() throws Exception
	{
		final String body = "--batch_x\r\nContent-Type: application/http\r\nContent-ID: 1\r\n\r\nHTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"d\":{\"Name\":\"Ada\",\"__metadata\":{}}}\r\n"
			+ "--batch_x\r\nContent-Type: multipart/mixed; boundary=change_y\r\n\r\n--change_y\r\nContent-Type: application/http\r\nContent-ID: 2\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n--change_y--\r\n--batch_x--\r\n";
		final ObjectNode response = ODataSupport.v2BatchResponse("multipart/mixed; boundary=\"batch_x\"", body, 3, 4096);
		check(response.withArray("responses").size() == 2, "nested v2 batch responses missing");
		check("Ada".equals(Json.at(response, "responses[0].body.d.Name").asText()) && Json.at(response, "responses[0].body.d").get("__metadata") == null, "v2 batch JSON not normalized");
		boolean rejected = false;
		try { ODataSupport.v2BatchResponse("multipart/mixed; boundary=batch_x", body, 1, 4096); }
		catch (final ConnectorException expected) { rejected = true; }
		check(rejected, "v2 multipart part bound ignored");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
