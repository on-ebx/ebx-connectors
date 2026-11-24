package com.on.ebx.connect.coverage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.connect.core.ConnectorRegistry;

/** Enforces the live connector coverage inventory without adding a YAML dependency. */
public final class ConnectorCoverageManifestTest
{
	private static final Set<String> WORKFLOW_STATUSES = set(
		"BACKLOG", "READY", "IN_PROGRESS", "BLOCKED", "REVIEW", "DONE", "N_A");
	private static final Set<String> COVERAGE_STATUSES = set(
		"COVERED", "PARTIAL", "PLANNED", "N_A", "UNVERIFIED");
	private static final Set<String> REGISTRATION_MODES = set(
		"BUILT_IN", "OPTIONAL_BUILT_IN", "EXTENSION");

	private ConnectorCoverageManifestTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String moduleDir = System.getProperty("ebx.connectors.moduleDir");
		require(moduleDir != null && !moduleDir.trim().isEmpty(),
			"Set -Debx.connectors.moduleDir to the module root");
		final File root = new File(moduleDir).getCanonicalFile();
		final File manifest = new File(root, "connector-coverage.yaml").getCanonicalFile();
		require(manifest.isFile(), "Missing connector coverage manifest: " + manifest);

		final JsonNode document = new ObjectMapper().readTree(manifest);
		require(document.path("schemaVersion").asInt() == 1, "Unsupported coverage schemaVersion");
		final JsonNode catalog = document.path("catalog");
		final JsonNode targets = document.path("externalTargets");
		require(catalog.isArray() && catalog.size() > 0, "catalog must be a non-empty array");
		require(targets.isArray() && targets.size() > 0, "externalTargets must be a non-empty array");

		final Set<String> ids = new HashSet<String>();
		final Set<String> catalogTypes = validateCatalog(root, catalog, ids);
		validateRegistry(catalogTypes);
		validateTargets(targets, ids, catalogTypes);
		validateNeutralTracking(root, manifest);

		System.out.println("PASS: connector coverage manifest tracks " + catalog.size()
			+ " catalog types and " + targets.size() + " capability baseline items");
	}

	private static Set<String> validateCatalog(final File pRoot, final JsonNode pCatalog,
		final Set<String> pIds) throws Exception
	{
		final Set<String> types = new HashSet<String>();
		for (final JsonNode item : pCatalog)
		{
			final String id = required(item, "id");
			require(pIds.add(id), "Duplicate coverage id: " + id);
			final String type = required(item, "connectorType");
			require(types.add(type), "Duplicate catalog connectorType: " + type);
			final String implementation = required(item, "implementation");
			require(REGISTRATION_MODES.contains(required(item, "registrationMode")),
				id + " has an invalid registrationMode");
			require(WORKFLOW_STATUSES.contains(required(item, "workflowStatus")),
				id + " has an invalid workflowStatus");

			final File source = repositoryPath(pRoot,
				"src/main/java/" + implementation.replace('.', '/') + ".java");
			require(source.isFile(), id + " implementation source does not exist: " + source);
			if ("DONE".equals(item.path("workflowStatus").asText()))
			{
				validateEvidence(pRoot, item, id);
			}
		}
		return types;
	}

	private static void validateRegistry(final Set<String> pCatalogTypes)
	{
		final Set<String> missing = new HashSet<String>(ConnectorRegistry.get().getTypes());
		missing.removeAll(pCatalogTypes);
		require(missing.isEmpty(), "Registered connector types missing from coverage manifest: " + missing);
	}

	private static void validateTargets(final JsonNode pTargets, final Set<String> pIds,
		final Set<String> pCatalogTypes)
	{
		final Set<String> sourceKeys = new HashSet<String>();
		int baselineItems = 0;
		for (final JsonNode item : pTargets)
		{
			final String id = required(item, "id");
			require(pIds.add(id), "Duplicate coverage id: " + id);
			final String source = required(item, "source");
			final String section = required(item, "sourceSection");
			final String sourceName = required(item, "sourceName");
			require(sourceKeys.add(section + "|" + sourceName),
				"Duplicate capability baseline item: " + section + "/" + sourceName);
			required(item, "strategy");
			final String workflow = required(item, "workflowStatus");
			final String coverage = required(item, "coverageStatus");
			require(WORKFLOW_STATUSES.contains(workflow), id + " has an invalid workflowStatus");
			require(COVERAGE_STATUSES.contains(coverage), id + " has an invalid coverageStatus");

			if ("COVERED".equals(coverage) || "PARTIAL".equals(coverage))
			{
				final String coveredBy = required(item, "coveredBy");
				if (coveredBy.indexOf('.') < 0 && Character.isLowerCase(coveredBy.charAt(0)))
				{
					require(pCatalogTypes.contains(coveredBy),
						id + " references unknown catalog connectorType " + coveredBy);
				}
			}
			if ("N_A".equals(coverage))
			{
				require("DONE".equals(workflow), id + " N_A disposition must be DONE");
				require(item.hasNonNull("notes") || item.hasNonNull("coveredBy"),
					id + " N_A disposition requires a rationale");
			}

			require("CAPABILITY_BASELINE".equals(source),
				id + " has unsupported source " + source);
			require(!section.trim().isEmpty(), id + " has a blank capability section");
			baselineItems++;
		}
		require(baselineItems == 86,
			"Expected 86 capability baseline items, found " + baselineItems);
	}

	private static void validateEvidence(final File pRoot, final JsonNode pItem,
		final String pId) throws Exception
	{
		final JsonNode evidence = pItem.path("evidence");
		require(evidence.isArray() && evidence.size() > 0, pId + " DONE item requires evidence paths");
		for (final JsonNode path : evidence)
		{
			require(path.isTextual() && !path.asText().trim().isEmpty(), pId + " has invalid evidence");
			require(repositoryPath(pRoot, path.asText()).exists(),
				pId + " evidence path does not exist: " + path.asText());
		}
	}

	private static void validateNeutralTracking(final File pRoot, final File pManifest) throws Exception
	{
		final File roadmap = repositoryPath(pRoot, "docs/CONNECTOR-COVERAGE-ROADMAP.md");
		require(roadmap.isFile(), "Missing human-readable connector coverage roadmap");
		validateForbiddenTokens(pManifest);
		validateForbiddenTokens(roadmap);
	}

	private static void validateForbiddenTokens(final File pFile) throws Exception
	{
		final String text = new String(Files.readAllBytes(pFile.toPath()), StandardCharsets.UTF_8)
			.toLowerCase(java.util.Locale.ROOT);
		final String[] forbidden = { "reltio", "semarchy", "docs.reltio", "semarchy.com",
			"competitor", "competitive", "comparison" };
		for (final String token : forbidden)
		{
			require(text.indexOf(token) < 0,
				pFile.getName() + " contains forbidden attribution token: " + token);
		}
	}

	private static File repositoryPath(final File pRoot, final String pPath) throws Exception
	{
		require(pPath != null && !pPath.trim().isEmpty(), "Repository path is blank");
		final File candidate = new File(pPath);
		require(!candidate.isAbsolute(), "Repository path must be relative: " + pPath);
		final File resolved = new File(pRoot, pPath).getCanonicalFile();
		require(resolved.toPath().startsWith(pRoot.toPath()), "Repository path escapes module root: " + pPath);
		return resolved;
	}

	private static String required(final JsonNode pItem, final String pName)
	{
		final JsonNode value = pItem.get(pName);
		require(value != null && value.isTextual() && !value.asText().trim().isEmpty(),
			"Coverage item requires non-blank " + pName + ": " + pItem);
		return value.asText().trim();
	}

	private static Set<String> set(final String... pValues)
	{
		return new HashSet<String>(Arrays.asList(pValues));
	}

	private static void require(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
