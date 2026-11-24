package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Preview functions closed by default (decision of 2026-11-12, audit C3-03 to C3-06): the property {@code ebx.connectors.preview}
 * opens {@code documents}, {@code cdcMatching} and {@code redshift} one by one; absent, empty or misspelt it opens nothing; a closed
 * function refuses at the registry (every execution passes it) and when a CDC mapping asks for matching, with a coded message in
 * English and French that names the property; the type stays known. Also the run-time inputs of a CDC feed (C3-04).
 */
public final class PreviewFeaturesTest
{
	private PreviewFeaturesTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String saved = System.getProperty(PreviewFeatures.PROPERTY);
		try
		{
			defaultsAreClosed();
			eachWordOpensOnlyItsFunction();
			misspeltWordsOpenNothing();
			refusalIsCodedAndBilingual();
			registryRefusesWhenClosed();
			registryServesWhenOpen();
			mappingsWithClosedMatching();
			queuedGoldenRefusedWhenClosed();
			cdcClosedByDefault();
			publishGoldenRefusedWithoutCdcMatching();
			damaConflictRefusedWhenDocumentsClosed();
			statusBlock();
			cdcRuntimeInputs();
			seededDocumentsConnection();
		}
		finally
		{
			if (saved == null) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, saved);
		}
		System.out.println("PASS: preview functions are closed by default, opened word by word, refused with a coded bilingual message, and CDC run-time inputs cannot replace the mapping's");
	}

	private static void defaultsAreClosed()
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		for (final PreviewFeatures.Feature feature : PreviewFeatures.Feature.values())
		{
			check(!PreviewFeatures.isOpen(feature), feature.word() + " must be closed when the property is absent");
		}
		for (final String blank : new String[] { "", "   ", " , ,, " })
		{
			System.setProperty(PreviewFeatures.PROPERTY, blank);
			check(PreviewFeatures.opened().isEmpty(), "blank value '" + blank + "' opened something");
		}
	}

	private static void eachWordOpensOnlyItsFunction()
	{
		for (final PreviewFeatures.Feature wanted : PreviewFeatures.Feature.values())
		{
			System.setProperty(PreviewFeatures.PROPERTY, " " + wanted.word() + " ");
			for (final PreviewFeatures.Feature feature : PreviewFeatures.Feature.values())
			{
				// cdcMatching works on the batches of the CDC and on what they merge: alone it opens nothing (1.4.2)
				final boolean expected = feature == wanted && wanted != PreviewFeatures.Feature.CDC_MATCHING;
				check(PreviewFeatures.isOpen(feature) == expected, "word " + wanted.word() + " must open only itself, saw " + feature.word());
			}
		}
		System.setProperty(PreviewFeatures.PROPERTY, "documents, redshift");
		check(PreviewFeatures.isOpen(PreviewFeatures.Feature.DOCUMENTS) && PreviewFeatures.isOpen(PreviewFeatures.Feature.REDSHIFT)
			&& !PreviewFeatures.isOpen(PreviewFeatures.Feature.CDC_MATCHING) && !PreviewFeatures.isOpen(PreviewFeatures.Feature.CDC), "a list opens exactly its words");
		System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
		check(PreviewFeatures.isOpen(PreviewFeatures.Feature.CDC) && PreviewFeatures.isOpen(PreviewFeatures.Feature.CDC_MATCHING)
			&& !PreviewFeatures.isOpen(PreviewFeatures.Feature.DOCUMENTS), "cdc and cdcMatching together open both, and only them");
	}

	private static void misspeltWordsOpenNothing()
	{
		// exact spelling and case: anything else is reported and never taken for an opening
		for (final String wrong : new String[] { "Documents", "cdcmatching", "document", "all", "true", "*", "redshift2", "cdc-matching" })
		{
			System.setProperty(PreviewFeatures.PROPERTY, wrong);
			check(PreviewFeatures.opened().isEmpty(), "'" + wrong + "' opened something");
			check(PreviewFeatures.unknownWords().equals(Arrays.asList(wrong)), "'" + wrong + "' is not reported as unknown: " + PreviewFeatures.unknownWords());
		}
		System.setProperty(PreviewFeatures.PROPERTY, "documnets,redshift");
		check(PreviewFeatures.isOpen(PreviewFeatures.Feature.REDSHIFT) && !PreviewFeatures.isOpen(PreviewFeatures.Feature.DOCUMENTS),
			"a misspelt word must not stop a good one, nor open its neighbour");
		check(PreviewFeatures.unknownWords().equals(Arrays.asList("documnets")), "the misspelt word is the one reported");
	}

	private static void refusalIsCodedAndBilingual() throws Exception
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		for (final PreviewFeatures.Feature feature : PreviewFeatures.Feature.values())
		{
			final String message = PreviewFeatures.closedMessage(feature);
			check(message.startsWith("[" + PreviewFeatures.CODE + "] "), "message carries the code: " + message);
			check(message.contains("-D" + PreviewFeatures.PROPERTY) && message.contains(feature.word()), "message names the property and the word: " + message);
			check(message.contains("closed on this server") && message.contains("fermée sur ce serveur"), "message is in English AND French: " + message);
			try
			{
				PreviewFeatures.require(feature);
				throw new AssertionError(feature.word() + " ran while closed");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().equals(message), "require() refuses with that message");
			}
		}
		System.setProperty(PreviewFeatures.PROPERTY, "documents");
		PreviewFeatures.require(PreviewFeatures.Feature.DOCUMENTS);
	}

	private static ConnectorRegistry.ConfigSource source(final String pType, final String pBaseUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("probe-" + pType);
		config.setType(pType);
		config.setBaseUrl(pBaseUrl);
		config.setAuthType(ConnectorConfig.AuthType.NONE);
		return new ConnectorRegistry.ConfigSource()
		{
			@Override
			public ConnectorConfig connector(final String pId)
			{
				return config;
			}
		};
	}

	private static void registryRefusesWhenClosed() throws Exception
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		final ConnectorRegistry registry = ConnectorRegistry.get();
		// the types stay known: a connection of that type must not turn the configuration into "unknown type"
		check(registry.getTypes().contains("documents") && registry.getTypes().contains("redshift"), "preview types are still registered");
		check("documents".equals(registry.describeType("documents").getType()) && "redshift".equals(registry.describeType("redshift").getType()),
			"describing a closed type still works");
		final String[][] cases = { { "documents", "local://documents", "documents" }, { "redshift", "jdbc:redshift://c.abc.eu-west-1.redshift.amazonaws.com:5439/dev", "redshift" } };
		for (final String[] one : cases)
		{
			try
			{
				registry.connector("probe-" + one[0], source(one[0], one[1]));
				throw new AssertionError(one[0] + " connection was built while closed");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().startsWith("[" + PreviewFeatures.CODE + "] "), one[0] + ": coded refusal, saw " + expected.getMessage());
				check(expected.getMessage().contains(PreviewFeatures.PROPERTY) && expected.getMessage().contains(one[2]), one[0] + ": names the property and the word");
			}
		}
		// a type that is not a preview one is never refused by this gate
		registry.connector("probe-sample", source("sample-file", "local://sample-file"));
		registry.invalidate();
	}

	private static void registryServesWhenOpen() throws Exception
	{
		final ConnectorRegistry registry = ConnectorRegistry.get();
		System.setProperty(PreviewFeatures.PROPERTY, "documents");
		check(registry.connector("probe-documents", source("documents", "local://documents")) != null, "documents connection built once opened");
		try
		{
			registry.connector("probe-redshift", source("redshift", "jdbc:redshift://c.abc.eu-west-1.redshift.amazonaws.com:5439/dev"));
			throw new AssertionError("opening documents must not open redshift");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains(PreviewFeatures.CODE), "redshift still refused: " + expected.getMessage());
		}
		System.setProperty(PreviewFeatures.PROPERTY, "redshift");
		try
		{
			registry.connector("probe-documents", source("documents", "local://documents"));
			throw new AssertionError("a cached documents connection must not outlive its closing");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains(PreviewFeatures.CODE), "documents refused again: " + expected.getMessage());
		}
		check(registry.connector("probe-redshift", source("redshift", "jdbc:redshift://c.abc.eu-west-1.redshift.amazonaws.com:5439/dev")) != null,
			"redshift connection built once opened");
		registry.invalidate();
	}

	private static void mappingsWithClosedMatching() throws Exception
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		for (final String value : new String[] { "afterApply", "AFTERAPPLY", " after_apply ", "off", "OFF" })
		{
			final Map<String, String> inputs = new HashMap<String, String>();
			inputs.put("matching", value);
			try
			{
				PreviewFeatures.requireMappingAllowed("cdc", inputs);
				throw new AssertionError("matching=" + value + " accepted while closed");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().startsWith("[" + PreviewFeatures.CODE + "] ") && expected.getMessage().contains("cdcMatching"), "coded: " + expected.getMessage());
			}
		}
		final Map<String, String> policy = new HashMap<String, String>();
		policy.put("matching", "policy");
		PreviewFeatures.requireMappingAllowed("cdc", policy);
		PreviewFeatures.requireMappingAllowed("cdc", new HashMap<String, String>());
		PreviewFeatures.requireMappingAllowed("cdc", null);
		final Map<String, String> other = new HashMap<String, String>();
		other.put("matching", "off");
		PreviewFeatures.requireMappingAllowed("query", other); // another operation's input named matching is none of this gate's business
		check(PreviewFeatures.cdcMatchingRequested(other) && !PreviewFeatures.cdcMatchingRequested(policy), "which values ask for matching");
		check(PreviewFeatures.publishGoldenRequested(" Golden ") && !PreviewFeatures.publishGoldenRequested("record") && !PreviewFeatures.publishGoldenRequested(null),
			"which publish values ask for the golden record");
		System.setProperty(PreviewFeatures.PROPERTY, "cdcMatching");
		try
		{
			PreviewFeatures.requireMappingAllowed("cdc", other);
			throw new AssertionError("cdcMatching alone must open nothing");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains(PreviewFeatures.CODE), "coded: " + expected.getMessage());
		}
		System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
		PreviewFeatures.requireMappingAllowed("cdc", other); // opened: accepted
	}

	private static void queuedGoldenRefusedWhenClosed()
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		final OutboundQueue.Entry entry = new OutboundQueue.Entry();
		entry.publish = OutboundQueue.PUBLISH_GOLDEN;
		entry.recordKey = "1";
		try
		{
			ConnectorService.get().resolveQueued(null, entry);
			throw new AssertionError("a publish=golden entry was resolved while closed");
		}
		catch (final IllegalStateException expected)
		{
			// the queue keeps the row for its retries, then sets it aside with this reason
			check(expected.getMessage().startsWith("[" + PreviewFeatures.CODE + "] "), "coded reason: " + expected.getMessage());
		}
	}

	/** Audit C3-13, C3-15 (1.4.2): the simple CDC is closed with its own word; the ordinary Kafka reads are not. */
	private static void cdcClosedByDefault() throws Exception
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		try
		{
			PreviewFeatures.requireOperation("kafka", "cdc");
			throw new AssertionError("the cdc operation ran while closed");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().startsWith("[" + PreviewFeatures.CODE + "] ") && expected.getMessage().contains("cdc") && expected.getMessage().contains("fermée sur ce serveur"),
				"coded and bilingual: " + expected.getMessage());
		}
		// the frontier: consume, peek, publish and the catalog operations of Kafka are older than November and stay open with every word closed
		for (final String operation : new String[] { "consume", "peek", "publish", "catalog", "profile", "sample" })
		{
			PreviewFeatures.requireOperation("kafka", operation);
			check(PreviewFeatures.featureOfOperation("kafka", operation) == null, operation + " is not a preview operation");
		}
		PreviewFeatures.requireOperation("jdbc", "cdc"); // another type has no such operation: not this gate's business
		// the second door of a CDC run, reached by the feed dispatcher whatever the surface (REST, MCP, screen, schedule, workflow, in-JVM contract)
		for (final String closedWords : new String[] { "documents,redshift", "cdcMatching" })
		{
			System.setProperty(PreviewFeatures.PROPERTY, closedWords);
			try
			{
				PreviewFeatures.requireOperation("kafka", "cdc");
				throw new AssertionError("cdc opened by " + closedWords);
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().contains(PreviewFeatures.CODE), "still refused with " + closedWords);
			}
		}
		System.setProperty(PreviewFeatures.PROPERTY, "cdc");
		PreviewFeatures.requireOperation("kafka", "cdc");
	}

	/** Audit C3-05 (1.4.2): a trigger or a setting with publish=golden is refused when cdcMatching is closed, and when it is named without cdc. */
	private static void publishGoldenRefusedWithoutCdcMatching() throws Exception
	{
		for (final String words : new String[] { "", "documents", "cdc", "cdcMatching" })
		{
			if (words.isEmpty()) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, words);
			try
			{
				PreviewFeatures.requirePublishAllowed(" golden ");
				throw new AssertionError("publish=golden accepted with '" + words + "'");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().startsWith("[" + PreviewFeatures.CODE + "] ") && expected.getMessage().contains("cdcMatching"), "coded: " + expected.getMessage());
			}
			PreviewFeatures.requirePublishAllowed("record");
			PreviewFeatures.requirePublishAllowed(null);
		}
		System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
		PreviewFeatures.requirePublishAllowed("golden");
	}

	/** Bilan 1.4.1 gap 1 (1.4.2): accepting a dama: proposal writes into DAMA, which the documents function owns. */
	private static void damaConflictRefusedWhenDocumentsClosed()
	{
		System.clearProperty(PreviewFeatures.PROPERTY);
		final String field = DamaProposals.PREFIX + "file/0b9d3c1e-1111-2222-3333-444455556666/metadata/TITLE";
		final String refusal = ConflictResolver.assetRefusal(field);
		check(refusal != null && refusal.startsWith("[" + PreviewFeatures.CODE + "] ") && refusal.contains("documents"), "closed: a dama: conflict is refused, coded: " + refusal);
		check(ConflictResolver.assetRefusal("./name") == null, "an ordinary field is not this gate's business");
		System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching,redshift");
		check(ConflictResolver.assetRefusal(field) != null, "other words do not open it");
		System.setProperty(PreviewFeatures.PROPERTY, "documents");
		check(ConflictResolver.assetRefusal(field) == null, "opened: the proposal can be accepted");
	}

	private static void statusBlock()
	{
		System.setProperty(PreviewFeatures.PROPERTY, "redshift,oops");
		final ObjectNode status = PreviewFeatures.status();
		check(PreviewFeatures.PROPERTY.equals(status.path("property").asText()), "status names the property");
		check(status.path("open").size() == 1 && "redshift".equals(status.path("open").get(0).asText()), "open: " + status.path("open"));
		check(status.path("closed").size() == 3 && status.path("closed").toString().contains("documents") && status.path("closed").toString().contains("cdcMatching")
			&& status.path("closed").toString().contains("\"cdc\""), "closed: " + status.path("closed"));
		check(!status.path("cdcMatchingWithoutCdc").asBoolean(true), "no warning when cdcMatching is not named");
		System.setProperty(PreviewFeatures.PROPERTY, "cdcMatching");
		final ObjectNode alone = PreviewFeatures.status();
		check(alone.path("open").size() == 0 && alone.path("closed").toString().contains("cdcMatching") && alone.path("cdcMatchingWithoutCdc").asBoolean(false),
			"cdcMatching without cdc opens nothing and the status says so: " + alone);
		System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
		check(!PreviewFeatures.status().path("cdcMatchingWithoutCdc").asBoolean(true) && PreviewFeatures.status().path("open").size() == 2, "both words: both open");
		System.setProperty(PreviewFeatures.PROPERTY, "redshift,oops");
		check(status.path("unknownWords").size() == 1 && "oops".equals(status.path("unknownWords").get(0).asText()), "unknown: " + status.path("unknownWords"));
	}

	private static void cdcRuntimeInputs()
	{
		final Map<String, String> mapping = new HashMap<String, String>();
		mapping.put("deleteMode", "flag");
		mapping.put("deleteFlagField", "./deleted");
		mapping.put("matching", "policy");
		mapping.put("maxEvents", "100");
		final Map<String, String> run = new HashMap<String, String>();
		run.put("deleteMode", "delete");
		run.put("deleteFlagField", "./x");
		run.put("matching", "off");
		run.put("matchingPolicy", "P");
		run.put("topic", "other.topic");
		run.put("maxEvents", "50");
		run.put("maxBytes", "99999999");
		final List<String> ignored = new ArrayList<String>();
		final Map<String, String> used = ConnectorService.cdcRunInputs(mapping, run, ignored);
		check("flag".equals(used.get("deleteMode")), "the mapping's deleteMode wins, saw " + used.get("deleteMode"));
		check("./deleted".equals(used.get("deleteFlagField")), "the mapping's flag field wins");
		check("policy".equals(used.get("matching")) && !used.containsKey("matchingPolicy"), "matching is the mapping's");
		check(!used.containsKey("topic"), "a run cannot name another topic");
		check("50".equals(used.get("maxEvents")), "a run may lower the batch size, saw " + used.get("maxEvents"));
		check(!used.containsKey("maxBytes"), "a run may not raise the byte ceiling above the default");
		check(CdcApplyPlan.Options.from(used).getDeleteMode() == CdcApplyPlan.DeleteMode.FLAG, "the plan still marks, never deletes");
		check(ignored.containsAll(Arrays.asList("deleteMode", "deleteFlagField", "matching", "matchingPolicy", "topic", "maxBytes")) && !ignored.contains("maxEvents"),
			"the dropped inputs are reported: " + ignored);
		check(RunLimits.ignoredInputsMessage(ignored).contains("deleteMode"), "reported with the run's usual wording");

		// raising is refused too, a mapping with nothing configured does not hand the choice to the run, junk is ignored
		final Map<String, String> bare = new HashMap<String, String>();
		final Map<String, String> raise = new HashMap<String, String>();
		raise.put("maxEvents", "9000");
		raise.put("deleteMode", "delete");
		final List<String> ignoredBare = new ArrayList<String>();
		final Map<String, String> usedBare = ConnectorService.cdcRunInputs(bare, raise, ignoredBare);
		check(usedBare.isEmpty() && ignoredBare.containsAll(Arrays.asList("maxEvents", "deleteMode")), "nothing is taken from the run: " + usedBare + " / " + ignoredBare);
		final Map<String, String> junk = new HashMap<String, String>();
		junk.put("maxEvents", "lots");
		final List<String> ignoredJunk = new ArrayList<String>();
		check(ConnectorService.cdcRunInputs(bare, junk, ignoredJunk).isEmpty() && ignoredJunk.contains("maxEvents"), "a non-number is ignored");
		check(ConnectorService.cdcRunInputs(mapping, null, new ArrayList<String>()).equals(mapping), "no run input: the mapping as it is");
	}

	private static void seededDocumentsConnection() throws Exception
	{
		final Class<?> installer = Class.forName("com.on.ebx.connect.ebx.ConnectorsInstaller");
		final java.lang.reflect.Method seeds = installer.getDeclaredMethod("seedsDocuments");
		final java.lang.reflect.Method properties = installer.getDeclaredMethod("documentsSeedProperties");
		seeds.setAccessible(true);
		properties.setAccessible(true);
		System.clearProperty(PreviewFeatures.PROPERTY);
		check(!((Boolean) seeds.invoke(null)).booleanValue(), "the Documents connection is not seeded while closed");
		System.setProperty(PreviewFeatures.PROPERTY, "redshift,cdcMatching");
		check(!((Boolean) seeds.invoke(null)).booleanValue(), "other words do not seed it");
		System.setProperty(PreviewFeatures.PROPERTY, "documents");
		check(((Boolean) seeds.invoke(null)).booleanValue(), "seeded once opened");
		for (final String[] property : (String[][]) properties.invoke(null))
		{
			check(!"llm.connector".equals(property[0]), "the seeded connection names no language model (a person chooses)");
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
