package com.on.ebx.connect.connectors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Audit C-15 on the JDBC profile, against an in-memory H2: figures say they are exact, the table budget names what it left out, a dry run runs no statement. */
public final class JdbcProfileBudgetTest
{
	private JdbcProfileBudgetTest()
	{
	}

	private static final String URL = "jdbc:h2:mem:profilebudget;DB_CLOSE_DELAY=-1";

	public static void main(final String[] pArgs) throws Exception
	{
		final Connection setup = DriverManager.getConnection(URL, "sa", "");
		try
		{
			final Statement st = setup.createStatement();
			st.execute("create schema S1");
			st.execute("create table S1.A (ID int primary key, NAME varchar(20))");
			st.execute("create table S1.B (ID int primary key, NAME varchar(20))");
			st.execute("create table S1.C (ID int primary key, NAME varchar(20))");
			st.execute("insert into S1.A values (1, 'x')");
			st.execute("insert into S1.A values (2, null)");
			st.execute("insert into S1.B values (1, 'y')");
			st.execute("insert into S1.C values (1, 'z')");
			st.close();

			final H2Connector connector = new H2Connector();
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("h2-profile");
			config.setType("h2");
			config.setBaseUrl(URL);
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("sa");
			config.setPassword("");
			connector.configure(config);

			final JsonNode all = connector.execute(null, "profile", inputs("schemas", "S1"), null);
			check(all.get("count").asInt() == 6 && all.get("exact").asBoolean() && !all.get("partial").asBoolean(), "every column of three tables, exact: " + all.get("budget"));
			final JsonNode column = all.get("columns").get(1);
			check("FULL_SCAN".equals(column.get("method").asText()) && column.get("exact").asBoolean() && "EXACT".equals(column.get("distinctMethod").asText()), "exact markers: " + column);
			check(all.get("budget").get("tablesProfiled").asInt() == 3, "budget block counts the tables");

			final JsonNode limited = connector.execute(null, "profile", inputs("schemas", "S1", "maxTables", "2"), null);
			check(limited.get("count").asInt() == 4 && limited.get("partial").asBoolean() && !limited.get("exact").asBoolean(), "two tables only: " + limited.get("budget"));
			check("maxTables".equals(limited.get("budget").get("stoppedBy").asText()) && limited.get("budget").get("skipped").size() == 1, "the table left out is named: " + limited.get("budget"));

			final JsonNode plan = connector.execute(null, "profile", inputs("schemas", "S1", "dryRun", "true"), null);
			check(plan.get("dryRun").asBoolean() && plan.get("count").asInt() == 0 && plan.get("columns").size() == 0, "a dry run writes nothing");
			check(plan.get("plan").get("tables").size() == 3 && plan.get("plan").get("tables").get(0).get("sql").asText().startsWith("SELECT COUNT(*)")
				&& plan.get("plan").get("statements").asInt() == 3, "a dry run lists the statements: " + plan.get("plan"));
		}
		finally
		{
			setup.close();
		}
		System.out.println("PASS: JDBC profile budget (exact markers, table budget, dry run)");
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", "H2");
		for (int i = 0; i < pPairs.length; i += 2)
		{
			inputs.put(pPairs[i], pPairs[i + 1]);
		}
		return inputs;
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
