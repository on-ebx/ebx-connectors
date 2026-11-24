package com.on.ebx.connect.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.connect.core.PreviewFeatures;

/**
 * A mapping that asks for matching around a CDC batch is refused when it is SAVED (connectors_mapping_upsert and the configuration
 * import both pass {@code templateRefusal}) while the {@code cdcMatching} preview function is closed; opened, it is stored (audit C3-05).
 */
public final class PreviewMappingRefusalTest
{
	private PreviewMappingRefusalTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String saved = System.getProperty(PreviewFeatures.PROPERTY);
		try
		{
			System.clearProperty(PreviewFeatures.PROPERTY);
			final String off = ConfigTransfer.templateRefusal(null, json("{\"label\":\"orders cdc\",\"operation\":\"cdc\",\"inputs\":{\"matching\":\"off\"}}"));
			check(off != null && off.startsWith("mapping 'orders cdc': [" + PreviewFeatures.CODE + "]") && off.contains(PreviewFeatures.PROPERTY), "matching=off saved while closed: " + off);
			check(ConfigTransfer.templateRefusal(null, json("{\"label\":\"m\",\"operation\":\"cdc\",\"inputs\":{\"matching\":\"afterApply\",\"deleteMode\":\"flag\"}}")) != null,
				"matching=afterApply saved while closed");
			check(ConfigTransfer.templateRefusal(null, json("{\"label\":\"m\",\"operation\":\"cdc\",\"inputs\":{\"matching\":\"policy\"}}")) == null, "matching=policy is not a preview function");
			check(ConfigTransfer.templateRefusal(null, json("{\"label\":\"m\",\"operation\":\"cdc\",\"inputs\":{\"deleteMode\":\"flag\"}}")) == null, "a CDC mapping without matching is accepted");
			check(ConfigTransfer.templateRefusal(null, json("{\"label\":\"m\",\"operation\":\"query\",\"inputs\":{\"matching\":\"off\"}}")) == null, "another operation's input named matching is accepted");
			System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
			check(ConfigTransfer.templateRefusal(null, json("{\"label\":\"m\",\"operation\":\"cdc\",\"inputs\":{\"matching\":\"off\"}}")) == null, "opened: accepted");
		}
		finally
		{
			if (saved == null) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, saved);
		}
		System.out.println("PASS: a mapping asking for matching around a CDC batch is refused at save while the preview function is closed");
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return new ObjectMapper().readTree(pText);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
