package com.on.ebx.connect.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Audit X3-01: {@code -Debx.connectors.egress.privateExceptions} given a file path WITHOUT the {@code file:} prefix is read as a
 * plain list, which allows nothing and used to say nothing. The report says where each list comes from, how many entries were
 * read and how many can never match, and warns when a plain value looks like a path. A valid value is read exactly as before.
 */
public final class EgressListReportTest
{
	private static final String[] PROPERTIES = { Egress.PROPERTY, Egress.PRIVATE_EXCEPTIONS_PROPERTY, Egress.BLOCK_PRIVATE_PROPERTY };

	private EgressListReportTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String[] saved = new String[PROPERTIES.length];
		for (int i = 0; i < PROPERTIES.length; i++)
		{
			saved[i] = System.getProperty(PROPERTIES[i]);
			System.clearProperty(PROPERTIES[i]);
		}
		final Path work = Files.createTempDirectory("ebx-egress-report");
		try
		{
			final Path list = work.resolve("private.txt");
			Files.write(list, "# the local services\nlocalhost:8080\n127.0.0.1:9092\n10.1.0.0/16\n".getBytes("UTF-8"));

			// unset
			Egress.ListReport report = Egress.privateExceptionsReport();
			check("unset".equals(report.source) && report.entries == 0 && report.ignored == 0 && report.warning == null, "unset");

			// the failure of 2026-10-20: the path without file:
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, list.toString());
			report = Egress.privateExceptionsReport();
			check("value".equals(report.source), "a path without file: is a value: " + report.source);
			check(report.entries == 0 && report.ignored == 1, "zero usable entries, one ignored: " + report.entries + "/" + report.ignored);
			check(report.looksLikePath && report.warning != null && report.warning.contains("file:"), "warns that file: is expected: " + report.warning);
			System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "true");
			boolean refused = false;
			try
			{
				Egress.check("http://localhost:8080/x");
			}
			catch (final IllegalStateException ex)
			{
				refused = true;
			}
			check(refused, "the semantics did not change: the bare path still allows nothing");

			// the same with a home-relative and a Windows path
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "~/ebx/private.txt");
			check(Egress.privateExceptionsReport().looksLikePath, "~ path");
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "C:\\ebx\\private.txt");
			check(Egress.privateExceptionsReport().looksLikePath && Egress.privateExceptionsReport().ignored == 1, "drive letter path");
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "private.txt");
			// a relative name of a file that exists in the working directory
			Files.write(work.resolve("rel.txt"), "x".getBytes("UTF-8"));

			// the correct form
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "file:" + list);
			report = Egress.privateExceptionsReport();
			check("file".equals(report.source) && list.toString().equals(report.path) && report.fileReadable, "file source");
			check(report.entries == 3 && report.ignored == 0 && report.warning == null && !report.looksLikePath, "3 entries read, none ignored: " + report.entries + "/" + report.ignored);
			check(Egress.privateExceptionCount() == 3, "the same three entries count at use");
			Egress.check("http://localhost:8080/x");

			// a file that is not there
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "file:" + work.resolve("absent.txt"));
			report = Egress.privateExceptionsReport();
			check(!report.fileReadable && report.entries == 0 && report.warning != null && report.warning.contains("absent or unreadable"), "absent file warned: " + report.warning);

			// a plain list, one entry that can never match
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "localhost:8080, 10.0.0.0/8, localhost:http, [::1]:9092");
			report = Egress.privateExceptionsReport();
			check(report.entries == 3 && report.ignored == 1 && !report.looksLikePath && report.warning != null, "mixed list: " + report.entries + "/" + report.ignored);
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "localhost:8080,10.1.0.0/16");
			report = Egress.privateExceptionsReport();
			check("value".equals(report.source) && report.entries == 2 && report.ignored == 0 && report.warning == null, "a valid value is silent");

			// the allow-list gets the same treatment
			System.setProperty(Egress.PROPERTY, work.resolve("hosts.txt").toString());
			report = Egress.allowedHostsReport();
			check(report.looksLikePath && report.ignored == 1 && report.entries == 0 && report.warning.contains(Egress.PROPERTY), "allowedHosts without file: warned: " + report.warning);
			Files.write(work.resolve("hosts.txt"), "api.example.com\n*.example.org\n".getBytes("UTF-8"));
			System.setProperty(Egress.PROPERTY, "file:" + work.resolve("hosts.txt"));
			report = Egress.allowedHostsReport();
			check(report.entries == 2 && report.ignored == 0 && report.warning == null, "allowedHosts file: 2 entries");
			System.clearProperty(Egress.PROPERTY);

			Egress.logAtStartup();
		}
		finally
		{
			for (int i = 0; i < PROPERTIES.length; i++)
			{
				if (saved[i] == null)
				{
					System.clearProperty(PROPERTIES[i]);
				}
				else
				{
					System.setProperty(PROPERTIES[i], saved[i]);
				}
			}
		}
		System.out.println("PASS: X3-01 egress lists report their source, entries read and ignored, and warn when file: is missing");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
