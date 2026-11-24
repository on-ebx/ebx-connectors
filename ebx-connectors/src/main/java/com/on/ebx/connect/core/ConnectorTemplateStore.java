package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.List;

/** Persistence boundary for versioned connector templates. */
public interface ConnectorTemplateStore
{
	ConnectorTemplate find(String pId) throws IOException;
	List<ConnectorTemplate> list() throws IOException;
	void create(ConnectorTemplate pTemplate, String pActor) throws IOException;
	void replacePackaged(ConnectorTemplate pTemplate, String pActor) throws IOException;
}
