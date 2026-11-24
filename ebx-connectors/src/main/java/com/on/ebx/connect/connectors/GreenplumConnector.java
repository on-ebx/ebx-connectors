package com.on.ebx.connect.connectors;

import java.util.Collections;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/**
 * Greenplum profile backed by the PostgreSQL JDBC family and the shared bounded JDBC engine.
 * Greenplum retains its own catalog identity while reusing the PostgreSQL driver contract.
 */
public final class GreenplumConnector extends PostgreSqlConnector
{
	@Override public String getType() { return "greenplum"; }
	@Override public String getLabel() { return "Greenplum"; }

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Greenplum connectivity through the PostgreSQL JDBC driver with bounded discovery, preview, parameterized SQL, batching, and transactions. The shared upsert operation uses portable update-then-insert semantics; validate write behavior against the target Greenplum version before production use.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT)
			.capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE)
			.capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Greenplum via PostgreSQL JDBC 4.2; live target acceptance required")
			.dependency(new ConnectorDependency("PostgreSQL JDBC driver", "org.postgresql:postgresql:42.7.x", true,
				"Reuses the PostgreSQL JDBC driver packaged by EBX-Connectors; certify metadata and writes against the target Greenplum release"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), Collections.singleton(direction),
				Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	private static ConfigurationPropertyDefinition property(final String pName,
		final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
