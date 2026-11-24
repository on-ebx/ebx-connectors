package com.on.ebx.connect.ebx.ui;

/**
 * {@code isAcceptedDataUrl}/{@code kindLabel}/{@code fileNameOf} are plain string logic (no EBX
 * context needed), so they are exercised directly here rather than through the wider
 * grep-the-source style the rest of this package's tests use. Covers the {@code name} parameter
 * introduced by the sample-file-name lot (2026-09-23): an older stored value with none must keep
 * working exactly as before. {@link SampleDataUrlTest} in {@code core} covers the shared parser's own
 * grammar in depth; this test only checks the widget's own consumption of it.
 */
public final class UploadedSampleFileWidgetFactoryTest
{
	private UploadedSampleFileWidgetFactoryTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// Legacy stored value, no name parameter: today's behaviour, unchanged.
		final String legacy = "data:text/csv;base64,aWQ7bmFtZQ==";
		check(UploadedSampleFileWidgetFactory.isAcceptedDataUrl(legacy), "a legacy csv data URL (no name) must still be accepted");
		check("CSV".equals(UploadedSampleFileWidgetFactory.kindLabel(legacy)), "legacy csv kind label");
		check(UploadedSampleFileWidgetFactory.fileNameOf(legacy) == null, "a legacy data URL carries no file name");

		// A named upload of every accepted kind: accepted, kind and name both recovered.
		final String namedJson = "data:application/json;name=Personnes_SAP_Sample.json;base64,W10=";
		check(UploadedSampleFileWidgetFactory.isAcceptedDataUrl(namedJson), "a named json data URL must be accepted");
		check("JSON".equals(UploadedSampleFileWidgetFactory.kindLabel(namedJson)), "named json kind label");
		check("Personnes_SAP_Sample.json".equals(UploadedSampleFileWidgetFactory.fileNameOf(namedJson)), "named json file name must round-trip");

		final String namedTsv = "data:text/tab-separated-values;name=export.tsv;base64,aWQ=";
		check(UploadedSampleFileWidgetFactory.isAcceptedDataUrl(namedTsv), "a named tsv data URL must be accepted");
		check("TSV".equals(UploadedSampleFileWidgetFactory.kindLabel(namedTsv)), "named tsv kind label");

		final String namedNdjson = "data:application/x-ndjson;name=events.ndjson;base64,e30=";
		check("NDJSON".equals(UploadedSampleFileWidgetFactory.kindLabel(namedNdjson)), "named ndjson kind label");

		// Parameter order must not affect acceptance or kind.
		final String reordered = "data:text/csv;base64;name=reordered.csv,aWQ=";
		check(UploadedSampleFileWidgetFactory.isAcceptedDataUrl(reordered), "base64-before-name order must still be accepted");
		check("reordered.csv".equals(UploadedSampleFileWidgetFactory.fileNameOf(reordered)), "base64-before-name order must still recover the name");

		// A name that sanitizes away to nothing (only a path separator) counts as no name, not a rejected sample.
		final String pathOnly = "data:text/csv;name=%2F;base64,aWQ=";
		check(UploadedSampleFileWidgetFactory.isAcceptedDataUrl(pathOnly), "a data URL is still accepted even if its name sanitizes to nothing");
		check(UploadedSampleFileWidgetFactory.fileNameOf(pathOnly) == null, "a name that sanitizes to empty must read back as no name");

		// Refusals: unsupported MIME, null, empty payload.
		check(!UploadedSampleFileWidgetFactory.isAcceptedDataUrl("data:text/plain;base64,aGVsbG8="), "an unsupported MIME must be refused");
		check(!UploadedSampleFileWidgetFactory.isAcceptedDataUrl(null), "a null value must be refused");
		check(!UploadedSampleFileWidgetFactory.isAcceptedDataUrl("data:text/csv;base64,"), "an empty payload must be refused");
		check("".equals(UploadedSampleFileWidgetFactory.kindLabel(null)), "kindLabel of null must be empty, not throw");
		check(UploadedSampleFileWidgetFactory.fileNameOf(null) == null, "fileNameOf of null must be null, not throw");

		System.out.println("PASS: sample upload widget tolerates the name parameter (legacy, named, reordered, sanitized, refusals)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}
}
