package com.on.ebx.connect.connectors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Expressions;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.SynchronizationCheckpoint;
import com.on.ebx.connect.core.WatermarkFilter;
import com.on.ebx.connect.core.WatermarkRun;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/**
 * Volume lot V2-D, end to end against a real in-memory H2: the mapping's {@code sql} input written with
 * {@code ${watermark}} is resolved by the engine's own {@link WatermarkRun}, and the connector binds the
 * checkpoint as a typed prepared-statement parameter - a timestamp as a TIMESTAMP, a number as a number,
 * a string as a string (so {@code '0012'} is not the number 12 and a hostile value reads nothing instead
 * of everything). Covers the first run (no checkpoint), a stored checkpoint, and the client-side filter
 * layered on top.
 */
public final class JdbcWatermarkTest
{
	private JdbcWatermarkTest() { }

	private static final String URL = "jdbc:h2:mem:wmtest;DB_CLOSE_DELAY=-1";

	public static void main(final String[] args) throws Exception
	{
		final Connection setup = DriverManager.getConnection(URL, "sa", "");
		try
		{
			final Statement st = setup.createStatement();
			st.execute("create table CONTACT (ID bigint primary key, NAME varchar(40), CODE varchar(10), LAST_MODIFIED timestamp)");
			st.execute("insert into CONTACT values (1, 'Ada',   '0001', timestamp '2026-01-01 08:00:00')");
			st.execute("insert into CONTACT values (2, 'Bob',   '0002', timestamp '2026-01-02 09:00:00')");
			st.execute("insert into CONTACT values (3, 'Cleo',  '0010', timestamp '2026-01-03 10:00:00')");
			st.execute("insert into CONTACT values (4, 'Dan',   '0011', null)");
			st.close();

			final H2Connector connector = new H2Connector();
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("h2-watermark");
			config.setType("h2");
			config.setBaseUrl(URL);
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("sa");
			config.setPassword("");
			connector.configure(config);

			check(connector.bindsWatermark("query", "sql"), "the JDBC query sql input binds the watermark");
			check(!connector.bindsWatermark("query", "other") && !connector.bindsWatermark("execute", "sql"), "no other input or operation does");

			// --- timestamp: the example of the docs, first run then a stored checkpoint
			final String sql = "select ID, NAME, LAST_MODIFIED from CONTACT s where s.LAST_MODIFIED >= ${watermark} order by s.LAST_MODIFIED";
			List<JsonNode> rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), sql, null);
			check(ids(rows).equals("1,2,3"), "first run (no checkpoint): every row with a change value, the NULL one excluded by the comparison: " + ids(rows));

			// the checkpoint as the engine stores it (LocalDateTime.toString drops zero seconds) still binds as a TIMESTAMP
			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), sql, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-02T09:00"));
			check(ids(rows).equals("2,3"), "inclusive (>=): the row equal to the checkpoint is read again with the newer one: " + ids(rows));

			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), sql.replace(">=", ">"), stored(SynchronizationStrategy.TIMESTAMP, "2026-01-02T09:00:00"));
			check(ids(rows).equals("3"), "exclusive (>): only the newer row: " + ids(rows));

			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), sql, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-03T10:00:01"));
			check(rows.isEmpty(), "nothing at or above the checkpoint: empty read: " + ids(rows));

			// the initial value is where a first run starts
			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "2026-01-03", "./_rowHash"), sql, null);
			check(ids(rows).equals("3"), "checkpointInitialValue bounds the first run: " + ids(rows));

			// the isSet form, valid SQL on a first run
			final String isSetSql = "select ID from CONTACT s where (${watermark.isSet} = 0 or s.LAST_MODIFIED >= ${watermark}) order by ID";
			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), isSetSql, null);
			check(ids(rows).equals("1,2,3,4"), "watermark.isSet = 0 reads everything, the NULL row included: " + ids(rows));
			rows = read(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", "./_rowHash"), isSetSql, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-03T10:00"));
			check(ids(rows).equals("3"), "with a checkpoint the comparison applies: " + ids(rows));

			// --- number
			final String numeric = "select ID from CONTACT s where s.ID > ${watermark} order by ID";
			rows = read(connector, mapping("MONOTONIC_ID", "ID", "", ""), numeric, null);
			check(ids(rows).equals("1,2,3,4"), "number, first run: the floor is below every id: " + ids(rows));
			rows = read(connector, mapping("MONOTONIC_ID", "ID", "", ""), numeric, stored(SynchronizationStrategy.MONOTONIC_ID, "2"));
			check(ids(rows).equals("3,4"), "number: > 2: " + ids(rows));

			// --- string: kept as a string ('0010' > '0002' in string order), never turned into a number
			final String textual = "select ID from CONTACT s where s.CODE > ${watermark} order by ID";
			rows = read(connector, mapping("ORDERED_STRING", "CODE", "", ""), textual, stored(SynchronizationStrategy.ORDERED_STRING, "0002"));
			check(ids(rows).equals("3,4"), "string: CODE > '0002': " + ids(rows));
			rows = read(connector, mapping("ORDERED_STRING", "CODE", "", ""), textual, null);
			check(ids(rows).equals("1,2,3,4"), "string, first run: the floor sorts before every code: " + ids(rows));

			// --- binding, not concatenation: a hostile value is data
			rows = read(connector, mapping("ORDERED_STRING", "CODE", "", ""), textual, stored(SynchronizationStrategy.ORDERED_STRING, "x' or '1'='1"));
			check(rows.isEmpty(), "a hostile checkpoint value is compared as data, it does not rewrite the statement: " + ids(rows));

			// --- the client-side filter layered on the source-side one (exclusive boundary over an inclusive SQL)
			rows = readFiltered(connector, mapping("TIMESTAMP", "LAST_MODIFIED", "", ""), sql, stored(SynchronizationStrategy.TIMESTAMP, "2026-01-02T09:00"));
			check(ids(rows).equals("3"), "exclusive boundary drops the equal row that the >= SQL still returned: " + ids(rows));

			// --- the typed value helper
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			check(JdbcConnector.watermarkValue(inputs) == null && JdbcConnector.watermarkValue(null) == null, "no watermark inputs: nothing typed");
			inputs.put("watermark", "0012");
			check(JdbcConnector.watermarkValue(inputs) == null, "a bare input named watermark keeps the ordinary text-guessing binding");
			inputs.put("watermarkType", "STRING");
			check("0012".equals(JdbcConnector.watermarkValue(inputs)), "with its type, '0012' stays a string");
		}
		finally
		{
			setup.createStatement().execute("shutdown");
			setup.close();
		}
		System.out.println("PASS: JDBC watermark is bound as a typed parameter (timestamp, number, string), first run, stored checkpoint, initial value, isSet, client-side filter");
	}

	private static MappingDefinition mapping(final String pStrategy, final String pField, final String pInitial, final String pUnchangedIfEqual)
	{
		final MappingDefinition m = new MappingDefinition(7, "h2-watermark", "query", MappingDefinition.Direction.INBOUND, "Contacts", null, "DS", "/root/Contact", "", true);
		m.setFeed("rows", "./ID='${ID}'", true);
		m.setSynchronizationPolicy("INCREMENTAL", pStrategy, "", pField, pInitial);
		m.setUnchangedIfEqual(pUnchangedIfEqual);
		return m;
	}

	private static SynchronizationCheckpoint stored(final SynchronizationStrategy pStrategy, final String pValue)
	{
		return new SynchronizationCheckpoint(7, pStrategy, pValue, new Date(), 1L);
	}

	/** What ConnectorService.importFrom does with the mapping's inputs, then a real connector read; the source-side filter only. */
	private static List<JsonNode> read(final H2Connector pConnector, final MappingDefinition pMapping, final String pSql, final SynchronizationCheckpoint pStored) throws Exception
	{
		return run(pConnector, pMapping, pSql, pStored, false);
	}

	private static List<JsonNode> readFiltered(final H2Connector pConnector, final MappingDefinition pMapping, final String pSql, final SynchronizationCheckpoint pStored) throws Exception
	{
		return run(pConnector, pMapping, pSql, pStored, true);
	}

	private static List<JsonNode> run(final H2Connector pConnector, final MappingDefinition pMapping, final String pSql, final SynchronizationCheckpoint pStored,
		final boolean pFilter) throws Exception
	{
		final WatermarkRun plan = WatermarkRun.plan(pMapping, pStored, false);
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("sql", plan.resolveInput(pSql, pConnector.bindsWatermark("query", "sql"), new WatermarkRun.Resolver()
		{
			@Override
			public String resolve(final String pTemplate)
			{
				return Expressions.resolve(pTemplate, null, null);
			}
		}));
		plan.completeInputs(inputs, pMapping.getCheckpointInput());
		check(!inputs.get("sql").contains("2026-01") && !inputs.get("sql").contains("x'"), "the checkpoint value is not in the SQL text: " + inputs.get("sql"));
		inputs.put("max", "1000");
		final List<JsonNode> rows = new ArrayList<JsonNode>();
		final Connector.RowSink collector = new Connector.RowSink()
		{
			@Override
			public boolean accept(final JsonNode pRow)
			{
				rows.add(pRow);
				return true;
			}
		};
		final Connector.RowStreamResult result = pConnector.readRows(null, "query", inputs, "rows", 1000, pFilter ? plan.filter(collector, null) : collector);
		check(!result.truncated, "not truncated");
		return rows;
	}

	private static String ids(final List<JsonNode> pRows)
	{
		final StringBuilder sb = new StringBuilder();
		for (final JsonNode row : pRows)
		{
			if (sb.length() > 0) sb.append(',');
			sb.append(row.path("ID").asText());
		}
		return sb.toString();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
