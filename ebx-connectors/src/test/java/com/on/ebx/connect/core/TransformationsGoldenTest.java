package com.on.ebx.connect.core;

import com.onwbp.adaptation.Adaptation;

/**
 * Offline checks of the {@code golden}/{@code mdmState} transformations that do not require an EBX repository or the Match and Merge add-on: registration, the
 * blank/absent-context guard (real code, exercised through the public {@link Transformations#apply}
 * exactly as a mapping rule would call it), and the {@link TransformationContext} seam a test uses to
 * inject a fake {@link GoldenRecords} instead of the real, add-on-backed one.
 */
public final class TransformationsGoldenTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testBothAreRegistered();
		testBlankOrMissingValueIsNull();
		testNoContextIsNull();
		testContextInjectsAndDefaultsGoldenRecords();
		System.out.println("PASS: golden/mdmState registration, blank guard, GoldenRecords injection seam");
	}

	private static void testBothAreRegistered()
	{
		check(Transformations.names().contains("golden"), "golden is registered: " + Transformations.names());
		check(Transformations.names().contains("mdmstate"), "mdmState is registered (as 'mdmstate'): " + Transformations.names());
	}

	/** Blank/absent value short-circuits before the context (even a null one) is ever touched. */
	private static void testBlankOrMissingValueIsNull() throws Exception
	{
		check(Transformations.apply("golden(/root/Companies)", "", null) == null, "golden('') -> null, no context needed");
		check(Transformations.apply("golden(/root/Companies)", "   ", null) == null, "golden(blank) -> null");
		check(Transformations.apply("golden(/root/Companies)", null, null) == null, "golden(null) -> null");
		check(Transformations.apply("mdmstate(/root/Companies)", "", null) == null, "mdmState('') -> null, no context needed");
		check(Transformations.apply("mdmstate(/root/Companies)", null, null) == null, "mdmState(null) -> null");
	}

	/** A non-blank value with no context (or a context with no dataset) is refused the same way
	 * {@code fk}/{@code lookup} are: no EBX call is attempted. */
	private static void testNoContextIsNull() throws Exception
	{
		check(Transformations.apply("golden(/root/Companies)", "123", null) == null, "golden('123') with no context -> null");
		check(Transformations.apply("mdmstate(/root/Companies)", "123", null) == null, "mdmState('123') with no context -> null");
	}

	/** {@link TransformationContext#getGoldenRecords()} answers an injected fake, and falls back to the
	 * real {@link GoldenRecords#get()} singleton (the same instance every time) once the fake is cleared. */
	private static void testContextInjectsAndDefaultsGoldenRecords()
	{
		final TransformationContext context = new TransformationContext(null, null, null);
		final GoldenRecords real = context.getGoldenRecords();
		check(real != null, "defaults to a non-null GoldenRecords (GoldenRecords.get())");
		check(real == context.getGoldenRecords(), "GoldenRecords.get() is memoized: the same instance every call");

		final GoldenRecords fake = new GoldenRecords()
		{
			@Override
			public String state(final Adaptation pRecord)
			{
				return "GOLDEN";
			}

			@Override
			public Adaptation golden(final Adaptation pRecord)
			{
				return pRecord;
			}
		};
		context.setGoldenRecords(fake);
		check(context.getGoldenRecords() == fake, "the injected fake is returned once set");
		check("GOLDEN".equals(context.getGoldenRecords().state(null)), "the fake's own logic runs, not the real add-on lookup");

		context.setGoldenRecords(null);
		check(context.getGoldenRecords() == real, "clearing the fake restores the real GoldenRecords.get() singleton");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
