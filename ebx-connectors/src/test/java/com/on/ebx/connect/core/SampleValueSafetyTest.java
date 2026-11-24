package com.on.ebx.connect.core;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.ValueContextForUpdate;

/** Offline synthetic-only tests of the staging/reporting boundary. */
public final class SampleValueSafetyTest
{
    private static final String MARKER = "synthetic-sensitive-marker@example.invalid";
    private static MappingDefinition mapping(String table)
    {
        MappingDefinition m = new MappingDefinition(1, "fixture", "catalog.sample", MappingDefinition.Direction.INBOUND,
            "fixture", "BDataCatalog", "DataCatalog", table, "", true);
        m.addRule(new MappingRule(1, "values", "./values", "", MappingRule.Policy.ALWAYS, ""));
        return m;
    }
    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception
    {
        MappingDefinition mapping = mapping("/root/ColumnSample");
        MappingEngine.Values values = MappingEngine.compute(mapping, Json.object().put("values", MARKER), null);
        check(SampleValueSafety.OMITTED.equals(values.getByPath().get("./values")), "dry-run map must omit sample");
        check(SampleValueSafety.OMITTED.equals(values.get("values")), "public lookup must omit sample");
        SchemaNode field = (SchemaNode) Proxy.newProxyInstance(SchemaNode.class.getClassLoader(), new Class<?>[]{SchemaNode.class},
            (p, m, a) -> { if (m.getName().equals("isTerminalValue")) return true; throw new UnsupportedOperationException(m.getName()); });
        SchemaNode table = (SchemaNode) Proxy.newProxyInstance(SchemaNode.class.getClassLoader(), new Class<?>[]{SchemaNode.class},
            (p, m, a) -> { if (m.getName().equals("getNode")) return field; throw new UnsupportedOperationException(m.getName()); });
        AtomicReference<String> staged = new AtomicReference<>();
        ValueContextForUpdate update = (ValueContextForUpdate) Proxy.newProxyInstance(ValueContextForUpdate.class.getClassLoader(),
            new Class<?>[]{ValueContextForUpdate.class}, (p, m, a) -> {
                if (m.getName().equals("setValueFromXsString")) { staged.set((String)a[0]); return null; }
                throw new UnsupportedOperationException(m.getName());
            });
        MappingResult result = MappingEngine.applyValues(values, null, update, table);
        check(MARKER.equals(staged.get()), "raw value must still reach classification staging");
        check(result.getChanges().size() == 1, "retain accurate change count");
        check(!result.getChanges().toString().contains(MARKER), "change text must omit sample");
        check(SampleValueSafety.OMITTED.equals(result.getChanges().get(0).getNewValue()), "REST change value must omit sample");
        check(SampleValueSafety.OMITTED.equals(SampleValueSafety.reportValue("/root/ColumnSample", "./values", MARKER)), "provenance guard");
        ValueContextForUpdate rejecting = (ValueContextForUpdate) Proxy.newProxyInstance(ValueContextForUpdate.class.getClassLoader(),
            new Class<?>[]{ValueContextForUpdate.class}, (p, m, a) -> { throw new IllegalArgumentException(MARKER); });
        MappingResult rejected = MappingEngine.applyValues(values, null, rejecting, table);
        check(!rejected.getErrors().isEmpty() && !rejected.getErrors().toString().contains(MARKER), "quoted exception must not leak");
        check(!SampleValueSafety.safeFailure(mapping, MARKER).contains(MARKER), "trigger/provider exception guard");
        MappingEngine.Values ordinary = MappingEngine.compute(mapping("/root/Ordinary"), Json.object().put("values", MARKER), null);
        check(MARKER.equals(ordinary.getByPath().get("./values")), "ordinary business values are unaffected");
        check(MARKER.equals(SampleValueSafety.reportValue("/root/ColumnSample", "./column", MARKER)), "scope limited to sample field");
        check(SampleValueSafety.reportValue("/root/ColumnSample", "./values", null) == null, "null remains null");
        System.out.println("SampleValueSafetyTest PASS (synthetic staging, changes, dry-run, provenance guard, errors and scope)");
    }
}
