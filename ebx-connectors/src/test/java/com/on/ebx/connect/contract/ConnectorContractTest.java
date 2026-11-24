package com.on.ebx.connect.contract;

import java.util.HashSet;
	import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.Connector;
	import com.on.ebx.connect.spi.ConnectorDescriptor;
	import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
	import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
	import com.on.ebx.connect.spi.ConfigurationPropertyType;
	import com.on.ebx.connect.spi.ConfigurationPropertyVisibility;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.Operation;

/**
 * Dependency-free smoke contracts for every connector visible to the registry.
 * Run through build-test.sh; deliberately performs no connector execution or network I/O.
 */
public final class ConnectorContractTest
{
	private ConnectorContractTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final Map<String, Class<? extends Connector>> registered = ConnectorRegistry.get().types();
		check(!registered.isEmpty(), "The connector registry is empty");

		final Set<String> typeIds = new HashSet<String>();
		int operationCount = 0;
		for (final Map.Entry<String, Class<? extends Connector>> entry : registered.entrySet())
		{
			final Connector connector = entry.getValue().newInstance();
			final String type = nonBlank(connector.getType(), entry.getValue().getName() + " type");
			configureDynamicProbe(connector, type);
			check(type.equals(entry.getKey()), "Registry key does not match connector type: " + entry.getKey());
			check(typeIds.add(type), "Duplicate connector type: " + type);
			nonBlank(connector.getLabel(), type + " label");

			final List<Operation> operations = connector.getOperations();
			check(operations != null, type + " returned null operations");
			check(!operations.isEmpty(), type + " declares no operations");
			final Set<String> operationNames = new HashSet<String>();
			for (final Operation operation : operations)
			{
				check(operation != null, type + " declares a null operation");
				final String operationName = nonBlank(operation.getName(), type + " operation name");
				check(operationNames.add(operationName), type + " has duplicate operation " + operationName);
				check(operation.getKind() != null, type + "/" + operationName + " has no kind");
				check(operation.getRequiredInputs() != null,
					type + "/" + operationName + " returned null required inputs");
				check(operation.getOptionalInputs() != null,
					type + "/" + operationName + " returned null optional inputs");
				operationCount++;
			}
			validateDescriptor(connector, operations, type);
		}
		validateTypedPropertyModel();

		System.out.println("PASS: " + registered.size() + " connector types and " + operationCount
			+ " operation declarations satisfy the baseline contracts");
	}

	private static void validateDescriptor(final Connector pConnector, final List<Operation> pOperations,
		final String pType)
	{
		try
		{
			check(pConnector.getClass().getMethod("getDescriptor").getDeclaringClass() != Connector.class,
				pType + " relies on the generic compatibility descriptor");
		}
		catch (final NoSuchMethodException ex)
		{
			throw new AssertionError(pType + " has no descriptor method", ex);
		}
		final ConnectorDescriptor descriptor = pConnector.getDescriptor();
		check(descriptor != null, pType + " returned a null descriptor");
		check(pType.equals(descriptor.getType()), pType + " descriptor type does not match");
		check(pConnector.getLabel().equals(descriptor.getLabel()), pType + " descriptor label does not match");
		nonBlank(descriptor.getCategory(), pType + " descriptor category");
		check(descriptor.getOperations() != null, pType + " descriptor returned null operations");
		check(descriptor.getOperations().size() == pOperations.size(), pType + " descriptor operation count differs");
		check(descriptor.getConfigurationProperties() != null, pType + " descriptor returned null properties");
		check(descriptor.getDirections() != null, pType + " descriptor returned null directions");
		check(descriptor.getCapabilities() != null, pType + " descriptor returned null capabilities");
		check(descriptor.getAuthenticationMethods() != null, pType + " descriptor returned null authentication methods");
		check(descriptor.getDependencies() != null, pType + " descriptor returned null dependencies");
		check(descriptor.getApiVersions() != null, pType + " descriptor returned null API versions");

		final Set<String> names = new HashSet<String>();
		for (int index = 0; index < descriptor.getOperations().size(); index++)
		{
			final ConnectorOperationDescriptor described = descriptor.getOperations().get(index);
			final Operation operation = pOperations.get(index);
			check(names.add(nonBlank(described.getName(), pType + " descriptor operation name")),
				pType + " descriptor has a duplicate operation " + described.getName());
			check(operation.getName().equals(described.getName()), pType + " descriptor operation name differs");
			check(operation.getKind() == described.getKind(), pType + " descriptor operation kind differs");
			check(described.getDirections() != null, pType + " descriptor operation returned null directions");
			check(described.getCapabilities() != null, pType + " descriptor operation returned null capabilities");
		}

		final Set<String> propertyNames = new HashSet<String>();
		for (final ConfigurationPropertyDefinition property : descriptor.getConfigurationProperties())
		{
			check(property != null, pType + " descriptor contains a null property");
			check(propertyNames.add(nonBlank(property.getName(), pType + " property name")),
				pType + " descriptor has duplicate property " + property.getName());
		}
	}

	private static void validateTypedPropertyModel()
	{
		final ConfigurationPropertyDefinition auth = ConfigurationPropertyDefinition.builder(
			"authentication", ConfigurationPropertyType.ENUMERATION)
			.enumerationValues("none", "oauth2").defaultValue("none").required(true).build();
		final ConfigurationPropertyDefinition secret = ConfigurationPropertyDefinition.builder(
			"clientSecret", ConfigurationPropertyType.SECRET)
			.visibleWhen(ConfigurationPropertyVisibility.equalTo("authentication", "oauth2")).build();
		final Map<String, Object> values = new HashMap<String, Object>();
		values.put("authentication", "none");
		check(auth.isVisible(values), "Unconditional typed property is hidden");
		check(!secret.isVisible(values), "Conditional typed property is visible for the wrong mode");
		values.put("authentication", "oauth2");
		check(secret.isVisible(values), "Conditional typed property is hidden for the matching mode");
		check(secret.isSecret(), "SECRET property is not marked secret");
		try
		{
			auth.getEnumerationValues().add("invalid");
			throw new AssertionError("Enumeration values are mutable");
		}
		catch (final UnsupportedOperationException expected)
		{
			// Immutable by contract.
		}
	}

	/** Generic protocol connectors intentionally derive operations from instance properties. */
	private static void configureDynamicProbe(final Connector pConnector, final String pType)
	{
		if (!"rest".equals(pType) && !"soap".equals(pType) && !"graphql".equals(pType))
		{
			return;
		}
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("contract-probe");
		config.setType(pType);
		config.setBaseUrl("https://example.invalid");
		if ("soap".equals(pType))
		{
			config.setProperty("op.contractProbe.body", "<GetObject><id>{id}</id></GetObject>");
		}
		else if ("graphql".equals(pType))
		{
			config.setProperty("op.contractProbe.query", "query ContractProbe($id: ID!) { object(id: $id) { id } }");
		}
		else
		{
			config.setProperty("op.contractProbe.method", "GET");
			config.setProperty("op.contractProbe.path", "/objects/{id}");
		}
		pConnector.configure(config);
	}

	private static String nonBlank(final String pValue, final String pName)
	{
		check(pValue != null && !pValue.trim().isEmpty(), pName + " is blank");
		return pValue.trim();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
