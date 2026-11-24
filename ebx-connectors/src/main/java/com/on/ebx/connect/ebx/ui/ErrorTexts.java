package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.ErrorCatalog;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.core.SampleValueSafety;

/**
 * A failure as a screen tells it (audit C-38): what happened and what to do next, in the reader's language, from the
 * {@link ErrorCatalog}, and under it the technical detail (the redacted message, never a stack, nothing of a mapping on
 * ephemeral samples). The cause and the action are fixed text of the bundles, so they carry no secret and no record value
 * whatever the failure quoted.
 */
final class ErrorTexts
{
	/** Styles of {@link #html}; a screen that shows it adds this once. */
	static final String STYLE = "<style>.ftErr .ftErrCause{font-weight:600}.ftErr .ftErrAction{margin:2px 0 0}"
		+ ".ftErr details{margin:6px 0 0;font-size:12px;color:#5f6368}.ftErr summary{cursor:pointer}"
		+ ".ftErr summary:focus-visible{outline:2px solid var(--ebx-focus,#1a73e8);outline-offset:2px}"
		+ ".ftErr .ftErrDetail{margin:4px 0 0;white-space:pre-wrap;overflow-wrap:anywhere;font-family:monospace}"
		+ ".ftErrHint{margin:4px 0 0;font-size:12px;color:#5f6368}</style>";

	private final String cause;
	private final String action;
	private final String detail;
	private final boolean known;

	private ErrorTexts(final String pCause, final String pAction, final String pDetail, final boolean pKnown)
	{
		this.cause = pCause;
		this.action = pAction;
		this.detail = pDetail;
		this.known = pKnown;
	}

	/** The failure of an exception: the catalogue entry of its chain, and its redacted message as the detail. */
	static ErrorTexts of(final Locale pLocale, final Throwable pFailure, final MappingDefinition pMapping)
	{
		return ErrorTexts.build(pLocale, ErrorCatalog.classify(pFailure), SampleValueSafety.safeFailure(pMapping, Redaction.failure(pFailure)));
	}

	/** The failure behind a text already stored or produced by the engine (a log message, the message of a run). */
	static ErrorTexts of(final Locale pLocale, final String pMessage, final MappingDefinition pMapping)
	{
		final String detail = pMessage == null ? "" : SampleValueSafety.safeFailure(pMapping, Redaction.redact(pMessage));
		return ErrorTexts.build(pLocale, ErrorCatalog.classify(pMessage), detail);
	}

	/**
	 * The catalogue's cause and action for a stored failure text, on one line for a table cell; null when the text is not one
	 * of the frequent failures. Reads the text only to classify it: nothing of it is repeated.
	 */
	static String hint(final Locale pLocale, final String pMessage)
	{
		final ErrorCatalog.Entry entry = ErrorCatalog.classify(pMessage);
		return entry.isKnown() ? ErrorTexts.text(pLocale, entry.causeKey(), entry.getProperties()) + " " + ErrorTexts.text(pLocale, entry.actionKey(), entry.getProperties()) : null;
	}

	private static ErrorTexts build(final Locale pLocale, final ErrorCatalog.Entry pEntry, final String pDetail)
	{
		final String properties = pEntry.getProperties();
		return new ErrorTexts(ErrorTexts.text(pLocale, pEntry.causeKey(), properties), ErrorTexts.text(pLocale, pEntry.actionKey(), properties),
			pDetail == null ? "" : pDetail, pEntry.isKnown());
	}

	private static String text(final Locale pLocale, final String pKey, final String pProperties)
	{
		return Messages.get(ErrorTexts.class, pLocale, pKey, pProperties);
	}

	String getCause()
	{
		return this.cause;
	}

	String getAction()
	{
		return this.action;
	}

	String getDetail()
	{
		return this.detail;
	}

	boolean isKnown()
	{
		return this.known;
	}

	/** Cause and action on one line, for a table cell; null when the failure is not one the catalogue knows. */
	String inline()
	{
		return this.known ? this.cause + " " + this.action : null;
	}

	/** The block a screen shows: cause, next action, and the technical detail folded away. {@code pLocale} labels the detail. */
	String html(final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder("<div class=\"ftErr\"><div class=\"ftErrCause\">").append(ErrorTexts.escape(this.cause)).append("</div>");
		out.append("<div class=\"ftErrAction\">").append(ErrorTexts.escape(this.action)).append("</div>");
		if (!this.detail.isEmpty())
		{
			out.append("<details").append(this.known ? "" : " open").append("><summary>").append(ErrorTexts.escape(Messages.get(ErrorTexts.class, pLocale, "error.detail")))
				.append("</summary><div class=\"ftErrDetail\">").append(ErrorTexts.escape(this.detail)).append("</div></details>");
		}
		return out.append("</div>").toString();
	}

	static String escape(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
