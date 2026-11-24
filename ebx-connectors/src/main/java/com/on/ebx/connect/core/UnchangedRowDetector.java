package com.on.ebx.connect.core;

/**
 * Volume lot 5, task 3: the pure comparison behind a feed's {@code unchangedIfEqual} property. A
 * mapping names one target field (typically a hash/version field such as {@code ./_rowHash}); when
 * the incoming value computed for that field equals the value already on the record, the whole row
 * is reported {@code NO_CHANGE} and nothing is written - no {@code doModifyContent}, no provenance,
 * no extra sync-log line beyond the one every item already gets. This class only decides "are these
 * two XS-string values the same", so it is unit tested without an EBX record; the EBX-aware wrapper
 * that reads the current value and the mapped value is {@link MappingEngine#unchanged}.
 */
final class UnchangedRowDetector
{
	private UnchangedRowDetector()
	{
	}

	/**
	 * @param pCurrentXsString the record's current value at the field, formatted as an XS string
	 *        ({@code SchemaNode.formatToXsString}), or {@code null} when the field is empty
	 * @param pIncomingXsString the value the mapping computed for that field, or {@code null} when no
	 *        rule of the mapping produced one
	 * @return whether the two are the same value for this purpose. EBX stores an empty string as null
	 *         (see the CLAUDE.md XPath note on {@code osd:is-null}), so null and "" both mean "no
	 *         value" here and compare equal to each other
	 */
	static boolean sameValue(final String pCurrentXsString, final String pIncomingXsString)
	{
		final String current = pCurrentXsString == null ? "" : pCurrentXsString;
		final String incoming = pIncomingXsString == null ? "" : pIncomingXsString;
		return current.equals(incoming);
	}
}
