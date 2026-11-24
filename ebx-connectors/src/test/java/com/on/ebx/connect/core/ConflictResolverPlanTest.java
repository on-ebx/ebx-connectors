package com.on.ebx.connect.core;

/**
 * What a decision on a conflict does with the status it finds. The repository side (claim, write, close as three
 * transactions) needs EBX; this is the part that decides, and it is what makes a half-finished acceptance
 * recoverable: a conflict left {@code APPLYING} is closed when the record already holds the proposed value,
 * applied again otherwise, and is listed with the open ones so that somebody sees it.
 */
public final class ConflictResolverPlanTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// a normal acceptance: claim, write, close
		check(ConflictResolver.planAccept("OPEN", false) == ConflictResolver.Step.APPLY, "an open conflict is applied");
		// a conflict whose record changed by someone else meanwhile still carries a proposal to apply
		check(ConflictResolver.planAccept("OPEN", true) == ConflictResolver.Step.APPLY, "an open conflict is claimed even when the value is already there");

		// the acceptance that stopped after the write: only the closing is left
		check(ConflictResolver.planAccept("APPLYING", true) == ConflictResolver.Step.FINISH, "the record holds the value: close it");
		// the acceptance that stopped before the write: reopened and applied again
		check(ConflictResolver.planAccept("APPLYING", false) == ConflictResolver.Step.APPLY, "the record does not hold it: apply again");

		// decided conflicts are never touched again
		check(ConflictResolver.planAccept("ACCEPTED", true) == ConflictResolver.Step.REFUSE, "an accepted conflict is not accepted twice");
		check(ConflictResolver.planAccept("REJECTED", false) == ConflictResolver.Step.REFUSE, "a rejected conflict is not accepted");
		check(ConflictResolver.planAccept(null, false) == ConflictResolver.Step.REFUSE, "a conflict that is gone is refused");

		// rejecting
		check(ConflictResolver.planReject("OPEN", false) == ConflictResolver.Step.REJECT, "an open conflict is rejected");
		check(ConflictResolver.planReject("APPLYING", false) == ConflictResolver.Step.REJECT, "a claimed conflict whose value was never written can be rejected");
		check(ConflictResolver.planReject("APPLYING", true) == ConflictResolver.Step.REFUSE, "one whose value was written cannot be rejected: the data says accepted");
		check(ConflictResolver.planReject("ACCEPTED", false) == ConflictResolver.Step.REFUSE && ConflictResolver.planReject(null, false) == ConflictResolver.Step.REFUSE,
			"decided or missing: refused");

		// "the record holds the value": no value and an empty value are the same, case counts
		check(ConflictResolver.holds("A", "A") && !ConflictResolver.holds("A", "a"), "equal text only");
		check(ConflictResolver.holds(null, null) && ConflictResolver.holds(null, "") && ConflictResolver.holds("", null), "nothing equals nothing");
		check(!ConflictResolver.holds(null, "x") && !ConflictResolver.holds("x", null), "a value is not no value");

		// the listing of open conflicts shows the unfinished ones; any other status is exact
		final String open = ConflictResolver.statusFilter(null);
		check(open.equals("(./status='OPEN' or ./status='APPLYING')") && open.equals(ConflictResolver.statusFilter("OPEN")) && open.equals(ConflictResolver.statusFilter("  ")),
			"open lists APPLYING too: " + open);
		check(ConflictResolver.statusFilter("ACCEPTED").equals("./status='ACCEPTED'"), "accepted: " + ConflictResolver.statusFilter("ACCEPTED"));
		check(ConflictResolver.statusFilter("APPLYING").equals("./status='APPLYING'"), "applying alone: " + ConflictResolver.statusFilter("APPLYING"));
		check(ConflictResolver.statusFilter("x' or '1'='1").equals("./status='x'' or ''1''=''1'"), "a status is quoted: " + ConflictResolver.statusFilter("x' or '1'='1"));
		System.out.println("PASS: conflict decisions: claim, finish or apply again, refuse; unfinished acceptances are listed as open");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
