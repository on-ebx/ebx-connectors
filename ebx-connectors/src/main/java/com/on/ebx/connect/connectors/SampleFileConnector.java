package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.CatalogOperations;
import com.on.ebx.connect.core.Json;

import com.on.ebx.connect.core.SampleDataUrl;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/**
 * Declares a data source in the catalog from a manually uploaded CSV or JSON sample, before any
 * real connection to that source exists: useful at the very start of an MDM project when only a
 * sample export or a document describing the source's structure is available. To the rest of the
 * platform (catalog scan, mapping designer, matching) it behaves exactly like any other read-only
 * file connector; only {@link #readBytes}, {@link #listEntries} and {@link #testConnection} are
 * connector-specific, everything else (field inference, preview, discovery, and the {@code catalog}
 * operation) comes from {@link AbstractFileConnector} for free. There is exactly one virtual object
 * (the uploaded sample), so {@code path} never selects between several: its content is always the
 * stored sample, and a {@code catalog} scan therefore always inventories exactly one table.
 * The stored {@code sampleData} data URL may also carry the uploaded file's ORIGINAL name (see
 * {@link SampleDataUrl}), set by {@code UploadedSampleFileWidgetFactory}'s upload widget. When it
 * does, {@link #listEntries}, {@link #readBytes} (via its {@code path} input) and the {@code catalog}
 * operation all name the single entry/asset after that file, extension stripped (a sample stored as
 * {@code Personnes_SAP_Sample.json} names its asset {@code Personnes_SAP_Sample}), instead of the
 * generic {@code sample.<ext>} used when no name was supplied — today's behaviour, unchanged. The
 * FORMAT (csv/tsv/json/ndjson) is always decided by the stored MIME, never by that file name's own
 * extension: {@link #configure} pins an explicit {@code format} (and, for a tab-separated sample, a
 * default {@code separator}) connector property from the MIME, so the extension-based fallback in
 * {@link AbstractFileConnector#execute} is never actually exercised. sample-file-name lot (2026-09-23).
 */
public final class SampleFileConnector extends AbstractFileConnector
{
	// "path" is required by AbstractFileConnector#execute for every non-list operation. Its content is
	// always the stored sample regardless of value; format is decided by the stored MIME (see #configure
	// and the class Javadoc), not by this path's extension, so a caller normally passes what list()
	// returned (e.g. "sample.csv", or the uploaded file's own name minus extension).
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("read", "Rows of the uploaded csv / json / ndjson sample (inputs path, format, separator, max, max.bytes)", "path"),
		Operation.search("list", "The single uploaded sample, if any"),
		Operation.search("catalog", "Inventories the single uploaded sample, with columns inferred from it (inputs source, prefix, max, columnsMax)", "source"),
		Operation.search("sample", "Distinct sampled values of the text columns of the uploaded sample, for classification "
			+ "(inputs source, tables or *, limit = rows read, default 50)", "source"));

	@Override public String getType() { return "sample-file"; }
	@Override public String getLabel() { return "Sample file (CSV / JSON)"; }
	@Override public List<Operation> getOperations() { return SampleFileConnector.OPERATIONS; }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		// sample-file-name lot (2026-09-23): once the display name below can be the uploaded file's own
		// name (extension stripped), that name's extension can no longer be trusted to drive format
		// detection (it may disagree with the MIME, or be gone entirely). Pin the format from the MIME
		// as an explicit connector property, so AbstractFileConnector#execute's extension-based fallback
		// is never reached; only set a default tab separator when the caller has not already configured
		// one explicitly (the "separator" property remains user-overridable).
		final SampleDataUrl parsed = pConfig == null ? null : SampleDataUrl.parse(pConfig.getSampleData());
		if (parsed != null)
		{
			final String format = SampleFileConnector.formatOfMime(parsed.mime());
			if (format != null)
			{
				pConfig.setProperty("format", format);
			}
			if (SampleFileConnector.isTabSeparated(parsed.mime()) && pConfig.getProperty("separator", null) == null)
			{
				pConfig.setProperty("separator", "tab");
			}
		}
		super.configure(pConfig);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "CLOUD_STORAGE")
			.description("Serves rows parsed from a manually uploaded CSV or JSON sample, in place of a real connection. "
				+ "Read-only: declare the source's shape early, then replace this connector with a real one once a live "
				+ "connection exists.")
			.authentication(AuthenticationMethod.NONE)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.PREVIEW_RECORDS)
			.configurationProperty(ConfigurationPropertyDefinition.builder("separator", ConfigurationPropertyType.STRING)
				.required(false).build());
		for (final Operation operation : getOperations())
		{
			builder.direction(ConnectorDirection.INBOUND).operation(new ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), Collections.singleton(ConnectorDirection.INBOUND),
				Collections.<ConnectorCapability>emptySet()));
		}
		return builder.build();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if ("sample".equals(pOperation))
		{
			return this.sample(pContext, pInputs);
		}
		return super.execute(pContext, pOperation, pInputs, pPayload);
	}

	/**
	 * {@code sample}, the same contract as {@code JdbcConnector.sample}: {@code {source, sampledAt, count, columns:[{source,
	 * assetKey, qualifiedName, columnKey, name, dataType, sampledAt, sampleSize, values:[...], valuesText}]}} - the first
	 * {@code limit} rows (default 50, bounded 1..500) of the single uploaded sample, the distinct non-blank values of its TEXT
	 * columns (each cut at 200 characters, in order of first appearance), for pattern-based classification; the consumer is
	 * expected to discard the values once classified. The columns are the ones the {@code catalog} operation infers (the flattened
	 * first row, a column is text unless its first value is a number or a boolean), and {@code assetKey}/{@code qualifiedName}
	 * are the strings it writes for the same table, so the catalogue can join on {@code columnKey}. {@code tables} is {@code *}
	 * (or empty) or a comma-separated list; the one table is selected by its own name, any other name gives no column (null).
	 */
	private JsonNode sample(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = AbstractFileConnector.required(pInputs, "source");
		final String requested = pInputs.get("tables") == null || pInputs.get("tables").trim().isEmpty() ? "*" : pInputs.get("tables").trim();
		int limit = 50;
		try
		{
			limit = Math.max(1, Math.min(500, Integer.parseInt(pInputs.get("limit") == null ? "50" : pInputs.get("limit").trim())));
		}
		catch (final RuntimeException ex)
		{
			limit = 50;
		}
		final List<Entry> entries = this.listEntries("", 1);
		if (entries.isEmpty())
		{
			return null;
		}
		final String path = entries.get(0).path;
		boolean selected = "*".equals(requested);
		for (final String name : requested.split(","))
		{
			if (name.trim().equals(path))
			{
				selected = true;
			}
		}
		if (!selected)
		{
			return null;
		}
		final Map<String, String> readInputs = new LinkedHashMap<String, String>();
		readInputs.put("path", path);
		readInputs.put("max", Integer.toString(limit));
		final JsonNode read = this.execute(pContext, "read", readInputs, null);
		final JsonNode rows = read == null ? null : read.get("rows");
		if (rows == null || !rows.isArray() || rows.size() == 0)
		{
			return null;
		}
		// the columns the catalog operation infers: the flattened first row, text unless that first value is a number or a boolean
		final Map<String, Set<String>> values = new LinkedHashMap<String, Set<String>>();
		for (final String[] flat : Json.flatten(rows.get(0), 200))
		{
			if ("STRING".equals(AbstractFileConnector.catalogDataType(flat[2])) && !values.containsKey(flat[0]))
			{
				values.put(flat[0], new LinkedHashSet<String>());
			}
		}
		int rowsRead = 0;
		for (final JsonNode row : rows)
		{
			if (rowsRead >= limit)
			{
				break;
			}
			rowsRead++;
			for (final String[] flat : Json.flatten(row, 200))
			{
				final Set<String> distinct = values.get(flat[0]);
				if (distinct != null && !"null".equals(flat[2]) && !flat[1].trim().isEmpty())
				{
					distinct.add(flat[1].length() > 200 ? flat[1].substring(0, 200) : flat[1]);
				}
			}
		}
		final String sampledAt = CatalogOperations.timestamp();
		final String assetKey = source + ":" + path;
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		for (final Map.Entry<String, Set<String>> column : values.entrySet())
		{
			final ObjectNode c = columns.addObject();
			c.put("source", source);
			c.put("assetKey", assetKey);
			c.put("qualifiedName", path);
			c.put("columnKey", assetKey + "." + column.getKey());
			c.put("name", column.getKey());
			c.put("dataType", "STRING");
			c.put("sampledAt", sampledAt);
			c.put("sampleSize", rowsRead);
			final ArrayNode array = c.putArray("values");
			final StringBuilder text = new StringBuilder();
			for (final String v : column.getValue())
			{
				array.add(v);
				text.append(text.length() > 0 ? "\n" : "").append(v.replace('\n', ' ').replace('\r', ' '));
			}
			c.put("valuesText", text.toString());
		}
		out.put("count", columns.size());
		return columns.size() == 0 ? null : out;
	}

	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final byte[] sample = decodedSample();
		if (sample == null)
		{
			return ConnectionTestResult.failure("No sample file has been uploaded yet: upload one on the connector's Connection tab", 0L);
		}
		return ConnectionTestResult.success("Sample file present: " + syntheticPath() + " (" + sample.length + " bytes)", 0L);
	}

	@Override
	protected byte[] readBytes(final String pPath) throws ConnectorException
	{
		return decodedSample();
	}

	@Override
	protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
	{
		throw new ConnectorException("The sample file connector is read-only: it serves a manually uploaded sample, not a "
			+ "live connection. Replace this connector with a real one once a connection to the source exists.");
	}

	@Override
	protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
	{
		final byte[] sample = decodedSample();
		if (sample == null)
		{
			return Collections.<Entry> emptyList();
		}
		return Collections.singletonList(new Entry(syntheticPath(), sample.length, null));
	}

	/** The uploaded sample's bytes, or null when none has been uploaded. */
	private byte[] decodedSample() throws ConnectorException
	{
		final String dataUrl = getConfig() == null ? null : getConfig().getSampleData();
		if (dataUrl == null || dataUrl.trim().isEmpty())
		{
			return null;
		}
		final SampleDataUrl parsed = SampleDataUrl.parse(dataUrl);
		if (parsed == null)
		{
			throw new ConnectorException("Stored sample data is not a base64 data URL");
		}
		try
		{
			return Base64.getDecoder().decode(parsed.payload().getBytes(StandardCharsets.US_ASCII));
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException("Stored sample data is not valid base64", ex);
		}
	}

	/**
	 * The uploaded sample's display name — used as the single {@link #listEntries} entry, hence also
	 * as the {@code catalog} operation's {@code assetKey}/{@code qualifiedName}/{@code name} (see
	 * {@link AbstractFileConnector#catalog}) — with format detection pinned by MIME in
	 * {@link #configure} rather than left to this name's own extension. The uploaded file's own name
	 * when the stored data URL carries one, extension stripped (e.g. {@code Personnes_SAP_Sample.json}
	 * -> {@code Personnes_SAP_Sample}); else the MIME-based {@code sample.<ext>} default, unchanged.
	 */
	private String syntheticPath() throws ConnectorException
	{
		final String dataUrl = getConfig() == null ? null : getConfig().getSampleData();
		if (dataUrl == null)
		{
			throw new ConnectorException("No sample file has been uploaded yet");
		}
		final SampleDataUrl parsed = SampleDataUrl.parse(dataUrl);
		if (parsed == null)
		{
			throw new ConnectorException("Stored sample data is not a base64 data URL");
		}
		if (parsed.name() != null)
		{
			return SampleFileConnector.withoutExtension(parsed.name());
		}
		final String extension = SampleFileConnector.extensionOfMime(parsed.mime());
		if (extension == null)
		{
			throw new ConnectorException("Stored sample data has an unrecognized MIME prefix");
		}
		return "sample." + extension;
	}

	/** Strips a trailing {@code .<extension>}, if any; a name with no dot (or a leading dot only) is returned as-is. */
	private static String withoutExtension(final String pName)
	{
		final int dot = pName.lastIndexOf('.');
		return dot > 0 ? pName.substring(0, dot) : pName;
	}

	/** The default {@code sample.<ext>} extension for a recognized sample MIME, or null when unrecognized. */
	private static String extensionOfMime(final String pMime)
	{
		if ("text/csv".equals(pMime)) { return "csv"; }
		if ("text/tab-separated-values".equals(pMime)) { return "tsv"; }
		if ("application/x-ndjson".equals(pMime)) { return "ndjson"; }
		if ("application/json".equals(pMime)) { return "json"; }
		return null;
	}

	/** The {@code FileFormats} format name for a recognized sample MIME (tab-separated is plain csv with a tab separator), or null when unrecognized. */
	private static String formatOfMime(final String pMime)
	{
		if ("text/csv".equals(pMime) || "text/tab-separated-values".equals(pMime)) { return "csv"; }
		if ("application/x-ndjson".equals(pMime)) { return "ndjson"; }
		if ("application/json".equals(pMime)) { return "json"; }
		return null;
	}

	private static boolean isTabSeparated(final String pMime)
	{
		return "text/tab-separated-values".equals(pMime);
	}
}
