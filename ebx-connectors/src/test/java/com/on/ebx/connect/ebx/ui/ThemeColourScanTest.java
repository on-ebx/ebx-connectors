package com.on.ebx.connect.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The screens follow the colours of the perspective (audit C-40): the module's own accent colours (the purple of the
 * catalog and connection forms, the blue of the run, mapping and log screens) may only appear as the fallback of a
 * {@code var(--ebx-...)} or {@code var(--ftc-...)}, and every user service and record form pane writes the theme tokens.
 * The light blue of the information messages is the one exception, allowed in the rules listed here only.
 */
public final class ThemeColourScanTest
{
	/** Accent colours of the module before C-40: never a semantic colour. */
	private static final List<String> BRAND = Arrays.asList("#7137d8", "#5b21b6", "#4c1d95", "#35206e", "#59447a", "#f3effc", "#faf8ff", "#f8f6ff",
		"#fbfaff", "#f6f3ff", "#f4f0fa", "#eee9fa", "#f3efff", "#e8e0f7", "#e4e0ee", "#ddd6fe", "#cfc3ff", "#b9a6e8", "#cbb7ef", "#cdc3ee", "#9f7aea",
		"#1a73e8", "#1765cc");
	/** The blue of an information message (and of the conflict manager's "take the incoming value"): the accent elsewhere, kept as is in these rules only. */
	private static final List<String> INFO_BLUE = Arrays.asList("#e8f0fe", "#c6dafc", "#174ea6", "#0b5394");
	private static final List<String> INFO_RULES = Arrays.asList(".ftroInfo", ".ftmdMsg", ".ftcsBanner", ".ftcsNotice", ".ftmdType.ftmdTFk", ".ftoa .msg",
		".ftcfTake");
	private static final Pattern COLOUR = Pattern.compile("(?<![&\\w])#[0-9a-fA-F]{6}\\b");
	private static final Pattern FALLBACK = Pattern.compile("var\\(--(?:ebx|ftc)-[a-z-]+,\\s*$");

	public static void main(final String[] pArgs) throws IOException
	{
		final Path module = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final List<Path> files = new ArrayList<Path>();
		files.addAll(list(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui"), ".java"));
		files.addAll(list(module.resolve("src/main/webapp/www/common/stylesheets/connect"), ".css"));
		files.addAll(list(module.resolve("src/main/webapp/www/common/jscripts/connect"), ".js"));
		check(files.size() > 20, "the screens were found under " + module.toAbsolutePath());

		final List<String> failures = new ArrayList<String>();
		for (final Path file : files)
		{
			if (file.getFileName().toString().equals("ThemeTokens.java"))
			{
				continue;
			}
			final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final Matcher colour = COLOUR.matcher(text);
			while (colour.find())
			{
				final String value = colour.group().toLowerCase(Locale.ROOT);
				final boolean brand = BRAND.contains(value);
				final boolean info = INFO_BLUE.contains(value);
				if (!brand && !info)
				{
					continue;
				}
				final String before = text.substring(Math.max(0, colour.start() - 60), colour.start());
				if (FALLBACK.matcher(before).find())
				{
					continue;
				}
				if (info && ThemeColourScanTest.inInfoRule(text, colour.start()))
				{
					continue;
				}
				failures.add(file.getFileName() + ": " + value + " outside a var() fallback near \"" + text.substring(Math.max(0, colour.start() - 50), colour.start()).trim() + "\"");
			}
		}
		check(failures.isEmpty(), "accent colours not themed:\n  " + String.join("\n  ", failures));

		// every pane of the module writes the theme tokens
		for (final Path file : list(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui"), ".java"))
		{
			final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final int panes = count(text, "public void writePane(");
			final int written = count(text, "ThemeTokens.write(pWriter, ");
			check(panes == written, file.getFileName() + ": " + panes + " writePane, " + written + " ThemeTokens.write");
		}
		System.out.println("ThemeColourScanTest: OK (" + files.size() + " files)");
	}

	/** Whether the colour sits in a rule whose selector is one of the information messages. */
	private static boolean inInfoRule(final String pText, final int pAt)
	{
		final int open = pText.lastIndexOf('{', pAt);
		if (open < 0)
		{
			return false;
		}
		final int start = Math.max(Math.max(pText.lastIndexOf('}', open), pText.lastIndexOf('"', open)), 0);
		final String selector = pText.substring(start, open + 1);
		for (final String rule : INFO_RULES)
		{
			if (selector.contains(rule + "{") || selector.contains(rule + ",") || selector.contains(rule + " "))
			{
				return true;
			}
		}
		return false;
	}

	private static int count(final String pText, final String pNeedle)
	{
		int n = 0;
		for (int i = pText.indexOf(pNeedle); i >= 0; i = pText.indexOf(pNeedle, i + 1))
		{
			n++;
		}
		return n;
	}

	private static List<Path> list(final Path pDirectory, final String pSuffix) throws IOException
	{
		try (Stream<Path> paths = Files.list(pDirectory))
		{
			return paths.filter(p -> p.toString().endsWith(pSuffix)).sorted().collect(Collectors.toList());
		}
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
