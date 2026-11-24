package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable condition controlling whether a configuration property is visible.
 *
 * <p>Authentication mode is deliberately represented like any other controlling property. For
 * example, {@code equalTo("authentication", "oauth2")} displays an OAuth field only when OAuth
 * is selected. Missing values never match equality or membership conditions.</p>
 */
public final class ConfigurationPropertyVisibility
{
	public enum Operator
	{
		EQUALS,
		NOT_EQUALS,
		IN,
		PRESENT,
		ABSENT
	}

	private final String propertyName;
	private final Operator operator;
	private final List<String> values;

	private ConfigurationPropertyVisibility(final String pPropertyName, final Operator pOperator,
		final List<String> pValues)
	{
		this.propertyName = ConfigurationPropertyVisibility.required(pPropertyName, "propertyName");
		if (pOperator == null)
		{
			throw new IllegalArgumentException("operator is required");
		}
		this.operator = pOperator;
		this.values = Collections.unmodifiableList(new ArrayList<String>(pValues));
		if ((pOperator == Operator.EQUALS || pOperator == Operator.NOT_EQUALS) && this.values.size() != 1)
		{
			throw new IllegalArgumentException(pOperator + " requires exactly one value");
		}
		if (pOperator == Operator.IN && this.values.isEmpty())
		{
			throw new IllegalArgumentException("IN requires at least one value");
		}
		if ((pOperator == Operator.PRESENT || pOperator == Operator.ABSENT) && !this.values.isEmpty())
		{
			throw new IllegalArgumentException(pOperator + " does not accept values");
		}
	}

	public static ConfigurationPropertyVisibility equalTo(final String pPropertyName, final String pValue)
	{
		return new ConfigurationPropertyVisibility(pPropertyName, Operator.EQUALS,
			Collections.singletonList(ConfigurationPropertyVisibility.required(pValue, "value")));
	}

	public static ConfigurationPropertyVisibility notEqualTo(final String pPropertyName, final String pValue)
	{
		return new ConfigurationPropertyVisibility(pPropertyName, Operator.NOT_EQUALS,
			Collections.singletonList(ConfigurationPropertyVisibility.required(pValue, "value")));
	}

	public static ConfigurationPropertyVisibility in(final String pPropertyName, final String... pValues)
	{
		if (pValues == null)
		{
			throw new IllegalArgumentException("values are required");
		}
		final List<String> values = new ArrayList<String>(pValues.length);
		for (final String value : pValues)
		{
			values.add(ConfigurationPropertyVisibility.required(value, "value"));
		}
		return new ConfigurationPropertyVisibility(pPropertyName, Operator.IN, values);
	}

	public static ConfigurationPropertyVisibility present(final String pPropertyName)
	{
		return new ConfigurationPropertyVisibility(pPropertyName, Operator.PRESENT, Collections.<String>emptyList());
	}

	public static ConfigurationPropertyVisibility absent(final String pPropertyName)
	{
		return new ConfigurationPropertyVisibility(pPropertyName, Operator.ABSENT, Collections.<String>emptyList());
	}

	public String getPropertyName()
	{
		return this.propertyName;
	}

	public Operator getOperator()
	{
		return this.operator;
	}

	public List<String> getValues()
	{
		return this.values;
	}

	/** Evaluates this condition against raw connector configuration values. */
	public boolean isVisible(final Map<String, ?> pConfiguration)
	{
		final Object raw = pConfiguration == null ? null : pConfiguration.get(this.propertyName);
		final String actual = raw == null ? null : String.valueOf(raw);
		final boolean present = actual != null && !actual.trim().isEmpty();
		switch (this.operator)
		{
			case PRESENT:
				return present;
			case ABSENT:
				return !present;
			case EQUALS:
				return present && this.values.get(0).equals(actual);
			case NOT_EQUALS:
				return present && !this.values.get(0).equals(actual);
			case IN:
				return present && this.values.contains(actual);
			default:
				throw new IllegalStateException("Unsupported visibility operator " + this.operator);
		}
	}

	private static String required(final String pValue, final String pName)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			throw new IllegalArgumentException(pName + " is required");
		}
		return pValue;
	}
}
