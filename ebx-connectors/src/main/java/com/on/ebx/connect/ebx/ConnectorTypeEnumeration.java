package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.Connector;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.ConstraintEnumeration;
import com.orchestranetworks.schema.InvalidSchemaException;

/**
 * Value list of {@code Connector/type}: the connector types registered in the module (built-in
 * classes, {@code META-INF/services} extensions, and {@code rest} for configuration-only APIs).
 * A new type appears here as soon as its class is registered, nothing to type in by hand.
 */
public final class ConnectorTypeEnumeration implements ConstraintEnumeration<String>
{
	@Override
	public void setup(final ConstraintContext pContext)
	{
		// nothing to check in the model
	}

	@Override
	public List<String> getValues(final ValueContext pContext) throws InvalidSchemaException
	{
		final List<String> types = new ArrayList<String>(ConnectorRegistry.get().getTypes());
		Collections.sort(types);
		return types;
	}

	@Override
	public String displayOccurrence(final String pValue, final ValueContext pContext, final Locale pLocale) throws InvalidSchemaException
	{
		try
		{
			final Connector probe = ConnectorRegistry.get().describeType(pValue);
			return probe.getLabel() + " (" + pValue + ")";
		}
		catch (final Exception ex)
		{
			return pValue;
		}
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (pValue != null && !ConnectorRegistry.get().getTypes().contains(pValue))
		{
			pContext.addError("Unknown connector type '" + pValue + "'; registered types: " + ConnectorRegistry.get().getTypes());
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return Locale.FRENCH.getLanguage().equals(pLocale.getLanguage()) ? "Un des types de connecteurs enregistrés dans le module."
				: "One of the connector types registered in the module.";
	}
}
