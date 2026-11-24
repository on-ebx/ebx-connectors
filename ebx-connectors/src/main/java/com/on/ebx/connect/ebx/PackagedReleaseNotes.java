package com.on.ebx.connect.ebx;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads the immutable product-owned release-note manifest packaged with the module. */
public final class PackagedReleaseNotes
{
	private static final String RESOURCE = "/com/on/ebx/connect/release/release-notes.xml";
	private PackagedReleaseNotes() { }
	public static final class Release
	{
		public final String version, releaseDate, status, title, summary;
		public final List<Item> items;
		public final List<Metric> metrics;
		public final List<PassStatus> passes;
		public final List<PassStep> steps;
		Release(final String v, final String d, final String s, final String t, final String y, final List<Item> i, final List<Metric> m, final List<PassStatus> p, final List<PassStep> e)
		{
			version = v;
			releaseDate = d;
			status = s;
			title = t;
			summary = y;
			items = Collections.unmodifiableList(i);
			metrics = Collections.unmodifiableList(m);
			passes = Collections.unmodifiableList(p);
			steps = Collections.unmodifiableList(e);
		}
	}
	public static final class Item
	{
		public final String id, type, title, detail;
		public final int order;
		Item(String i, String t, int o, String l, String d)
		{
			id = i;
			type = t;
			order = o;
			title = l;
			detail = d;
		}
	}
	public static final class Metric
	{
		public final String code, label, value;
		public final int order;
		Metric(String c, String l, String v, int o)
		{
			code = c;
			label = l;
			value = v;
			order = o;
		}
	}
	public static final class PassStatus
	{
		public final String code, label, status, remainingGates;
		public final int progress, order;
		PassStatus(String c, String l, String s, int p, String r, int o)
		{
			code = c;
			label = l;
			status = s;
			progress = p;
			remainingGates = r;
			order = o;
		}
	}
	public static final class PassStep
	{
		public final String passCode, code, label, status, occurredOn, detail;
		public final int order;
		PassStep(String p, String c, String l, String s, String d, String t, int o)
		{
			passCode = p;
			code = c;
			label = l;
			status = s;
			occurredOn = d;
			detail = t;
			order = o;
		}
	}

	/** Ordered manifest; development entries use their stable id while formal entries use SemVer. */
	public static List<Release> all()
	{
		InputStream input=null;
		try
		{
			input=PackagedReleaseNotes.class.getResourceAsStream(RESOURCE);
			if(input==null) throw new IllegalStateException("Missing packaged release notes "+RESOURCE);
			final DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			final NodeList nodes=f.newDocumentBuilder().parse(input).getDocumentElement().getElementsByTagName("release");
			final List<Release> result = new ArrayList<Release>();
			for (int i = 0; i < nodes.getLength(); i++) result.add(parse((Element)nodes.item(i)));
			return Collections.unmodifiableList(result);
		}
		catch(final Exception ex){throw new IllegalStateException("Invalid packaged release notes",ex);}
		finally{if(input!=null)try{input.close();}catch(final Exception ignored){}}
	}
	static Release parse(final Element release)
	{
		final boolean formal = "formal".equals(release.getAttribute("kind"));
		final String version = formal ? release.getAttribute("version") : release.getAttribute("id");
		final List<Item> items = new ArrayList<Item>();
		int order = 10;
		for (final Element section:children(release, "section"))
		{
			final String type = itemType(section.getAttribute("category"));
			for (final Element item:children(section, "item"))
			{
				final String detail = item.getTextContent().trim();
				final String explicit = item.getAttribute("id").trim();
				items.add(new Item(explicit.isEmpty() ? section.getAttribute("category")+"-"+order : explicit, type, order, detail, detail));
				order += 10;
			}
		}
		final List<Metric> metrics = new ArrayList<Metric>();
		order = 10;
		for (final Element metric:children(release, "metric"))
		{
			metrics.add(new Metric(metric.getAttribute("code"), metric.getAttribute("label"), metric.getAttribute("value"), order));
			order += 10;
		}
		final List<PassStatus> passes = new ArrayList<PassStatus>();
		final List<PassStep> steps = new ArrayList<PassStep>();
		order = 10;
		for(final Element pass:children(release,"pass"))
		{
			final String passCode = pass.getAttribute("code");
			final String gates = attributeOrChild(pass, "remainingGates");
			passes.add(new PassStatus(passCode,pass.getAttribute("label"),enumValue(pass.getAttribute("status")),Integer.parseInt(pass.getAttribute("progress")),gates.isEmpty()?directText(pass):gates,order));
			int stepOrder = 10;
			for (final Element step:children(pass, "step"))
			{
				final String configured = step.getAttribute("order");
				if (!configured.isEmpty()) stepOrder = Integer.parseInt(configured);
				final String label = step.getAttribute("label").isEmpty() ? textOrDirect(step, "title") : step.getAttribute("label");
				steps.add(new PassStep(passCode, step.getAttribute("code"), label, enumValue(step.getAttribute("status")), step.getAttribute("occurredOn"), textOrDirect(step, "detail"), stepOrder));
				stepOrder += 10;
			}
			order+=10;
		}
		for(final Element group:children(release,"substeps"))
		{
			final String passCode = group.getAttribute("passCode");
			int stepOrder = 10;
			for(final Element step:children(group,"substep"))
			{
				final String configured = step.getAttribute("order");
				if (!configured.isEmpty()) stepOrder = Integer.parseInt(configured);
				final String detail = textOrDirect(step, "detail");
				final String label = step.getAttribute("label").isEmpty() ? detail : step.getAttribute("label");
				steps.add(new PassStep(passCode, step.getAttribute("id"), label, enumValue(step.getAttribute("status")), step.getAttribute("occurredOn"), detail, stepOrder));
				stepOrder += 10;
			}
		}
		return new Release(version,release.getAttribute("releasedOn"),releaseStatus(formal,release.getAttribute("status")),text(release,"title"),text(release,"summary"),items,metrics,passes,steps);
	}
	private static List<Element> children(final Element parent, final String name)
	{
		final List<Element> result = new ArrayList<Element>();
		final NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++)
		{
			final Node node = nodes.item(i);
			if (node instanceof Element && name.equals(node.getNodeName())) result.add((Element)node);
		}
		return result;
	}
	private static String text(final Element parent,final String name){return children(parent,name).get(0).getTextContent().trim();}
	private static String textOrDirect(final Element parent, final String name)
	{
		final List<Element> values = children(parent, name);
		return values.isEmpty() ? directText(parent) : values.get(0).getTextContent().trim();
	}
	private static String attributeOrChild(final Element parent, final String name)
	{
		final String attribute = parent.getAttribute(name).trim();
		final List<Element> values = children(parent, name);
		return !attribute.isEmpty() ? attribute : values.isEmpty() ? "" : values.get(0).getTextContent().trim();
	}
	private static String directText(final Element parent)
	{
		final StringBuilder result = new StringBuilder();
		final NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++)
		{
			final Node node = nodes.item(i);
			if (node.getNodeType() == Node.TEXT_NODE)
			{
				final String value = node.getTextContent().trim();
				if (!value.isEmpty())
				{
					if (result.length() > 0) result.append(' ');
					result.append(value);
				}
			}
		}
		return result.toString();
	}
	private static String enumValue(final String value)
	{
		final String normalized = value.trim().toUpperCase(java.util.Locale.ENGLISH).replace('-', '_');
		return "PENDING".equals(normalized) ? "PLANNED" : normalized;
	}
	private static String itemType(final String category)
	{
		if ("fixed".equals(category)) return "FIX";
		if ("security".equals(category)) return "SECURITY";
		if ("known-issue".equals(category)) return "KNOWN_ISSUE";
		if ("upgrade".equals(category)) return "UPGRADE";
		return "FEATURE";
	}
	/**
	 * The status of the Release table for an entry of the manifest. A formal release is {@code current} (one only),
	 * {@code supported} or {@code archived}; a development increment is {@code in-progress} or {@code complete}, and a
	 * complete one is shown as ARCHIVED (the table has no other value for "closed"). Until 1.5.0 the attribute was not read:
	 * every formal release was CURRENT and every increment IN_PROGRESS. An unknown value keeps that old reading; the
	 * manifest test refuses it at build time.
	 */
	static String releaseStatus(final boolean formal, final String declared)
	{
		final String value = declared == null ? "" : declared.trim().toLowerCase(java.util.Locale.ENGLISH);
		if (formal)
		{
			return "supported".equals(value) ? "SUPPORTED" : "archived".equals(value) ? "ARCHIVED" : "CURRENT";
		}
		return "complete".equals(value) ? "ARCHIVED" : "draft".equals(value) ? "DRAFT" : "IN_PROGRESS";
	}
	/**
	 * Whether the status of an installed release row follows the packaged notes: only a row the module owns, and only when
	 * the value differs (1.4.2 was installed as CURRENT; once 1.5.0 is the current one it becomes SUPPORTED). A customer
	 * row is never touched, and nothing else of an installed row is rewritten.
	 */
	static boolean updatePackagedStatus(final String existingOwnership, final String existingStatus, final String packagedStatus)
	{
		return "PACKAGED".equals(existingOwnership) && packagedStatus != null && !packagedStatus.equals(existingStatus);
	}
	/** Existing records of either owner are authoritative and therefore skipped. */
	static boolean installPackagedRelease(final String existingOwnership){return existingOwnership==null;}
	static boolean seedPackagedChildren(final String existingOwnership){return existingOwnership==null||"PACKAGED".equals(existingOwnership);}
	static boolean createMissingChild(final boolean exists){return !exists;}
}
