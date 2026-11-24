package com.on.ebx.connect.connectors;

import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.ConnectorException;

/** Bounded metadata-only harvesting. No file bodies, arbitrary field values or credentials are retained. */
public final class SharedDriveCatalog {
    public interface Transport { JsonNode call(String method, String path) throws ConnectorException; }
    private final Transport http;
    private final String endpoint, provider, tenant, source, now;
    private final Map<String,String> inputs;
    private final int max, maxRequests;
    private int requests;
    private final ObjectNode output = Json.object();
    private final ArrayNode errors = output.putArray("errors");
    private final Map<String,JsonNode> cache = new HashMap<String,JsonNode>();

    public SharedDriveCatalog(Transport http, String endpoint, String provider, String tenant, Map<String,String> inputs) throws ConnectorException {
        this.http = http;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.provider = provider;
        this.tenant = tenant;
        this.inputs = inputs;
        this.source = require(inputs, "source");
        this.max=bound(inputs.get("max"),200,1,5000);
        this.maxRequests=bound(inputs.get("maxRequests"),2000,1,10000);
        this.now=java.time.Instant.now().toString();
        output.put("source", source);
        output.put("scannedAt", now);
    }
    public JsonNode run(String operation) throws ConnectorException {
        if ("glossary".equals(operation))
        {
            graphTerms();
            return output;
        }
        if (!"catalog".equals(operation)) throw new ConnectorException("Unsupported metadata operation");
        ArrayNode assets=output.putArray("tables");
        if ("google-drive".equals(provider)) google(assets);
        else graph(assets);
        output.put("count",assets.size());
        return output;
    }
    private JsonNode call(String method,String path) throws ConnectorException {
        if(!"GET".equals(method)&&!"POST".equals(method)) throw new ConnectorException("Unsupported metadata request method");
        if(path==null||path.indexOf('\\')>=0||hasControl(path)) throw new ConnectorException("Invalid metadata request path");
        if (++requests>maxRequests) throw new ConnectorException("Metadata request budget exceeded");
        // Absolute pagination links must remain under the configured API root; never forward credentials elsewhere.
        if (path.startsWith("https://") || path.startsWith("http://")) {
            URI root, next;
            try
            {
                root = URI.create(endpoint);
                next = URI.create(path);
            }
            catch(IllegalArgumentException e) { throw new ConnectorException("Invalid metadata pagination link"); }
            String rootPath=root.getRawPath(), nextPath=next.getRawPath();
            if (!same(root.getScheme(),next.getScheme()) || !same(root.getHost(),next.getHost()) || effectivePort(root)!=effectivePort(next)
                || nextPath==null || !(nextPath.equals(rootPath)||nextPath.startsWith(rootPath+"/"))
                || next.getUserInfo()!=null || next.getFragment()!=null || containsEncodedTraversal(nextPath))
                throw new ConnectorException("Cross-origin metadata pagination refused");
            path=nextPath.substring(rootPath.length());
            if(next.getRawQuery()!=null) path+="?"+next.getRawQuery();
        }
        String requestPath=path;
        int queryAt = requestPath.indexOf('?');
        if (queryAt >= 0) requestPath = requestPath.substring(0, queryAt);
        if (!path.startsWith("/") || path.startsWith("//") || requestPath.contains("..") || containsEncodedTraversal(requestPath)) throw new ConnectorException("Invalid metadata request path");
        JsonNode r=http.call(method,path);
        if(r==null || !r.isObject()) throw new ConnectorException("Invalid metadata response");
        return r;
    }
    private static boolean same(String a,String b) { return a!=null&&b!=null&&a.equalsIgnoreCase(b); }
    private static int effectivePort(URI uri)
    {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }
    private static boolean containsEncodedTraversal(String value)
    {
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("%2e") || s.contains("%2f") || s.contains("%5c");
    }
    private static boolean hasControl(String value)
    {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private List<JsonNode> list(String path,String field,String token) throws ConnectorException {
        return list(path,field,token,false);
    }
    private List<JsonNode> list(String path,String field,String token,boolean requireComplete) throws ConnectorException {
        List<JsonNode> rows = new ArrayList<JsonNode>();
        Set<String> seen = new HashSet<String>();
        String next=path;
        while(next!=null) {
            if(!seen.add(next)) throw new ConnectorException("Repeated metadata pagination token");
            JsonNode page=call("GET",next);
            if(page.path("incompleteSearch").asBoolean(false)) throw new ConnectorException("Provider returned an incomplete metadata search; narrow the scope");
            if(!page.path(field).isArray()) throw new ConnectorException("Missing metadata collection: "+field);
            for(JsonNode row:page.path(field)) {
                if(rows.size()>=max) {
                    if(requireComplete) throw new ConnectorException("Incomplete label inventory: metadata collection limit reached");
                    warning("LIMIT", "Metadata collection truncated; narrow scope or raise max");
                    return rows;
                }
                rows.add(row);
            }
            String n=page.path(token).asText("");
            if(n.isEmpty()) next=null;
            else if("@odata.nextLink".equals(token)) next=n;
            else next=path+(path.contains("?")?"&":"?")+"pageToken="+HttpJsonClient.encode(n);
        }
        return rows;
    }
    private void warning(String code,String message) { errors.addObject().put("code",code).put("message",message); }
    private String status(ConnectorException e) {
        int s=e.getStatus();
        return s==401||s==403 ? "ACCESS_DENIED" : s==404 ? "UNAVAILABLE" : s==405||s==501 ? "UNSUPPORTED" : "ERROR";
    }
    private JsonNode optional(String path, ObjectNode target, String field) {
        try
        {
            JsonNode n = call("GET", path);
            target.put(field, "AVAILABLE");
            return n;
        }
        catch (ConnectorException e)
        {
            target.put(field, status(e));
            warning(status(e), field+" could not be read");
            return null;
        }
    }
    private JsonNode cached(String path) throws ConnectorException {
        if(!cache.containsKey(path)) cache.put(path,call("GET",path));
        return cache.get(path);
    }
    private ObjectNode asset(JsonNode file,String drive) throws ConnectorException {
        String id=requireId(file);
        ObjectNode a=Json.object();
        a.put("source", source);
        a.put("assetKey", source+":"+provider+":"+drive+":"+id);
        a.put("qualifiedName", file.path("name").asText(id));
        a.put("name", file.path("name").asText(id));
        a.put("kind", "FILE");
        a.put("scannedAt", now);
        a.put("url",file.path("webUrl").asText(file.path("webViewLink").asText("")));
        a.put("originProvider", provider);
        a.put("originTenantId", tenant);
        a.put("originContainerId", drive);
        a.put("originItemId", id);
        a.put("sourceRetentionStatus", "UNKNOWN");
        a.put("sourceSensitivityStatus", "UNKNOWN");
        a.put("sourceRetention", "Unknown");
        a.put("sourceSensitivity", "Unknown");
        a.put("sourceRetentionDetails", "{}");
        a.put("sourceSensitivityDetails", "{}");
        a.put("sourceMetadataObservedAt", now);
        a.put("sourceMetadataStatus", "AVAILABLE");
        a.putArray("columns");
        return a;
    }
    private void graph(ArrayNode assets) throws ConnectorException {
        String drive=require(inputs,"driveId");
        JsonNode driveInfo=call("GET","/drives/"+seg(drive)+"?$select=id,name,webUrl,driveType,sharePointIds");
        JsonNode root=call("GET","/drives/"+seg(drive)+"/root?$select=id,parentReference,sharepointIds");
        String site=inputs.get("siteId");
        if(site==null || site.isEmpty()) site=root.path("parentReference").path("siteId").asText("");
        JsonNode siteInfo=null;
        if(!site.isEmpty()) siteInfo=call("GET","/sites/"+seg(site)+"?$select=id,name,displayName,webUrl");
        String folder = inputs.get("folderId");
        if (folder == null || folder.isEmpty()) folder = "root";
        Deque<String> folders = new ArrayDeque<String>();
        folders.add(folder);
        Set<String> visited=new HashSet<String>();
        while(!folders.isEmpty()) {
            String f = folders.removeFirst();
            if (!visited.add(f)) continue;
            for(JsonNode item:list("/drives/"+seg(drive)+("root".equals(f)?"/root":"/items/"+seg(f))+"/children?$select=id,name,webUrl,file,folder,parentReference,sharepointIds","value","@odata.nextLink")) {
                if (item.has("folder"))
                {
                    folders.add(requireId(item));
                    continue;
                }
                if(!item.has("file")) continue;
                if (assets.size() >= max)
                {
                    warning("LIMIT", "File inventory truncated");
                    return;
                }
                ObjectNode a=asset(item,drive);
                a.put("originContainerName",driveInfo.path("name").asText(drive));
                a.put("originSiteId",site);
                if (siteInfo != null)
                {
                    a.put("originSiteName", siteInfo.path("displayName").asText(siteInfo.path("name").asText(site)));
                    a.put("originSiteUrl", siteInfo.path("webUrl").asText(""));
                }
                a.put("originPath",item.path("parentReference").path("path").asText("")+"/"+item.path("name").asText());
                String itemPath="/drives/"+seg(drive)+"/items/"+seg(requireId(item));
                JsonNode retention=optional(itemPath+"/retentionLabel",a,"sourceRetentionStatus");
                if(retention!=null) {
                    String label=retention.path("name").asText("");
                    a.put("sourceRetention",label.isEmpty()?"No label reported":label);
                    a.put("sourceRetentionStatus",label.isEmpty()?"NOT_ASSIGNED":"AVAILABLE");
                    ObjectNode safe=Json.object();
                    for(String k:Arrays.asList("name","retentionSettings","isLabelAppliedExplicitly","labelAppliedDateTime")) if(retention.has(k)) safe.set(k,retention.get(k));
                    a.put("sourceRetentionDetails",safe.toString());
                }
                a.put("sourceSensitivityStatus","NOT_REQUESTED");
                if("true".equalsIgnoreCase(inputs.get("extractSensitivityLabels"))) {
                    try {
                        JsonNode response=call("POST",itemPath+"/extractSensitivityLabels");
                        JsonNode labels=response.path("value").path("labels");
                        if(!labels.isArray()) labels=response.path("labels");
                        if(!labels.isArray()) throw new ConnectorException("Missing sensitivity labels in response");
                        ArrayNode safe = Json.mapper().createArrayNode();
                        List<String> ids = new ArrayList<String>();
                        for(JsonNode label:labels) {
                            String id=label.path("sensitivityLabelId").asText("");
                            if(id.isEmpty()) throw new ConnectorException("Sensitivity label has no stable id");
                            ids.add(id);
                            ObjectNode entry = safe.addObject();
                            entry.put("id", id);
                            entry.put("tenantId", label.path("tenantId").asText(""));
                            entry.put("assignmentMethod", label.path("assignmentMethod").asText(""));
                        }
                        a.put("sourceSensitivity",ids.isEmpty()?"No label reported":String.join(", ",ids));
                        a.put("sourceSensitivityStatus",ids.isEmpty()?"NOT_ASSIGNED":"AVAILABLE");
                        a.put("sourceSensitivityDetails",safe.toString());
                }
                catch (ConnectorException e)
                {
                    a.put("sourceSensitivityStatus", status(e));
                    warning(status(e), "Sensitivity labels could not be read");
                }
                }
                if(!site.isEmpty()) graphMetadata(a,item,site,itemPath);
                else {
                    a.put("sourceMetadataStructure","{}");
                    a.put("sourceMetadataStatus","sharepoint".equals(provider)?"UNAVAILABLE":"NOT_REQUESTED");
                    if("sharepoint".equals(provider)) warning("UNAVAILABLE","SharePoint site identity unavailable; metadata structure was not read");
                }
                assets.add(a);
            }
        }
    }
    private void graphMetadata(ObjectNode a,JsonNode item,String site,String itemPath) {
        ObjectNode structure=Json.object();
        try {
            JsonNode li=call("GET",itemPath+"/listItem?$expand=fields");
            String listId=item.path("sharepointIds").path("listId").asText(li.path("sharepointIds").path("listId").asText(""));
            a.put("originListId",listId);
            a.put("originContentTypeId",li.path("contentType").path("id").asText(""));
            a.put("originContentTypeName",li.path("contentType").path("name").asText(""));
            String base="/sites/"+seg(site);
            structure.set("siteColumns",collectionCached(base+"/columns"));
            structure.set("contentTypes",collectionCached(base+"/contentTypes"));
            if(!listId.isEmpty()) {
                JsonNode columns=collectionCached(base+"/lists/"+seg(listId)+"/columns");
                structure.set("libraryColumns",columns);
                structure.set("libraryContentTypes",collectionCached(base+"/lists/"+seg(listId)+"/contentTypes"));
                int position=0;
                for(JsonNode column:columns) {
                    String id=requireId(column), name=column.path("name").asText(id);
                    ObjectNode c=((ArrayNode)a.get("columns")).addObject();
                    c.put("columnKey",a.path("assetKey").asText()+":column:"+id);
                    c.put("name", name);
                    c.put("position", ++position);
                    c.put("dataType", "JSON");
                    c.put("nativeType", "SharePoint metadata");
                    c.put("nullable", !column.path("required").asBoolean(false));
                    c.put("primaryKey", false);
                    c.put("scannedAt", now);
                    c.put("remarks",column.path("description").asText(column.path("displayName").asText(name)));
                    c.put("sourceDefinitionId", id);
                    c.put("sourceDefinition", column.toString());
                    c.put("sourceRetention",a.path("sourceRetention").asText("Unknown"));
                    c.put("sourceSensitivity",a.path("sourceSensitivity").asText("Unknown"));
                    // Only managed taxonomy assignments, never arbitrary document field values.
                    JsonNode value=li.path("fields").path(name);
                    if(column.has("term")) c.put("sourceTermAssignments",value.isMissingNode()?"[]":value.toString());
                    else c.put("sourceTermAssignments","[]");
                }
            }
            a.put("sourceMetadataStructure",structure.toString());
        } catch(ConnectorException e) {
            // Keep successfully observed definitions while exposing incomplete retrieval.
            // A denied later collection must not erase the earlier site definitions.
            a.put("sourceMetadataStatus",structure.size()>0?"PARTIAL":status(e));
            a.put("sourceMetadataStructure",structure.toString());
            warning(status(e),"SharePoint metadata structure could not be read");
        }
    }
    private JsonNode collectionCached(String path) throws ConnectorException {
        if(!cache.containsKey(path)) {
            ArrayNode a=Json.mapper().createArrayNode();
            // Definitions are an authoritative inventory, unlike bounded file listings.
            // Never cache a truncated structure as complete/AVAILABLE.
            for(JsonNode n:list(path,"value","@odata.nextLink",true)) a.add(n);
            cache.put(path,a);
        }
        return cache.get(path);
    }
    private void google(ArrayNode assets) throws ConnectorException {
        String drive=inputs.get("driveId"), folder=inputs.get("folderId");
        boolean shared=drive!=null&&!drive.isEmpty();
        if(!shared&&(folder==null||folder.isEmpty())) throw new ConnectorException("Configure driveId or an explicit My Drive folderId; no implicit whole-account scan");
        String scope=shared?drive:folder;
        JsonNode driveInfo=call("GET",shared?"/drives/"+seg(drive)+"?fields=id,name":"/files/"+seg(folder)+"?fields=id,name,mimeType");
        // Resolve the two configured labels once. Duplicate names must never select an arbitrary policy.
        JsonNode definitions=googleDefinitions();
        String prefix=shared?"/files?corpora=drive&driveId="+seg(drive):"/files?corpora=user";
        String predicate="trashed = false and mimeType != 'application/vnd.google-apps.folder'";
        // My Drive and explicitly scoped shared-drive folders are direct-child inventories.
        if(folder!=null&&!folder.isEmpty()) {
            if(!folder.matches("[A-Za-z0-9_-]+")) throw new ConnectorException("Invalid Google folderId");
            predicate+=" and '"+folder+"' in parents";
        }
        String q=prefix+"&supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=100&q="+HttpJsonClient.encode(predicate)
            +"&fields="+HttpJsonClient.encode("nextPageToken,incompleteSearch,files(id,name,mimeType,webViewLink,parents)");
        for(JsonNode f:list(q,"files","nextPageToken")) {
            ObjectNode a = asset(f, shared ? drive : "MY_DRIVE");
            a.put("originContainerName", driveInfo.path("name").asText(scope));
            // An unreadable scan must not overwrite a previous label observation with invented absence.
            a.remove(Arrays.asList("sourceRetention","sourceSensitivity","sourceRetentionDetails","sourceSensitivityDetails"));
            a.put("originPath",f.path("parents").toString()); // parent IDs, not an invented folder path
            a.put("sourceMetadataStructure","{}");
            try {
                ArrayNode labels=Json.mapper().createArrayNode();
                for(JsonNode label:list("/files/"+seg(requireId(f))+"/listLabels?maxResults=100","labels","nextPageToken",true)) labels.add(label);
                googleLabel(a,labels,definitions,"retention","sourceRetention");
                googleLabel(a,labels,definitions,"sensitivity","sourceSensitivity");
            } catch(ConnectorException e) {
                a.put("sourceRetentionStatus", status(e));
                a.put("sourceSensitivityStatus", status(e));
                warning(status(e),"Google Drive labels could not be read");
            }
            assets.add(a);
        }
    }
    private JsonNode googleDefinitions() throws ConnectorException {
        ArrayNode definitions=Json.mapper().createArrayNode();
        for(JsonNode d:list("/labels-api/labels?publishedOnly=true&view=LABEL_VIEW_FULL&pageSize=200","labels","nextPageToken",true)) definitions.add(d);
        return definitions;
    }
    private void googleLabel(ObjectNode a,JsonNode labels,JsonNode definitions,String mapping,String field) throws ConnectorException {
        String id=inputs.get(mapping+"LabelId"), fieldId=inputs.get(mapping+"FieldId");
        String title=inputs.get(mapping+"LabelName");
        if(title==null) title="retention".equals(mapping)?"File Retention":"File Sensitivity";
        JsonNode definition=null;
        for(JsonNode d:definitions) if(id!=null&&!id.isEmpty()?id.equals(d.path("id").asText()):title.equals(d.path("properties").path("title").asText())) {
            if(definition!=null) throw new ConnectorException("Ambiguous "+mapping+" label name; configure its stable label ID");
            definition=d;
        }
        if (definition == null)
        {
            a.put(field+"Status", "NOT_CONFIGURED");
            warning("NOT_CONFIGURED", mapping+" label definition was not found or is not accessible");
            return;
        }
        id=requireId(definition);
        String fieldName=inputs.get(mapping+"FieldName");
        if(fieldName==null&&"retention".equals(mapping)) fieldName="File Retention Period";
        JsonNode fieldDefinition=null;
        for(JsonNode d:definition.path("fields")) if(fieldId!=null&&!fieldId.isEmpty()?fieldId.equals(d.path("id").asText()):fieldName==null||fieldName.equals(d.path("properties").path("displayName").asText())) {
            if(fieldDefinition!=null) throw new ConnectorException("Ambiguous "+mapping+" label field; configure fieldId");
            fieldDefinition=d;
        }
        if (fieldDefinition == null)
        {
            a.put(field+"Status", "NOT_CONFIGURED");
            warning("NOT_CONFIGURED", mapping+" label field was not found");
            return;
        }
        fieldId=requireId(fieldDefinition);
        ObjectNode c=((ArrayNode)a.get("columns")).addObject();
        c.put("name", mapping);
        c.put("columnKey", a.path("assetKey").asText()+":label:"+id+":"+fieldId);
        c.put("position", ((ArrayNode)a.get("columns")).size());
        c.put("dataType", "STRING");
        c.put("nativeType","Google Drive label field (not file content)");
        c.put("nullable", true);
        c.put("primaryKey", false);
        c.put("scannedAt", now);
        c.put("sourceDefinitionId", id+":"+fieldId);
        c.put("sourceTermAssignments", "[]");
        ObjectNode observedDefinition=fieldDefinition.deepCopy();
        observedDefinition.put("sourceLabelId", id);
        observedDefinition.put("observedAt", now);
        observedDefinition.put("assignmentStatus","NOT_ASSIGNED");
        c.put("sourceDefinition",observedDefinition.toString());
        // Explicit null is authoritative absence; MappingEngine permits clearing only source-owned label fields.
        c.putNull(field);
        a.putNull(field);
        a.put(field+"Details", "{}");
        a.put(field+"Status", "NOT_ASSIGNED");
        for(JsonNode label:labels) if(id.equals(label.path("id").asText())) {
            JsonNode value=label.path("fields").path(fieldId);
            if(value.isMissingNode() || value.isNull()) return;
            if(value.size()>0 && !value.has("selection") && !value.has("text") && !value.has("integer") && !value.has("dateString")) {
                a.remove(field);
                a.remove(field+"Details");
                c.remove(field);
                a.put(field+"Status", "UNSUPPORTED");
                observedDefinition.put("assignmentStatus", "UNSUPPORTED");
                c.put("sourceDefinition",observedDefinition.toString());
                warning("UNSUPPORTED", "Configured Google label value type is not supported; prior observation retained");
                return;
            }
            List<String> display=new ArrayList<String>();
            for(String type:Arrays.asList("selection","text","integer","dateString")) for(JsonNode v:value.path(type)) {
                String shown=v.asText();
                if ("selection".equals(type)) for (JsonNode choice:fieldDefinition.path("selectionOptions").path("choices")) if (shown.equals(choice.path("id").asText()))
                {
                    shown = choice.path("properties").path("displayName").asText(shown);
                    break;
                }
                display.add(shown);
            }
            if(display.isEmpty()) return;
            a.put(field+"Status","AVAILABLE");
            a.put(field,String.join(", ",display));
            ObjectNode detail = Json.object();
            detail.put("labelId", id);
            detail.put("fieldId", fieldId);
            detail.put("revisionId", label.path("revisionId").asText(""));
            detail.put("labelName", definition.path("properties").path("title").asText());
            detail.set("value", value);
            a.put(field+"Details",detail.toString());
            observedDefinition.put("assignmentStatus","AVAILABLE");
            c.put("sourceDefinition", observedDefinition.toString());
            c.put(field, String.join(", ", display));
            return;
        }
    }
    private void graphTerms() throws ConnectorException {
        if("google-drive".equals(provider)) throw new ConnectorException("Term Store applies only to Microsoft SharePoint");
        String site=require(inputs,"siteId");
        ArrayNode terms=output.putArray("terms");
        String base="/sites/"+seg(site)+"/termStore";
        JsonNode store=call("GET",base);
        for(JsonNode group:list(base+"/groups","value","@odata.nextLink")) {
            for(JsonNode set:list(base+"/groups/"+seg(requireId(group))+"/sets","value","@odata.nextLink")) {
                String setId=requireId(set);
                Deque<String[]> queue = new ArrayDeque<String[]>();
                queue.add(new String[] {"", base+"/sets/"+seg(setId)+"/children"});
                Set<String> seen=new HashSet<String>();
                while(!queue.isEmpty()) {
                    String[] branch=queue.removeFirst();
                    for(JsonNode term:list(branch[1],"value","@odata.nextLink")) {
                        String id=requireId(term);
                        if(!seen.add(id)) continue;
                        if(terms.size()>=max) throw new ConnectorException("Term inventory limit exceeded; narrow source scope or increase max");
                        ObjectNode t=terms.addObject();
                        // Stable hash avoids source IDs exceeding the existing glossary code field.
                        t.put("code",termCode(source,store.path("id").asText(),setId,id));
                        String label = id;
                        for (JsonNode v:term.path("labels")) if (v.path("isDefault").asBoolean(false))
                        {
                            label = v.path("name").asText(id);
                            break;
                        }
                        t.put("label",label);
                        t.put("definition",term.path("descriptions").size()>0?term.path("descriptions").get(0).path("description").asText(""):"");
                        t.put("sourceTermId", id);
                        t.put("sourceTermSetId", setId);
                        t.put("sourceTermGroupId", requireId(group));
                        t.put("sourceTermStoreId", store.path("id").asText(""));
                        t.put("sourceTermParentId", branch[0].isEmpty() ? "ROOT" : branch[0]);
                        t.put("sourceTermSource", source);
                        t.put("sourceTermObservedAt", now);
                        ObjectNode definition = Json.object();
                        definition.set("term", term);
                        definition.set("set", set);
                        definition.set("group", group);
                        t.put("sourceTermDefinition",definition.toString());
                        queue.add(new String[]{id,base+"/sets/"+seg(setId)+"/terms/"+seg(id)+"/children"});
                    }
                }
            }
        }
        output.put("count",terms.size());
    }
    public static String termCode(String source,String store,String set,String term) {
        return "SP_"+UUID.nameUUIDFromBytes((source+"|"+store+"|"+set+"|"+term).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","");
    }
    public static String seg(String s) throws ConnectorException {
        if(s==null||s.isEmpty()||s.contains("/")||s.contains("\\")||s.contains("..")) throw new ConnectorException("Missing or invalid provider identifier");
        return HttpJsonClient.encode(s);
    }
    private static String requireId(JsonNode n) throws ConnectorException
    {
        String s = n.path("id").asText("");
        if (s.isEmpty()) throw new ConnectorException("Provider object has no stable id");
        return s;
    }
    private static String require(Map<String, String> m, String k) throws ConnectorException
    {
        String v = m.get(k);
        if (v == null || v.trim().isEmpty()) throw new ConnectorException("Missing "+k);
        return v.trim();
    }
    private static int bound(String s, int fallback, int min, int max) throws ConnectorException
    {
        if (s == null) return fallback;
        try
        {
            return Math.max(min, Math.min(max, Integer.parseInt(s)));
        }
        catch (NumberFormatException e)
        {
            throw new ConnectorException("Invalid metadata limit");
        }
    }
}
