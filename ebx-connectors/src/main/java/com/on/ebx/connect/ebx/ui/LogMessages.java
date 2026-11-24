package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.LogMessageCodes;

/**
 * What a screen shows of the engine's stored log sentences (audit C-37): the common ones in the reader's language through
 * {@link LogMessageCodes}, everything else as stored. The stored text is never changed; a screen that localises a sentence keeps the
 * stored one in a tooltip.
 */
final class LogMessages
{
	private LogMessages()
	{
	}

	/** The sentence in the reader's language, null when the stored text is not one of the common ones. */
	static String localize(final Locale pLocale, final String pStored)
	{
		final LogMessageCodes.Coded coded = LogMessageCodes.decode(pStored);
		return coded == null ? null : Messages.get(LogMessages.class, pLocale, coded.key(), (Object[]) coded.getArgs());
	}

	/** The status in words: the same labels the synchronisation screen uses; an unknown status as it is. */
	static String status(final Locale pLocale, final String pStatus)
	{
		if (pStatus == null || pStatus.isEmpty())
		{
			return "–";
		}
		final String key = "status." + pStatus;
		final String text = Messages.get(LogMessages.class, pLocale, key);
		return key.equals(text) ? pStatus : text;
	}
}
