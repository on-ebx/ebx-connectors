package com.on.ebx.connect.spi;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable catalog metadata for one named connector operation. */
public final class ConnectorOperationDescriptor
{
	private final String name;
	private final Operation.Kind kind;
	private final String description;
	private final Set<ConnectorDirection> directions;
	private final Set<ConnectorCapability> capabilities;
	private final Set<SynchronizationStrategy> synchronizationStrategies;

	/**
	 * Creates operation metadata with full-refresh synchronization support only.
	 * This constructor is retained for source compatibility with existing connector descriptors.
	 */
	public ConnectorOperationDescriptor(final String pName, final Operation.Kind pKind,
		final String pDescription, final Set<ConnectorDirection> pDirections,
		final Set<ConnectorCapability> pCapabilities)
	{
		this(pName, pKind, pDescription, pDirections, pCapabilities,
			Collections.singleton(SynchronizationStrategy.FULL));
	}

	/** Creates operation metadata with an explicit immutable set of synchronization strategies. */
	public ConnectorOperationDescriptor(final String pName, final Operation.Kind pKind,
		final String pDescription, final Set<ConnectorDirection> pDirections,
		final Set<ConnectorCapability> pCapabilities,
		final Set<SynchronizationStrategy> pSynchronizationStrategies)
	{
		this.name = requireText(pName, "name");
		this.kind = Objects.requireNonNull(pKind, "kind");
		this.description = pDescription == null ? null : pDescription.trim();
		this.directions = immutableEnumSet(pDirections, ConnectorDirection.class);
		this.capabilities = immutableEnumSet(pCapabilities, ConnectorCapability.class);
		this.synchronizationStrategies = pSynchronizationStrategies == null || pSynchronizationStrategies.isEmpty()
			? Collections.singleton(SynchronizationStrategy.FULL)
			: immutableEnumSet(pSynchronizationStrategies, SynchronizationStrategy.class);
	}

	public String getName() { return this.name; }
	public Operation.Kind getKind() { return this.kind; }
	public String getDescription() { return this.description; }
	public Set<ConnectorDirection> getDirections() { return this.directions; }
	public Set<ConnectorCapability> getCapabilities() { return this.capabilities; }
	public Set<SynchronizationStrategy> getSynchronizationStrategies() { return this.synchronizationStrategies; }

	private static String requireText(final String pValue, final String pName)
	{
		if (pValue == null || pValue.trim().isEmpty()) throw new IllegalArgumentException(pName + " must not be blank");
		return pValue.trim();
	}

	private static <E extends Enum<E>> Set<E> immutableEnumSet(final Set<E> pValues, final Class<E> pType)
	{
		return pValues == null || pValues.isEmpty() ? Collections.<E>emptySet()
			: Collections.unmodifiableSet(EnumSet.copyOf(pValues));
	}
}
