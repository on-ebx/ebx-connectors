package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Safe metadata-only adapters for connectors written against earlier SPI versions. */
public final class ConnectorInspectionAdapters
{
	private ConnectorInspectionAdapters() { }

	public static ObjectDiscoveryResult discoverOperations(final List<Operation> pOperations,
		final ObjectDiscoveryRequest pRequest)
	{
		final ObjectDiscoveryRequest request = pRequest == null
			? ObjectDiscoveryRequest.firstPage("", ObjectDiscoveryRequest.DEFAULT_LIMIT) : pRequest;
		if (request.getContinuationToken() != null)
			return ObjectDiscoveryResult.page(Collections.<DiscoveredObject>emptyList(), null);
		final String query = request.getQuery().toLowerCase(Locale.ROOT);
		final List<DiscoveredObject> objects = new ArrayList<DiscoveredObject>();
		if (pOperations != null)
		{
			for (final Operation operation : pOperations)
			{
				if (operation == null || operation.getKind() == Operation.Kind.WRITE) continue;
				final String searchable = (operation.getName() + " " + operation.getDescription())
					.toLowerCase(Locale.ROOT);
				if (!query.isEmpty() && !searchable.contains(query)) continue;
				objects.add(new DiscoveredObject(operation.getName(), operation.getDescription(),
					operation.getKind().name(), true));
				if (objects.size() >= request.getLimit()) break;
			}
		}
		return ObjectDiscoveryResult.page(objects, null);
	}

	public static String operationLabel(final List<Operation> pOperations, final String pOperationName)
	{
		if (pOperations != null)
		{
			for (final Operation operation : pOperations)
			{
				if (operation != null && operation.getName().equals(pOperationName))
					return operation.getDescription();
			}
		}
		return pOperationName;
	}

	public static DraftMapping suggestMapping(final ObjectDescription pSource,
		final DraftMappingRequest pRequest)
	{
		if (pSource == null || !pSource.isSupported())
			return DraftMapping.unsupported(pSource == null ? "Object metadata is unavailable" : pSource.getMessage());
		final List<DraftMappingField> suggestions = new ArrayList<DraftMappingField>();
		for (final FieldInfo source : pSource.getFields())
		{
			final String sourceName = leaf(source.getPath());
			for (final FieldInfo target : pRequest.getTargetFields())
			{
				final String targetName = leaf(target.getPath());
				if (sourceName.equalsIgnoreCase(targetName))
				{
					final boolean exact = source.getPath().equalsIgnoreCase(target.getPath());
					suggestions.add(new DraftMappingField(source.getPath(), target.getPath(),
						exact ? 1.0d : 0.85d));
					break;
				}
			}
		}
		return DraftMapping.of(suggestions);
	}

	private static String leaf(final String pPath)
	{
		if (pPath == null) return "";
		final String normalized = pPath.replace('\\', '/');
		final int slash = normalized.lastIndexOf('/');
		final int dot = normalized.lastIndexOf('.');
		return normalized.substring(Math.max(slash, dot) + 1).replaceAll("[^A-Za-z0-9]", "");
	}
}
