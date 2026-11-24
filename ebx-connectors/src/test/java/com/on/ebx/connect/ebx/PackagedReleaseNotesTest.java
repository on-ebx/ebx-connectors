package com.on.ebx.connect.ebx;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/** Deterministic contract for packaged release content and non-overwriting installation. */
public final class PackagedReleaseNotesTest
{
	public static void main(final String[] pArgs)
	{
		final List<PackagedReleaseNotes.Release> releases = PackagedReleaseNotes.all();
		check(!releases.isEmpty(), "a packaged release is required");
		final Set<String> versions = new HashSet<String>();
		for (final PackagedReleaseNotes.Release release : releases)
		{
			check(versions.add(release.version), "release versions must be unique");
			check(release.releaseDate.isEmpty() || release.releaseDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "formal release date must be deterministic ISO format");
			check(!release.items.isEmpty(), "release items are required");
			final Set<String> itemIds = new HashSet<String>();
			for (final PackagedReleaseNotes.Item item : release.items) check(itemIds.add(item.id), "item ids must be unique within a release");
			for (final PackagedReleaseNotes.PassStatus pass : release.passes)
				check(pass.progress >= 0 && pass.progress <= 100, "pass progress must be a percentage");
			final Set<String> passCodes = new HashSet<String>();
			for (final PackagedReleaseNotes.PassStatus pass : release.passes) check(passCodes.add(pass.code), "pass codes must be unique");
			final Set<String> stepKeys = new HashSet<String>();
			for (final PackagedReleaseNotes.PassStep step : release.steps)
			{
				check(passCodes.contains(step.passCode), "every pass step must name an existing pass");
				check(stepKeys.add(step.passCode + "/" + step.code), "step codes must be unique within a pass");
			}
		}
		int current = 0;
		for (final PackagedReleaseNotes.Release release : releases)
		{
			final boolean formal = release.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+");
			check(formal ? release.status.matches("CURRENT|SUPPORTED|ARCHIVED") : release.status.matches("IN_PROGRESS|ARCHIVED|DRAFT"),
				"status of " + release.version + ": " + release.status);
			if ("CURRENT".equals(release.status)) current++;
		}
		check(current == 1, "exactly one packaged release is the current one, found " + current);
		check("CURRENT".equals(PackagedReleaseNotes.releaseStatus(true, "current")) && "SUPPORTED".equals(PackagedReleaseNotes.releaseStatus(true, "supported"))
			&& "ARCHIVED".equals(PackagedReleaseNotes.releaseStatus(true, "archived")), "a formal release shows the status the notes give it");
		check("IN_PROGRESS".equals(PackagedReleaseNotes.releaseStatus(false, "in-progress")) && "ARCHIVED".equals(PackagedReleaseNotes.releaseStatus(false, "complete")),
			"a development increment is in progress, or archived once complete");
		check(PackagedReleaseNotes.updatePackagedStatus("PACKAGED", "CURRENT", "SUPPORTED"), "an installed packaged release follows the notes when its status differs");
		check(!PackagedReleaseNotes.updatePackagedStatus("PACKAGED", "SUPPORTED", "SUPPORTED"), "an equal status is not rewritten");
		check(!PackagedReleaseNotes.updatePackagedStatus("CUSTOMER", "CURRENT", "SUPPORTED"), "a customer release keeps its status");
		check(!PackagedReleaseNotes.updatePackagedStatus(null, null, "CURRENT"), "an absent release has no status to update");
		check(PackagedReleaseNotes.installPackagedRelease(null), "an absent release must be installed");
		check(!PackagedReleaseNotes.installPackagedRelease("PACKAGED"), "reinstall must not overwrite packaged content");
		check(!PackagedReleaseNotes.installPackagedRelease("CUSTOMER"), "installer must not overwrite customer content");
		check(PackagedReleaseNotes.seedPackagedChildren("PACKAGED"), "an existing packaged release must receive missing children");
		check(!PackagedReleaseNotes.seedPackagedChildren("CUSTOMER"), "a customer release must block packaged children");
		check(PackagedReleaseNotes.createMissingChild(false), "a missing child must be added");
		check(!PackagedReleaseNotes.createMissingChild(true), "an existing child of either owner must be preserved");
		checkNestedStepParsing();
		System.out.println("PASS: packaged release-note manifest is deterministic, additive and ownership-safe");
	}

	private static void checkNestedStepParsing()
	{
		try
		{
			final String xml = "<release id='preview' kind='development'><title>Title</title><summary>Summary</summary>"
				+ "<pass code='pass-1' label='Pass 1' status='complete' progress='100'><remainingGates>None</remainingGates>"
				+ "<step code='catalog' label='Catalog' status='complete' occurredOn='2026-09-08' order='20'><detail>Shipped.</detail></step></pass>"
				+ "<section category='new'><item id='stable-item'>Feature</item></section></release>";
			final PackagedReleaseNotes.Release release = PackagedReleaseNotes.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(xml.getBytes("UTF-8"))).getDocumentElement());
			check("stable-item".equals(release.items.get(0).id), "explicit release-item ids must be stable");
			check("None".equals(release.passes.get(0).remainingGates), "remaining gates must exclude nested step text");
			check(release.steps.size() == 1, "nested pass step must be parsed");
			final PackagedReleaseNotes.PassStep step = release.steps.get(0);
			check("pass-1".equals(step.passCode) && "catalog".equals(step.code), "pass-step parent and code");
			check("COMPLETE".equals(step.status) && step.order == 20, "pass-step status and order");
			check("2026-09-08".equals(step.occurredOn) && "Shipped.".equals(step.detail), "pass-step history fields");
			final String groupedXml = "<release id='preview' kind='development'><title>Title</title><summary>Summary</summary>"
				+ "<pass code='pass-2' label='Pass 2' status='in-progress' progress='80'>Gates.</pass>"
				+ "<substeps passCode='pass-2'><substep id='upgrade' status='pending'>Upgrade acceptance.</substep></substeps>"
				+ "<section category='new'><item>Feature</item></section></release>";
			final PackagedReleaseNotes.Release grouped = PackagedReleaseNotes.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(groupedXml.getBytes("UTF-8"))).getDocumentElement());
			check(grouped.steps.size() == 1 && "upgrade".equals(grouped.steps.get(0).code), "sibling substeps group must be parsed");
			check("PLANNED".equals(grouped.steps.get(0).status), "manifest pending status must map to the persisted PLANNED status");
		}
		catch (final Exception ex) { throw new AssertionError("nested pass-step fixture", ex); }
	}

	private static void check(final boolean pActual, final String pMessage)
	{
		if (!pActual) throw new AssertionError(pMessage);
	}
}
