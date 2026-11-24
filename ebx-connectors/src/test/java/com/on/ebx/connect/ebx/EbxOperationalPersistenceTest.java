package com.on.ebx.connect.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorGovernanceAuditEvent;
import com.on.ebx.connect.core.ConnectorGovernanceRecord;
import com.on.ebx.connect.core.ConnectorOperationalMetrics;

/** Account-free adapter round-trip and EBX schema compatibility checks. */
public final class EbxOperationalPersistenceTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		metricsRoundTripPreservesLongCountersWithoutXsLong();
		governanceAndAuditRoundTripSafely();
		schemaIsAdditiveAndUsesSupportedTypes();
		System.out.println("PASS: EBX operational metrics and governance persistence");
	}

	private static void metricsRoundTripPreservesLongCountersWithoutXsLong()
	{
		final MetricBackend backend = new MetricBackend();
		final ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(new EbxConnectorOperationalMetricsPersistence(backend));
		metrics.success("postgresql", new ConnectorOperationalMetrics.Observation()
			.add(ConnectorOperationalMetrics.Counter.READ, (long) Integer.MAX_VALUE + 10L)
			.add(ConnectorOperationalMetrics.Counter.REQUEST, 4L).durationMillis(17L), new Date(1000L));
		metrics.checkpoint("postgresql", new Date(900L)); metrics.schemaDrift("postgresql", new Date(1100L));
		final ConnectorOperationalMetrics.Snapshot restored = new ConnectorOperationalMetrics(new EbxConnectorOperationalMetricsPersistence(backend)).snapshot("postgresql");
		check(restored.get(ConnectorOperationalMetrics.Counter.READ) == (long) Integer.MAX_VALUE + 10L, "large counter lost");
		check(restored.percentile(95) == 17L && restored.checkpointAgeMillis(new Date(1900L)) == 1000L, "latency/checkpoint lost");
		check(restored.getSchemaDriftCount() == 1, "schema drift lost");
		boolean rejected = false; try { EbxConnectorOperationalMetricsPersistence.parseCounter("not-a-number"); }
		catch (final IllegalArgumentException expected) { rejected = true; } check(rejected, "corrupt counter accepted");
	}

	private static void governanceAndAuditRoundTripSafely()
	{
		final GovernanceBackend backend = new GovernanceBackend(); final EbxConnectorGovernanceStore store = new EbxConnectorGovernanceStore(backend);
		store.save(new ConnectorGovernanceRecord("mapping:company,sap", "data-owner", "SAP S/4", 90,
			ConnectorGovernanceRecord.Approval.APPROVED, new Date(1000L), null, 3,
			Arrays.asList("connector:sap", "table:/root/Company,EU"), Arrays.asList("vault:secret/connectors/sap")));
		final ConnectorGovernanceRecord restored = store.find("mapping:company,sap");
		check(restored.getDependencies().equals(Arrays.asList("connector:sap", "table:/root/Company,EU")), "dependency encoding");
		check(restored.getSecretReferences().equals(Arrays.asList("vault:secret/connectors/sap")), "secret reference encoding");
		store.appendAudit(new ConnectorGovernanceAuditEvent(restored.getResourceId(), "APPROVED", "reviewer", "authorization=Bearer hidden", new Date(2000L)));
		check(store.audit(restored.getResourceId()).size() == 1, "audit missing");
		check(!store.audit(restored.getResourceId()).get(0).getDetail().contains("hidden"), "audit secret not masked");
	}

	private static void schemaIsAdditiveAndUsesSupportedTypes() throws Exception
	{
		final String configured = System.getProperty("ebx.connectors.moduleDir", ".");
		final Path schema = Paths.get(configured, "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd");
		final String xml = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
		check(xml.contains("name=\"ConnectorOperationalMetrics\""), "metrics table absent");
		check(xml.contains("name=\"ConnectorGovernance\""), "governance table absent");
		check(xml.contains("name=\"ConnectorGovernanceAudit\""), "audit table absent");
		check(xml.contains("name=\"metricCounter\""), "counter compatibility type absent");
		check(!xml.contains("xs:long"), "unsupported xs:long reintroduced");
	}

	private static final class MetricBackend implements EbxConnectorOperationalMetricsPersistence.Backend
	{
		private final Map<String, EbxConnectorOperationalMetricsPersistence.Row> rows = new LinkedHashMap<String, EbxConnectorOperationalMetricsPersistence.Row>();
		@Override public EbxConnectorOperationalMetricsPersistence.Row load(final String pId) { return rows.get(pId); }
		@Override public void save(final EbxConnectorOperationalMetricsPersistence.Row pRow) { rows.put(pRow.connectorId, pRow); }
	}
	private static final class GovernanceBackend implements EbxConnectorGovernanceStore.Backend
	{
		private final Map<String, EbxConnectorGovernanceStore.GovernanceRow> records = new LinkedHashMap<String, EbxConnectorGovernanceStore.GovernanceRow>();
		private final List<EbxConnectorGovernanceStore.AuditRow> audits = new ArrayList<EbxConnectorGovernanceStore.AuditRow>();
		@Override public EbxConnectorGovernanceStore.GovernanceRow find(final String pId) { return records.get(pId); }
		@Override public List<EbxConnectorGovernanceStore.GovernanceRow> list() { return new ArrayList<EbxConnectorGovernanceStore.GovernanceRow>(records.values()); }
		@Override public void save(final EbxConnectorGovernanceStore.GovernanceRow pRow) { records.put(pRow.resourceId, pRow); }
		@Override public void append(final EbxConnectorGovernanceStore.AuditRow pRow) { audits.add(pRow); }
		@Override public List<EbxConnectorGovernanceStore.AuditRow> audit(final String pId) {
			final List<EbxConnectorGovernanceStore.AuditRow> out = new ArrayList<EbxConnectorGovernanceStore.AuditRow>();
			for (final EbxConnectorGovernanceStore.AuditRow row : audits) if (pId.equals(row.resourceId)) out.add(row); return out;
		}
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
