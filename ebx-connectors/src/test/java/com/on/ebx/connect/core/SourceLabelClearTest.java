package com.on.ebx.connect.core;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.ValueContextForUpdate;
public final class SourceLabelClearTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static MappingEngine.Values compute(String table,String field,MappingRule.Policy policy,boolean present) {
  MappingDefinition m=new MappingDefinition(1,"fixture","catalog",MappingDefinition.Direction.INBOUND,"fixture","B","D",table,"",true);
  m.addRule(new MappingRule(1,"label",field,"",policy,""));
  return MappingEngine.compute(m,present?Json.object().putNull("label"):Json.object(),null);
 }
 public static void main(String[] args) {
  MappingEngine.Values v=compute("/root/Column","sourceRetention",MappingRule.Policy.ALWAYS,true);
  check(v.getByPath().containsKey("./sourceRetention")&&v.get("sourceRetention")==null,"explicit-null intent retained");
  check(compute("/root/Column","sourceRetention",MappingRule.Policy.ALWAYS,false).getByPath().isEmpty(),"missing is not deletion");
  check(compute("/root/Column","sensitivity",MappingRule.Policy.ALWAYS,true).getByPath().isEmpty(),"curated sensitivity protected");
  check(compute("/root/Column","tags",MappingRule.Policy.ALWAYS,true).getByPath().isEmpty(),"curated tags protected");
  check(compute("/root/Column","sourceRetention",MappingRule.Policy.IF_EMPTY,true).getByPath().isEmpty(),"respect mapping policy");
  check(compute("/root/Other","sourceRetention",MappingRule.Policy.ALWAYS,true).getByPath().isEmpty(),"limit target tables");
  AtomicBoolean cleared=new AtomicBoolean();
  SchemaNode field=(SchemaNode)Proxy.newProxyInstance(SchemaNode.class.getClassLoader(),new Class<?>[]{SchemaNode.class},(p,m,a)->true);
  SchemaNode table=(SchemaNode)Proxy.newProxyInstance(SchemaNode.class.getClassLoader(),new Class<?>[]{SchemaNode.class},(p,m,a)->field);
  ValueContextForUpdate update=(ValueContextForUpdate)Proxy.newProxyInstance(ValueContextForUpdate.class.getClassLoader(),new Class<?>[]{ValueContextForUpdate.class},(p,m,a)->{
   check("setValue".equals(m.getName())&&a[0]==null,"clear uses typed null rather than empty XS string");cleared.set(true);return null;
  });
  MappingResult result=MappingEngine.applyValues(v,null,update,table);
  check(cleared.get()&&result.getErrors().isEmpty()&&result.getChanges().size()==1,"null staged and auditable");
  System.out.println("SourceLabelClearTest PASS");
 }
}
