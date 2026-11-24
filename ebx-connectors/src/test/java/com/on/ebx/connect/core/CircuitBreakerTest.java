package com.on.ebx.connect.core;

/**
 * Pure counter behind the volume lot 6 batched import's circuit breaker: it must trip only after N
 * CONSECUTIVE fully-failed batches, reset on any batch that is not a full failure, and never un-trip
 * itself once tripped. No EBX type involved - see {@link CircuitBreaker}.
 */
public final class CircuitBreakerTest
{
	private CircuitBreakerTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// trips exactly at the threshold, not before
		final CircuitBreaker three = new CircuitBreaker(3);
		check(!three.isTripped(), "a fresh breaker must not be tripped");
		three.record(true);
		check(!three.isTripped(), "one failure out of three must not trip it");
		check(three.getConsecutiveFailures() == 1, "one failure must be counted");
		three.record(true);
		check(!three.isTripped(), "two failures out of three must not trip it");
		three.record(true);
		check(three.isTripped(), "the third consecutive failure must trip it");
		check(three.getConsecutiveFailures() == 3, "three consecutive failures must be counted");

		// a non-failing batch resets the streak
		final CircuitBreaker resets = new CircuitBreaker(3);
		resets.record(true);
		resets.record(true);
		resets.record(false); // a batch with at least one success, or nothing to write
		check(!resets.isTripped(), "a reset streak must not trip the breaker");
		check(resets.getConsecutiveFailures() == 0, "a successful batch must reset the streak to zero");
		resets.record(true);
		resets.record(true);
		check(!resets.isTripped(), "two failures after a reset must not trip a threshold-3 breaker");
		resets.record(true);
		check(resets.isTripped(), "three consecutive failures after a reset must trip it");

		// once tripped, stays tripped even if later batches would otherwise reset the streak
		final CircuitBreaker sticky = new CircuitBreaker(2);
		sticky.record(true);
		sticky.record(true);
		check(sticky.isTripped(), "two consecutive failures on a threshold-2 breaker must trip it");
		sticky.record(false);
		check(sticky.isTripped(), "a tripped breaker must never un-trip itself");

		// a threshold of 1 trips on the very first failure
		final CircuitBreaker one = new CircuitBreaker(1);
		check(!one.isTripped(), "a fresh threshold-1 breaker must not be tripped before any batch");
		one.record(true);
		check(one.isTripped(), "a threshold-1 breaker must trip on the first failure");

		// an invalid threshold is rejected rather than silently misbehaving
		boolean rejected = false;
		try { new CircuitBreaker(0); } catch (final IllegalArgumentException ex) { rejected = true; }
		check(rejected, "a threshold below 1 must be rejected");

		System.out.println("PASS: CircuitBreaker trips only on N consecutive full failures, resets on any success, and never un-trips");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
