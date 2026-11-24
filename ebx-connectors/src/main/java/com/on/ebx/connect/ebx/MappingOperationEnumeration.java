package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.Operation;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.ConstraintEnumeration;
import com.orchestranetworks.schema.InvalidSchemaException;
import com.orchestranetworks.schema.Path;

/**
 * Value list of {@code Mapping/operation}: the operations of the connector selected on the same
 * record (read through {@code ./connector}). For the configuration-only {@code rest} type the
 * operations come from the connector's {@code op.*} properties, so the configured instance is
 * used; other types answer from their class. Unknown operations are reported as errors, an
 * unknown or inactive connector only as a warning (the mapping may be prepared ahead).
 */
public final class MappingOperationEnumeration implements ConstraintEnumeration<String>
{
	// paths of a constraint are relative to the constrained node (operation): the connector is a sibling
	private static final Path CONNECTOR = Path.parse("../connector");

	@Override
	public void setup(final ConstraintContext pContext)
	{
		pContext.addDependencyToModify(MappingOperationEnumeration.CONNECTOR);
	}

	private List<Operation> operations(final ValueContext pContext)
	{
		final List<Operation> out = new ArrayList<Operation>();
		final Object connectorId = pContext.getValue(MappingOperationEnumeration.CONNECTOR);
		if (connectorId == null || String.valueOf(connectorId).isEmpty())
		{
			return out;
		}
		try
		{
			final ConnectorRepository repo = ConnectorService.get().repository(pContext.getHome().getRepository());
			final ConnectorConfig config = repo.connector(String.valueOf(connectorId));
			if (config == null)
			{
				return out;
			}
			Connector connector;
			try
			{
				connector = ConnectorRegistry.get().connector(config.getId(), repo);
			}
			catch (final Exception inactive)
			{
				connector = ConnectorRegistry.get().describeType(config.getType());
				connector.configure(config);
			}
			out.addAll(connector.getOperations());
		}
		catch (final Exception ex)
		{
			// no list: the field stays free
		}
		return out;
	}

	@Override
	public List<String> getValues(final ValueContext pContext) throws InvalidSchemaException
	{
		final List<String> names = new ArrayList<String>();
		for (final Operation operation : this.operations(pContext))
		{
			names.add(operation.getName());
		}
		return names;
	}

	@Override
	public String displayOccurrence(final String pValue, final ValueContext pContext, final Locale pLocale) throws InvalidSchemaException
	{
		for (final Operation operation : this.operations(pContext))
		{
			if (operation.getName().equals(pValue))
			{
				return pValue + " — " + operation.getKind().name().toLowerCase(Locale.ROOT) + ": " + operation.getDescription();
			}
		}
		return pValue;
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (pValue == null || pValue.isEmpty())
		{
			return;
		}
		final List<String> known = this.getValues(pContext);
		if (known.isEmpty())
		{
			pContext.addWarning("The connector is unknown or unavailable: the operation '" + pValue + "' cannot be checked.");
			return;
		}
		if (!known.contains(pValue))
		{
			pContext.addError("Operation '" + pValue + "' is not offered by this connector; available: " + known);
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return Locale.FRENCH.getLanguage().equals(pLocale.getLanguage()) ? "Une opération du connecteur sélectionné." : "An operation of the selected connector.";
	}
}
