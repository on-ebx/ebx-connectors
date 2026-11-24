package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Immutable, typed definition of one connector configuration property.
 *
 * <p>Definitions describe configuration without depending on EBX or a particular UI. Use
 * {@link #builder(String, ConfigurationPropertyType)} to create one.</p>
 */
public final class ConfigurationPropertyDefinition
{
	private final String name;
	private final String label;
	private final String description;
	private final ConfigurationPropertyType type;
	private final boolean required;
	private final boolean secret;
	private final boolean advanced;
	private final Object defaultValue;
	private final List<String> enumerationValues;
	private final ConfigurationPropertyVisibility visibility;
	private final java.util.Map<String, String> deprecatedValues;

	private ConfigurationPropertyDefinition(final Builder pBuilder)
	{
		this.name = requiredText(pBuilder.name, "name");
		this.label = requiredText(pBuilder.label == null ? pBuilder.name : pBuilder.label, "label");
		this.description = pBuilder.description;
		if (pBuilder.type == null)
		{
			throw new IllegalArgumentException("type is required");
		}
		this.type = pBuilder.type;
		this.required = pBuilder.required;
		this.secret = pBuilder.secret || pBuilder.type == ConfigurationPropertyType.SECRET;
		this.advanced = pBuilder.advanced;
		this.defaultValue = ConfigurationPropertyDefinition.validateDefault(pBuilder.type, pBuilder.defaultValue);
		this.enumerationValues = immutableDistinct(pBuilder.enumerationValues);
		this.visibility = pBuilder.visibility;
		this.deprecatedValues = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, String>(pBuilder.deprecatedValues));
		if (!this.enumerationValues.containsAll(this.deprecatedValues.keySet()))
		{
			throw new IllegalArgumentException("A deprecated value must be one of the enumeration values");
		}

		if (this.type == ConfigurationPropertyType.ENUMERATION && this.enumerationValues.isEmpty())
		{
			throw new IllegalArgumentException("An enumeration property requires at least one value");
		}
		if (this.type != ConfigurationPropertyType.ENUMERATION && !this.enumerationValues.isEmpty())
		{
			throw new IllegalArgumentException("Enumeration values are only valid for ENUMERATION properties");
		}
		if (this.type == ConfigurationPropertyType.ENUMERATION && this.defaultValue != null
			&& !this.enumerationValues.contains(this.defaultValue))
		{
			throw new IllegalArgumentException("The default value must be one of the enumeration values");
		}
	}

	public static Builder builder(final String pName, final ConfigurationPropertyType pType)
	{
		return new Builder(pName, pType);
	}

	public String getName() { return this.name; }
	public String getLabel() { return this.label; }
	public String getDescription() { return this.description; }
	public ConfigurationPropertyType getType() { return this.type; }
	public boolean isRequired() { return this.required; }
	public boolean isSecret() { return this.secret; }
	public boolean isAdvanced() { return this.advanced; }
	public Object getDefaultValue() { return this.defaultValue; }
	public List<String> getEnumerationValues() { return this.enumerationValues; }
	public ConfigurationPropertyVisibility getVisibility() { return this.visibility; }

	/** Why a value still accepted should no longer be used (and what replaces it), or null when it is not deprecated. */
	public String getDeprecationMessage(final String pValue)
	{
		return pValue == null ? null : this.deprecatedValues.get(pValue.trim());
	}

	/** Returns true when this property has no condition or its condition matches. */
	public boolean isVisible(final java.util.Map<String, ?> pConfiguration)
	{
		return this.visibility == null || this.visibility.isVisible(pConfiguration);
	}

	public static final class Builder
	{
		private final String name;
		private final ConfigurationPropertyType type;
		private String label;
		private String description;
		private boolean required;
		private boolean secret;
		private boolean advanced;
		private Object defaultValue;
		private final List<String> enumerationValues = new ArrayList<String>();
		private ConfigurationPropertyVisibility visibility;
		private final java.util.Map<String, String> deprecatedValues = new java.util.LinkedHashMap<String, String>();

		private Builder(final String pName, final ConfigurationPropertyType pType)
		{
			this.name = pName;
			this.type = pType;
		}

		public Builder label(final String pLabel)
		{
			this.label = pLabel;
			return this;
		}
		public Builder description(final String pDescription)
		{
			this.description = pDescription;
			return this;
		}
		public Builder required(final boolean pRequired)
		{
			this.required = pRequired;
			return this;
		}
		public Builder secret(final boolean pSecret)
		{
			this.secret = pSecret;
			return this;
		}
		public Builder advanced(final boolean pAdvanced)
		{
			this.advanced = pAdvanced;
			return this;
		}
		public Builder defaultValue(final Object pDefaultValue)
		{
			this.defaultValue = pDefaultValue;
			return this;
		}

		public Builder enumerationValues(final String... pValues)
		{
			if (pValues == null)
			{
				throw new IllegalArgumentException("enumerationValues are required");
			}
			for (final String value : pValues)
			{
				this.enumerationValues.add(requiredText(value, "enumerationValue"));
			}
			return this;
		}

		public Builder visibleWhen(final ConfigurationPropertyVisibility pVisibility)
		{
			this.visibility = pVisibility;
			return this;
		}

		/**
		 * Marks an enumeration value as deprecated: it still validates and works, and the validator
		 * reports {@code pMessage} as a warning (never as a blocking issue).
		 */
		public Builder deprecatedValue(final String pValue, final String pMessage)
		{
			this.deprecatedValues.put(requiredText(pValue, "deprecatedValue"), requiredText(pMessage, "deprecationMessage"));
			return this;
		}

		public ConfigurationPropertyDefinition build()
		{
			return new ConfigurationPropertyDefinition(this);
		}
	}

	private static Object validateDefault(final ConfigurationPropertyType pType, final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pType == ConfigurationPropertyType.BOOLEAN && !(pValue instanceof Boolean))
		{
			throw new IllegalArgumentException("A BOOLEAN default value must be a Boolean");
		}
		if (pType == ConfigurationPropertyType.INTEGER && !(pValue instanceof Byte || pValue instanceof Short
			|| pValue instanceof Integer || pValue instanceof Long))
		{
			throw new IllegalArgumentException("An INTEGER default value must be an integer Number");
		}
		if (pType != ConfigurationPropertyType.BOOLEAN && pType != ConfigurationPropertyType.INTEGER
			&& !(pValue instanceof String))
		{
			throw new IllegalArgumentException(pType + " default values must be Strings");
		}
		return pValue;
	}

	private static List<String> immutableDistinct(final List<String> pValues)
	{
		return Collections.unmodifiableList(new ArrayList<String>(new LinkedHashSet<String>(pValues)));
	}

	private static String requiredText(final String pValue, final String pName)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			throw new IllegalArgumentException(pName + " is required");
		}
		return pValue;
	}
}
