package com.on.ebx.connect.ebx.ui;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Order and date of the releases on the release-notes page. Pure rules, tested offline ({@code ReleaseOrderTest}).
 * <p>
 * Until 1.5.0 the page sorted and printed the TEXT of the date ({@code String.valueOf(java.util.Date)}): "Thu Nov 12 00:00:00
 * CET 2026" sorts after "Mon Nov 23 00:00:00 CET 2026", so the releases of a Thursday stood above a newer one of a Monday, and
 * that raw text was what the reader saw.
 */
final class ReleaseOrder
{
	private ReleaseOrder() { }

	/**
	 * Newest first: the dated releases by date, then by version number, both descending; the entries without a date (the
	 * development increments) after them, by identifier descending (their identifier carries their day).
	 */
	static int compare(final Date pDateA, final String pVersionA, final Date pDateB, final String pVersionB)
	{
		final String versionA = pVersionA == null ? "" : pVersionA;
		final String versionB = pVersionB == null ? "" : pVersionB;
		if (pDateA == null || pDateB == null)
		{
			if (pDateA != null) return -1;
			if (pDateB != null) return 1;
			return versionB.compareTo(versionA);
		}
		final int byDate = pDateB.compareTo(pDateA);
		return byDate != 0 ? byDate : ReleaseOrder.compareVersions(versionB, versionA);
	}

	/** {@code 1.10.0} is after {@code 1.9.0}: each part is compared as a number when both are numbers, as text otherwise. */
	static int compareVersions(final String pA, final String pB)
	{
		final String[] a = pA.split("\\.", -1);
		final String[] b = pB.split("\\.", -1);
		for (int i = 0; i < Math.max(a.length, b.length); i++)
		{
			final String partA = i < a.length ? a[i] : "0";
			final String partB = i < b.length ? b[i] : "0";
			final boolean numbers = partA.matches("[0-9]{1,9}") && partB.matches("[0-9]{1,9}");
			final int difference = numbers ? Integer.compare(Integer.parseInt(partA), Integer.parseInt(partB)) : partA.compareTo(partB);
			if (difference != 0) return difference;
		}
		return 0;
	}

	/** The date as a reader of {@code pLocale} writes it: "November 23, 2026", "23 novembre 2026". */
	static String dateLabel(final Date pDate, final Locale pLocale)
	{
		return pDate == null ? "" : DateFormat.getDateInstance(DateFormat.LONG, pLocale == null ? Locale.ENGLISH : pLocale).format(pDate);
	}

	/** {@code 2026-11-23}, for the {@code datetime} attribute of the page's {@code time} element. */
	static String isoDate(final Date pDate)
	{
		return pDate == null ? "" : new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(pDate);
	}
}
