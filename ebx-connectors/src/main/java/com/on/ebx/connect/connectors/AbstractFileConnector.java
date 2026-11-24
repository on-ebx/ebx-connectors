package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.CatalogOperations;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/**
 * Files on a remote store (SFTP, S3, Azure Blob, GCS). Four operations:
 * <ul>
 * <li>{@code read} (SEARCH): inputs {@code path}, optional {@code format} (csv / json / ndjson / xml,
 * guessed from the extension), {@code separator}, {@code max} → {@code {rows:[...], count}},
 * a feed with {@code itemsPath = rows}.</li>
 * <li>{@code write} (WRITE): inputs {@code path}, {@code format}, {@code mode} ({@code overwrite}
 * default, or {@code append} for csv / jsonl: the existing content is read back and the rows
 * added). The outbound document is one row; {@code {rows:[...]}} writes several.</li>
 * <li>{@code list} (SEARCH): input {@code prefix} → {@code {results:[{path,size,modified}], count}}.</li>
 * <li>{@code catalog} (SEARCH): inputs {@code source}, optional {@code prefix}, {@code max} (files
 * listed, default 200), {@code columnsMax} (files whose columns are actually inferred via a real
 * read, default 50 — files beyond this count still appear with empty {@code columns}) →
 * {@code {source, scannedAt, count, tables:[{source, assetKey, qualifiedName, name, kind="FILE",
 * scannedAt, columns:[{name, columnKey, position, dataType, nativeType, nullable, primaryKey,
 * scannedAt}]}]}}, the same shape {@code JdbcConnector}'s own {@code catalog} produces
 * ({@code assetKey = source:path}, {@code columnKey = assetKey.column}). Column types come from
 * {@link Json#flatten} on the first row of a bounded {@code read}; a file whose column inference
 * fails does not abort the scan — that one table's {@code columns} stays empty and the failure is
 * reported in its own {@code columnsError}, mirroring {@code JdbcConnector}'s {@code rowCountError}.</li>
 * </ul>
 * Property {@code root} prefixes every path.
 */
abstract class AbstractFileConnector implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("read", "Rows of a csv / json / ndjson / xml file (inputs path, format, separator, max, max.bytes)", "path"),
		Operation.write("write", "Write the outbound document(s) to a file (inputs path, format, mode=overwrite|append)", "path"),
		Operation.search("list", "Files under a prefix (input prefix)", new String[0]),
		Operation.search("catalog", "Inventories the files under a prefix, with columns inferred from a bounded sample of each (inputs source, prefix, max, columnsMax)", "source"));

	private ConnectorConfig config;

	static final class Entry
	{
		final String path;
		final long size;
		final String modified;

		Entry(final String pPath, final long pSize, final String pModified)
		{
			this.path = pPath;
			this.size = pSize;
			this.modified = pModified;
		}
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
	}

	protected ConnectorConfig getConfig()
	{
		return this.config;
	}

	@Override
	public List<Operation> getOperations()
	{
		return AbstractFileConnector.OPERATIONS;
	}

	/** File content, or null when it does not exist. */
	protected abstract byte[] readBytes(String pPath) throws ConnectorException;

	protected abstract void writeBytes(String pPath, byte[] pContent, String pContentType) throws ConnectorException;

	protected abstract List<Entry> listEntries(String pPrefix, int pMax) throws ConnectorException;

	/**
	 * Verifies authentication and access with a bounded metadata listing. This deliberately does
	 * not read object contents and never invokes the write primitive.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.currentTimeMillis();
		final String root = this.fullPath("");
		this.listEntries(root, 1);
		return ConnectionTestResult.success("Connected; read-only metadata listing succeeded",
			System.currentTimeMillis() - started);
	}

	/**
	 * Provides a bounded metadata preview. Object content is intentionally not previewed here:
	 * the storage primitives currently return a complete byte array, so a transport-level byte
	 * ceiling cannot be guaranteed for an arbitrary remote object.
	 */
	@Override
	public PreviewResult preview(final ConnectorContext pContext, final PreviewRequest pRequest)
		throws ConnectorException
	{
		if (pRequest == null)
		{
			return PreviewResult.unsupported("A preview request is required");
		}
		final String operation = pRequest.getOperation().isEmpty()
			? pRequest.getObjectId() : pRequest.getOperation();
		if (!"list".equals(operation))
		{
			return PreviewResult.unsupported("Safe file content preview is unavailable; use the bounded list preview");
		}
		final String requestedPrefix = pRequest.getInputs().get("prefix");
		final String prefix = this.fullPath(requestedPrefix == null ? "" : requestedPrefix);
		final int fetchLimit = pRequest.getLimit() + 1;
		final List<Entry> entries = this.listEntries(prefix, fetchLimit);
		final List<JsonNode> records = new java.util.ArrayList<JsonNode>();
		if (entries != null)
		{
			for (final Entry entry : entries)
			{
				if (records.size() >= pRequest.getLimit()) break;
				final ObjectNode row = Json.object();
				row.put("path", entry.path);
				row.put("name", entry.path.contains("/") ? entry.path.substring(entry.path.lastIndexOf('/') + 1) : entry.path);
				row.put("size", entry.size);
				row.put("modified", entry.modified);
				records.add(row);
			}
		}
		return PreviewResult.of(records, entries != null && entries.size() > records.size());
	}

	protected String fullPath(final String pPath)
	{
		final String root = this.config.getProperty("root", "").trim();
		final String path = pPath == null ? "" : pPath.trim();
		if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0)
		{
			throw new IllegalArgumentException("Invalid connector file path");
		}
		for (final String segment : path.split("/"))
		{
			if ("..".equals(segment)) throw new IllegalArgumentException("Parent traversal is not allowed in connector file paths");
		}
		if (root.isEmpty())
		{
			return path;
		}
		final String boundary = root.endsWith("/") ? root : root + "/";
		if (path.equals(root) || path.startsWith(boundary)) return path;
		if (path.startsWith("/")) throw new IllegalArgumentException("Absolute path is outside the connector root");
		return boundary + path;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if ("list".equals(pOperation))
		{
			final String prefix = this.fullPath(pInputs.get("prefix") == null ? "" : pInputs.get("prefix"));
			final int max = pInputs.get("max") == null ? 1000 : Integer.parseInt(pInputs.get("max").trim());
			final ObjectNode out = Json.object();
			final ArrayNode results = out.putArray("results");
			for (final Entry entry : this.listEntries(prefix, max))
			{
				final ObjectNode row = results.addObject();
				row.put("path", entry.path);
				row.put("name", entry.path.contains("/") ? entry.path.substring(entry.path.lastIndexOf('/') + 1) : entry.path);
				row.put("size", entry.size);
				row.put("modified", entry.modified);
			}
			out.put("count", results.size());
			return results.size() == 0 ? null : out;
		}
		if ("catalog".equals(pOperation))
		{
			return this.catalog(pContext, pInputs);
		}
		final String path = this.fullPath(AbstractFileConnector.required(pInputs, "path"));
		final String format = FileFormats.formatOf(pInputs.get("format") == null ? this.config.getProperty("format", null) : pInputs.get("format"), path);
		final String separatorSetting = pInputs.get("separator") == null ? this.config.getProperty("separator", null) : pInputs.get("separator");
		char separator = FileFormats.separator(separatorSetting, path);
		if ("read".equals(pOperation))
		{
			final byte[] bytes = this.readBytes(path);
			if (bytes == null)
			{
				return null;
			}
			final int maxBytes = FileFormats.boundedBytes(pInputs.get("max.bytes") == null ? this.config.getProperty("max.bytes", null) : pInputs.get("max.bytes"));
			FileFormats.checkSize(bytes, maxBytes, "File " + path);
			separator = FileFormats.separator(separatorSetting, path, bytes); // detection (2026-10-17); a configured separator wins
			final int max = FileFormats.boundedRows(pInputs.get("max") == null ? Integer.parseInt(this.config.getProperty("max.rows", "10000")) : Integer.parseInt(pInputs.get("max").trim()));
			final ObjectNode parsed = FileFormats.parse(bytes, format, separator, max, maxBytes);
			parsed.put("path", path);
			return parsed.get("count").asInt() == 0 ? null : parsed;
		}
		if ("write".equals(pOperation))
		{
			final List<JsonNode> rows = FileFormats.rows(pPayload);
			if (rows.isEmpty())
			{
				throw new ConnectorException("Nothing to write to " + path);
			}
			if (rows.size() > FileFormats.ABSOLUTE_MAX_ROWS) throw new ConnectorException("Write exceeds the row limit of " + FileFormats.ABSOLUTE_MAX_ROWS);
			final boolean append = "append".equalsIgnoreCase(pInputs.get("mode") == null ? this.config.getProperty("write.mode", "overwrite") : pInputs.get("mode"));
			if (append && ("json".equals(format) || "xml".equals(format))) throw new ConnectorException("Append is supported only for CSV and NDJSON documents");
			byte[] encoded;
			if (append && !"json".equals(format))
			{
				final byte[] existing = this.readBytes(path);
				final int maxBytes = FileFormats.boundedBytes(pInputs.get("max.bytes") == null ? this.config.getProperty("max.bytes", null) : pInputs.get("max.bytes"));
				FileFormats.checkSize(existing, maxBytes, "Existing file " + path);
				final String previous = existing == null ? "" : new String(existing, StandardCharsets.UTF_8);
				if ("csv".equals(format) && existing != null) separator = FileFormats.separator(separatorSetting, path, existing); // keep the file's own separator (2026-10-17)
				final boolean header = previous.trim().isEmpty();
				final String content = (previous.isEmpty() || previous.endsWith("\n") ? previous : previous + "\r\n") + FileFormats.serialise(rows, format, separator, header);
				encoded = content.getBytes(StandardCharsets.UTF_8);
			}
			else
			{
				encoded = FileFormats.serialiseBytes(rows, format, separator, true,
					FileFormats.boundedBytes(pInputs.get("max.bytes") == null ? this.config.getProperty("max.bytes", null) : pInputs.get("max.bytes")));
			}
			FileFormats.checkSize(encoded, FileFormats.boundedBytes(pInputs.get("max.bytes") == null ? this.config.getProperty("max.bytes", null) : pInputs.get("max.bytes")), "Generated file " + path);
			this.writeBytes(path, encoded, FileFormats.contentType(format));
			final ObjectNode out = Json.object();
			out.put("path", path);
			out.put("rows", rows.size());
			out.put("bytes", encoded.length);
			out.put("mode", append ? "append" : "overwrite");
			return out;
		}
		throw new ConnectorException(this.getType() + ": unknown operation '" + pOperation + "'");
	}

	/**
	 * Volume lot 7b: real streaming for the {@code read} operation on a CSV/TSV or NDJSON file - rows
	 * reach {@code pSink} one at a time via {@link FileFormats#streamCsv}/{@link FileFormats#streamNdjson}
	 * (a real {@link java.io.Reader} over the file's bytes, never a {@code byte[] -> String -> one big
	 * tree} conversion), instead of {@link FileFormats#parse} building one {@code {rows:[...]}} document
	 * holding every row before the caller ever sees the first one. Every other operation
	 * ({@code write}/{@code list}/{@code catalog}), and a {@code read} of {@code json}/{@code xml} (which
	 * "may stay materialised" per the task - a JSON array or an XML document has no natural streaming
	 * shape the way a line-oriented text format does) or of a format a registered
	 * {@link DocumentCodecProvider} claims (excel/parquet always do; {@link FileFormats#hasCustomProvider}
	 * covers the in-principle case of a third-party provider claiming csv/ndjson too), falls back to the
	 * default (execute + replay) implementation, unchanged.
	 * <p>
	 * {@code itemsPath} is ignored, like the other native streaming overrides (JDBC, Snowflake) - each
	 * row IS an item, the same {@code itemsPath="rows"} convention {@code read}'s own document already
	 * used. The 100 MB ({@link FileFormats#ABSOLUTE_MAX_BYTES}) and 100,000-row
	 * ({@link FileFormats#ABSOLUTE_MAX_ROWS}) absolute ceilings stay in force exactly as for the
	 * non-streamed path - {@code max.bytes}/{@code max}/{@code max.rows} settings, checked BEFORE a
	 * single row is streamed via the same {@link FileFormats#checkSize}/{@link FileFormats#boundedRows}
	 * calls {@code execute}'s own {@code read} branch uses. The underlying {@link #readBytes} storage
	 * primitive still returns one complete {@code byte[]} per file (a true network-level streaming
	 * download would need a per-backend change - SFTP/S3/Azure Blob/GCS each with their own SDK - out of
	 * this lot's scope); what changes here is that the PARSED rows are never all held as {@link JsonNode}
	 * objects at once, which is the dominant memory cost for a file with many columns or many rows.
	 */
	@Override
	public Connector.RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		if (!"read".equals(pOperation))
		{
			return Connector.super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		final String path = this.fullPath(AbstractFileConnector.required(pInputs, "path"));
		final String format = FileFormats.formatOf(pInputs.get("format") == null ? this.config.getProperty("format", null) : pInputs.get("format"), path);
		if (!("csv".equals(format) || "ndjson".equals(format)) || FileFormats.hasCustomProvider(format))
		{
			return Connector.super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		final byte[] bytes = this.readBytes(path);
		if (bytes == null)
		{
			return new Connector.RowStreamResult(0, false, -1, null);
		}
		final int maxBytes = FileFormats.boundedBytes(pInputs.get("max.bytes") == null ? this.config.getProperty("max.bytes", null) : pInputs.get("max.bytes"));
		FileFormats.checkSize(bytes, maxBytes, "File " + path);
		// pMax is the caller's real ceiling (ConnectorService.FeedSink/a direct readRows caller) - unlike
		// execute()'s "read" branch above, which has no such parameter and falls back to the "max" input
		// or the connector's own max.rows property; readRows always honours pMax directly, exactly like
		// JdbcConnector/SnowflakeConnector's own native overrides do.
		final int max = FileFormats.boundedRows(pMax);
		if ("csv".equals(format))
		{
			final char separator = FileFormats.separator(pInputs.get("separator") == null ? this.config.getProperty("separator", null) : pInputs.get("separator"), path, bytes);
			return FileFormats.streamCsv(bytes, separator, max, pSink);
		}
		return FileFormats.streamNdjson(bytes, max, pSink);
	}

	/**
	 * {@code catalog}: {@code {source, scannedAt, count, tables:[{source, assetKey, qualifiedName,
	 * name, kind="FILE", scannedAt, columns:[{name, columnKey, position, dataType, nativeType,
	 * nullable, primaryKey, scannedAt}]}]}}. {@code assetKey = source:path}, {@code columnKey =
	 * assetKey.column} — the same convention as {@code JdbcConnector.catalog}. Columns are inferred,
	 * via a real bounded {@code read}, only for the first {@code columnsMax} listed files; the rest
	 * still appear in {@code tables} with an empty {@code columns} array, not dropped.
	 */
	private JsonNode catalog(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = AbstractFileConnector.required(pInputs, "source");
		final String prefix = this.fullPath(pInputs.get("prefix") == null ? "" : pInputs.get("prefix"));
		final int max = pInputs.get("max") == null ? 200 : Integer.parseInt(pInputs.get("max").trim());
		final int columnsMax = pInputs.get("columnsMax") == null ? 50 : Integer.parseInt(pInputs.get("columnsMax").trim());
		final String scannedAt = CatalogOperations.timestamp();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode tables = out.putArray("tables");
		int index = 0;
		for (final Entry entry : this.listEntries(prefix, max))
		{
			final ObjectNode t = tables.addObject();
			final String assetKey = source + ":" + entry.path;
			t.put("source", source);
			t.put("assetKey", assetKey);
			t.put("qualifiedName", entry.path);
			t.put("name", entry.path);
			t.put("kind", "FILE");
			t.put("scannedAt", scannedAt);
			// (2026-10-17) always sent, false unless inferColumns re-read every column: the catalog's scan flow writes it ALWAYS, so a
			// file that was complete once and no longer is (error, beyond columnsMax, field cap) loses the flag and nothing is marked gone
			t.put("columnsComplete", false);
			final ArrayNode columns = t.putArray("columns");
			if (index < columnsMax)
			{
				this.inferColumns(pContext, entry.path, assetKey, scannedAt, columns, t);
			}
			index++;
			// Read recipe + deduced scan (2026-09-24): "read" over this one file, itemsPath "rows" (what "read" returns).
			final Map<String, String> recipeInputs = java.util.Collections.singletonMap("path", entry.path);
			t.put("readRecipe", CatalogOperations.readRecipe(this.getConfig().getId(), "read", recipeInputs, "rows"));
			t.put("scanOrigin", "OFFICIAL");
			t.put("scanState", "CONFIRMED");
		}
		out.put("count", tables.size());
		return out;
	}

	/**
	 * Reads one row of the file at {@code pPath} and flattens it into {@code pColumns}. A read/parse
	 * failure on this one file (malformed content, an object that is unreadable for some other
	 * reason under a broad prefix) does not abort the surrounding scan: {@code columns} is left
	 * empty and the failure is recorded on {@code pTable} as {@code columnsError}, the same way
	 * {@code JdbcConnector.catalog} records a failed row count as {@code rowCountError} on its own
	 * table entry rather than failing the whole call.
	 */
	private void inferColumns(final ConnectorContext pContext, final String pPath, final String pAssetKey,
		final String pScannedAt, final ArrayNode pColumns, final ObjectNode pTable) throws ConnectorException
	{
		final Map<String, String> inputs = new java.util.LinkedHashMap<String, String>();
		inputs.put("path", pPath);
		inputs.put("max", "1");
		final JsonNode sample;
		try
		{
			sample = this.execute(pContext, "read", inputs, null);
		}
		catch (final ConnectorException ex)
		{
			pTable.put("columnsError", ex.getMessage());
			return;
		}
		final JsonNode row = Json.at(sample, "rows[0]");
		if (row == null)
		{
			return;
		}
		int position = 0;
		final List<String[]> flats = Json.flatten(row, 200);
		// (2026-10-17) columnsComplete = this scan really re-read the file and returned ALL its columns (no error, a row was
		// read, the 200-field cap was not hit): the only case where a consumer may treat a column absent from "columns" as gone.
		// An asset beyond columnsMax, with a columnsError or an empty file carries false (set by catalog()).
		if (flats.size() < 200)
		{
			pTable.put("columnsComplete", true);
		}
		for (final String[] flat : flats)
		{
			position++;
			final ObjectNode c = pColumns.addObject();
			c.put("name", flat[0]);
			c.put("columnKey", pAssetKey + "." + flat[0]);
			c.put("position", position);
			c.put("dataType", AbstractFileConnector.catalogDataType(flat[2]));
			c.put("nativeType", flat[2]);
			c.put("nullable", true);
			c.put("primaryKey", false);
			c.put("scannedAt", pScannedAt);
		}
	}

	/** Maps {@link Json#flatten}'s type vocabulary to the catalog's coarse, upper-case taxonomy. */
	static String catalogDataType(final String pFlattenType)
	{
		if ("string".equals(pFlattenType)) return "STRING";
		if ("number".equals(pFlattenType)) return "NUMBER";
		if ("boolean".equals(pFlattenType)) return "BOOLEAN";
		return "STRING";
	}

	static String required(final Map<String, String> pInputs, final String pName) throws ConnectorException
	{
		final String value = pInputs == null ? null : pInputs.get(pName);
		if (value == null || value.trim().isEmpty())
		{
			throw new ConnectorException("Input '" + pName + "' is required");
		}
		return value.trim();
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new java.util.ArrayList<FieldInfo>();
		if ("list".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("results[0].path", "Path", "string"), FieldInfo.of("results[0].name", "File name", "string"),
				FieldInfo.of("results[0].size", "Size (bytes)", "number"), FieldInfo.of("results[0].modified", "Last modified", "datetime"));
		}
		final String path = pInputs == null ? null : pInputs.get("path");
		if (path == null || path.trim().isEmpty())
		{
			return fields;
		}
		final Map<String, String> inputs = new java.util.LinkedHashMap<String, String>(pInputs);
		inputs.put("max", "1");
		final JsonNode sample = this.execute(pContext, "read", inputs, null);
		final JsonNode row = Json.at(sample, "rows[0]");
		if (row != null)
		{
			for (final String[] flat : Json.flatten(row, 200))
			{
				fields.add(new FieldInfo("rows[0]." + flat[0], flat[0], flat[2], flat[1])); // (path, label, type, sample): was swapped, fixed (2026-10-17)
			}
		}
		return fields;
	}
}
