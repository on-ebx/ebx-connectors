package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The order and the date of the release-notes page. The page used to sort and print the text of a java.util.Date: with the
 * releases of this test it listed 1.4.2, 1.4.1, 1.4.0 (a Thursday) above 1.5.0 (a Monday eleven days later).
 */
public final class ReleaseOrderTest
{
	private ReleaseOrderTest() { }

	public static void main(final String[] pArgs)
	{
		final Date november12 = day(2026, Calendar.NOVEMBER, 12);
		final Date november23 = day(2026, Calendar.NOVEMBER, 23);
		check(String.valueOf(november12).compareTo(String.valueOf(november23)) > 0,
			"the premise: as text, Thursday 12 November sorts after Monday 23 November (" + november12 + " / " + november23 + ")");

		final List<Object[]> releases = new ArrayList<Object[]>();
		releases.add(new Object[] { november12, "1.4.0" });
		releases.add(new Object[] { null, "development-preview-2026-09-09-pass10" });
		releases.add(new Object[] { november23, "1.5.0" });
		releases.add(new Object[] { november12, "1.4.2" });
		releases.add(new Object[] { null, "development-preview-2026-11-02-documents-dama-vision" });
		releases.add(new Object[] { november12, "1.4.1" });
		Collections.sort(releases, (a, b) -> ReleaseOrder.compare((Date) a[0], (String) a[1], (Date) b[0], (String) b[1]));
		final StringBuilder order = new StringBuilder();
		for (final Object[] release : releases) order.append(release[1]).append(' ');
		check("1.5.0 1.4.2 1.4.1 1.4.0 development-preview-2026-11-02-documents-dama-vision development-preview-2026-09-09-pass10 ".equals(order.toString()),
			"newest first, then the development increments: " + order);

		check(ReleaseOrder.compare(november12, "1.10.0", november12, "1.9.0") < 0, "on the same day 1.10.0 stands above 1.9.0");
		check(ReleaseOrder.compareVersions("1.10.0", "1.9.0") > 0 && ReleaseOrder.compareVersions("1.4", "1.4.0") == 0
			&& ReleaseOrder.compareVersions("2.0.0", "10.0.0") < 0, "version parts are compared as numbers");
		check(ReleaseOrder.compare(null, null, null, null) == 0 && ReleaseOrder.compare(november12, null, null, "x") < 0, "absent values do not break the order");

		check("2026-11-23".equals(ReleaseOrder.isoDate(november23)) && ReleaseOrder.isoDate(null).isEmpty(), "ISO date: " + ReleaseOrder.isoDate(november23));
		final String english = ReleaseOrder.dateLabel(november23, Locale.ENGLISH);
		final String french = ReleaseOrder.dateLabel(november23, Locale.FRENCH);
		check(english.contains("November") && english.contains("23") && english.contains("2026") && !english.contains(":"), "English date: " + english);
		check(french.contains("novembre") && french.contains("23") && french.contains("2026") && !french.contains(":"), "French date: " + french);
		check(ReleaseOrder.dateLabel(null, Locale.ENGLISH).isEmpty() && !ReleaseOrder.dateLabel(november23, null).isEmpty(), "absent date or locale");
		System.out.println("PASS: the release-notes page lists the newest release first and writes its date as a date (" + english + " / " + french + ")");
	}

	private static Date day(final int pYear, final int pMonth, final int pDay)
	{
		final Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(pYear, pMonth, pDay);
		return calendar.getTime();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
