package com.on.ebx.connect.codecs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DocumentCodecProvider;

/** Bounded OOXML workbook codec. Supports .xlsx only, one worksheet, scalar cells and no formulas/macros. */
public final class XlsxDocumentCodecProvider implements DocumentCodecProvider
{
	private static final int MAX_ENTRIES=64;
	private static final int MAX_EXPANDED_BYTES=16*1024*1024, MAX_COLUMNS=1024, MAX_CELL_CHARS=32767, MAX_SHEET_ROWS=10001;
	public Set<String> getFormats(){return Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(Arrays.asList("xlsx","excel")));}
	public String getContentType(final String f){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}

	public ObjectNode parse(final byte[] content,final String format,final int maxRows,final int maxBytes)throws ConnectorException
	{
		if(!"xlsx".equals(format))throw new ConnectorException("Excel codec supports .xlsx only; legacy .xls is not accepted");
		if(maxRows<1||maxBytes<1)throw new ConnectorException("Excel parser limits must be positive");
		if(content==null||content.length>Math.min(maxBytes,MAX_EXPANDED_BYTES))throw new ConnectorException("Excel input exceeds max.bytes");
		final Map<String,byte[]> entries=unzip(content,Math.min(maxBytes,MAX_EXPANDED_BYTES));
		final Element sheetInfo = worksheet(entries);
		final String sheetPath = "xl/worksheets/sheet1.xml";
		final List<String> shared = shared(entries.get("xl/sharedStrings.xml"));
		final Document doc = xml(entries.get(sheetPath));
		if(doc.getElementsByTagNameNS("*","f").getLength()>0)throw new ConnectorException("Excel formulas are unsupported; cached results are not treated as source values");
		final NodeList rowNodes = doc.getElementsByTagNameNS("*", "row");
		if (rowNodes.getLength() > MAX_SHEET_ROWS) throw new ConnectorException("Excel worksheet exceeds row parsing limit");
		int previousRow=0;
		for(int r=0;r<rowNodes.getLength();r++){
			Element row = (Element)rowNodes.item(r);
			int number = rowNumber(row, r+1);
			if (number <= previousRow) throw new ConnectorException("Excel row identities must be unique and increasing");
			previousRow = number;
			NodeList cells=row.getElementsByTagNameNS("*","c");
			for(int c=0;c<cells.getLength();c++){String ref=((Element)cells.item(c)).getAttribute("r");if(!ref.isEmpty()){
				column(ref);
				int cellRow = Integer.parseInt(ref.replaceFirst("^[A-Z]+", ""));
				if(cellRow!=number)throw new ConnectorException("Excel cell row reference does not match its parent row");
			}}
		}
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		final ObjectNode provenance = out.putObject("provenance");
		provenance.put("format", "xlsx");
		provenance.put("sheetName", sheetInfo.getAttribute("name"));
		provenance.put("sheetPath", sheetPath);
		provenance.put("formulaPolicy", "REJECTED");
		provenance.put("externalReferences", "REJECTED");
		final ArrayNode sourceRows = provenance.putArray("sourceRows");
		out.put("truncated", rowNodes.getLength() > maxRows+1L);
		if (rowNodes.getLength() == 0) return out;
		provenance.put("headerRow",rowNumber((Element)rowNodes.item(0),1));
		final List<String> header = cells((Element)rowNodes.item(0), shared);
		final java.util.HashSet<String> names = new java.util.HashSet<String>();
		for(String h:header)if(!h.trim().isEmpty()&&!names.add(h.trim()))throw new ConnectorException("Duplicate Excel header names are unsupported");
		for (int r = 1; r < rowNodes.getLength() && rows.size() < maxRows; r++)
		{
			final List<String> values = cells((Element)rowNodes.item(r), shared);
			if (values.size() > header.size()) throw new ConnectorException("Excel data extends beyond the header columns");
			final ObjectNode record = rows.addObject();
			sourceRows.add(rowNumber((Element)rowNodes.item(r), r+1));
			for (int c = 0; c < header.size(); c++)
			{
				final String name = header.get(c).trim();
				if (!name.isEmpty()) record.put(name, c < values.size() ? values.get(c) : "");
			}
		}
		return out;
	}

	public byte[] serialise(final List<JsonNode> rows,final String format,final int maxBytes)throws ConnectorException
	{
		if (!"xlsx".equals(format)) throw new ConnectorException("Excel codec supports .xlsx only");
		final List<String> header = new ArrayList<String>();
		if (!rows.isEmpty())
		{
			if (!rows.get(0).isObject()) throw new ConnectorException("Excel rows must be JSON objects");
			rows.get(0).fieldNames().forEachRemaining(header::add);
		}
		final StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
		appendRow(sheet, header);
		for (final JsonNode row:rows)
		{
			if (!row.isObject()) throw new ConnectorException("Excel rows must be JSON objects");
			final List<String> values = new ArrayList<String>();
			for (final String h:header)
			{
				final JsonNode v = row.get(h);
				if (v != null && (v.isContainerNode())) throw new ConnectorException("Excel cells must be scalar values");
				values.add(v == null || v.isNull() ? "" : v.asText());
			}
			appendRow(sheet, values);
		}
		sheet.append("</sheetData></worksheet>");
		try
		{
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final ZipOutputStream zip = new ZipOutputStream(bytes);
			put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
			put(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
			put(zip, "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
			put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
			put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
			zip.close();
			if (bytes.size() > maxBytes) throw new ConnectorException("Generated Excel workbook exceeds max.bytes");
			return bytes.toByteArray();
		}
		catch (final ConnectorException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Unable to generate Excel workbook", e);
		}
	}

	private static Map<String, byte[]> unzip(final byte[] content, final int maxBytes)throws ConnectorException
	{
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content)))
		{
			final Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
			ZipEntry e;
			int entries = 0;
			long total = 0;
			while ((e = zip.getNextEntry()) != null)
			{
				String name = e.getName();
				if (++entries > MAX_ENTRIES || name.contains("..") || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.length() > 256 || out.containsKey(name)) throw new ConnectorException("Unsafe, duplicate or oversized Excel package");
				String lower = name.toLowerCase(java.util.Locale.ROOT);
				if (lower.contains("vbaproject") || lower.startsWith("xl/externallinks/") || lower.startsWith("xl/embeddings/")) throw new ConnectorException("Excel macros, embedded objects and external links are unsupported");
				final ByteArrayOutputStream b = new ByteArrayOutputStream();
				final byte[] buffer = new byte[4096];
				for (int n; (n = zip.read(buffer)) >= 0;)
				{
					total += n;
					if (total > maxBytes) throw new ConnectorException("Expanded Excel workbook exceeds max.bytes");
					b.write(buffer, 0, n);
				}
				out.put(name, b.toByteArray());
			}
			return out;
		}
		catch (final ConnectorException x)
		{
			throw x;
		}
		catch (final Exception x)
		{
			throw new ConnectorException("Invalid Excel package", x);
		}
	}
	private static Document xml(final byte[] data)throws ConnectorException
	{
		try
		{
			final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
			builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()
			{
				@Override
				public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException
				{
					throw e;
				}
				@Override
				public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException
				{
					throw e;
				}
			});
			return builder.parse(new ByteArrayInputStream(data));
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Invalid Excel XML", e);
		}
	}
	private static List<String> shared(final byte[] data)throws ConnectorException
	{
		final List<String> out = new ArrayList<String>();
		if (data == null) return out;
		final NodeList values = xml(data).getElementsByTagNameNS("*", "si");
		if (values.getLength() > 100000) throw new ConnectorException("Excel shared-string limit exceeded");
		for (int i = 0; i < values.getLength(); i++)
		{
			String value = values.item(i).getTextContent();
			if (value.length() > MAX_CELL_CHARS) throw new ConnectorException("Excel shared-string text limit exceeded");
			out.add(value);
		}
		return out;
	}
	private static List<String> cells(final Element row, final List<String> shared)throws ConnectorException
	{
		final List<String> out = new ArrayList<String>();
		final java.util.Set<Integer> occupied = new java.util.HashSet<Integer>();
		final NodeList cells = row.getElementsByTagNameNS("*", "c");
		if (cells.getLength() > MAX_COLUMNS) throw new ConnectorException("Excel column limit exceeded");
		for (int i = 0; i < cells.getLength(); i++)
		{
			final Element c = (Element)cells.item(i);
			final String ref = c.getAttribute("r");
			final int column = ref.isEmpty() ? i : column(ref);
			if (!occupied.add(column)) throw new ConnectorException("Duplicate Excel cell reference");
			while (out.size() <= column) out.add("");
			final NodeList t = c.getElementsByTagNameNS("*", "t"), v = c.getElementsByTagNameNS("*", "v");
			String value = t.getLength() > 0 ? t.item(0).getTextContent() : v.getLength() > 0 ? v.item(0).getTextContent() : "";
			if ("s".equals(c.getAttribute("t")))
			{
				try
				{
					value = shared.get(Integer.parseInt(value));
				}
				catch (final RuntimeException e)
				{
					throw new ConnectorException("Invalid Excel shared-string reference");
				}
			}
			if (value.length() > MAX_CELL_CHARS) throw new ConnectorException("Excel cell text limit exceeded");
			out.set(column, value);
		}
		return out;
	}
	private static int column(final String ref)throws ConnectorException
	{
		if (!ref.matches("[A-Z]{1,3}[1-9][0-9]{0,6}")) throw new ConnectorException("Invalid Excel cell reference");
		int n = 0, i = 0;
		while (i < ref.length() && ref.charAt(i) >= 'A' && ref.charAt(i) <= 'Z')
		{
			n = n*26+(ref.charAt(i)-'A'+1);
			if (n > MAX_COLUMNS) throw new ConnectorException("Excel column limit exceeded");
			i++;
		}
		return n-1;
	}
	private static int rowNumber(Element row, int fallback)throws ConnectorException
	{
		String r = row.getAttribute("r");
		if (r.isEmpty()) return fallback;
		if (!r.matches("[1-9][0-9]{0,6}")) throw new ConnectorException("Invalid Excel row reference");
		int n = Integer.parseInt(r);
		if (n > 1048576) throw new ConnectorException("Invalid Excel row reference: exceeds worksheet row maximum");
		return n;
	}
	private static Element worksheet(Map<String,byte[]> entries)throws ConnectorException{
		if(!entries.containsKey("xl/workbook.xml")||!entries.containsKey("xl/_rels/workbook.xml.rels")||!entries.containsKey("xl/worksheets/sheet1.xml"))throw new ConnectorException("Excel workbook has no supported first worksheet");
		for (Map.Entry<String, byte[]> e:entries.entrySet()) if (e.getKey().endsWith(".rels"))
		{
			NodeList links = xml(e.getValue()).getElementsByTagNameNS("*", "Relationship");
			for (int i = 0; i < links.getLength(); i++) if ("External".equalsIgnoreCase(((Element)links.item(i)).getAttribute("TargetMode"))) throw new ConnectorException("Excel external relationships are unsupported");
		}
		NodeList sheets = xml(entries.get("xl/workbook.xml")).getElementsByTagNameNS("*", "sheet");
		if (sheets.getLength() != 1) throw new ConnectorException("Excel codec supports exactly one worksheet");
		Element sheet = (Element)sheets.item(0);
		String id = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
		NodeList links = xml(entries.get("xl/_rels/workbook.xml.rels")).getElementsByTagNameNS("*", "Relationship");
		int matches = 0;
		for (int i = 0; i < links.getLength(); i++)
		{
			Element link = (Element)links.item(i);
			if (id.equals(link.getAttribute("Id")) && "worksheets/sheet1.xml".equals(link.getAttribute("Target")) && "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet".equals(link.getAttribute("Type"))) matches++;
		}
		if (id.isEmpty() || matches != 1) throw new ConnectorException("Excel worksheet relationship is unsupported or ambiguous");
		return sheet;
	}
	private static void appendRow(final StringBuilder b, final List<String> values)
	{
		b.append("<row>");
		for (final String v:values) b.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(v)).append("</t></is></c>");
		b.append("</row>");
	}
	private static String escape(final String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
	private static void put(final ZipOutputStream zip, final String name, final String value)throws Exception
	{
		zip.putNextEntry(new ZipEntry(name));
		zip.write(value.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
