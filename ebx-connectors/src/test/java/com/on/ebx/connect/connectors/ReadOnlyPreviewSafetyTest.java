package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/** Dependency-free checks for JDBC SQL gating and bounded file metadata previews. */
public final class ReadOnlyPreviewSafetyTest
{
	private ReadOnlyPreviewSafetyTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		jdbcAllowsOnlySingleNonLockingSelect();
		filePreviewIsBoundedAndNeverReadsOrWrites();
		System.out.println("PASS: JDBC and file previews enforce read-only bounds");
	}

	private static void jdbcAllowsOnlySingleNonLockingSelect()
	{
		check(JdbcConnector.isSafePreviewSelect("SELECT id, name FROM customer WHERE id = :id"),
			"ordinary SELECT was rejected");
		check(!JdbcConnector.isSafePreviewSelect("UPDATE customer SET name = 'x'"),
			"UPDATE was accepted");
		check(!JdbcConnector.isSafePreviewSelect("WITH deleted AS (DELETE FROM customer RETURNING *) SELECT * FROM deleted"),
			"data-changing CTE was accepted");
		check(!JdbcConnector.isSafePreviewSelect("SELECT * INTO customer_copy FROM customer"),
			"SELECT INTO was accepted");
		check(!JdbcConnector.isSafePreviewSelect("SELECT * FROM customer FOR UPDATE"),
			"locking SELECT was accepted");
		check(!JdbcConnector.isSafePreviewSelect("SELECT * FROM customer; DELETE FROM customer"),
			"multiple statements were accepted");
	}

	private static void filePreviewIsBoundedAndNeverReadsOrWrites() throws Exception
	{
		final ProbeFileConnector connector = new ProbeFileConnector();
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("root", "catalog");
		connector.configure(config);
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("prefix", "incoming");
		final PreviewResult result = connector.preview(null,
			new PreviewRequest("list", "list", inputs, 2));
		check(result.isSupported(), "bounded listing preview is unsupported");
		check(result.getRecords().size() == 2, "file preview did not enforce its row limit");
		check(result.isTruncated(), "file preview did not report truncation");
		check(connector.requestedMax == 3, "file preview did not fetch only limit + 1 entries");
		check("catalog/incoming".equals(connector.requestedPrefix), "configured root was not applied");
		check(!connector.read && !connector.wrote, "file preview read or wrote object content");

		final PreviewResult content = connector.preview(null,
			new PreviewRequest("read", "read", Collections.singletonMap("path", "large.csv"), 2));
		check(!content.isSupported(), "unbounded object-content preview was accepted");
		check(!connector.read && !connector.wrote, "unsupported content preview touched storage");
	}

	private static final class ProbeFileConnector extends AbstractFileConnector
	{
		boolean read;
		boolean wrote;
		String requestedPrefix;
		int requestedMax;

		public String getType() { return "preview-probe"; }
		public String getLabel() { return "Preview probe"; }
		protected byte[] readBytes(final String pPath) { this.read = true; return null; }
		protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) { this.wrote = true; }
		protected List<Entry> listEntries(final String pPrefix, final int pMax)
		{
			this.requestedPrefix = pPrefix;
			this.requestedMax = pMax;
			final List<Entry> entries = new ArrayList<Entry>();
			for (int i = 0; i < pMax; i++) entries.add(new Entry(pPrefix + "/file-" + i + ".json", i, "now"));
			return entries;
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
