package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable type-level metadata used by catalogs and configuration tooling.
 * It contains no configured endpoint or secret and is therefore safe to cache.
 */
public final class ConnectorDescriptor
{
	private final String type;
	private final String label;
	private final String description;
	private final String category;
	private final List<ConnectorOperationDescriptor> operations;
	private final List<ConfigurationPropertyDefinition> configurationProperties;
	private final Set<ConnectorDirection> directions;
	private final Set<ConnectorCapability> capabilities;
	private final Set<AuthenticationMethod> authenticationMethods;
	private final List<ConnectorDependency> dependencies;
	private final List<String> apiVersions;
	private final PaginationDescriptor pagination;
	private final ConnectorDefaults defaults;
	private final ConnectorAvailability availability;
	private final String availabilityMessage;
	private final boolean deprecated;
	private final String deprecationMessage;
	private final String replacementType;

	private ConnectorDescriptor(final Builder pBuilder)
	{
		this.type = requireText(pBuilder.type, "type");
		this.label = requireText(pBuilder.label, "label");
		this.description = trimToNull(pBuilder.description);
		this.category = requireText(pBuilder.category, "category");
		this.operations = immutableList(pBuilder.operations);
		this.configurationProperties = immutableList(pBuilder.configurationProperties);
		this.directions = immutableEnumSet(pBuilder.directions);
		this.capabilities = immutableEnumSet(pBuilder.capabilities);
		this.authenticationMethods = immutableEnumSet(pBuilder.authenticationMethods);
		this.dependencies = immutableList(pBuilder.dependencies);
		this.apiVersions = immutableList(pBuilder.apiVersions);
		this.pagination = pBuilder.pagination;
		this.defaults = pBuilder.defaults;
		this.availability = pBuilder.availability;
		this.availabilityMessage = trimToNull(pBuilder.availabilityMessage);
		this.deprecated = pBuilder.deprecated || this.availability == ConnectorAvailability.DEPRECATED;
		this.deprecationMessage = trimToNull(pBuilder.deprecationMessage);
		this.replacementType = trimToNull(pBuilder.replacementType);
	}

	public static Builder builder(final String pType, final String pLabel, final String pCategory)
	{
		return new Builder(pType, pLabel, pCategory);
	}

	public String getType() { return this.type; }
	public String getLabel() { return this.label; }
	public String getDescription() { return this.description; }
	public String getCategory() { return this.category; }
	public List<ConnectorOperationDescriptor> getOperations() { return this.operations; }
	public List<ConfigurationPropertyDefinition> getConfigurationProperties() { return this.configurationProperties; }
	public Set<ConnectorDirection> getDirections() { return this.directions; }
	public Set<ConnectorCapability> getCapabilities() { return this.capabilities; }
	public Set<AuthenticationMethod> getAuthenticationMethods() { return this.authenticationMethods; }
	public List<ConnectorDependency> getDependencies() { return this.dependencies; }
	public List<String> getApiVersions() { return this.apiVersions; }
	public PaginationDescriptor getPagination() { return this.pagination; }
	public ConnectorDefaults getDefaults() { return this.defaults; }
	public ConnectorAvailability getAvailability() { return this.availability; }
	public String getAvailabilityMessage() { return this.availabilityMessage; }
	public boolean isDeprecated() { return this.deprecated; }
	public String getDeprecationMessage() { return this.deprecationMessage; }
	public String getReplacementType() { return this.replacementType; }

	public static final class Builder
	{
		private final String type;
		private final String label;
		private final String category;
		private String description;
		private final List<ConnectorOperationDescriptor> operations = new ArrayList<ConnectorOperationDescriptor>();
		private final List<ConfigurationPropertyDefinition> configurationProperties = new ArrayList<ConfigurationPropertyDefinition>();
		private final Set<ConnectorDirection> directions = EnumSet.noneOf(ConnectorDirection.class);
		private final Set<ConnectorCapability> capabilities = EnumSet.noneOf(ConnectorCapability.class);
		private final Set<AuthenticationMethod> authenticationMethods = EnumSet.noneOf(AuthenticationMethod.class);
		private final List<ConnectorDependency> dependencies = new ArrayList<ConnectorDependency>();
		private final Set<String> apiVersions = new LinkedHashSet<String>();
		private PaginationDescriptor pagination = PaginationDescriptor.none();
		private ConnectorDefaults defaults = ConnectorDefaults.standard();
		private ConnectorAvailability availability = ConnectorAvailability.AVAILABLE;
		private String availabilityMessage;
		private boolean deprecated;
		private String deprecationMessage;
		private String replacementType;

		private Builder(final String pType, final String pLabel, final String pCategory)
		{
			this.type = pType;
			this.label = pLabel;
			this.category = pCategory;
		}

		public Builder description(final String pValue)
		{
			this.description = pValue;
			return this;
		}
		public Builder operation(final ConnectorOperationDescriptor pValue)
		{
			if (pValue != null) this.operations.add(pValue);
			return this;
		}
		public Builder configurationProperty(final ConfigurationPropertyDefinition pValue)
		{
			if (pValue != null) this.configurationProperties.add(pValue);
			return this;
		}
		public Builder direction(final ConnectorDirection pValue)
		{
			if (pValue != null) this.directions.add(pValue);
			return this;
		}
		public Builder capability(final ConnectorCapability pValue)
		{
			if (pValue != null) this.capabilities.add(pValue);
			return this;
		}
		public Builder authentication(final AuthenticationMethod pValue)
		{
			if (pValue != null) this.authenticationMethods.add(pValue);
			return this;
		}
		public Builder dependency(final ConnectorDependency pValue)
		{
			if (pValue != null) this.dependencies.add(pValue);
			return this;
		}
		public Builder apiVersion(final String pValue)
		{
			final String value = trimToNull(pValue);
			if (value != null) this.apiVersions.add(value);
			return this;
		}
		public Builder pagination(final PaginationDescriptor pValue)
		{
			if (pValue != null) this.pagination = pValue;
			return this;
		}
		public Builder defaults(final ConnectorDefaults pValue)
		{
			if (pValue != null) this.defaults = pValue;
			return this;
		}
		public Builder availability(final ConnectorAvailability pValue, final String pMessage)
		{
			if (pValue != null) this.availability = pValue;
			this.availabilityMessage = pMessage;
			return this;
		}
		public Builder deprecated(final String pMessage, final String pReplacementType)
		{
			this.deprecated = true;
			this.deprecationMessage = pMessage;
			this.replacementType = pReplacementType;
			return this;
		}
		public ConnectorDescriptor build() { return new ConnectorDescriptor(this); }
	}

	private static String requireText(final String pValue, final String pName)
	{
		final String value = trimToNull(pValue);
		if (value == null) throw new IllegalArgumentException(pName + " must not be blank");
		return value;
	}

	private static String trimToNull(final String pValue) { return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim(); }
	private static <T> List<T> immutableList(final java.util.Collection<T> pValues) { return Collections.unmodifiableList(new ArrayList<T>(pValues)); }
	private static <E extends Enum<E>> Set<E> immutableEnumSet(final Set<E> pValues)
	{
		return pValues.isEmpty() ? Collections.<E>emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(pValues));
	}
}
