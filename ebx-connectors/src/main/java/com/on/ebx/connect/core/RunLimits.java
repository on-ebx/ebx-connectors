package com.on.ebx.connect.core;

import java.util.List;

/**
 * The default of {@code max} for each family of entry points, in one place (audit C-35), and the
 * sentences a run says when it is cut by it or when it drops an input.
 * <p>
 * {@code max} bounds what ONE run takes: the records of a table or of an outbound scope, or the items a
 * feed imports. It is never a silent cut: a run that stops at it adds a "limit reached" notice to its
 * results ({@link SyncResult#isLimitNotice()}), its answer says {@code truncated: true}, and its status is
 * never a plain success. The defaults differ because the callers differ, not by accident:
 * <ul>
 * <li>{@link #SCHEDULER}: an unattended run fails on a cut, so a small batch keeps a backlog moving in
 * steps an operator can follow;</li>
 * <li>{@link #API}: an MCP or REST call or a workflow step holds its answer in memory;</li>
 * <li>{@link #SCREEN_RECORDS} and {@link #SCREEN_FEED}: what the synchronisation screen proposes; a feed
 * imports one item per column for a profile or per table for a catalog scan, hence a higher start;</li>
 * <li>{@link #WEBHOOK}: a posted document is already whole in memory;</li>
 * <li>{@link #PUBLICATION}: 0, every row - a data product is published whole or the run is PARTIAL.</li>
 * </ul>
 * A connector's own {@code max.rows} property (1000 for a JDBC source) is a different thing: what the
 * connector returns when it is called without {@code max} ({@code connectors_call}, a preview). The engine
 * always hands its {@code max} to the connector when it runs a feed.
 */
public final class RunLimits
{
	/** {@code ConnectorScheduledTask.maxRecords}. */
	public static final int SCHEDULER = 100;
	/** {@code connectors_sync} (MCP and in-JVM bean), {@code POST /sync/{mapping}}, the workflow bean's {@code maxRecords}. */
	public static final int API = 200;
	/** The synchronisation screen, on records or an outbound scope. */
	public static final int SCREEN_RECORDS = 100;
	/** The synchronisation screen, on a feed. */
	public static final int SCREEN_FEED = 499;
	/** {@code POST /webhook/{feed}}: default and ceiling. */
	public static final int WEBHOOK = 10000;
	/** {@code connectors_publish}, {@code POST /publish}, the publication bean: 0 means every row. */
	public static final int PUBLICATION = 0;

	private RunLimits()
	{
	}

	/**
	 * What a run on records or on an outbound scope says when it stopped at {@code pMax} with more left.
	 *
	 * @param pDrains true for a backlog that the records processed leave (outbound scopes unsent, changed and
	 *            failed): a later run takes the rest. False when a run starts again from the first record.
	 */
	public static String recordsLimitMessage(final int pMax, final String pWhat, final boolean pDrains)
	{
		return "Limit reached: " + pMax + " record(s) of " + pWhat + " were taken (max=" + pMax + ") and more remain: "
			+ (pDrains ? "a later run takes the rest, or raise max" : "raise max to process the others");
	}

	/** What a run says about run-time inputs it dropped because the mapping does not read them as {@code ${param.name}}. */
	public static String ignoredInputsMessage(final List<String> pIgnored)
	{
		return "Run-time input(s) ignored because the mapping does not read them as ${param.name}: " + String.join(", ", pIgnored)
			+ " - the run used the mapping's own inputs";
	}

	/**
	 * The status an answer shows: {@code WARNING} in place of a plain {@code OK} when the run had something to
	 * say (an input it ignored); every other status is left as it is, since it already says more than OK.
	 */
	public static String answerStatus(final String pStatus, final boolean pWarning)
	{
		return pWarning && "OK".equals(pStatus) ? "WARNING" : pStatus;
	}
}
