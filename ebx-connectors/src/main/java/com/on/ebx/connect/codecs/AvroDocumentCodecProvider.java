package com.on.ebx.connect.codecs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DocumentCodecProvider;

/** Bounded Avro Object Container File codec for flat JSON records and primitive nullable fields. */
public final class AvroDocumentCodecProvider implements DocumentCodecProvider
{
	public Set<String> getFormats(){return Collections.singleton("avro");}
	public String getContentType(final String f){return "application/avro";}
	public ObjectNode parse(final byte[] content,final String format,final int maxRows,final int maxBytes)throws ConnectorException
	{
		if (content == null || content.length > maxBytes) throw new ConnectorException("Avro input exceeds max.bytes");
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		try
		{
			final DataFileStream<GenericRecord> stream = new DataFileStream<GenericRecord>(new ByteArrayInputStream(content), new GenericDatumReader<GenericRecord>());
			while (stream.hasNext() && rows.size() < maxRows)
			{
				final GenericRecord record = stream.next();
				final ObjectNode row = rows.addObject();
				for (final Schema.Field field:record.getSchema().getFields())
				{
					final Object value = record.get(field.name());
					if (value == null) row.putNull(field.name());
					else if (value instanceof Boolean) row.put(field.name(), ((Boolean)value).booleanValue());
					else if (value instanceof Integer) row.put(field.name(), ((Integer)value).intValue());
					else if (value instanceof Long) row.put(field.name(), ((Long)value).longValue());
					else if (value instanceof Float || value instanceof Double) row.put(field.name(), ((Number)value).doubleValue());
					else if (value instanceof CharSequence) row.put(field.name(), value.toString());
					else throw new ConnectorException("Avro field '"+field.name()+"' uses an unsupported complex type");
				}
			}
			stream.close();
			return out;
		}
		catch (final ConnectorException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Invalid or unsupported Avro object container", e);
		}
	}
	public byte[] serialise(final List<JsonNode> rows,final String format,final int maxBytes)throws ConnectorException
	{
		if (rows.size() > 10000) throw new ConnectorException("Avro row count exceeds 10000");
		final LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
		for (final JsonNode row:rows)
		{
			if (!row.isObject()) throw new ConnectorException("Avro rows must be JSON objects");
			final java.util.Iterator<Map.Entry<String, JsonNode>> it = row.fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> e = it.next();
				if (!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ConnectorException("Avro field name is invalid: "+e.getKey());
				final String type = type(e.getValue());
				final String previous = fields.get(e.getKey());
				fields.put(e.getKey(), previous == null ? type : previous.equals(type) ? previous : "string");
			}
		}
		final StringBuilder schemaJson = new StringBuilder("{\"type\":\"record\",\"name\":\"EbxRecord\",\"namespace\":\"com.on.ebx.connect.avro\",\"fields\":[");
		int n = 0;
		for (final Map.Entry<String, String> f:fields.entrySet())
		{
			if (n++>0) schemaJson.append(',');
			schemaJson.append("{\"name\":").append(quote(f.getKey())).append(",\"type\":[\"null\",").append(quote(f.getValue())).append("],\"default\":null}");
		}
		schemaJson.append("]}");
		try
		{
			final Schema schema = new Schema.Parser().parse(schemaJson.toString());
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final DataFileWriter<GenericRecord> writer = new DataFileWriter<GenericRecord>(new GenericDatumWriter<GenericRecord>(schema));
			writer.setCodec(CodecFactory.nullCodec());
			writer.create(schema, bytes);
			for (final JsonNode row:rows)
			{
				final GenericRecord record = new GenericData.Record(schema);
				for (final Schema.Field f:schema.getFields()) record.put(f.name(), value(row.get(f.name()), fields.get(f.name())));
				writer.append(record);
				if (bytes.size() > maxBytes) throw new ConnectorException("Generated Avro file exceeds max.bytes");
			}
			writer.close();
			if (bytes.size() > maxBytes) throw new ConnectorException("Generated Avro file exceeds max.bytes");
			return bytes.toByteArray();
		}
		catch (final ConnectorException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Unable to generate Avro object container", e);
		}
	}
	private static String type(final JsonNode v)throws ConnectorException
	{
		if (v == null || v.isNull()) return "string";
		if (v.isBoolean()) return "boolean";
		if (v.isIntegralNumber()) return "long";
		if (v.isFloatingPointNumber()) return "double";
		if (v.isTextual()) return "string";
		throw new ConnectorException("Avro codec supports flat scalar records only");
	}
	private static Object value(final JsonNode v, final String type)
	{
		if (v == null || v.isNull()) return null;
		if ("boolean".equals(type)) return Boolean.valueOf(v.asBoolean());
		if ("long".equals(type)) return Long.valueOf(v.asLong());
		if ("double".equals(type)) return Double.valueOf(v.asDouble());
		return v.isTextual() ? v.asText() : v.toString();
	}
	private static String quote(final String value){return Json.mapper().getNodeFactory().textNode(value).toString();}
}
