package com.on.ebx.connect.connectors;
import java.util.*;
import com.on.ebx.connect.spi.ConnectorException;
public final class TextDocumentSafetyTest {
 interface Run{void run()throws Exception;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void rejected(Run r)throws Exception{try{r.run();throw new AssertionError("Unsafe content accepted");}catch(ConnectorException e){check(!e.toString().contains("private-marker"),"no raw marker in error");check(e.getCause()==null,"no raw parser exception cause");}}
 public static void main(String[] args)throws Exception {
  rejected(()->FileFormats.parse("{\"x\":1,\"x\":\"private-marker\"}","json",',',20));
  rejected(()->FileFormats.parse("{} {\"private-marker\":1}","json",',',20));
  rejected(()->FileFormats.parse("{\"rows\":[],\"data\":[]}","json",',',20));
  rejected(()->FileFormats.parse("{}\n{\"x\":1,\"x\":\"private-marker\"}","ndjson",',',1));
  rejected(()->FileFormats.parse("{\"broken\":private-marker}","json",',',20));
  rejected(()->SafeTextDocuments.json("{\"x\":1}",new int[]{2}));
  int[] sharedBudget={4};SafeTextDocuments.json("{}",sharedBudget);SafeTextDocuments.json("{}",sharedBudget);
  rejected(()->SafeTextDocuments.json("{}",sharedBudget));
  String deep=String.join("",Collections.nCopies(70,"["))+"0"+String.join("",Collections.nCopies(70,"]"));
  rejected(()->FileFormats.parse(deep,"json",',',20));
  String xml=String.join("",Collections.nCopies(70,"<x>"))+"private-marker"+String.join("",Collections.nCopies(70,"</x>"));
  rejected(()->FileFormats.parse(xml,"xml",',',20));
  rejected(()->FileFormats.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private-marker'>]><x>&e;</x>","xml",',',20));
  rejected(()->FileFormats.parse("<r xmlns:a='urn:a' xmlns:b='urn:b'><a:x>one</a:x><b:x>private-marker</b:x></r>","xml",',',20));
  rejected(()->FileFormats.parse("<r xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='file:///private-marker'/></r>","xml",',',20));
  rejected(()->FileFormats.parse(new byte[]{(byte)0xc3,0x28},"json",',',20,100));
  rejected(()->FileFormats.parse(new byte[0],"json",',',20,0));
  check(FileFormats.parse("[{\"x\":1},{\"x\":2}]","json",',',1).path("truncated").asBoolean(),"JSON truncation explicit");
  check(FileFormats.parse("{}\n{}","ndjson",',',1).path("truncated").asBoolean(),"NDJSON truncation explicit");
  check(FileFormats.parse("<rows><r>one</r><r>two</r></rows>","xml",',',1).path("truncated").asBoolean(),"XML truncation explicit");
  check(FileFormats.parse("{\"rows\":[{\"x\":1}]}","json",',',20).path("count").asInt()==1,"normal envelope retained");
  System.out.println("TextDocumentSafetyTest PASS: strict JSON/NDJSON, bounded depth, UTF8, safe XML, no raw diagnostics and truncation");
 }
}
