package com.on.ebx.connect.connectors;
import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Xml;
import com.on.ebx.connect.spi.ConnectorException;
/** Strict local file ingestion boundaries, without changing shared API parsers. */
final class SafeTextDocuments {
 static final int MAX_DEPTH=64,MAX_NODES=250000,MAX_VALUE=1048576;
 private SafeTextDocuments(){}
 static JsonNode json(String text,int[] remaining)throws ConnectorException {
  try(JsonParser parser=Json.mapper().getFactory().createParser(text)) {
   parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
int depth = 0, roots = 0;
JsonToken token;
   while((token=parser.nextToken())!=null) {
    if(--remaining[0]<0)throw new IllegalArgumentException();
    if(depth==0&&!token.isStructEnd())roots++;
    if(token.isStructStart()&&++depth>MAX_DEPTH)throw new IllegalArgumentException();
    if(token.isStructEnd())depth--;
    if((token==JsonToken.FIELD_NAME||token==JsonToken.VALUE_STRING||token.isNumeric())&&parser.getTextLength()>MAX_VALUE)throw new IllegalArgumentException();
   }
   if(roots!=1||depth!=0)throw new IllegalArgumentException();
   return Json.mapper().readTree(text);
  }catch(Exception e){throw new ConnectorException("Invalid JSON document or parser safety limit exceeded");}
 }
 static Document xml(String text)throws ConnectorException {
  try {
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
f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", String.valueOf(MAX_DEPTH));
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
Document doc = b.parse(new InputSource(new StringReader(text)));
Deque<Node> pending = new ArrayDeque<Node>();
pending.push(doc);
int count = 0;
   while(!pending.isEmpty()){
    Node node = pending.pop();
    if (++count > MAX_NODES) throw new IllegalArgumentException();
    if(node.getNodeValue()!=null&&node.getNodeValue().length()>MAX_VALUE)throw new IllegalArgumentException();
    if(node instanceof Element){
     if("http://www.w3.org/2001/XInclude".equals(node.getNamespaceURI()))throw new IllegalArgumentException();
     Map<String,String> names=new HashMap<String,String>();
     for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element)namespace(names,child);
    names.clear();
    NamedNodeMap attributes = node.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++)
    {
        Node a = attributes.item(i);
        if (a.getNodeName().startsWith("xmlns")) continue;
        namespace(names, a);
        if (++count > MAX_NODES || a.getNodeValue().length() > MAX_VALUE) throw new IllegalArgumentException();
    }
    }
    for(Node c=node.getLastChild();c!=null;c=c.getPreviousSibling())pending.push(c);
   }return doc;
  }catch(Exception e){throw new ConnectorException("Invalid xml content or parser safety limit exceeded");}
 }
private static void namespace(Map<String, String> seen, Node node)
{
    String name = Xml.localName(node), ns = node.getNamespaceURI() == null ? "" : node.getNamespaceURI();
    if (seen.containsKey(name) && !seen.get(name).equals(ns)) throw new IllegalArgumentException();
    seen.put(name, ns);
}
}
