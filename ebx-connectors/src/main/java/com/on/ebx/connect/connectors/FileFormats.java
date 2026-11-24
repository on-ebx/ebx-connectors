package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Csv;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Xml;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DocumentCodecProvider;

/**
 * Text file formats shared by the file and object-store connectors: {@code csv} (header line),
 * {@code json} (an array, or an object whose {@code rows}/{@code items}/{@code data} is the array, or whose only
 * array of objects is the envelope - see {@link #envelopeArray} -, or a single object), {@code jsonl} (one JSON object per line). Parsing yields
 * {@code {rows:[...], count}}; serialising takes either one document or {@code {rows:[...]}}.
 */
final class FileFormats
{
	static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
	static final int ABSOLUTE_MAX_BYTES = 100 * 1024 * 1024;
	static final int ABSOLUTE_MAX_ROWS = 100000;
	private FileFormats()
	{
	}

	static String formatOf(final String pFormat, final String pPath)
	{
		if (pFormat != null && !pFormat.trim().isEmpty())
		{
			return pFormat.trim().toLowerCase();
		}
		final String lower = pPath == null ? "" : pPath.toLowerCase();
		return lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv") ? "csv"
			: lower.endsWith(".jsonl") || lower.endsWith(".ndjson") ? "ndjson"
			: lower.endsWith(".xml") ? "xml"
			: lower.endsWith(".xlsx") || lower.endsWith(".xls") ? "excel"
			: lower.endsWith(".parquet") ? "parquet" : "json";
	}

	static char separator(final String pSeparator, final String pPath)
	{
		if (pSeparator != null && !pSeparator.isEmpty())
		{
			return "tab".equalsIgnoreCase(pSeparator) || "\\t".equals(pSeparator) ? '\t' : pSeparator.charAt(0);
		}
		return pPath != null && pPath.toLowerCase().endsWith(".tsv") ? '\t' : ';';
	}

	private static final java.util.Set<String> ENVELOPE_METADATA = new java.util.HashSet<String>(java.util.Arrays.asList(
		"total", "totalcount", "count", "page", "pagesize", "pages", "offset", "limit", "next", "previous", "status", "success",
		"message", "hasmore", "cursor", "nexttoken", "size", "version"));

	/**
	 * The array of a JSON object that is an envelope with a non-standard key (2026-10-17): {@code {"customers":[{...},...]}}
	 * is 160 rows, not one document with a column {@code customers[0].customerId}. Deterministic: the object must hold exactly
	 * ONE property whose value is a non-empty array of objects, and every other property must be a scalar whose name is
	 * pagination/status metadata (total, count, page, status...). A genuine single document (several properties, or an array
	 * next to business fields) returns null and stays one row. The well-known keys (rows, items, data...) are handled by the caller.
	 */
	static JsonNode envelopeArray(final JsonNode pObject)
	{
		if (pObject == null || !pObject.isObject())
		{
			return null;
		}
		JsonNode found = null;
		final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = pObject.fields();
		while (fields.hasNext())
		{
			final java.util.Map.Entry<String, JsonNode> field = fields.next();
			final JsonNode value = field.getValue();
			if (value.isArray() && value.size() > 0 && allObjects(value))
			{
				if (found != null)
				{
					return null;
				}
				found = value;
			}
			else if (!(value.isValueNode() && ENVELOPE_METADATA.contains(field.getKey().toLowerCase(java.util.Locale.ROOT))))
			{
				return null;
			}
		}
		return found;
	}

	private static boolean allObjects(final JsonNode pArray)
	{
		for (final JsonNode item : pArray)
		{
			if (!item.isObject())
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Separator with detection (2026-10-17): a configured/passed separator still wins; otherwise it is detected from the first
	 * lines of {@code pContent} (see {@link #detectSeparator}); when nothing can be detected, the historical default of
	 * {@link #separator(String, String)} applies.
	 */
	static char separator(final String pSeparator, final String pPath, final byte[] pContent)
	{
		if (pSeparator != null && !pSeparator.isEmpty())
		{
			return separator(pSeparator, pPath);
		}
		final Character detected = detectSeparator(pContent);
		return detected != null ? detected.charValue() : separator(pSeparator, pPath);
	}

	/**
	 * Detects the CSV separator among ',', ';', tab and '|' from the header line and the next few lines (at most 6 records, first
	 * 64 KB): occurrences outside double quotes are counted per record; a candidate qualifies when every sampled record has the
	 * same count above zero (consistent column count > 1). ';' wins ties (the module default), then the highest count, then the
	 * order ';' ',' tab '|'. When no candidate is consistent, the one with the most occurrences in the header is taken; null when
	 * the header holds none (a single-column file).
	 */
	static Character detectSeparator(final byte[] pContent)
	{
		if (pContent == null || pContent.length == 0)
		{
			return null;
		}
		final int length = Math.min(pContent.length, 65536);
		final String text = new String(pContent, 0, length, java.nio.charset.StandardCharsets.UTF_8);
		final char[] candidates = { ';', ',', '\t', '|' };
		final int[][] counts = new int[candidates.length][8];
		int records = 0;
		int[] current = new int[candidates.length];
		boolean quoted = false;
		boolean any = false;
		final String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
		for (int i = 0; i < body.length() && records < 6; i++)
		{
			final char c = body.charAt(i);
			if (c == '"')
			{
				quoted = !quoted;
				any = true;
			}
			else if (!quoted && (c == '\n' || c == '\r'))
			{
				if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n')
				{
					i++;
				}
				if (any)
				{
					for (int k = 0; k < candidates.length; k++)
					{
						counts[k][records] = current[k];
					}
					records++;
				}
				current = new int[candidates.length];
				any = false;
			}
			else
			{
				any = true;
				if (!quoted)
				{
					for (int k = 0; k < candidates.length; k++)
					{
						if (c == candidates[k])
						{
							current[k]++;
						}
					}
				}
			}
		}
		if (any && records < 6 && !quoted)
		{
			for (int k = 0; k < candidates.length; k++)
			{
				counts[k][records] = current[k];
			}
			records++;
		}
		if (records == 0)
		{
			return null;
		}
		int best = -1;
		for (int k = 0; k < candidates.length; k++)
		{
			final int header = counts[k][0];
			boolean consistent = header > 0;
			for (int r = 1; r < records && consistent; r++)
			{
				consistent = counts[k][r] == header;
			}
			if (consistent && (best < 0 || header > counts[best][0]))
			{
				best = k;
			}
		}
		if (best >= 0)
		{
			return Character.valueOf(candidates[best]);
		}
		for (int k = 0; k < candidates.length; k++)
		{
			if (counts[k][0] > 0 && (best < 0 || counts[k][0] > counts[best][0]))
			{
				best = k;
			}
		}
		return best < 0 ? null : Character.valueOf(candidates[best]);
	}

	static ObjectNode parse(final String pText, final String pFormat, final char pSeparator, final int pMax) throws ConnectorException
	{
		final int max = boundedRows(pMax);
		if(pText!=null&&(pText.length()>ABSOLUTE_MAX_BYTES||pText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>ABSOLUTE_MAX_BYTES))throw new ConnectorException("Input document exceeds max.bytes safety limit");
		final String text = pText == null ? "" : pText.startsWith("﻿") ? pText.substring(1) : pText;
		ensureSupported(pFormat);
		if ("csv".equals(pFormat))
		{
			try
			{
				return Csv.parse(text, pSeparator, max);
			}
			catch (final IllegalArgumentException ex)
			{
				throw new ConnectorException("Invalid csv content: " + ex.getMessage(), ex);
			}
		}
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		final int[] remainingTokens={SafeTextDocuments.MAX_NODES};
		// Real total of data records/elements/rows in the document, counted for free while it is already
		// walked end to end; "truncated" is derived from it once, at the bottom, instead of per-branch.
		int available=0;
		try
		{
			if ("jsonl".equals(pFormat) || "ndjson".equals(pFormat))
			{
				final java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(text));
				String line;
				int lineNumber = 0;
				while ((line = reader.readLine()) != null)
				{
					lineNumber++;
					if (!line.trim().isEmpty())
					{
						final JsonNode value = SafeTextDocuments.json(line,remainingTokens);
						if (value == null || (!value.isObject() && !value.isArray()))
						{
							throw new ConnectorException("Invalid ndjson content at line " + lineNumber + ": expected a JSON object or array");
						}
						available++;
						if(rows.size()<max)rows.add(value);
					}
				}
			}
			else if ("xml".equals(pFormat))
			{
				final org.w3c.dom.Element root = SafeTextDocuments.xml(text).getDocumentElement();
				final org.w3c.dom.NodeList children = root.getChildNodes();
				String repeatedName = null;
				int elementCount = 0;
				for (int i = 0; i < children.getLength(); i++)
				{
					if (children.item(i) instanceof org.w3c.dom.Element)
					{
						elementCount++;
						final String name = Xml.localName(children.item(i));
						repeatedName = repeatedName == null ? name : repeatedName.equals(name) ? repeatedName : "";
					}
				}
				if ((elementCount > 1 && !"".equals(repeatedName)) || (elementCount > 0 && "rows".equalsIgnoreCase(Xml.localName(root))))
				{
					available=elementCount;
					for (int i = 0; i < children.getLength() && rows.size() < max; i++)
					{
						if (children.item(i) instanceof org.w3c.dom.Element) rows.add(Xml.toJson((org.w3c.dom.Element) children.item(i)));
					}
				}
				else if (max > 0)
				{
					available=1;
					rows.add(Xml.toJson(root));
				}
			}
			else
			{
				final JsonNode parsed = text.trim().isEmpty() ? null : SafeTextDocuments.json(text,remainingTokens);
				if(parsed!=null&&!parsed.isObject()&&!parsed.isArray())throw new ConnectorException("Invalid JSON content: expected an object or array");
				JsonNode array = parsed;
				if (parsed != null && parsed.isObject())
				{
					array = null;
					for (final String key : new String[] { "rows", "items", "data", "results", "value", "records" })
					{
						if (parsed.get(key) != null && parsed.get(key).isArray())
						{
							if(array!=null)throw new ConnectorException("Ambiguous JSON row collections; select a single envelope array");
							array = parsed.get(key);
						}
					}
					if (array == null)
					{
						array = envelopeArray(parsed);
					}
					if (array == null)
					{
						available=1;
						rows.add(parsed);
					}
				}
				if (array != null && array.isArray())
				{
					available=array.size();
					for (final JsonNode item : array)
					{
						if (rows.size() < max)
						{
							rows.add(item);
						}
					}
				}
			}
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Invalid document content or parser safety limit exceeded");
		}
		out.put("count", rows.size());
		out.put("available", available);
		out.put("truncated", available > rows.size());
		return out;
	}

	static ObjectNode parse(final byte[] pContent, final String pFormat, final char pSeparator, final int pMax,
		final int pMaxBytes) throws ConnectorException
	{
		checkSize(pContent, pMaxBytes, "Input document");
		final DocumentCodecProvider provider = provider(pFormat);
		if (provider != null)
		{
			final int maxRows = boundedRows(pMax);
			final ObjectNode parsed = provider.parse(pContent, canonicalFormat(pFormat), maxRows, pMaxBytes);
			if (parsed == null || parsed.get("rows") == null || !parsed.get("rows").isArray())
				throw new ConnectorException("Document codec provider " + provider.getClass().getName()
					+ " returned an invalid result for '" + pFormat + "': rows must be an array");
			if (parsed.get("rows").size() > maxRows)
				throw new ConnectorException("Document codec provider " + provider.getClass().getName()
					+ " exceeded the configured row limit of " + maxRows);
			parsed.put("count", parsed.get("rows").size());
			return parsed;
		}
		ensureSupported(pFormat);
		try {
			String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(pContent==null?new byte[0]:pContent)).toString();
			return parse(decoded,pFormat,pSeparator,pMax);
		} catch(java.nio.charset.CharacterCodingException invalid){throw new ConnectorException("Invalid UTF-8 document encoding");}
	}

	/**
	 * True when a registered {@link DocumentCodecProvider} claims {@code pFormat} (excel/parquet
	 * always do; a third-party provider could in principle also claim {@code csv}/{@code ndjson}).
	 * {@code AbstractFileConnector.readRows} checks this before taking the streaming path below - a
	 * format a provider owns must keep going through {@link #parse(byte[], String, char, int, int)},
	 * exactly as {@code execute}'s non-streamed {@code read} still does, so a provider's own decoding
	 * (which may not be line-oriented UTF-8 text at all) is never bypassed.
	 */
	static boolean hasCustomProvider(final String pFormat) throws ConnectorException
	{
		return provider(pFormat) != null;
	}

	/**
	 * Volume lot 7b: a {@link java.io.Reader} over {@code pBytes}, strict UTF-8 (malformed/unmappable
	 * input reported as a {@link java.nio.charset.CharacterCodingException}, exactly like {@link
	 * #parse(byte[], String, char, int, int)}'s own decode), with a leading UTF-8 BOM stripped (the
	 * same {@code pText.startsWith("﻿")} stripping {@link #parse(String, String, char, int)} does
	 * for every format, done here at the byte level before a single character is decoded). Buffered,
	 * since both {@link #streamCsv} (via {@link Csv#parseStream}'s {@link java.io.PushbackReader}) and
	 * {@link #streamNdjson} (via {@link java.io.BufferedReader#readLine()}) read it one character/line
	 * at a time - never converts the whole content to one {@code String} first.
	 */
	private static java.io.BufferedReader decodingReader(final byte[] pBytes)
	{
		final byte[] content = pBytes == null ? new byte[0] : pBytes;
		final boolean hasBom = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF;
		final java.io.InputStream in = new java.io.ByteArrayInputStream(content, hasBom ? 3 : 0, content.length - (hasBom ? 3 : 0));
		final java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
			.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
		return new java.io.BufferedReader(new java.io.InputStreamReader(in, decoder));
	}

	/**
	 * Volume lot 7b: streams a CSV/TSV file's rows to {@code pSink} one at a time via {@link
	 * Csv#parseStream} over a real {@link java.io.Reader} - never converts {@code pBytes} to one
	 * {@code String} first, and never builds one {@code {rows:[...]}} {@link ArrayNode} holding every
	 * row (the row shape - {@code {header[i]: value}} - is identical to what {@link #parse} produces).
	 * {@code pMax}/truncation/availability behave exactly as {@link Csv#parseStream} documents.
	 */
	static Connector.RowStreamResult streamCsv(final byte[] pBytes, final char pSeparator, final int pMax, final Connector.RowSink pSink)
		throws ConnectorException
	{
		try (java.io.BufferedReader reader = decodingReader(pBytes))
		{
			final List<String> header = new ArrayList<String>();
			final Csv.StreamResult result = Csv.parseStream(reader, pSeparator, pMax, new Csv.RecordSink()
			{
				@Override
				public void header(final List<String> pHeader)
				{
					header.clear();
					header.addAll(pHeader);
				}

				@Override
				public boolean accept(final List<String> pRecord)
				{
					final ObjectNode row = Json.object();
					for (int c = 0; c < header.size(); c++)
					{
						row.put(header.get(c), c < pRecord.size() ? pRecord.get(c) : null);
					}
					return pSink.accept(row);
				}
			});
			return new Connector.RowStreamResult(result.delivered, result.truncated, result.available);
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException("Invalid csv content: " + ex.getMessage(), ex);
		}
		catch (final java.nio.charset.CharacterCodingException ex)
		{
			throw new ConnectorException("Invalid UTF-8 document encoding");
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Failed reading csv content: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Volume lot 7b: streams an NDJSON file's rows to {@code pSink} one line at a time (each line is
	 * one JSON value, parsed with the same {@link SafeTextDocuments#json} safety guard - shared node
	 * budget across the whole file, exactly like {@link #parse(String, String, char, int)}'s own
	 * {@code remainingTokens}) - never converts {@code pBytes} to one {@code String} first, and never
	 * builds one {@code {rows:[...]}} {@link ArrayNode} holding every row.
	 */
	static Connector.RowStreamResult streamNdjson(final byte[] pBytes, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		final int[] remainingTokens = { SafeTextDocuments.MAX_NODES };
		int delivered = 0;
		long available = 0;
		boolean sinkStopped = false;
		int lineNumber = 0;
		try (java.io.BufferedReader reader = decodingReader(pBytes))
		{
			String line;
			while (!sinkStopped && (line = reader.readLine()) != null)
			{
				lineNumber++;
				if (line.trim().isEmpty())
				{
					continue;
				}
				final JsonNode value = SafeTextDocuments.json(line, remainingTokens);
				if (value == null || (!value.isObject() && !value.isArray()))
				{
					throw new ConnectorException("Invalid ndjson content at line " + lineNumber + ": expected a JSON object or array");
				}
				available++;
				if (delivered < pMax)
				{
					delivered++;
					if (!pSink.accept(value))
					{
						sinkStopped = true;
					}
				}
			}
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final java.nio.charset.CharacterCodingException ex)
		{
			throw new ConnectorException("Invalid UTF-8 document encoding");
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Failed reading ndjson content: " + ex.getMessage(), ex);
		}
		catch (final RuntimeException ex)
		{
			throw new ConnectorException("Invalid document content or parser safety limit exceeded");
		}
		final boolean truncated = !sinkStopped && available > delivered;
		return new Connector.RowStreamResult(delivered, truncated, sinkStopped ? -1 : available);
	}

	/** The rows of an outbound payload: {@code {rows:[...]}} or a single document. */
	static List<JsonNode> rows(final JsonNode pPayload)
	{
		final List<JsonNode> rows = new ArrayList<JsonNode>();
		if (pPayload == null || pPayload.isNull())
		{
			return rows;
		}
		final JsonNode array = pPayload.isArray() ? pPayload : pPayload.get("rows") != null && pPayload.get("rows").isArray() ? pPayload.get("rows") : null;
		if (array != null)
		{
			for (final JsonNode row : array)
			{
				rows.add(row);
			}
		}
		else
		{
			rows.add(pPayload);
		}
		return rows;
	}

	static String serialise(final List<JsonNode> pRows, final String pFormat, final char pSeparator, final boolean pWithHeader)
		throws ConnectorException
	{
		ensureSupported(pFormat);
		final StringBuilder sb = new StringBuilder();
		if ("csv".equals(pFormat))
		{
			final List<String> header = pRows.isEmpty() ? new ArrayList<String>() : Csv.header(pRows.get(0));
			if (pWithHeader && !header.isEmpty())
			{
				for (int i = 0; i < header.size(); i++)
				{
					sb.append(i > 0 ? String.valueOf(pSeparator) : "").append(Csv.quote(header.get(i), pSeparator));
				}
				sb.append("\r\n");
			}
			for (final JsonNode row : pRows)
			{
				sb.append(Csv.line(row, header, pSeparator)).append("\r\n");
			}
			return sb.toString();
		}
		if ("jsonl".equals(pFormat) || "ndjson".equals(pFormat))
		{
			for (final JsonNode row : pRows)
			{
				sb.append(Json.write(row)).append('\n');
			}
			return sb.toString();
		}
		if ("xml".equals(pFormat))
		{
			sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rows>");
			for (final JsonNode row : pRows) appendXml(sb, "row", row);
			return sb.append("</rows>").toString();
		}
		if (pRows.size() == 1)
		{
			return Json.write(pRows.get(0));
		}
		final ArrayNode array = Json.mapper().createArrayNode();
		for (final JsonNode row : pRows)
		{
			array.add(row);
		}
		return Json.write(array);
	}

	static byte[] serialiseBytes(final List<JsonNode> pRows, final String pFormat, final char pSeparator,
		final boolean pWithHeader, final int pMaxBytes) throws ConnectorException
	{
		final DocumentCodecProvider provider = provider(pFormat);
		final byte[] bytes = provider == null
			? serialise(pRows, pFormat, pSeparator, pWithHeader).getBytes(java.nio.charset.StandardCharsets.UTF_8)
			: provider.serialise(pRows, canonicalFormat(pFormat), pMaxBytes);
		if (bytes == null) throw new ConnectorException("Document codec provider " + provider.getClass().getName()
			+ " returned no content for '" + pFormat + "'");
		checkSize(bytes, pMaxBytes, "Generated document");
		return bytes;
	}

	static String contentType(final String pFormat) throws ConnectorException
	{
		final DocumentCodecProvider provider = provider(pFormat);
		if (provider != null)
		{
			final String contentType = provider.getContentType(canonicalFormat(pFormat));
			if (contentType == null || contentType.trim().isEmpty())
				throw new ConnectorException("Document codec provider " + provider.getClass().getName()
					+ " returned no content type for '" + pFormat + "'");
			return contentType;
		}
		return "csv".equals(pFormat) ? "text/csv; charset=UTF-8"
			: "jsonl".equals(pFormat) || "ndjson".equals(pFormat) ? "application/x-ndjson"
			: "xml".equals(pFormat) ? "application/xml; charset=UTF-8" : "application/json";
	}

	static int boundedRows(final int pRequested) throws ConnectorException
	{
		if (pRequested < 1) throw new ConnectorException("Row limit must be positive");
		return Math.min(pRequested, ABSOLUTE_MAX_ROWS);
	}

	static int boundedBytes(final String pValue) throws ConnectorException
	{
		if (pValue == null || pValue.trim().isEmpty()) return DEFAULT_MAX_BYTES;
		try
		{
			final long value = Long.parseLong(pValue.trim());
			if (value < 1 || value > ABSOLUTE_MAX_BYTES) throw new ConnectorException("max.bytes must be between 1 and " + ABSOLUTE_MAX_BYTES);
			return (int) value;
		}
		catch (final NumberFormatException ex)
		{
			throw new ConnectorException("max.bytes must be an integer", ex);
		}
	}

	static void checkSize(final byte[] pBytes, final int pMaxBytes, final String pAction) throws ConnectorException
	{
		if(pMaxBytes<1||pMaxBytes>ABSOLUTE_MAX_BYTES)throw new ConnectorException("max.bytes is outside supported limits");
		if (pBytes != null && pBytes.length > pMaxBytes)
			throw new ConnectorException(pAction + " exceeds the configured max.bytes limit of " + pMaxBytes);
	}

	private static void ensureSupported(final String pFormat) throws ConnectorException
	{
		if ("excel".equals(pFormat) || "xlsx".equals(pFormat) || "xls".equals(pFormat))
			throw new ConnectorException("Excel format is unavailable: install a DocumentCodecProvider backed by Apache POI; use CSV, JSON, NDJSON or XML");
		if ("parquet".equals(pFormat))
			throw new ConnectorException("Parquet format is unavailable: install a DocumentCodecProvider backed by Apache Parquet; use CSV, JSON, NDJSON or XML");
		if (!("csv".equals(pFormat) || "json".equals(pFormat) || "jsonl".equals(pFormat) || "ndjson".equals(pFormat) || "xml".equals(pFormat)))
			throw new ConnectorException("Unsupported document format '" + pFormat + "'");
	}

	private static DocumentCodecProvider provider(final String pFormat) throws ConnectorException
	{
		final String requested = canonicalFormat(pFormat);
		try
		{
			DocumentCodecProvider selected = null;
			for (final DocumentCodecProvider provider : java.util.ServiceLoader.load(DocumentCodecProvider.class,
				Thread.currentThread().getContextClassLoader()))
			{
				if (!supports(provider, requested)) continue;
				if (selected != null)
					throw new ConnectorException("Multiple document codec providers claim format '" + requested + "': "
						+ selected.getClass().getName() + " and " + provider.getClass().getName());
				selected = provider;
			}
			return selected;
		}
		catch (final java.util.ServiceConfigurationError ex)
		{
			throw new ConnectorException("Unable to load the document codec provider for '" + pFormat + "': " + ex.getMessage(), ex);
		}
	}

	private static boolean supports(final DocumentCodecProvider pProvider, final String pRequested)
	{
		if (pProvider.getFormats() == null) return false;
		for (final String format : pProvider.getFormats())
		{
			if (pRequested.equals(canonicalFormat(format))) return true;
		}
		return false;
	}

	private static String canonicalFormat(final String pFormat)
	{
		final String format = pFormat == null ? "" : pFormat.trim().toLowerCase(java.util.Locale.ROOT);
		return "excel".equals(format) || "xls".equals(format) ? "xlsx" : format;
	}

	private static void appendXml(final StringBuilder pOut, final String pName, final JsonNode pValue)
	{
		final String name = pName != null && pName.matches("[A-Za-z_][A-Za-z0-9_.-]*") ? pName : "field";
		pOut.append('<').append(name).append('>');
		if (pValue != null && pValue.isObject())
		{
			final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = pValue.fields();
			while (fields.hasNext())
			{
				final java.util.Map.Entry<String, JsonNode> field = fields.next();
				appendXml(pOut, field.getKey(), field.getValue());
			}
		}
		else if (pValue != null && pValue.isArray())
		{
			for (final JsonNode item : pValue) appendXml(pOut, "item", item);
		}
		else if (pValue != null && !pValue.isNull())
		{
			pOut.append(escapeXml(pValue.asText()));
		}
		pOut.append("</").append(name).append('>');
	}

	private static String escapeXml(final String pText)
	{
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
	}
}
