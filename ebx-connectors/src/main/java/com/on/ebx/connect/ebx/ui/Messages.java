package com.on.ebx.connect.ebx.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.onwbp.base.text.Severity;
import com.onwbp.base.text.UserMessage;

/**
 * Labels of the module, read from the {@code Messages_xx.properties} bundle next to the calling
 * class (same contract as the presales toolbox helper this module used to depend on, so that the
 * module stays self-contained and shareable).
 */
public final class Messages
{
	private Messages()
	{
	}

	public static String get(final Class<?> pClass, final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final String bundleName = pClass.getPackage().getName() + ".Messages";
		String pattern = null;
		try
		{
			pattern = ResourceBundle.getBundle(bundleName, pLocale == null ? Locale.ENGLISH : pLocale, pClass.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(bundleName, Locale.ENGLISH, pClass.getClassLoader()).getString(pKey);
			}
			catch (final MissingResourceException ex2)
			{
				return pKey;
			}
		}
		if (pArgs == null || pArgs.length == 0)
		{
			return pattern;
		}
		try
		{
			return new MessageFormat(pattern, pLocale == null ? Locale.ENGLISH : pLocale).format(pArgs);
		}
		catch (final IllegalArgumentException ex)
		{
			return pattern;
		}
	}

	public static UserMessage getInfo(final Class<?> pClass, final String pKey, final Object... pArgs)
	{
		return new Localized(pClass, pKey, pArgs, Severity.INFO);
	}

	public static UserMessage getError(final Class<?> pClass, final String pKey, final Object... pArgs)
	{
		return new Localized(pClass, pKey, pArgs, Severity.ERROR);
	}

	/** A message resolved in the locale of the user who sees it. */
	private static final class Localized extends UserMessage
	{
		private final Class<?> owner;
		private final String key;
		private final Object[] args;

		Localized(final Class<?> pOwner, final String pKey, final Object[] pArgs, final Severity pSeverity)
		{
			super(pSeverity, pArgs == null ? new Object[0] : pArgs);
			this.owner = pOwner;
			this.key = pKey;
			this.args = pArgs == null ? new Object[0] : pArgs;
		}

		@Override
		protected String lookupRawString(final Locale pLocale)
		{
			return Messages.get(this.owner, pLocale, this.key, this.args);
		}

		@Override
		protected String getRawStringOrNull(final Locale pLocale)
		{
			return Messages.get(this.owner, pLocale, this.key, this.args);
		}

		@Override
		public String getInternalId()
		{
			return this.owner.getName() + "#" + this.key;
		}

		@Override
		protected UserMessage copy(final Object[] pArgs)
		{
			return new Localized(this.owner, this.key, pArgs, this.getSeverity());
		}
	}
}
