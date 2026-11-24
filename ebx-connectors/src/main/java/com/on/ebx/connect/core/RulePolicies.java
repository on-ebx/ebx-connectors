package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The write policy of the rules of a mapping as a choice made, not a default met by surprise (audit C-35).
 * <p>
 * A rule written {@code IF_EMPTY} fills an empty field and never touches it again: a feed run again after the source
 * changed updates nothing. {@code ALWAYS} overwrites at every run, which is what a feed that must follow its source
 * needs. The key fields of a feed (the fields its {@code keyPredicate} reads, the {@code childKeyFields} and the
 * {@code parentLinkField} of a sub-object mapping) stay {@code IF_EMPTY}: a run never rewrites the key of the row it found.
 * <p>
 * The MCP upsert and the configuration import keep {@code IF_EMPTY} for a rule sent without a policy (what every caller
 * so far relies on) and say so in their answer; the mapping designer asks for the policy of the rules added to a feed.
 * Pure, so the rules are tested apart from EBX.
 */
public final class RulePolicies
{
	/** The policy a rule sent without one is written with. */
	public static final String DEFAULT = MappingRule.Policy.IF_EMPTY.name();

	private static final Pattern FIELD = Pattern.compile("\\./([A-Za-z_][A-Za-z0-9_\\-]*(?:/[A-Za-z_][A-Za-z0-9_\\-]*)*)");

	private RulePolicies()
	{
	}

	/** True when a rule document carries no policy of its own (absent, null or blank). */
	public static boolean isMissing(final String pPolicy)
	{
		return pPolicy == null || pPolicy.trim().isEmpty();
	}

	/**
	 * The target fields of a mapping that stay {@code IF_EMPTY} whatever policy is chosen for its other rules, as
	 * {@code ./path}: those its key predicate reads ({@code ./Code='${code}'}), its child key fields and its parent link field.
	 */
	public static Set<String> keyTargets(final String pKeyPredicate, final List<String> pChildKeyFields, final String pParentLinkField)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pKeyPredicate != null)
		{
			final Matcher matcher = RulePolicies.FIELD.matcher(pKeyPredicate);
			while (matcher.find())
			{
				out.add("./" + matcher.group(1));
			}
		}
		if (pChildKeyFields != null)
		{
			for (final String field : pChildKeyFields)
			{
				final String normal = RulePolicies.normalTarget(field);
				if (normal != null)
				{
					out.add(normal);
				}
			}
		}
		final String link = RulePolicies.normalTarget(pParentLinkField);
		if (link != null)
		{
			out.add(link);
		}
		return out;
	}

	/** A target path as {@code ./a/b}, whether written {@code ./a/b}, {@code /a/b} or {@code a/b}; null when blank. */
	public static String normalTarget(final String pTarget)
	{
		if (pTarget == null || pTarget.trim().isEmpty())
		{
			return null;
		}
		String path = pTarget.trim();
		if (path.startsWith("./"))
		{
			path = path.substring(2);
		}
		else if (path.startsWith("/"))
		{
			path = path.substring(1);
		}
		return "./" + path;
	}

	/** The policy of a rule added with {@code pChosen} as the choice for the mapping: {@code IF_EMPTY} on a key field, the choice otherwise. */
	public static String forNewRule(final String pChosen, final String pTarget, final Set<String> pKeyTargets)
	{
		final String target = RulePolicies.normalTarget(pTarget);
		if (target != null && pKeyTargets != null && pKeyTargets.contains(target))
		{
			return RulePolicies.DEFAULT;
		}
		return RulePolicies.isMissing(pChosen) ? RulePolicies.DEFAULT : pChosen.trim().toUpperCase(Locale.ROOT);
	}

	/** What an upsert or an import wrote, rule by rule: the count per policy and the rules that took the default. */
	public static final class Tally
	{
		private final Map<String, Integer> byPolicy = new LinkedHashMap<String, Integer>();
		private int defaulted;
		private boolean inbound;

		public Tally()
		{
			for (final MappingRule.Policy policy : MappingRule.Policy.values())
			{
				this.byPolicy.put(policy.name(), Integer.valueOf(0));
			}
		}

		/**
		 * Counts one rule written: {@code pPolicy} as sent (null or blank: the default was applied), on a mapping of
		 * direction {@code pDirection}.
		 */
		public void add(final String pPolicy, final String pDirection)
		{
			final String policy = RulePolicies.isMissing(pPolicy) ? RulePolicies.DEFAULT : pPolicy.trim().toUpperCase(Locale.ROOT);
			if (RulePolicies.isMissing(pPolicy))
			{
				this.defaulted++;
			}
			final Integer before = this.byPolicy.get(policy);
			this.byPolicy.put(policy, Integer.valueOf(before == null ? 1 : before.intValue() + 1));
			this.inbound |= !"OUTBOUND".equalsIgnoreCase(pDirection == null ? "" : pDirection.trim());
		}

		/** Rules written per policy, every policy listed (0 when none). */
		public Map<String, Integer> byPolicy()
		{
			return java.util.Collections.unmodifiableMap(this.byPolicy);
		}

		/** Rules sent without a policy, written {@link RulePolicies#DEFAULT}. */
		public int defaulted()
		{
			return this.defaulted;
		}

		/**
		 * The sentence an answer adds when rules of an inbound mapping took the default; null when none did (an outbound
		 * mapping builds a document, where the policy plays no part).
		 */
		public String note()
		{
			if (this.defaulted == 0 || !this.inbound)
			{
				return null;
			}
			return this.defaulted + " rule(s) sent without a policy were written " + RulePolicies.DEFAULT
				+ ": such a rule fills an empty field only, so a run after the source changed leaves a filled field as it is. "
				+ "Send policy ALWAYS on the rules that must follow the source (keep the key fields " + RulePolicies.DEFAULT + "), or TRUSTED to arbitrate between sources.";
		}
	}
}
