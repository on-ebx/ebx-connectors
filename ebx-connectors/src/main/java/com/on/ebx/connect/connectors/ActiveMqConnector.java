package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;

/** Apache ActiveMQ Classic profile on the provider-neutral JMS foundation. */
public final class ActiveMqConnector extends JmsConnector
{
	public static final String INITIAL_CONTEXT_FACTORY = "org.apache.activemq.jndi.ActiveMQInitialContextFactory";
	@Override public String getType() { return "activemq"; }
	@Override public String getLabel() { return "Apache ActiveMQ"; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("initial.context.factory", null) == null) pConfig.setProperty("initial.context.factory", INITIAL_CONTEXT_FACTORY);
		super.configure(pConfig);
	}
	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String url = pConfig.getBaseUrl(); if (url == null || !(url.startsWith("tcp://") || url.startsWith("ssl://") || url.startsWith("failover:")))
			issues.add(new ConfigurationValidationIssue("baseUrl", "ActiveMQ provider URL must use tcp://, ssl://, or failover:"));
		if (!INITIAL_CONTEXT_FACTORY.equals(pConfig.getProperty("initial.context.factory", INITIAL_CONTEXT_FACTORY)))
			issues.add(new ConfigurationValidationIssue("initial.context.factory", "Apache ActiveMQ requires " + INITIAL_CONTEXT_FACTORY));
		return new ConfigurationValidationResult(issues);
	}
	@Override protected String description() { return "Apache ActiveMQ Classic queues and topics with bounded publish/receive/peek, acknowledgement, selectors, and redelivery controls."; }
	@Override protected String apiVersion() { return "ActiveMQ Classic JMS 1.1"; }
	@Override protected ConnectorDependency dependency() { return new ConnectorDependency("Apache ActiveMQ client", "org.apache.activemq:activemq-client:5.x", true, "Package ActiveMQ client and JMS API with EBX-Connectors"); }
}
