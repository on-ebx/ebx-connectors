package com.on.ebx.connect.connectors;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;
/** Read-only, bounded Drive file metadata and published label definitions. */
public final class GoogleDriveConnector extends AbstractRestConnector {
    private static final String SCOPE="https://www.googleapis.com/auth/drive.metadata.readonly https://www.googleapis.com/auth/drive.labels.readonly";
    private final GoogleAuth auth=new GoogleAuth();
    public String getType(){return "google-drive";}
    public String getLabel(){return "Google Drive";}
    public List<Operation> getOperations(){return Collections.singletonList(Operation.search("catalog","Inventory files and source retention/sensitivity labels","source"));}
    public ConnectorDescriptor getDescriptor(){
        ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"BUSINESS_APPS")
            .description("Metadata-only inventory; explicit shared drive or direct-child folder scope.")
            .authentication(AuthenticationMethod.SERVICE_ACCOUNT).authentication(AuthenticationMethod.BEARER_TOKEN)
            .capability(ConnectorCapability.DISCOVER_OBJECTS).apiVersion("Drive v3; Drive Labels v2");
        for(String k:new String[]{"driveId","folderId","retentionLabelId","retentionFieldId","retentionLabelName","retentionFieldName","sensitivityLabelId","sensitivityFieldId","sensitivityLabelName","sensitivityFieldName"})
            b.configurationProperty(ConfigurationPropertyDefinition.builder(k,ConfigurationPropertyType.STRING).required(false).build());
        b.configurationProperty(ConfigurationPropertyDefinition.builder("max",ConfigurationPropertyType.INTEGER).required(false).defaultValue(Integer.valueOf(200)).build());
        b.configurationProperty(ConfigurationPropertyDefinition.builder("maxRequests",ConfigurationPropertyType.INTEGER).required(false).defaultValue(Integer.valueOf(2000)).build());
        b.configurationProperty(ConfigurationPropertyDefinition.builder("serviceAccountJson",ConfigurationPropertyType.SECRET).required(false).build());
        b.configurationProperty(ConfigurationPropertyDefinition.builder("serviceAccountFile",ConfigurationPropertyType.FILE).required(false).build());
        for(Operation o:getOperations()) b.direction(ConnectorDirection.INBOUND).operation(new ConnectorOperationDescriptor(o.getName(),o.getKind(),o.getDescription(),Collections.singleton(ConnectorDirection.INBOUND),Collections.<ConnectorCapability>emptySet()));
        return b.build();
    }
    public JsonNode execute(ConnectorContext c,String op,Map<String,String> in,JsonNode payload)throws ConnectorException{
        Map<String,String> args=new LinkedHashMap<String,String>();
        for(String k:new String[]{"driveId","folderId","retentionLabelId","retentionFieldId","retentionLabelName","retentionFieldName","sensitivityLabelId","sensitivityFieldId","sensitivityLabelName","sensitivityFieldName","max","maxRequests"}) {
            String v = getConfig().getProperty(k, null);
            if (v != null) args.put(k, v);
        }
        if(in!=null)args.putAll(in);
        final String endpoint=endpoint();
        return new SharedDriveCatalog(new SharedDriveCatalog.Transport(){
            public JsonNode call(String method,String path)throws ConnectorException{
                if(!"GET".equals(method))throw new ConnectorException("Google Drive metadata is read-only");
                String url=path.startsWith("/labels-api/")?
                    (endpoint.startsWith("http://")?endpoint+"/labels-api":"https://drivelabels.googleapis.com/v2")+path.substring("/labels-api".length()):endpoint+path;
                try{
                    Egress.check(url);
                    Map<String,String> h=new LinkedHashMap<String,String>();
                    h.put("Authorization","Bearer "+auth.token(getConfig(),SCOPE));
                    HttpJsonClient.Response r=new HttpJsonClient(getConfig().getConnectTimeoutMs(),getConfig().getReadTimeoutMs(),"EBX-Connectors Google Drive")
                        .sendBytesWithoutRedirects(method,url,h,null,2097152);
                    if(!r.isSuccess())throw new ConnectorException("Google metadata request failed",r.getStatus(),null);
                    return r.getBody();
                }catch(java.io.IOException e){throw new ConnectorException("Google metadata transport failed",e);}
            }
        },endpoint,"google-drive","",args).run(op);
    }
    private String endpoint()throws ConnectorException{
        String s=getConfig().getBaseUrl();
        String e=s==null||s.trim().isEmpty()?"https://www.googleapis.com/drive/v3":s.trim().replaceAll("/+$","");
        URI u;
        try
        {
            u = URI.create(e);
        }
        catch (Exception ex)
        {
            throw new ConnectorException("Invalid Drive endpoint");
        }
        boolean official="https".equals(u.getScheme())&&"www.googleapis.com".equals(u.getHost())&&"/drive/v3".equals(u.getPath())&&(u.getPort()==-1||u.getPort()==443);
        boolean fixture="http".equals(u.getScheme())&&("localhost".equals(u.getHost())||"127.0.0.1".equals(u.getHost()));
        if((!official&&!fixture)||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new ConnectorException("Use official Drive API or explicit localhost fixture");
        return e;
    }
}
