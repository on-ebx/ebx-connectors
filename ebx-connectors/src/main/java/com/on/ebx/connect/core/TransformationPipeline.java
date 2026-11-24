package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Volume lot 5, task 4: one parsed {@code Transformations.apply} pipeline - the steps in order, each
 * a transformation name and its already-split, already-unquoted arguments. Parsing a pipeline string
 * (splitting on {@code |} respecting parentheses, then splitting each step's arguments respecting
 * quotes) is pure text work with no EBX type involved, and a rule's pipeline string is the same on
 * every row of a run; {@link TransformationRunCache} caches the parse per pipeline string instead of
 * redoing it per row. {@link Transformations#splitPipeline}, {@link Transformations#splitArgs} and
 * {@link Transformations#unquote} are shared with this class rather than duplicated, so a cached
 * parse behaves exactly like the uncached one it replaces.
 */
final class TransformationPipeline
{
	/** One step: a transformation name (already lower-cased) and its arguments. */
	static final class Step
	{
		private final String name;
		private final List<String> args;

		Step(final String pName, final List<String> pArgs)
		{
			this.name = pName;
			this.args = pArgs;
		}

		String getName()
		{
			return this.name;
		}

		List<String> getArgs()
		{
			return this.args;
		}
	}

	private final List<Step> steps;

	private TransformationPipeline(final List<Step> pSteps)
	{
		this.steps = pSteps;
	}

	List<Step> getSteps()
	{
		return this.steps;
	}

	static TransformationPipeline parse(final String pPipeline)
	{
		final List<Step> steps = new ArrayList<Step>();
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return new TransformationPipeline(Collections.<Step>emptyList());
		}
		for (final String step : Transformations.splitPipeline(pPipeline))
		{
			final String trimmed = step.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final int open = trimmed.indexOf('(');
			final String name = (open < 0 ? trimmed : trimmed.substring(0, open)).trim().toLowerCase(Locale.ROOT);
			final List<String> args = new ArrayList<String>();
			if (open >= 0)
			{
				final int close = trimmed.lastIndexOf(')');
				final String inner = trimmed.substring(open + 1, close < open ? trimmed.length() : close);
				for (final String arg : Transformations.splitArgs(inner))
				{
					args.add(Transformations.unquote(arg));
				}
			}
			steps.add(new Step(name, Collections.unmodifiableList(args)));
		}
		return new TransformationPipeline(Collections.unmodifiableList(steps));
	}
}
