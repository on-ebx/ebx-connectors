package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Another EBX instance through its Data Services REST API (base URL
 * {@code http://host/ebx-dataservices/rest/data/v1}, HTTP Basic). The {@code content} envelopes are
 * unwrapped on read and rebuilt on write, so that mappings see plain records.
 *
 * <p>Operations: {@code read} (dataspace, dataset, table, key) one record; {@code search} (dataspace,
 * dataset, table, optional filter and pageSize) a feed ({@code rows[]}); {@code write} (dataspace,
 * dataset, table, optional key) creates or updates the record built by an outbound mapping.
 * Property {@code dataspacePrefix}: {@code B} (branch, default) or {@code V} (snapshot).
 *
 * <p>Data-catalog operations ({@link EbxCatalog}): {@code catalog}, {@code profile}, {@code sample}
 * and {@code lineage} describe the instance — its tables and columns from the metamodels, row
 * counts, statistics and samples from the first records, the foreign keys as column lineage — in the
 * documents the data catalog's flows consume, so that an EBX instance (this one included, through
 * its own Data Services) is scanned like a database.
 */
public final class EbxConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("read", "One record by primary key", "dataspace", "dataset", "table", "key"),
		Operation.search("search", "Records of a table (optional XPath filter, pageSize)", "dataspace", "dataset", "table"),
		Operation.write("write", "Create (no key) or update (key) a record from the document", "dataspace", "dataset", "table"),
		Operation.search("catalog", "Inventory of the tables and columns of the instance for the data catalog (input source; optional schemas = dataspaces "
			+ "or dataspace/dataset, exclude, rowCounts)", "source"),
		Operation.search("profile", "Column statistics from the first records of each table, for the data catalog (input source; optional schemas, tables, sampleSize)", "source"),
		Operation.search("sample", "Sampled values of the text columns, for the data catalog's classification (input source; optional schemas, tables, limit)", "source"),
		Operation.search("lineage", "Foreign keys of the data models as column lineage, for the data catalog (input source; optional schemas, fkScope = cross | all)", "source"));

	@Override
	public String getType()
	{
		return "ebx";
	}

	@Override
	public String getLabel()
	{
		return "EBX (another instance, Data Services)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "GENERIC_INTEGRATION")
			.description("EBX Data Services REST connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).apiVersion("data/v1")
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("dataspacePrefix", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).defaultValue("B").enumerationValues("B", "V").build());
		for (final Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return EbxConnector.OPERATIONS;
	}

	/** One bounded Data Services GET using this connector's saved endpoint and credentials. */
	@Override
	public com.on.ebx.connect.spi.PreviewResult preview(ConnectorContext context, com.on.ebx.connect.spi.PreviewRequest request) throws ConnectorException
	{
		if (!request.getOperation().isEmpty() && !"search".equals(request.getOperation()))
			return com.on.ebx.connect.spi.PreviewResult.unsupported("EBX preview only supports table search.");
		String[] identity = previewIdentity(request.getObjectId());
		Map<String,String> inputs = new LinkedHashMap<String,String>();
		inputs.put("dataspace", identity[0]);
		inputs.put("dataset", identity[1]);
		inputs.put("table", identity[2]);
		inputs.put("pageSize",String.valueOf(request.getLimit()+1));
		JsonNode response=execute(context,"search",inputs,null);
		if(response==null||!response.path("rows").isArray())throw new ConnectorException("EBX preview returned an invalid rows envelope.");
		List<JsonNode> records=new java.util.ArrayList<JsonNode>();
		for (JsonNode row:response.path("rows"))
		{
			if (records.size() >= request.getLimit()) break;
			records.add(row);
		}
		return com.on.ebx.connect.spi.PreviewResult.of(records,response.path("rows").size()>request.getLimit());
	}

	static String[] previewIdentity(String qualified) throws ConnectorException
	{
		String[] parts=qualified==null?new String[0]:qualified.split("/",3);
		if(parts.length!=3||!parts[2].startsWith("root/")||parts[2].endsWith("/"))throw new ConnectorException("EBX preview requires dataspace/dataset/root/table identity.");
		for(String segment:qualified.split("/",-1))
			if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||!segment.matches("[A-Za-z0-9_ .-]+"))throw new ConnectorException("Invalid EBX preview identity.");
		return new String[]{parts[0],parts[1],"/"+parts[2]};
	}

	/** One Data Services GET for the catalog operations; null on 404. */
	JsonNode fetch(final String pPath, final Map<String, String> pQuery, final String pWhat) throws ConnectorException
	{
		return this.bodyOrNull(this.get(pPath, pQuery), pWhat);
	}

	private String tablePath(final Map<String, String> pInputs) throws ConnectorException
	{
		final String prefix = this.getConfig().getProperty("dataspacePrefix", "B");
		final String table = AbstractRestConnector.require(pInputs, "table");
		return "/" + prefix + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "dataspace")) + "/"
				+ AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "dataset")) + (table.startsWith("/") ? table : "/" + table);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		if (EbxCatalog.OPERATIONS.contains(pOperation))
		{
			final String what = pOperation;
			// package-private accessor on purpose: a protected call from the anonymous class would need a compiler-specific synthetic accessor
			final EbxCatalog.Fetcher fetcher = new EbxCatalog.Fetcher()
			{
				@Override
				public JsonNode get(final String pPath, final Map<String, String> pQuery) throws ConnectorException
				{
					return EbxConnector.this.fetch(pPath, pQuery, what);
				}
			};
			return new EbxCatalog(fetcher, this.getConfig().getProperty("dataspacePrefix", "B"), this.getConfig().getBaseUrl(), this.getConfig().getId()).execute(pOperation, pInputs);
		}
		final String table = this.tablePath(pInputs);
		if ("read".equals(pOperation))
		{
			final JsonNode reply = this.bodyOrNull(this.get(table + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "key")), null), "read");
			return reply == null ? null : EbxConnector.unwrap(reply.get("content"));
		}
		if ("search".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			final String filter = pInputs.get("filter");
			if (filter != null && !filter.trim().isEmpty())
			{
				query.put("filter", filter.trim());
			}
			query.put("pageSize", pInputs.get("pageSize") == null || pInputs.get("pageSize").trim().isEmpty() ? "100" : pInputs.get("pageSize").trim());
			final JsonNode reply = this.bodyOrNull(this.get(table, query), "search");
			final ObjectNode out = Json.object();
			final ArrayNode rows = out.putArray("rows");
			if (reply != null && reply.get("rows") != null)
			{
				for (final JsonNode row : reply.get("rows"))
				{
					final JsonNode record = EbxConnector.unwrap(row.get("content"));
					if (record != null && record.isObject())
					{
						final String details = row.path("details").asText(null);
						if (details != null && details.lastIndexOf('/') > 0)
						{
							((ObjectNode) record).put("_key", details.substring(details.lastIndexOf('/') + 1));
						}
						rows.add(record);
					}
				}
			}
			out.put("count", rows.size());
			return out;
		}
		if ("write".equals(pOperation))
		{
			if (pPayload == null || !pPayload.isObject())
			{
				throw new ConnectorException(this.getConfig().getId() + ": write needs the document built by an outbound mapping");
			}
			final ObjectNode body = Json.object();
			body.set("content", EbxConnector.wrap(pPayload));
			final String key = pInputs.get("key");
			final boolean update = key != null && !key.trim().isEmpty();
			final HttpJsonClient.Response response = update ? this.send("PUT", table + "/" + AbstractRestConnector.segment(key.trim()), null, body)
					: this.send("POST", table, null, body);
			if (!response.isSuccess())
			{
				throw new ConnectorException(this.getConfig().getId() + ": write -> HTTP " + response.getStatus() + " " + String.valueOf(response.getRawBody()),
					response.getStatus(), null);
			}
			final ObjectNode out = Json.object();
			out.put("status", update ? "updated" : "created");
			out.put("http", response.getStatus());
			final String location = response.getHeader("Location");
			if (location != null)
			{
				out.put("key", location.substring(location.lastIndexOf('/') + 1));
			}
			else if (update)
			{
				out.put("key", key.trim());
			}
			return out;
		}
		throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
	}

	/** {@code {field: {content: v}}} envelopes to plain values, recursively. */
	static JsonNode unwrap(final JsonNode pContent)
	{
		if (pContent == null || !pContent.isObject())
		{
			return pContent;
		}
		final ObjectNode out = Json.object();
		final Iterator<Map.Entry<String, JsonNode>> fields = pContent.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> e = fields.next();
			final JsonNode v = e.getValue();
			if (v != null && v.isObject() && v.has("content"))
			{
				final JsonNode inner = v.get("content");
				if (inner.isObject())
				{
					out.set(e.getKey(), EbxConnector.unwrap(inner));
				}
				else if (inner.isArray())
				{
					final ArrayNode list = out.putArray(e.getKey());
					for (final JsonNode item : inner)
					{
						list.add(item.isObject() && item.has("content") ? item.get("content") : item);
					}
				}
				else
				{
					out.set(e.getKey(), inner);
				}
			}
			else
			{
				out.set(e.getKey(), v);
			}
		}
		return out;
	}

	/** Plain values to Data Services envelopes, recursively. */
	static ObjectNode wrap(final JsonNode pRecord)
	{
		final ObjectNode out = Json.object();
		final Iterator<Map.Entry<String, JsonNode>> fields = pRecord.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> e = fields.next();
			final ObjectNode holder = out.putObject(e.getKey());
			final JsonNode v = e.getValue();
			holder.set("content", v != null && v.isObject() ? EbxConnector.wrap(v) : v);
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("catalog".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("tables", "Tables (feed items): source, assetKey, qualifiedName, schema, name, kind, label, remarks, rowCount, url, scannedAt, columns[]", "array"),
				FieldInfo.of("tables[0].columns", "Columns: columnKey, name, position, dataType, nativeType, nullable, primaryKey, label, remarks, foreignKey, scannedAt", "array"),
				FieldInfo.of("count", "Number of tables", "number"));
		}
		if ("profile".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("columns", "One item per column: columnKey, assetKey, qualifiedName, name, dataType, profiledAt, profileDate, rowCount, "
				+ "nullCount, nullRate, distinctCount, uniqueRate, method, sampleSize, minValue, maxValue, meanValue, minLength, maxLength, avgLength", "array"),
				FieldInfo.of("count", "Number of columns", "number"));
		}
		if ("sample".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("columns", "One item per text column: columnKey, assetKey, qualifiedName, name, sampledAt, sampleSize, values[], valuesText", "array"),
				FieldInfo.of("count", "Number of columns", "number"));
		}
		if ("lineage".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("edges", "One item per foreign key: edgeKey, fromAssetKey, fromColumnKey, toAssetKey, toColumnKey, transformationType, transformation, origin, confidence, seenAt", "array"),
				FieldInfo.of("count", "Number of edges", "number"));
		}
		if ("write".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("status", "created / updated", "string"), FieldInfo.of("key", "Primary key", "string"),
				FieldInfo.of("http", "HTTP status", "number"));
		}
		if ("search".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("rows", "Records (feed items)", "array"), FieldInfo.of("count", "Number of records", "number"),
				FieldInfo.of("rows[0]._key", "Primary key of a record", "string"));
		}
		return Arrays.asList(FieldInfo.of("(record fields)", "The fields of the remote record, as in EBX (Group/field)", "object"));
	}
}
