package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Column statistics computed by ON EBX itself on records read from a source that cannot run an
 * aggregate query — an EBX instance through Data Services, a Kafka topic. The figures carry the
 * names of the SQL profile ({@code JdbcConnector}, {@link CatalogOperations}) plus
 * {@code method = SAMPLE} and {@code sampleSize}, so that the data catalog can tell an estimate from
 * a full scan: null rate, distinct count, min / max, mean and lengths describe the records read;
 * {@code rowCount} is the source's own count and {@code nullCount} the sampled rate scaled to it.
 */
public final class RecordProfiler
{
	/** Statistics of the records read by ON EBX, not of the whole table. */
	public static final String METHOD_SAMPLE = "SAMPLE";
	/** Statistics computed by the source on every row (one aggregate query per table). */
	public static final String METHOD_FULL_SCAN = "FULL_SCAN";

	private static final int MAX_DISTINCT = 100000;

	private RecordProfiler()
	{
	}

	/**
	 * Writes the statistics of one column into {@code pColumn}. {@code pPath} is a field path
	 * ({@code Group/field}) of the records; {@code pRowCount} the exact count of the source when known,
	 * else a negative value (the number of records read is used).
	 */
	public static void profile(final ObjectNode pColumn, final List<JsonNode> pRecords, final String pPath, final String pDataType, final long pRowCount)
	{
		final int n = pRecords.size();
		final long rowCount = pRowCount > n ? pRowCount : n;
		final boolean numeric = "INTEGER".equals(pDataType) || "DECIMAL".equals(pDataType);
		final boolean text = "STRING".equals(pDataType);
		int nonNull = 0;
		final Set<String> distinct = new LinkedHashSet<String>();
		String min = null, max = null;
		double minNum = Double.MAX_VALUE, maxNum = -Double.MAX_VALUE, sum = 0d;
		int numbers = 0, minLen = Integer.MAX_VALUE, maxLen = 0;
		long lenSum = 0L;
		for (final JsonNode record : pRecords)
		{
			final String v = RecordProfiler.valueText(record, pPath);
			if (v == null) continue;
			nonNull++;
			if (distinct.size() < RecordProfiler.MAX_DISTINCT) distinct.add(v);
			if (numeric)
			{
				try
				{
					final double d = Double.parseDouble(v);
					numbers++;
					sum += d;
					if (d < minNum)
					{
						minNum = d;
						min = v;
					}
					if (d > maxNum)
					{
						maxNum = d;
						max = v;
					}
				}
				catch (final NumberFormatException ex)
				{
					// a non-numeric text in a numeric column: ignored for min / max / mean
				}
			}
			else if (!"BOOLEAN".equals(pDataType) && !"BINARY".equals(pDataType) && !"JSON".equals(pDataType))
			{
				if (min == null || v.compareTo(min) < 0) min = v;
				if (max == null || v.compareTo(max) > 0) max = v;
			}
			if (text)
			{
				final int len = v.length();
				if (len < minLen) minLen = len;
				if (len > maxLen) maxLen = len;
				lenSum += len;
			}
		}
		pColumn.put("rowCount", rowCount);
		final double nullRate = n == 0 ? 0d : RecordProfiler.round4((double) (n - nonNull) / n);
		pColumn.put("nullCount", n == 0 ? 0L : Math.round(nullRate * rowCount));
		pColumn.put("nullRate", nullRate);
		pColumn.put("distinctCount", distinct.size());
		pColumn.put("uniqueRate", nonNull == 0 ? 0d : RecordProfiler.round4((double) distinct.size() / nonNull));
		pColumn.put("method", RecordProfiler.METHOD_SAMPLE);
		pColumn.put("sampleSize", n);
		if (min != null) pColumn.put("minValue", RecordProfiler.cut(min, 500));
		if (max != null) pColumn.put("maxValue", RecordProfiler.cut(max, 500));
		if (numeric && numbers > 0) pColumn.put("meanValue", RecordProfiler.round4(sum / numbers));
		if (text && nonNull > 0)
		{
			pColumn.put("minLength", minLen);
			pColumn.put("maxLength", maxLen);
			pColumn.put("avgLength", RecordProfiler.round4((double) lenSum / nonNull));
		}
	}

	/** Distinct non-blank values of a field, in reading order, each cut to {@code pMaxLength}. */
	public static List<String> distinctValues(final List<JsonNode> pRecords, final String pPath, final int pMaxLength)
	{
		final Set<String> values = new LinkedHashSet<String>();
		for (final JsonNode record : pRecords)
		{
			final String v = RecordProfiler.valueText(record, pPath);
			if (v != null && !v.trim().isEmpty()) values.add(RecordProfiler.cut(v, pMaxLength));
		}
		return new ArrayList<String>(values);
	}

	/** Text of a field ({@code Group/field}) of a record; null when absent, null, empty or an empty list / object. */
	public static String valueText(final JsonNode pRecord, final String pPath)
	{
		JsonNode v = pRecord;
		for (final String segment : pPath.split("/"))
		{
			if (v == null || !v.isObject()) return null;
			v = v.get(segment);
		}
		if (v == null || v.isNull()) return null;
		if (v.isArray() || v.isObject()) return v.size() == 0 ? null : v.toString();
		final String t = v.asText();
		return t.isEmpty() ? null : t;
	}

	static String cut(final String pValue, final int pMax)
	{
		return pValue.length() > pMax ? pValue.substring(0, pMax) : pValue;
	}

	static double round4(final double pValue)
	{
		return Math.round(pValue * 10000d) / 10000d;
	}
}
