package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Offline contract of the OpenLineage events the module emits for a mapping run: the EBX table and
 * the connector's object are named the way the data catalog names them, one column-lineage entry
 * per rule, the counts of the run in a facet, a stable run id. No EBX repository, no network.
 */
public final class OpenLineageEmitterTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// --- an outbound mapping Companies -> H2 REFERENCE.COMPANY (upsert), 3 records sent, 1 failed, 1 skipped
		final MappingDefinition mapping = new MappingDefinition(42, "CatalogDemoH2", "upsert", MappingDefinition.Direction.OUTBOUND,
			"Companies -> warehouse REFERENCE.COMPANY", "Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "COMPANY");
		mapping.addInput("key", "MDM_ID");
		mapping.addRule(new MappingRule(10, "./Identification/Id", "MDM_ID", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(20, "./Identification/Name", "NAME", "trim|upper", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(30, "", "SOURCE_SYSTEM", "constant(EBX)", MappingRule.Policy.ALWAYS, null));
		// a constant step that is not the first one, and one in another case: the source path is ignored, so no input field
		mapping.addRule(new MappingRule(40, "./Identification/Name", "LABEL", "upper|constant('X')", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(50, "./Identification/Id", "ORIGIN", "Constant(EBX)", MappingRule.Policy.ALWAYS, null));
		mapping.setOutboundPolicy("delete", Integer.valueOf(3), Integer.valueOf(10));
		final ConnectorConfig target = new ConnectorConfig();
		target.setId("CatalogDemoH2");
		target.setType("h2");
		target.setBaseUrl("jdbc:h2:tcp://localhost:9092/catalogdemo");
		target.setProperty("schema", "REFERENCE");

		final List<SyncResult> results = new ArrayList<SyncResult>();
		results.add(result(mapping, "upsert", SyncResult.Status.OK, "1"));
		results.add(result(mapping, "upsert", SyncResult.Status.OK, "2"));
		results.add(result(mapping, "upsert", SyncResult.Status.OK, "3"));
		results.add(result(mapping, "upsert", SyncResult.Status.ERROR, "4"));
		results.add(result(mapping, "upsert", SyncResult.Status.SKIPPED, "5"));
		results.add(result(mapping, "delete", SyncResult.Status.OK, "6"));
		check(OpenLineageEmitter.worthAnEvent(results), "a run that sent records is worth an event");

		final ConnectorConfig sink = new ConnectorConfig();
		sink.setId("CatalogOpenLineage");
		sink.setType("openlineage");
		sink.setProperty("ebx.namespace", "ebx://localhost:8080");
		final OpenLineageEmitter.Settings settings = OpenLineageEmitter.Settings.of(sink);
		check(settings.outbound && !settings.inbound && settings.excludedTypes.contains("chat-webhook"), "default settings: outbound only, chat webhooks excluded");
		check(settings.accepts(mapping, target, "CatalogOpenLineage"), "the H2 target is described");
		check(!settings.accepts(mapping, sink, "CatalogOpenLineage"), "the sink never describes itself");

		final Date when = new Date(1789000000000L);
		final ObjectNode event = OpenLineageEmitter.event(mapping, target, results, "R20260913-120000-0001", when, settings);
		check("FAIL".equals(event.get("eventType").asText()), "mixed failures must not claim a completed successful run");
		check(event.get("eventTime").asText().endsWith("Z"), "ISO instant event time: " + event.get("eventTime").asText());
		check("ebx-connectors".equals(event.get("job").get("namespace").asText()) && "Companies -> warehouse REFERENCE.COMPANY".equals(event.get("job").get("name").asText()),
			"job = mapping label in the ebx-connectors namespace");
		final JsonNode input = event.get("inputs").get(0);
		check("ebx://localhost:8080".equals(input.get("namespace").asText()) && "Companies/Companies/root/Companies".equals(input.get("name").asText()),
			"input = the EBX table, named as the catalog names it: " + input);
		final JsonNode output = event.get("outputs").get(0);
		check("h2://localhost:9092/catalogdemo".equals(output.get("namespace").asText()) && "REFERENCE.COMPANY".equals(output.get("name").asText()),
			"output = the JDBC object with the connector's schema: " + output);
		final JsonNode fields = output.get("facets").get("columnLineage").get("fields");
		check(fields.size() == 2 && fields.has("MDM_ID") && fields.has("NAME") && !fields.has("SOURCE_SYSTEM"), "one column entry per rule with a source, constants left out: " + fields);
		check(!fields.has("LABEL") && !fields.has("ORIGIN"), "a constant step anywhere in the pipeline, in any case, gives no input field: " + fields);
		final JsonNode name = fields.get("NAME").get("inputFields").get(0);
		check("Identification/Name".equals(name.get("field").asText()) && "TRANSFORMATION".equals(name.get("transformations").get(0).get("subtype").asText())
			&& "trim|upper".equals(name.get("transformations").get(0).get("description").asText()), "a transformed rule: " + name);
		check("IDENTITY".equals(fields.get("MDM_ID").get("inputFields").get(0).get("transformations").get(0).get("subtype").asText()), "a plain rule is an identity");
		check(output.get("outputFacets").get("outputStatistics").get("rowCount").asInt() == 3, "rowCount = records sent");
		final JsonNode summary = event.get("run").get("facets").get("ebx_connectors");
		check(summary.get("sent").asInt() == 3 && summary.get("failed").asInt() == 1 && summary.get("skipped").asInt() == 1 && summary.get("deleted").asInt() == 1
			&& summary.get("mapping").asInt() == 42, "counts of the run: " + summary);
		final String runId = event.get("run").get("runId").asText();
		check(runId.matches("[0-9a-f-]{36}"), "run id is a UUID: " + runId);
		check(runId.equals(OpenLineageEmitter.event(mapping, target, results, "R20260913-120000-0001", when, settings).get("run").get("runId").asText()), "run id is stable per run and mapping");
		check(!runId.equals(OpenLineageEmitter.event(mapping, target, results, "R20260913-120000-0002", when, settings).get("run").get("runId").asText()), "another run, another id");

		// --- everything failed: FAIL with the first message
		final List<SyncResult> failed = new ArrayList<SyncResult>();
		final SyncResult error = result(mapping, "upsert", SyncResult.Status.ERROR, "9");
		error.setMessage("connection refused");
		failed.add(error);
		final ObjectNode fail = OpenLineageEmitter.event(mapping, target, failed, "R1", when, settings);
		check("FAIL".equals(fail.get("eventType").asText()) && "connection refused".equals(fail.get("run").get("facets").get("errorMessage").get("message").asText()), "FAIL carries the error");

		// --- nothing sent: not worth an event; a simulation never is
		final List<SyncResult> quiet = new ArrayList<SyncResult>();
		quiet.add(result(mapping, "upsert", SyncResult.Status.NO_CHANGE, "1"));
		check(!OpenLineageEmitter.worthAnEvent(quiet), "unchanged records: no event");
		quiet.add(result(mapping, "upsert", SyncResult.Status.DRY_RUN, "2"));
		check(!OpenLineageEmitter.worthAnEvent(quiet), "a simulation: no event");
		quiet.get(1).getErrors().add("synthetic field error");
		check(!OpenLineageEmitter.worthAnEvent(quiet), "failed simulation must never emit observed lineage");
		final List<SyncResult> partialResults = new ArrayList<SyncResult>();
		final SyncResult partialResult = result(mapping, "upsert", SyncResult.Status.OK, "partial");
		partialResult.getErrors().add("synthetic field error");
		partialResults.add(partialResult);
		check(OpenLineageEmitter.worthAnEvent(partialResults), "partial write must be observable");
		final ObjectNode partialEvent = OpenLineageEmitter.event(mapping, target, partialResults, "partial-run", new Date(), settings);
		check("FAIL".equals(partialEvent.path("eventType").asText()), "partial item must fail run");
		check(partialEvent.path("run").path("facets").path("ebx_connectors").path("partial").asInt() == 1, "partial count retained");

		// --- inbound: EBX is the output, the connector's object the input
		final MappingDefinition inbound = new MappingDefinition(7, "Salesforce", "query", MappingDefinition.Direction.INBOUND, "Salesforce accounts -> Companies",
			null, "Companies", "/root/Companies", "", true);
		inbound.addInput("sobject", "Account");
		inbound.addRule(new MappingRule(10, "records[0].Name", "./Identification/Name", "", MappingRule.Policy.IF_EMPTY, null));
		final ConnectorConfig sf = new ConnectorConfig();
		sf.setId("Salesforce");
		sf.setType("salesforce");
		sf.setBaseUrl("https://acme.my.salesforce.com/services/data/v60.0");
		check(!settings.accepts(inbound, sf, "CatalogOpenLineage"), "inbound runs are off by default");
		sink.setProperty("emit.inbound", "true");
		final OpenLineageEmitter.Settings both = OpenLineageEmitter.Settings.of(sink);
		check(both.accepts(inbound, sf, "CatalogOpenLineage"), "inbound runs on request");
		final List<SyncResult> imported = new ArrayList<SyncResult>();
		imported.add(new SyncResult("Salesforce", "query", "IMPORT", "Companies", "/root/Companies", "./Identification/Id=12", "Acme"));
		imported.get(0).setStatus(SyncResult.Status.OK);
		final ObjectNode in = OpenLineageEmitter.event(inbound, sf, imported, "R2", when, both);
		check("salesforce://acme.my.salesforce.com".equals(in.get("inputs").get(0).get("namespace").asText()) && "Account".equals(in.get("inputs").get(0).get("name").asText()),
			"inbound input = Salesforce object: " + in.get("inputs"));
		check("Companies/Companies/root/Companies".equals(in.get("outputs").get(0).get("name").asText()), "inbound output = the EBX table, dataspace taken from the result");
		check(in.get("outputs").get(0).get("facets").get("columnLineage").get("fields").has("Identification/Name"), "inbound column lineage keyed by the EBX field");

		// --- namespaces of the usual URLs
		check("postgresql://db.corp:5432/mdm".equals(OpenLineageEmitter.jdbcNamespace("jdbc:postgresql://db.corp:5432/mdm?ssl=true")), "postgresql namespace");
		check("sqlserver://sql.corp:1433/MDM".equals(OpenLineageEmitter.jdbcNamespace("jdbc:sqlserver://sql.corp:1433;databaseName=MDM;encrypt=true")), "sqlserver namespace");
		check("oracle://ora.corp:1521/ORCL".equals(OpenLineageEmitter.jdbcNamespace("jdbc:oracle:thin:@//ora.corp:1521/ORCL")), "oracle namespace");
		check("snowflake://acme.snowflakecomputing.com".equals(OpenLineageEmitter.jdbcNamespace("jdbc:snowflake://acme.snowflakecomputing.com/?db=ANALYTICS")), "snowflake namespace");
		final ConnectorConfig rest = new ConnectorConfig();
		rest.setId("Wikidata");
		rest.setType("rest");
		rest.setBaseUrl("https://www.wikidata.org");
		check("rest://www.wikidata.org".equals(OpenLineageEmitter.namespaceOf(rest)), "REST namespace = type://host");
		final ConnectorConfig bare = new ConnectorConfig();
		bare.setId("Local");
		bare.setType("kafka");
		bare.setBaseUrl("localhost:9082");
		check("kafka://localhost:9082".equals(OpenLineageEmitter.namespaceOf(bare)), "host:port without a scheme names the cluster: " + OpenLineageEmitter.namespaceOf(bare));
		bare.setBaseUrl("broker1:9092,broker2:9092");
		check("kafka://broker1:9092".equals(OpenLineageEmitter.namespaceOf(bare)), "bootstrap servers: the first one: " + OpenLineageEmitter.namespaceOf(bare));

		// --- declared lineage: a JobEvent before any run, the topic named by the mapping (a 'table' input on a broker is only a label)
		final MappingDefinition kafka = new MappingDefinition(131, "Kafka", "publish", MappingDefinition.Direction.OUTBOUND, "Company -> Kafka / CRM partner",
			"Companies", "Companies", "/root/Companies", "", true);
		kafka.addInput("table", "Companies");
		kafka.addInput("topic", "crm.partner.upsert");
		kafka.addInput("key", "${./Identification/Id}");
		kafka.addRule(new MappingRule(10, "./Identification/Id", "partnerCode", "prefix(EBX-)", MappingRule.Policy.ALWAYS, null));
		kafka.addRule(new MappingRule(20, "", "meta.source", "constant(ON EBX)", MappingRule.Policy.ALWAYS, null));
		final MappingDefinition sites = new MappingDefinition(133, "Kafka", "publish", MappingDefinition.Direction.OUTBOUND, "Company -> Kafka / CRM partner / sites",
			"Companies", "Companies", "/root/CompanyAddress", "", true);
		sites.setParentId(Integer.valueOf(131));
		sites.setSubObject("sites", "./company", "", "", false);
		sites.addRule(new MappingRule(10, "./City", "city", "upper", MappingRule.Policy.ALWAYS, null));
		kafka.addChild(sites);
		final ConnectorConfig broker = new ConnectorConfig();
		broker.setId("Kafka");
		broker.setType("kafka");
		broker.setBaseUrl("localhost:9082");
		check(settings.declared && settings.acceptsDeclaration(kafka, broker, "CatalogOpenLineage"), "declarations are on by default");
		final ObjectNode declared = OpenLineageEmitter.declaration(kafka, broker, when, settings);
		check(!declared.has("run") && !declared.has("eventType") && declared.get("schemaURL").asText().endsWith("/JobEvent"), "a declaration is a JobEvent: " + declared);
		check("Company -> Kafka / CRM partner".equals(declared.get("job").get("name").asText())
			&& "DECLARED".equals(declared.get("job").get("facets").get(OpenLineageEmitter.DECLARATION_FACET).get("state").asText()), "job = the mapping, state DECLARED");
		final JsonNode topic = declared.get("outputs").get(0);
		check("kafka://localhost:9082".equals(topic.get("namespace").asText()) && "crm.partner.upsert".equals(topic.get("name").asText()), "output = the topic, not the 'table' label: " + topic);
		check("Companies/Companies/root/Companies".equals(declared.get("inputs").get(0).get("name").asText()), "input = the EBX table");
		final JsonNode declaredFields = topic.get("facets").get("columnLineage").get("fields");
		check(declaredFields.has("partnerCode") && declaredFields.has("sites[].city") && !declaredFields.has("meta.source"),
			"column lineage of the root and of its sub-object mapping, constants left out: " + declaredFields);
		check(!topic.has("outputFacets"), "no row count on a declaration");
		check(declared.get("inputs").size() == 2 && "Companies/Companies/root/CompanyAddress".equals(declared.get("inputs").get(1).get("name").asText()),
			"the sub-object mapping's table is an input of its own: " + declared.get("inputs"));
		check("Companies/Companies/root/CompanyAddress".equals(declaredFields.get("sites[].city").get("inputFields").get(0).get("name").asText())
			&& "City".equals(declaredFields.get("sites[].city").get("inputFields").get(0).get("field").asText())
			&& "Companies/Companies/root/Companies".equals(declaredFields.get("partnerCode").get("inputFields").get(0).get("name").asText()),
			"a collection's fields come from the child table, not from the root: " + declaredFields);

		// --- inbound feed with a sub-object mapping stored without dataset: the child table is an output, fed by the collection
		final MappingDefinition feed = new MappingDefinition(182, "Kafka", "consume", MappingDefinition.Direction.INBOUND, "ERP -> PointOfSale",
			"PointOfSale", "PointOfSale", "/root/PointOfSale", "", true);
		feed.addInput("topic", "erp.pos.extract");
		feed.setFeed("messages[*].value", "./Code='${code}'", true);
		feed.addRule(new MappingRule(10, "name", "./Name", "", MappingRule.Policy.IF_EMPTY, null));
		final MappingDefinition xref = new MappingDefinition(183, "Kafka", "consume", MappingDefinition.Direction.INBOUND, "ERP -> PointOfSale / xref",
			null, null, "/root/ErpCrossReference", "", true);
		xref.setParentId(Integer.valueOf(182));
		xref.setSubObject("erpReferences", "./PointOfSale", "./System,./ErpCode", "", false);
		xref.addRule(new MappingRule(10, "erpCode", "./ErpCode", "", MappingRule.Policy.ALWAYS, null));
		feed.addChild(xref);
		final ObjectNode feedDeclared = OpenLineageEmitter.declaration(feed, broker, when, both);
		final JsonNode feedOutputs = feedDeclared.get("outputs");
		check(feedOutputs.size() == 2 && "PointOfSale/PointOfSale/root/ErpCrossReference".equals(feedOutputs.get(1).get("name").asText()),
			"inbound: the sub-object mapping's table is an output, dataset and dataspace of the root: " + feedOutputs);
		final JsonNode xrefIn = feedOutputs.get(1).get("facets").get("columnLineage").get("fields").get("ErpCode").get("inputFields").get(0);
		check("erp.pos.extract".equals(xrefIn.get("name").asText()) && "erpReferences[].erpCode".equals(xrefIn.get("field").asText()),
			"inbound: the child table's field comes from the collection of the message: " + xrefIn);
		check(feedOutputs.get(0).get("facets").get("columnLineage").get("fields").has("Name") && !feedOutputs.get(0).get("facets").get("columnLineage").get("fields").has("ErpCode"),
			"inbound: the root table keeps only its own fields");
		final MappingDefinition untopic = new MappingDefinition(91, "Kafka", "publish", MappingDefinition.Direction.OUTBOUND, "Company -> Kafka",
			"Companies", "Companies", "/root/Companies", "", true);
		untopic.addInput("table", "Companies");
		check("publish".equals(OpenLineageEmitter.datasetOf(broker, untopic)[1]), "no topic anywhere: the operation, never the 'table' label");
		broker.setProperty("topic", "companies.default");
		check("companies.default".equals(OpenLineageEmitter.datasetOf(broker, untopic)[1]), "no topic input: the connector's default topic");

		// --- product publication event (§2.3): outputStatistics.rowCount must sit under outputFacets, a
		// sibling of facets (schema, columnLineage) - per the OpenLineage spec, and matching event()'s own
		// nesting above (line ~75) - so a consumer (the data catalog's OpenLineageImporter) reading
		// outputs[].outputFacets.outputStatistics.rowCount actually finds it.
		final ConnectorConfig snowflake = new ConnectorConfig();
		snowflake.setId("Snowflake");
		snowflake.setType("snowflake");
		snowflake.setBaseUrl("jdbc:snowflake://acme.snowflakecomputing.com/?db=SNOWFLAKE_LEARNING_DB");
		final MappingDefinition product = new MappingDefinition(210, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND,
			"Company 360 -> Snowflake", "Companies", "Companies", "/root/Companies", "", true);
		product.addInput("table", "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS.COMPANY_360");
		final List<String[]> columns = new ArrayList<String[]>();
		columns.add(new String[] { "ID", "NUMBER(38,0)" });
		final List<OpenLineageEmitter.PublicationTable> tables = new ArrayList<OpenLineageEmitter.PublicationTable>();
		tables.add(new OpenLineageEmitter.PublicationTable(210, "COMPANY_360", 632, columns));
		final ObjectNode publicationEvent = OpenLineageEmitter.publicationEvent(java.util.Collections.singletonList(product), "Companies", snowflake,
			"R20260919-180235-0001", "COMPANY_360", "1", "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", "2026-09-19T18:02:35", tables, false, null, when, settings);
		final JsonNode publicationOutput = publicationEvent.get("outputs").get(0);
		check("SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS.COMPANY_360".equals(publicationOutput.get("name").asText()), "output = the qualified table: " + publicationOutput);
		check(publicationOutput.has("outputFacets") && publicationOutput.get("outputFacets").has("outputStatistics"),
			"outputStatistics lives under outputFacets, not facets: " + publicationOutput);
		check(publicationOutput.get("outputFacets").get("outputStatistics").get("rowCount").asInt() == 632, "publication rowCount = rows written: " + publicationOutput);
		check(!publicationOutput.path("facets").has("outputStatistics"), "outputStatistics must not also sit under facets: " + publicationOutput);
		check(publicationOutput.get("facets").has("schema"), "the schema facet stays under facets, unaffected: " + publicationOutput);

		final ObjectNode retired = OpenLineageEmitter.retirement("Company -> Kafka / CRM partner", 131, when, settings);
		check("RETIRED".equals(retired.get("job").get("facets").get(OpenLineageEmitter.DECLARATION_FACET).get("state").asText())
			&& retired.get("inputs").size() == 0 && retired.get("outputs").size() == 0 && !retired.has("run"), "a retirement is a JobEvent without datasets: " + retired);
		check("mapping-7".equals(OpenLineageEmitter.jobName(7, " ")), "a mapping without label: mapping-<id>");
		sink.setProperty("emit.declared", "false");
		check(!OpenLineageEmitter.Settings.of(sink).acceptsDeclaration(kafka, broker, "CatalogOpenLineage"), "emit.declared=false turns declarations off for that sink");

		System.out.println("OpenLineageEmitterTest: OK");
	}

	private static SyncResult result(final MappingDefinition pMapping, final String pOperation, final SyncResult.Status pStatus, final String pKey)
	{
		final SyncResult r = new SyncResult(pMapping.getConnectorId(), pOperation, "OUTBOUND", "Companies", pMapping.getTablePath(), pKey, "record " + pKey);
		r.setStatus(pStatus);
		r.setMappingId(String.valueOf(pMapping.getId()));
		return r;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
