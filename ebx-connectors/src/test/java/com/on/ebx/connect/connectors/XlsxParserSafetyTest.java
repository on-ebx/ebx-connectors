package com.on.ebx.connect.connectors;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import com.on.ebx.connect.codecs.XlsxDocumentCodecProvider;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
import com.fasterxml.jackson.databind.JsonNode;
public final class XlsxParserSafetyTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 interface Run{void run()throws Exception;}
 static void reject(String reason,Run run)throws Exception{try{run.run();throw new AssertionError("accepted "+reason);}catch(ConnectorException expected){check(expected.getMessage().contains(reason),"expected "+reason+", got "+expected.getMessage());}}
 static byte[] change(byte[] input,String name,String body)throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(input));ZipOutputStream target=new ZipOutputStream(out)){
   ZipEntry e;boolean replaced=false;byte[] buffer=new byte[4096];
   while((e=zip.getNextEntry())!=null){target.putNextEntry(new ZipEntry(e.getName()));if(name.equals(e.getName())){target.write(body.getBytes(StandardCharsets.UTF_8));replaced=true;}else for(int n;(n=zip.read(buffer))!=-1;)target.write(buffer,0,n);target.closeEntry();}
   if(!replaced){target.putNextEntry(new ZipEntry(name));target.write(body.getBytes(StandardCharsets.UTF_8));target.closeEntry();}
  }return out.toByteArray();
 }
 static String sheet(String cell){return "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c t=\"inlineStr\"><is><t>header</t></is></c></row><row r=\"2\">"+cell+"</row></sheetData></worksheet>";}
 public static void main(String[] args)throws Exception {
  XlsxDocumentCodecProvider codec=new XlsxDocumentCodecProvider();
  byte[] base=codec.serialise(Arrays.<JsonNode>asList(Json.object().put("header","one"),Json.object().put("header","two")),"xlsx",100000);
  JsonNode parsed=codec.parse(base,"xlsx",1,100000);
  check(parsed.path("rows").size()==1&&parsed.path("truncated").asBoolean(),"explicit row truncation");
  check(parsed.path("provenance").path("sourceRows").get(0).asInt()==2,"worksheet row provenance");
  check("Data".equals(parsed.path("provenance").path("sheetName").asText()),"actual sheet name");
  reject("unique and increasing",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"A1\"><v>7</v></c>").replace("row r=\"2\"","row r=\"1\"")),"xlsx",5,100000));
  reject("unique and increasing",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"A2\"><v>7</v></c>").replace("row r=\"1\"","row r=\"3\"")),"xlsx",5,100000));
  reject("parent row",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"A9\"><v>7</v></c>")),"xlsx",5,100000));
  reject("row maximum",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c><v>7</v></c>").replace("row r=\"2\"","row r=\"1048577\"")),"xlsx",5,100000));
  reject("worksheet relationship",()->codec.parse(change(base,"xl/_rels/workbook.xml.rels","<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" Type=\"not-a-worksheet\"/></Relationships>"),"xlsx",5,100000));
  reject("column limit",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"ZZZ2\"><v>7</v></c>")),"xlsx",5,100000));
  reject("Invalid Excel cell",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"ZZZZZZZZZZZZ2\"><v>7</v></c>")),"xlsx",5,100000));
  reject("formulas",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c r=\"A2\"><f>WEBSERVICE(\"https://invalid.example\")</f><v>cached-secret</v></c>")),"xlsx",1,100000));
  reject("external relationships",()->codec.parse(change(base,"extra.rels","<Relationships><Relationship TargetMode=\"External\" Target=\"https://invalid.example\"/></Relationships>"),"xlsx",5,100000));
  reject("macros",()->codec.parse(change(base,"xl/vbaProject.bin","synthetic"),"xlsx",5,100000));
  reject("Unsafe",()->codec.parse(change(base,"../payload.xml","synthetic"),"xlsx",5,100000));
  reject("Expanded",()->codec.parse(change(base,"padding.xml",String.join("",Collections.nCopies(110000,"x"))),"xlsx",5,100000));
  reject("Invalid Excel XML",()->codec.parse(change(base,"xl/worksheets/sheet1.xml","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///must-not-open'>]><x>&e;</x>"),"xlsx",5,100000));
  reject("shared-string reference",()->codec.parse(change(base,"xl/worksheets/sheet1.xml",sheet("<c t=\"s\"><v>999</v></c>")),"xlsx",5,100000));
  System.out.println("XlsxParserSafetyTest PASS: bounded sparse references/ZIP expansion, rejected formulas/macros/external XML and extraction provenance");
 }
}
