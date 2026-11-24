package com.on.ebx.connect.spi;

/** Optional behaviors advertised by a connector type. */
public enum ConnectorCapability
{
	TEST_CONNECTION,
	DISCOVER_OBJECTS,
	DESCRIBE_OBJECT,
	PREVIEW_RECORDS,
	INCREMENTAL_READ,
	BULK_READ,
	BULK_WRITE,
	STREAMING,
	SCHEMA_DISCOVERY,
	TRANSACTIONS
}
