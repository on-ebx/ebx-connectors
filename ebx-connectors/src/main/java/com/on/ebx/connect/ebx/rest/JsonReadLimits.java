package com.on.ebx.connect.ebx.rest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;

/**
 * What Jackson (2.15 and later) accepts when it reads JSON, as a written choice of this module (lot 1.4.1, audit X3-08). From 2.15
 * Jackson refuses a document that nests deeper than 1000 levels, a number of more than 1000 characters, a name of more than 50,000
 * characters and a string of more than 20,000,000 characters. The first three are kept as Jackson sets them. The last one is not:
 * this module takes documents in base64 inside a JSON string (the documents function), up to the body bounds of {@link BodyLimit}
 * (32 MiB on REST by default), and reads answers of services up to the HTTP ceiling ({@link HttpJsonClient#defaultMaxResponseBytes()},
 * 128 MiB by default). The BYTE bound of each entry is the limit that governs; a string cannot hold more characters than its
 * document holds bytes. So the string bound of the readers follows those byte bounds, and {@value #PROP_MAX_STRING} sets it
 * explicitly when an administrator wants another value.
 * <p>
 * The value is applied ONCE, at module start ({@link #apply()}, first statement of the module's registration listener), as the
 * default of every Jackson reader created afterwards in this web application: the module's own mapper ({@link Json}), the ones of
 * the agent and of the OpenAPI importer, the validators, and the libraries of the war that use Jackson (Avro). A Jackson reader
 * created BEFORE that call keeps Jackson's defaults, which is why {@link #status(ObjectNode)} reads the constraints the module's
 * real mapper carries instead of repeating the setting. The nesting depth that the module itself imposes on what a caller sends
 * (64, {@link BodyLimit}) is checked before any parse and stays.
 */
public final class JsonReadLimits
{
	/** Maximum length, in characters, of one JSON string read by the module. Default: the largest of 20,000,000, the REST and MCP body bounds and the HTTP response ceiling. */
	public static final String PROP_MAX_STRING = "ebx.connectors.json.maxStringLength";

	/** Jackson's own default, kept as the floor. */
	public static final int JACKSON_DEFAULT_STRING = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;

	private static boolean applied;
	private static int appliedString;

	private JsonReadLimits()
	{
	}

	/** The string bound this JVM would apply now: the property, else the largest of Jackson's default and the byte bounds of the entries. */
	public static int configuredMaxString()
	{
		final long explicit = BodyLimit.longProperty(JsonReadLimits.PROP_MAX_STRING, 0L);
		if (explicit > 0L)
		{
			return (int) Math.min(Integer.MAX_VALUE, explicit);
		}
		long bound = JsonReadLimits.JACKSON_DEFAULT_STRING;
		bound = Math.max(bound, BodyLimit.maxRestBodyBytes());
		bound = Math.max(bound, BodyLimit.maxMcpBodyBytes());
		final int http = HttpJsonClient.defaultMaxResponseBytes();
		// 0 removes the HTTP ceiling: the string is then bounded by the heap only, as it was before Jackson 2.15
		bound = Math.max(bound, http == 0 ? Integer.MAX_VALUE : http);
		return (int) Math.min(Integer.MAX_VALUE, bound);
	}

	/** The constraints of this module: Jackson's defaults, with the string bound above. */
	public static StreamReadConstraints constraints(final int pMaxString)
	{
		return StreamReadConstraints.builder().maxStringLength(pMaxString).build();
	}

	/**
	 * Applies the bound as the default of every Jackson reader created from now on in this web application. Called once, first
	 * thing at module start, before any {@code ObjectMapper} or {@code JsonFactory} exists (a {@code static final} one included);
	 * a second call does nothing.
	 */
	public static synchronized void apply()
	{
		if (JsonReadLimits.applied)
		{
			return;
		}
		JsonReadLimits.appliedString = JsonReadLimits.configuredMaxString();
		StreamReadConstraints.overrideDefaultStreamReadConstraints(JsonReadLimits.constraints(JsonReadLimits.appliedString));
		JsonReadLimits.applied = true;
	}

	/** A factory that carries the bound whatever the order of class loading (the depth scanner of {@link BodyLimit}). */
	public static JsonFactory newFactory()
	{
		JsonReadLimits.apply();
		return JsonFactory.builder().streamReadConstraints(JsonReadLimits.constraints(JsonReadLimits.appliedString)).build();
	}

	/** What the module's own mapper really carries, for {@code GET /rest/v1/status}: measured, not the setting. */
	public static void status(final ObjectNode pOut)
	{
		final ObjectNode jackson = pOut.putObject("jackson");
		jackson.put("version", com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString());
		final StreamReadConstraints carried = Json.mapper().getFactory().streamReadConstraints();
		jackson.put("maxStringLength", carried.getMaxStringLength());
		jackson.put("maxNestingDepth", carried.getMaxNestingDepth());
		jackson.put("maxNumberLength", carried.getMaxNumberLength());
		jackson.put("maxNameLength", carried.getMaxNameLength());
		final int wanted = JsonReadLimits.configuredMaxString();
		jackson.put("maxStringLengthSetting", wanted);
		jackson.put("maxStringLengthProperty", JsonReadLimits.PROP_MAX_STRING);
		// false: a reader was created before apply(), or a body bound was raised after the start (a restart of the JVM re-reads them)
		jackson.put("readersCarryTheSetting", carried.getMaxStringLength() == wanted);
	}
}
