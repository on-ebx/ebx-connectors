package com.on.ebx.connect.core;
import com.on.ebx.connect.spi.ConnectorConfig;
public final class PreviewConfigurationTest {
    static ConnectorConfig config(){ConnectorConfig c=new ConnectorConfig();c.setId("same-id");c.setType("jdbc");c.setBaseUrl("jdbc:synthetic");c.setPassword("synthetic-secret");c.setProperty("schema","one");return c;}
    public static void main(String[] args){
        ConnectorConfig original=config();PreviewConfiguration snapshot=new PreviewConfiguration(original);
        snapshot.requireUnchanged(config());
        for(int i=0;i<8;i++){
            ConnectorConfig changed=config();
            switch(i){case 0:changed.setBaseUrl("different");break;case 1:changed.setPassword("changed-secret");break;
                case 2:changed.setProperty("schema","two");break;case 3:changed.setActive(false);break;
                case 4:changed.setProblem("synthetic-secret");break;case 5:changed.setType("other");break;
                case 6:changed.setUsername("different");break;default:changed=null;}
            try{snapshot.requireUnchanged(changed);throw new AssertionError("change accepted");}
            catch(SecurityException expected){if(expected.getMessage().contains("secret"))throw new AssertionError("secret in error");}
        }
        original.setProperty("schema","mutated");
        snapshot.requireUnchanged(config()); // snapshot must not share a mutable properties map
        ConnectorConfig a=config(),b=config();a.setProperty("z","1");a.setProperty("a","2");b.setProperty("a","2");b.setProperty("z","1");
        new PreviewConfiguration(a).requireUnchanged(b);
        System.out.println("PASS: preview same-ID configuration changes rejected, copied state and sanitized errors");
    }
}
