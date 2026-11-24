package com.on.ebx.connect.core;

/** Pure comparison behind the volume lot 5 {@code unchangedIfEqual} skip - see {@link UnchangedRowDetector}. */
public final class UnchangedRowDetectorTest
{
	private UnchangedRowDetectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		check(UnchangedRowDetector.sameValue("abc123", "abc123"), "identical values must be reported the same");
		check(!UnchangedRowDetector.sameValue("abc123", "def456"), "different values must be reported different");
		check(UnchangedRowDetector.sameValue(null, null), "null and null must be the same (both mean no value)");
		check(UnchangedRowDetector.sameValue(null, ""), "null and empty string must be the same, matching EBX's own null<->empty convention");
		check(UnchangedRowDetector.sameValue("", null), "empty string and null must be the same either way round");
		check(UnchangedRowDetector.sameValue("", ""), "empty string and empty string must be the same");
		check(!UnchangedRowDetector.sameValue(null, "x"), "null against a real value must be different");
		check(!UnchangedRowDetector.sameValue("x", null), "a real value against null must be different");
		check(!UnchangedRowDetector.sameValue("X", "x"), "the comparison must be case-sensitive: a hash is not");

		System.out.println("PASS: UnchangedRowDetector treats null and empty string as equal and is otherwise an exact match");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
