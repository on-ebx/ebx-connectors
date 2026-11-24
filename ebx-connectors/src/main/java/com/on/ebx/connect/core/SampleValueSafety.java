package com.on.ebx.connect.core;

/** Reporting boundary for ephemeral catalog classification samples; not general PII detection. */
public final class SampleValueSafety
{
    public static final String OMITTED = "[sample values omitted]";
    private SampleValueSafety() { }

    public static boolean isSampleTable(final String table)
    {
        return "/root/ColumnSample".equals(table) || "./root/ColumnSample".equals(table);
    }

    public static boolean isSampleField(final String table, final String field)
    {
        return isSampleTable(table) && ("values".equals(field) || "./values".equals(field) || "/values".equals(field));
    }

    public static String reportValue(final String table, final String field, final String value)
    {
        return value != null && isSampleField(table, field) ? OMITTED : value;
    }

    public static boolean containsSampleMapping(final MappingDefinition mapping)
    {
        if (mapping == null) return false;
        if (isSampleTable(mapping.getTablePath())) return true;
        for (MappingDefinition child : mapping.getChildren())
            if (containsSampleMapping(child)) return true;
        return false;
    }

    /** Provider/EBX exceptions may quote the rejected sample: retain no arbitrary exception text. */
    public static String safeFailure(final MappingDefinition mapping, final String message)
    {
        return containsSampleMapping(mapping) ? "Sample processing failed; value-bearing diagnostic omitted." : message;
    }
}
