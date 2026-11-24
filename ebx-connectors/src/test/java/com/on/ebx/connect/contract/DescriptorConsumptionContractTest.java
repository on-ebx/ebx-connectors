package com.on.ebx.connect.contract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Regression contracts for descriptor metadata and its EBX consumption boundaries. */
public final class DescriptorConsumptionContractTest
{
	private static final Set<String> CATEGORIES = new HashSet<String>(Arrays.asList(
		"AI", "BUSINESS_APPS", "CLOUD_STORAGE", "DATA_PLATFORMS", "DATABASE", "MESSAGING",
		"REFERENCE_DATA", "CORPORATE_REGISTRIES", "RISK_COMPLIANCE", "GENERIC_INTEGRATION"));

	private DescriptorConsumptionContractTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		int descriptors = 0;
		for (final Map.Entry<String, Class<? extends Connector>> entry : ConnectorRegistry.get().types().entrySet())
		{
			final Connector connector = entry.getValue().newInstance();
			configureDynamicProbe(connector, entry.getKey());
			validateDescriptor(connector.getDescriptor(), connector.getOperations());
			descriptors++;
		}
		validateEbxConsumption(Paths.get(System.getProperty("ebx.connectors.moduleDir", System.getProperty("user.dir"))));
		System.out.println("PASS: " + descriptors + " descriptors and EBX consumption boundaries verified");
	}

	private static void validateDescriptor(final ConnectorDescriptor pDescriptor, final List<Operation> pOperations)
	{
		check(CATEGORIES.contains(pDescriptor.getCategory()), pDescriptor.getType() + " uses unknown category " + pDescriptor.getCategory());
		check(pDescriptor.getOperations().size() == pOperations.size(), pDescriptor.getType() + " operation count differs");
		final Set<String> operations = new HashSet<String>();
		for (int index = 0; index < pOperations.size(); index++)
		{
			final Operation operation = pOperations.get(index);
			final ConnectorOperationDescriptor described = pDescriptor.getOperations().get(index);
			final ConnectorDirection expected = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			check(operation.getName().equals(described.getName()), pDescriptor.getType() + " operation order/name differs");
			check(operations.add(described.getName()), pDescriptor.getType() + " has duplicate operation " + described.getName());
			check(described.getDirections().size() == 1 && described.getDirections().contains(expected),
				pDescriptor.getType() + "/" + described.getName() + " must declare exactly " + expected);
		}

		final Set<String> properties = new HashSet<String>();
		for (final ConfigurationPropertyDefinition property : pDescriptor.getConfigurationProperties())
		{
			check(properties.add(property.getName()), pDescriptor.getType() + " has duplicate property " + property.getName());
			check(property.getName().equals(property.getName().trim()), pDescriptor.getType() + " has an untrimmed property name");
			check(property.getType() != null, pDescriptor.getType() + "/" + property.getName() + " has no type");
			check(property.getType() == ConfigurationPropertyType.ENUMERATION
				? !property.getEnumerationValues().isEmpty() : property.getEnumerationValues().isEmpty(),
				pDescriptor.getType() + "/" + property.getName() + " has incoherent enumeration metadata");
			if (property.getDefaultValue() != null)
			{
				check(!property.isSecret(), pDescriptor.getType() + "/" + property.getName() + " must not publish a secret default");
				check(defaultMatches(property), pDescriptor.getType() + "/" + property.getName() + " default/type mismatch");
			}
			if (property.getType() == ConfigurationPropertyType.SECRET)
				check(property.isSecret(), pDescriptor.getType() + "/" + property.getName() + " SECRET is not secret");
		}
	}

	private static boolean defaultMatches(final ConfigurationPropertyDefinition pProperty)
	{
		final Object value = pProperty.getDefaultValue();
		if (pProperty.getType() == ConfigurationPropertyType.BOOLEAN) return value instanceof Boolean;
		if (pProperty.getType() == ConfigurationPropertyType.INTEGER) return value instanceof Number;
		if (!(value instanceof String)) return false;
		return pProperty.getType() != ConfigurationPropertyType.ENUMERATION
			|| pProperty.getEnumerationValues().contains(value);
	}

	private static void validateEbxConsumption(final Path pModule) throws Exception
	{
		final String installer = read(pModule.resolve("src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java"));
		final int descriptorLookup = installer.indexOf("describeType(pType).getDescriptor()");
		final int fallback = installer.indexOf("return ConnectorsInstaller.legacyDefaultCategory(pType)");
		check(descriptorLookup >= 0 && fallback > descriptorLookup,
			"Installer category resolution must consult the descriptor before compatibility fallback");
		check(installer.contains("private static String legacyDefaultCategory"),
			"Installer compatibility category fallback was removed");
		check(installer.contains("DescriptorConfiguration.category(descriptor)"),
			"Installer does not consume normalized descriptor category metadata");
		check(installer.contains("DescriptorConfiguration.trustLevel("),
			"Installer does not consume descriptor runtime defaults");

		final String form = read(pModule.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorForm.java"));
		check(form.contains("describeType(type).getDescriptor()"), "Custom form does not resolve connector descriptors");
		check(form.contains("DescriptorConfiguration.visibleProperties(pDescriptor, pValues)"),
			"Custom form does not use the shared descriptor visibility model");
		check(form.contains("property.isSecret()"), "Custom form does not identify secret descriptor properties");
		check(form.contains("pValues.containsKey(property.getName())"), "Custom form does not report configured state safely");
		check(!form.contains("pValues.get(property.getName())"), "Custom form must not read stored properties for HTML output");
		check(!form.contains("getString(ConnectorsPaths.Connector.PASSWORD)"), "Custom form must not read the stored password");
		check(!form.contains("getString(ConnectorsPaths.Connector.API_KEY)"), "Custom form must not read the stored API key");
		check(form.contains("isPostgreSql(pRecord)"), "Custom form does not provide PostgreSQL-specific setup guidance");
		check(form.contains("jdbc:postgresql://host:5432/database"), "PostgreSQL JDBC URL guidance is missing");
		check(form.contains("ftcdAction=preview"), "Custom form does not expose bounded object discovery");
		check(form.contains("connectorForm.connectDiscover"), "PostgreSQL connect-and-discover action is missing");
		final String diagnostics = read(pModule.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorDiagnosticsUserService.java"));
		check(diagnostics.contains("ConnectorDiagnosticsAdapter.connectAndDiscover"),
			"Connect action is not wired to safe test-then-discovery behavior");
		check(form.contains("ConnectorConnectionStateService.State state"), "Custom form does not render governed connection state");
		check(form.contains("connectorForm.verifiedAt"), "Custom form does not render the last verification time");
		check(form.contains("ftcdAction=disconnect"), "Custom form does not expose Disconnect");
		check(diagnostics.contains("ConnectorDiagnosticsAdapter.disconnect"), "Disconnect is not wired to state revocation");
	}

	private static String read(final Path pFile) throws Exception
	{
		return new String(Files.readAllBytes(pFile), StandardCharsets.UTF_8);
	}

	private static void configureDynamicProbe(final Connector pConnector, final String pType)
	{
		if (!"rest".equals(pType) && !"soap".equals(pType) && !"graphql".equals(pType)) return;
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("descriptor-consumption-probe");
		config.setType(pType);
		config.setBaseUrl("https://example.invalid");
		if ("soap".equals(pType)) config.setProperty("op.probe.body", "<Probe/>");
		else if ("graphql".equals(pType)) config.setProperty("op.probe.query", "query Probe { probe }");
		else { config.setProperty("op.probe.method", "GET"); config.setProperty("op.probe.path", "/probe"); }
		pConnector.configure(config);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
