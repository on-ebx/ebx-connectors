package com.on.ebx.connect.connectors;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import com.on.ebx.connect.codecs.DocxDocumentCodecProvider;
import com.on.ebx.connect.spi.ConnectorException;
import com.fasterxml.jackson.databind.JsonNode;
public final class DocxExtractionTest {
 static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
 static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 interface Run{void run()throws Exception;}
 static void reject(String reason,Run r)throws Exception{try{r.run();throw new AssertionError("accepted "+reason);}catch(ConnectorException e){check(e.getMessage().contains(reason),e.getMessage());}}
 static void entry(ZipOutputStream zip,String name,String text)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
 static byte[] fixture(String body,String extraName,String extra)throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){
   entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
   entry(zip,"_rels/.rels","<Relationships xmlns=\""+REL+"\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
   entry(zip,"word/document.xml","<w:document xmlns:w=\""+W+"\"><w:body>"+body+"</w:body></w:document>");
   if(extraName!=null)entry(zip,extraName,extra);
  }return out.toByteArray();
 }
 static String p(String text){return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
 public static void main(String[] args)throws Exception{
  DocxDocumentCodecProvider codec=new DocxDocumentCodecProvider();
  String body="<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>"+p("Body")+"<w:tbl><w:tr><w:tc>"+p("Cell")+"</w:tc></w:tr></w:tbl>";
  byte[] bytes=fixture(body,null,null);JsonNode out=codec.parse(bytes,"docx",10,100000);
  check(out.path("rows").size()==3,"paragraph, heading and cell chunks");
  check("HEADING".equals(out.path("rows").get(0).path("kind").asText()),"heading style recorded");
  check(out.path("rows").get(2).path("location").asText().equals("word/document.xml#block=3/row=1/cell=1"),"table provenance");
  check(!out.path("provenance").path("pageNumbersAvailable").asBoolean(),"no invented page location");
  check(codec.parse(bytes,"docx",1,100000).path("truncated").asBoolean(),"chunk cap explicit");
  String longText=String.join("",Collections.nCopies(5000,"x"));JsonNode chunks=codec.parse(fixture(p(longText),null,null),"docx",10,100000);
  check(chunks.path("rows").size()==2&&chunks.path("rows").get(1).path("textStart").asInt()==4096,"bounded text chunks");
  reject("external",()->codec.parse(fixture(body,"word/_rels/document.xml.rels","<Relationships xmlns=\""+REL+"\"><Relationship TargetMode=\"External\" Target=\"https://must-not-call.invalid\"/></Relationships>"),"docx",10,100000));
  reject("active",()->codec.parse(fixture(p("safe")+"<w:p><w:fldSimple w:instr=\"INCLUDETEXT secret\"/></w:p>",null,null),"docx",1,100000));
  reject("macros",()->codec.parse(fixture(body,"word/vbaProject.bin","payload"),"docx",10,100000));
  reject("Unsafe",()->codec.parse(fixture(body,"../escape","payload"),"docx",10,100000));
  reject("Expanded",()->codec.parse(fixture(p(String.join("",Collections.nCopies(110000,"x"))),null,null),"docx",10,100000));
  reject("unsafe DOCX XML",()->codec.parse(fixture(body,"bad.rels","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///must-not-open'>]><x>&e;</x>"),"docx",10,100000));
  reject(".docx only",()->codec.parse(bytes,"doc",10,100000));
  reject("read-only",()->codec.serialise(Collections.emptyList(),"docx",100000));
  check("docx".equals(FileFormats.formatOf("docx","sample.docx")),"explicit opt-in format");
  check(!"docx".equals(FileFormats.formatOf("","sample.docx")),"no implicit new file harvesting");
  JsonNode routed=FileFormats.parse(bytes,"docx",',',10,100000);check(routed.path("rows").size()==3,"SPI provider routes explicit document parse");
  System.out.println("DocxExtractionTest PASS: explicit SPI routing, bounded main-body chunks/provenance, rejected external/active/legacy/unsafe content");
 }
}
