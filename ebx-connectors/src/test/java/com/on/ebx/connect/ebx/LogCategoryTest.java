package com.on.ebx.connect.ebx;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Every line the module logs goes to the kernel log. The category a startup context hands a module ({@code getLoggingCategory()},
 * {@code log.wbp.<module>.main}) is at FATAL in the standard {@code ebx.properties}: an INFO, a WARN or an ERROR written there
 * appears in no file, so a failed installation was silent on a standard instance. Read from the sources: no main class asks a
 * context for that category, and the registration hands the kernel category to the installers.
 */
public final class LogCategoryTest
{
	private LogCategoryTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		if (pArgs.length == 0) throw new AssertionError("LogCategoryTest needs the main source directory");
		final List<File> files = new ArrayList<File>();
		collect(new File(pArgs[0]), files);
		check(files.size() > 200, "only " + files.size() + " java source(s) under " + pArgs[0] + " (a vacuous pass is a failure)");
		int kernel = 0;
		for (final File file : files)
		{
			final String text = new String(Files.readAllBytes(file.toPath()), "UTF-8");
			check(!text.contains("getLoggingCategory("), file.getName() + " logs through the module category of its context, which the standard"
				+ " configuration sets to FATAL: use LoggingCategory.getKernel()");
			if (text.contains("LoggingCategory.getKernel()")) kernel++;
		}
		check(kernel >= 10, "only " + kernel + " source(s) use the kernel category: the scan is not reading the module");
		final String registration = new String(
			Files.readAllBytes(new File(pArgs[0], "com/on/ebx/connect/ebx/ConnectorsModuleRegistration.java").toPath()), "UTF-8");
		check(registration.contains("LoggingCategory.getKernel();"), "ConnectorsModuleRegistration no longer takes the kernel category for the start-up lines");
		for (final String installer : new String[] { "ConnectorsInstaller.install(", "PackagedProductProfileInstaller.install(", "PerspectiveInstaller.install(" })
		{
			final int at = registration.indexOf(installer);
			check(at >= 0, "ConnectorsModuleRegistration no longer calls " + installer + "...)");
			final String call = registration.substring(at, registration.indexOf(';', at));
			check(call.endsWith("log)"), installer + "...) is not given the kernel category: " + call);
		}
		System.out.println("PASS: LogCategory (" + files.size() + " sources read, none uses the module category; " + kernel + " use the kernel category)");
	}

	private static void collect(final File pDirectory, final List<File> pOut)
	{
		final File[] children = pDirectory.listFiles();
		if (children == null) return;
		for (final File child : children)
		{
			if (child.isDirectory()) collect(child, pOut);
			else if (child.getName().endsWith(".java")) pOut.add(child);
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
