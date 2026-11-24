package com.on.ebx.connect.connectors;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.*;
/** Microsoft OneDrive metadata inventory, using an explicitly configured drive. */
public final class OneDriveConnector extends SharePointConnector {
    @Override public String getType(){return "onedrive";}
    @Override public String getLabel(){return "Microsoft OneDrive";}
    @Override public JsonNode execute(ConnectorContext context,String operation,Map<String,String> in,JsonNode payload)throws ConnectorException{
        if(!"catalog".equals(operation))throw new ConnectorException("OneDrive supports metadata catalog only");
        return super.execute(context,operation,in,payload);
    }
    @Override public ConnectorDescriptor getDescriptor(){
        ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"BUSINESS_APPS")
            .description("Microsoft OneDrive metadata inventory through Microsoft Graph; no content writes")
            .capability(ConnectorCapability.BULK_READ).direction(ConnectorDirection.INBOUND)
            .authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).authentication(AuthenticationMethod.BEARER_TOKEN)
            .apiVersion("Microsoft Graph v1.0")
            .pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.LINK,200,5000,"@odata.nextLink"));
        // OAuth client credentials need these; pre-issued bearer-token authentication does not.
        b.configurationProperty(property("tenantId",ConfigurationPropertyType.STRING,false));
        b.configurationProperty(property("clientId",ConfigurationPropertyType.STRING,false));
        b.configurationProperty(property("driveId",ConfigurationPropertyType.STRING,true));
        b.configurationProperty(property("siteId",ConfigurationPropertyType.STRING,false));
        b.configurationProperty(property("folderId",ConfigurationPropertyType.STRING,false));
        b.configurationProperty(property("extractSensitivityLabels",ConfigurationPropertyType.BOOLEAN,false));
        b.configurationProperty(ConfigurationPropertyDefinition.builder("max",ConfigurationPropertyType.INTEGER).required(false).defaultValue(Integer.valueOf(200)).build());
        b.configurationProperty(ConfigurationPropertyDefinition.builder("maxRequests",ConfigurationPropertyType.INTEGER).required(false).defaultValue(Integer.valueOf(2000)).build());
        for(Operation operation:getOperations())b.operation(new ConnectorOperationDescriptor(operation.getName(),operation.getKind(),operation.getDescription(),Collections.singleton(ConnectorDirection.INBOUND),Collections.<ConnectorCapability>emptySet()));
        return b.build();
    }
    private static ConfigurationPropertyDefinition property(String name,ConfigurationPropertyType type,boolean required){
        return ConfigurationPropertyDefinition.builder(name,type).required(required).build();
    }
    @Override public List<Operation> getOperations(){return Collections.singletonList(
        Operation.search("catalog","Inventory file metadata and source governance labels","source"));}
}
