package com.on.ebx.connect.core;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small RFC 4180 reader / writer: header line, quoted fields, configurable separator. */
public final class Csv
{
	private Csv()
	{
	}

	/**
	 * Parses text into {@code {rows:[{header: value}], count, truncated, available}}; the first line is the
	 * header. {@code available} is the real number of data records in the text (every record is already
	 * walked to find line/record boundaries, so counting them all costs nothing extra); {@code truncated} is
	 * {@code available > count} - the same top-level fields {@code FileFormats.parse} sets for the other text
	 * formats, read the same way by {@code ConnectorService.importDocument}.
	 */
	public static ObjectNode parse(final String pText, final char pSeparator, final int pMax)
	{
		if (pSeparator == '"' || pSeparator == '\r' || pSeparator == '\n')
		{
			throw new IllegalArgumentException("CSV separator must not be a quote or line break");
		}
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		final List<List<String>> records = Csv.records(pText, pSeparator);
		if (records.isEmpty())
		{
			out.put("count", 0);
			out.put("available", 0);
			out.put("truncated", false);
			return out;
		}
		final List<String> header = records.get(0);
		final ArrayNode columns = out.putArray("columns");
		for (final String h : header)
		{
			columns.add(h);
		}
		int available = 0;
		for (int r = 1; r < records.size(); r++)
		{
			final List<String> record = records.get(r);
			if (record.size() == 1 && record.get(0).isEmpty())
			{
				continue;
			}
			available++;
			if (rows.size() < pMax)
			{
				final ObjectNode row = rows.addObject();
				for (int c = 0; c < header.size(); c++)
				{
					row.put(header.get(c), c < record.size() ? record.get(c) : null);
				}
			}
		}
		out.put("count", rows.size());
		out.put("available", available);
		out.put("truncated", available > rows.size());
		return out;
	}

	/**
	 * Callback for {@link #parseStream}: one raw CSV/TSV record at a time, never the whole file
	 * materialised at once. {@link #header} is called exactly once, with the first record read,
	 * before any {@link #accept} call - a caller that pairs each data record's fields with the header
	 * (building a {@code {header[i]: value}} row, the shape {@link #parse(String, char, int)} already
	 * produces) needs it in hand before the first data record arrives, not after the whole file is
	 * done. {@link #accept} returns {@code false} to stop reading further records - a batch/circuit-
	 * breaker/cancellation decision, mirrored on {@code Connector.RowSink} one layer up ({@code
	 * connectors} package, which is allowed to depend on this {@code core} one; the reverse is not, so
	 * this interface stays list-of-strings, not {@code spi.Connector}-aware).
	 */
	public interface RecordSink
	{
		void header(List<String> pHeader);

		boolean accept(List<String> pRecord);
	}

	/** Outcome of a {@link #parseStream} call - the header line (first record read, never passed to {@link RecordSink}), how many data records reached the sink, and truncation/availability exactly like {@link #parse(String, char, int)}'s top-level fields. */
	public static final class StreamResult
	{
		public final List<String> header;
		public final int delivered;
		/** The exact number of data records in the source, walked to completion the same way {@link #parse} always has - {@code -1} when a sink-initiated stop cut the walk short (an external stop is not the same signal as "the source held more than max": the caller already knows why it stopped and reports that separately, matching the convention {@code Connector.replay}/{@code JdbcConnector} already use). */
		public final long available;
		public final boolean truncated;

		StreamResult(final List<String> pHeader, final int pDelivered, final long pAvailable, final boolean pTruncated)
		{
			this.header = pHeader;
			this.delivered = pDelivered;
			this.available = pAvailable;
			this.truncated = pTruncated;
		}
	}

	/**
	 * Streams {@code pReader}'s records to {@code pSink} one at a time - the same RFC 4180 state
	 * machine {@link #records} uses (quoted fields, doubled-quote escaping, {@code \r}/{@code \n}/
	 * {@code \r\n} line endings all handled identically), but a record is delivered and then forgotten
	 * as soon as its terminator is seen, instead of being appended to one {@code List<List<String>>}
	 * holding the whole file. The first record read is the header (kept, never delivered to
	 * {@code pSink}); a data record that is a single empty field (a blank line) is skipped, exactly
	 * like {@link #parse} skips it. {@code pMax} bounds how many data records reach {@code pSink} -
	 * past it, records are still walked (to report the exact {@link StreamResult#available}, the same
	 * "count everything, it is already being walked" trade this whole class has always made) unless
	 * the sink itself asks to stop, in which case the walk stops immediately (see
	 * {@link StreamResult#available}'s own javadoc).
	 * <p>
	 * Uses one char of pushback ({@link PushbackReader}) to look one character ahead for a doubled
	 * quote or a {@code \r\n} pair - {@code pReader} does not need to support {@code mark}/{@code reset}
	 * itself; wrap it in a {@code BufferedReader} first for efficient reads from a slow underlying
	 * source. Never reads the whole content into one {@code String} first.
	 */
	public static StreamResult parseStream(final Reader pReader, final char pSeparator, final int pMax, final RecordSink pSink) throws IOException
	{
		if (pSeparator == '"' || pSeparator == '\r' || pSeparator == '\n')
		{
			throw new IllegalArgumentException("CSV separator must not be a quote or line break");
		}
		final PushbackReader in = new PushbackReader(pReader, 1);
		List<String> header = null;
		List<String> record = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		int delivered = 0;
		long available = 0;
		boolean sinkStopped = false;
		int ci;
		while (!sinkStopped && (ci = in.read()) != -1)
		{
			final char c = (char) ci;
			if (quoted)
			{
				if (c == '"')
				{
					final int next = in.read();
					if (next == '"')
					{
						field.append('"');
					}
					else
					{
						quoted = false;
						if (next != -1)
						{
							in.unread(next);
						}
					}
				}
				else
				{
					field.append(c);
				}
			}
			else if (c == '"')
			{
				quoted = true;
			}
			else if (c == pSeparator)
			{
				record.add(field.toString());
				field = new StringBuilder();
			}
			else if (c == '\n' || c == '\r')
			{
				if (c == '\r')
				{
					final int next = in.read();
					if (next != -1 && next != '\n')
					{
						in.unread(next);
					}
				}
				record.add(field.toString());
				field = new StringBuilder();
				if (header == null)
				{
					header = record;
					pSink.header(header);
				}
				else if (!(record.size() == 1 && record.get(0).isEmpty()))
				{
					available++;
					if (delivered < pMax)
					{
						delivered++;
						if (!pSink.accept(record))
						{
							sinkStopped = true;
						}
					}
				}
				record = new ArrayList<String>();
			}
			else
			{
				field.append(c);
			}
		}
		if (quoted)
		{
			throw new IllegalArgumentException("CSV contains an unterminated quoted field");
		}
		if (!sinkStopped && (field.length() > 0 || !record.isEmpty()))
		{
			record.add(field.toString());
			if (header == null)
			{
				header = record;
				pSink.header(header);
			}
			else if (!(record.size() == 1 && record.get(0).isEmpty()))
			{
				available++;
				if (delivered < pMax)
				{
					delivered++;
					if (!pSink.accept(record))
					{
						sinkStopped = true;
					}
				}
			}
		}
		final boolean truncated = !sinkStopped && available > delivered;
		return new StreamResult(header == null ? Collections.<String>emptyList() : header, delivered, sinkStopped ? -1 : available, truncated);
	}

	static List<List<String>> records(final String pText, final char pSeparator)
	{
		final List<List<String>> records = new ArrayList<List<String>>();
		List<String> record = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		final String text = pText == null ? "" : pText;
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (quoted)
			{
				if (c == '"')
				{
					if (i + 1 < text.length() && text.charAt(i + 1) == '"')
					{
						field.append('"');
						i++;
					}
					else
					{
						quoted = false;
					}
				}
				else
				{
					field.append(c);
				}
			}
			else if (c == '"')
			{
				quoted = true;
			}
			else if (c == pSeparator)
			{
				record.add(field.toString());
				field = new StringBuilder();
			}
			else if (c == '\n' || c == '\r')
			{
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')
				{
					i++;
				}
				record.add(field.toString());
				records.add(record);
				record = new ArrayList<String>();
				field = new StringBuilder();
			}
			else
			{
				field.append(c);
			}
		}
		if (quoted)
		{
			throw new IllegalArgumentException("CSV contains an unterminated quoted field");
		}
		if (field.length() > 0 || !record.isEmpty())
		{
			record.add(field.toString());
			records.add(record);
		}
		return records;
	}

	/** One CSV line for a flat document (nested values as JSON), in the order of the header. */
	public static String line(final JsonNode pDocument, final List<String> pHeader, final char pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pHeader.size(); i++)
		{
			final JsonNode value = pDocument == null ? null : pDocument.get(pHeader.get(i));
			final String text = value == null || value.isNull() ? "" : (value.isValueNode() ? value.asText() : Json.write(value));
			sb.append(i > 0 ? String.valueOf(pSeparator) : "").append(Csv.quote(text, pSeparator));
		}
		return sb.toString();
	}

	public static String quote(final String pValue, final char pSeparator)
	{
		if (pValue.indexOf(pSeparator) < 0 && pValue.indexOf('"') < 0 && pValue.indexOf('\n') < 0 && pValue.indexOf('\r') < 0)
		{
			return pValue;
		}
		return "\"" + pValue.replace("\"", "\"\"") + "\"";
	}

	public static List<String> header(final JsonNode pDocument)
	{
		final List<String> header = new ArrayList<String>();
		if (pDocument != null && pDocument.isObject())
		{
			final java.util.Iterator<String> names = pDocument.fieldNames();
			while (names.hasNext())
			{
				header.add(names.next());
			}
		}
		return header;
	}
}
