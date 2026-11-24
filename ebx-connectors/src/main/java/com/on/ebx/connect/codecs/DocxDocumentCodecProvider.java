package com.on.ebx.connect.codecs;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Explicit format=docx only: bounded main-body chunk extraction, never document execution or indexing. */
public final class DocxDocumentCodecProvider implements DocumentCodecProvider {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String PART="word/document.xml";
    private static final int BYTES=8*1024*1024,NODES=100000,TEXT=1024*1024,CHUNK=4096;
    public Set<String> getFormats(){return Collections.singleton("docx");}
    public String getContentType(String format){return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
    public byte[] serialise(List<JsonNode> rows,String format,int maxBytes)throws ConnectorException {
        throw new ConnectorException("DOCX extraction is read-only; document generation is unsupported");
    }
    public ObjectNode parse(byte[] content,String format,int maxRows,int maxBytes)throws ConnectorException {
        if(!"docx".equals(format))throw new ConnectorException("DOCX extraction accepts .docx only; legacy/encrypted Word formats are unsupported");
        if(maxRows<1||maxBytes<1||content==null||content.length>Math.min(maxBytes,BYTES))throw new ConnectorException("DOCX input exceeds valid configured limits");
        Map<String, byte[]> parts = unzip(content, Math.min(maxBytes, BYTES));
        validatePackage(parts);
        Document document=xml(parts.get(PART));
        if(!W.equals(document.getDocumentElement().getNamespaceURI())||!"document".equals(document.getDocumentElement().getLocalName()))throw new ConnectorException("Unsupported DOCX document namespace");
        validateTree(document);
        NodeList bodies = document.getElementsByTagNameNS(W, "body");
        if (bodies.getLength() != 1) throw new ConnectorException("Unsupported DOCX body structure");
        ObjectNode out = Json.object();
        ArrayNode rows = out.putArray("rows");
        ObjectNode meta = out.putObject("provenance");
        meta.put("format", "docx");
        meta.put("extractionScope", "MAIN_BODY_PARAGRAPHS_AND_TABLE_CELLS");
        meta.put("part", PART);
        meta.put("offsetUnit", "UTF-16 code units in extracted block text");
        meta.put("pageNumbersAvailable", false);
        meta.put("execution", "NONE");
        ArrayNode omitted = meta.putArray("omittedParts");
        for (String part:parts.keySet()) if (part.matches("word/(header|footer)[0-9]+\\.xml")) omitted.add(part);
        int block = 0, extracted = 0, limit = Math.min(maxRows, 10000);
        boolean truncated = false;
        outer:for(Element element:children((Element)bodies.item(0))) {
            if ("sectPr".equals(element.getLocalName())) continue;
            block++;
            if(!W.equals(element.getNamespaceURI()))throw new ConnectorException("Unsupported DOCX body element");
            if("p".equals(element.getLocalName())) {
                String text = text(element), style = style(element);
                extracted += text.length();
                if (extracted > TEXT) throw new ConnectorException("DOCX extracted text limit exceeded");
                if (!append(rows, text, style.matches("Heading[1-9]") ? "HEADING" : "PARAGRAPH", style, block, 0, 0, limit))
                {
                    truncated = true;
                    break;
                }
            }else if("tbl".equals(element.getLocalName())) {
                int r=0;for(Element tr:children(element)) {
                    if (!"tr".equals(tr.getLocalName())) continue;
                    r++;
                    int c = 0;
                    for(Element tc:children(tr)) {
                        if (!"tc".equals(tc.getLocalName())) continue;
                        c++;
                        if(tc.getElementsByTagNameNS(W,"tbl").getLength()>0)throw new ConnectorException("Nested DOCX tables are unsupported");
                        StringBuilder cell = new StringBuilder();
                        for (Element p:children(tc)) if ("p".equals(p.getLocalName()))
                        {
                            if (cell.length() > 0) cell.append('\n');
                            cell.append(text(p));
                        }
                        extracted += cell.length();
                        if (extracted > TEXT) throw new ConnectorException("DOCX extracted text limit exceeded");
                        if (!append(rows, cell.toString(), "TABLE_CELL", "", block, r, c, limit))
                        {
                            truncated = true;
                            break outer;
                        }
                    }
                }
            }else throw new ConnectorException("Unsupported DOCX body content");
        }
        out.put("count", rows.size());
        out.put("truncated", truncated);
        return out;
    }
    private static boolean append(ArrayNode rows,String text,String kind,String style,int block,int row,int cell,int limit) {
        if(text.isEmpty())return true;
        for(int start=0;start<text.length();) {
            if (rows.size() >= limit) return false;
            int end = Math.min(text.length(), start+CHUNK);
            if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
            ObjectNode chunk = rows.addObject();
            chunk.put("kind", kind);
            chunk.put("text", text.substring(start, end));
            chunk.put("styleId", style);
            chunk.put("part", PART);
            chunk.put("blockIndex", block);
            chunk.put("textStart", start);
            chunk.put("textEnd", end);
            String location = PART+"#block="+block;
            if (row > 0)
            {
                chunk.put("tableRow", row);
                chunk.put("tableCell", cell);
                location += "/row="+row+"/cell="+cell;
            }
            chunk.put("location", location);
            start = end;
        }return true;
    }
    private static String style(Element p)throws ConnectorException
    {
        NodeList styles = p.getElementsByTagNameNS(W, "pStyle");
        String style = styles.getLength() == 0 ? "" : ((Element)styles.item(0)).getAttributeNS(W, "val");
        if (style.length() > 256) throw new ConnectorException("DOCX style identifier limit exceeded");
        return style;
    }
    private static String text(Element root)throws ConnectorException {
        StringBuilder out = new StringBuilder();
        Deque<Node> pending = new ArrayDeque<Node>();
        pending.push(root);
        while(!pending.isEmpty()) {Node n=pending.pop();if(n instanceof Element&&W.equals(n.getNamespaceURI())) {
            if ("t".equals(n.getLocalName()))
            {
                out.append(n.getTextContent());
                if (out.length() > TEXT) throw new ConnectorException("DOCX block text limit exceeded");
                continue;
            }
            if ("tab".equals(n.getLocalName())) out.append('\t');
            if ("br".equals(n.getLocalName()) || "cr".equals(n.getLocalName())) out.append('\n');
        }for(Node c=n.getLastChild();c!=null;c=c.getPreviousSibling())pending.push(c);}
        return out.toString();
    }
    private static List<Element> children(Element parent)
    {
        List<Element> out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) out.add((Element)n);
        return out;
    }
    private static void validateTree(Document doc)throws ConnectorException {
        Set<String> unsupported=new HashSet<String>(Arrays.asList("altChunk","object","drawing","pict","instrText","fldSimple","fldChar","ins","del","sdt","subDoc"));
        Deque<Node> pending = new ArrayDeque<Node>();
        pending.push(doc);
        int count = 0;
        while (!pending.isEmpty())
        {
            Node n = pending.pop();
            if (++count > NODES) throw new ConnectorException("DOCX XML node limit exceeded");
            if(n instanceof Element&&unsupported.contains(n.getLocalName()))throw new ConnectorException("Unsupported DOCX active, embedded or revision content");
            for(Node c=n.getLastChild();c!=null;c=c.getPreviousSibling())pending.push(c);
        }
    }
    private static Map<String,byte[]> unzip(byte[] content,int limit)throws ConnectorException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry e;
            int count = 0;
            byte[] buffer = new byte[4096];
            while((e=zip.getNextEntry())!=null){String name=e.getName(),lower=name.toLowerCase(Locale.ROOT);
                if(++count>64||name.length()>256||name.startsWith("/")||name.contains("..")||name.contains("\\")||name.contains(":")||out.containsKey(name))throw new ConnectorException("Unsafe or duplicate DOCX package part");
                if(lower.contains("vbaproject")||lower.contains("embeddings/")||lower.contains("activex/")||lower.contains("encrypted"))throw new ConnectorException("DOCX macros, embeddings and encrypted packages are unsupported");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (int n; (n = zip.read(buffer)) != -1;)
                {
                    total += n;
                    if (total > limit) throw new ConnectorException("Expanded DOCX exceeds max.bytes");
                    bytes.write(buffer, 0, n);
                }
                out.put(name, bytes.toByteArray());
            }
    }
    catch (ConnectorException e)
    {
        throw e;
    }
    catch (Exception e)
    {
        throw new ConnectorException("Invalid DOCX package");
    }
        return out;
    }
    private static void validatePackage(Map<String,byte[]> parts)throws ConnectorException {
        if(!parts.containsKey(PART)||!parts.containsKey("_rels/.rels")||!parts.containsKey("[Content_Types].xml"))throw new ConnectorException("Unsupported or encrypted DOCX package");
        boolean documentType = false;
        NodeList types = xml(parts.get("[Content_Types].xml")).getElementsByTagNameNS("*", "Override");
        for (int i = 0; i < types.getLength(); i++)
        {
            Element type = (Element)types.item(i);
            String mime = type.getAttribute("ContentType");
            if (mime.toLowerCase(Locale.ROOT).contains("macroenabled")) throw new ConnectorException("DOCX macros are unsupported");
            if (("/"+PART).equals(type.getAttribute("PartName")) && "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(mime)) documentType = true;
        }
        if (!documentType) throw new ConnectorException("Unsupported DOCX main content type");
        int roots = 0;
        for(Map.Entry<String,byte[]> part:parts.entrySet())if(part.getKey().endsWith(".rels")) {
            Document relationships = xml(part.getValue());
            if (!REL.equals(relationships.getDocumentElement().getNamespaceURI()) || !"Relationships".equals(relationships.getDocumentElement().getLocalName())) throw new ConnectorException("Unsupported DOCX relationship namespace");
            NodeList links=relationships.getElementsByTagNameNS(REL,"Relationship");
            for(int i=0;i<links.getLength();i++){Element link=(Element)links.item(i);
                if("External".equalsIgnoreCase(link.getAttribute("TargetMode"))||link.getAttribute("Target").contains(":")||link.getAttribute("Target").startsWith("//"))throw new ConnectorException("DOCX external references are unsupported");
                if("_rels/.rels".equals(part.getKey())&&PART.equals(link.getAttribute("Target"))&&"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument".equals(link.getAttribute("Type")))roots++;
            }
        }if(roots!=1)throw new ConnectorException("Unsupported DOCX document relationship");
    }
    private static Document xml(byte[] content)throws ConnectorException {
        try
        {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new DefaultHandler()
            {
                @Override
                public void error(SAXParseException e)throws SAXException
                {
                    throw e;
                }
                @Override
                public void fatalError(SAXParseException e)throws SAXException
                {
                    throw e;
                }
            });
            return b.parse(new ByteArrayInputStream(content));
        }catch(Exception e){throw new ConnectorException("Invalid or unsafe DOCX XML");}
    }
}
