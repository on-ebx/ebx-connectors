package com.on.ebx.connect.spi;

/** Lifecycle and runtime availability of a connector type. */
public enum ConnectorAvailability
{
	AVAILABLE,
	EXPERIMENTAL,
	OPTIONAL_DEPENDENCY_REQUIRED,
	UNAVAILABLE,
	DEPRECATED
}
