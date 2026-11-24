package com.on.ebx.connect.core;

import java.util.*;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Request-local immutable comparison only. Never log, serialize or return configuration material. */
final class PreviewConfiguration {
    private final List<Object> values;
    private final Map<String,String> properties;
    PreviewConfiguration(ConnectorConfig config) {
        if(config==null || !config.isActive() || config.getProblem()!=null)
            throw new SecurityException("Preview connector unavailable.");
        values=Arrays.<Object>asList(config.getId(),config.getType(),config.getLabel(),config.getBaseUrl(),
            config.getAuthType(),config.getUsername(),config.getPassword(),config.getApiKeyHeader(),config.getApiKey(),
            config.getConnectTimeoutMs(),config.getReadTimeoutMs(),config.getTrust(),config.getLogo());
        properties=new TreeMap<String,String>(config.getProperties());
    }
    void requireUnchanged(ConnectorConfig current) {
        PreviewConfiguration other=new PreviewConfiguration(current);
        if(!values.equals(other.values)||!properties.equals(other.properties))
            throw new SecurityException("Preview connector configuration changed; retry.");
    }
}
