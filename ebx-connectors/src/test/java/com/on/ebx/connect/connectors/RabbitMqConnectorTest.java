package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

public final class RabbitMqConnectorTest
{
	public static void main(final String[] args) throws Exception
	{
		final Fake fake=new Fake(); fake.deliveries.add(new AmqpTransport.Delivery(1,false,"{\"id\":1}".getBytes(StandardCharsets.UTF_8),1)); fake.deliveries.add(new AmqpTransport.Delivery(2,true,"plain".getBytes(StandardCharsets.UTF_8),0));
		final RabbitMqConnector c=new RabbitMqConnector(new RabbitMqConnector.Factory(){public AmqpTransport open(final ConnectorConfig x){return fake;}}); final ConnectorConfig cfg=config(); c.configure(cfg);
		check(c.validateConfiguration(cfg).isValid(),"valid config"); check(!c.validateConfiguration(configuration("http://bad")).isValid(),"invalid scheme");
		final Map<String,String> max=new HashMap<String,String>();max.put("max","1"); JsonNode received=c.execute(null,"receive",max,null);check(received.path("count").asInt()==1&&fake.acked.equals(Arrays.asList(1L)),"receive ack/bound");
		fake.deliveries.add(new AmqpTransport.Delivery(3,true,"{}".getBytes(StandardCharsets.UTF_8),0)); JsonNode peek=c.execute(null,"peek",max,null);check(!peek.path("acknowledged").asBoolean(),"peek acknowledgement flag inverted");
		check(fake.nacked.equals(Arrays.asList(2L)),"peek must nack/requeue first queued delivery");
		final Map<String,String> route=new HashMap<String,String>();route.put("routingKey","customer.changed");c.execute(null,"publish",route,Json.object().put("id",7));check("customer.changed".equals(fake.route),"publish route");
		check(c.getDescriptor().getCapabilities().contains(ConnectorCapability.STREAMING),"streaming descriptor");
		System.out.println("PASS: RabbitMQ bounded publish/receive/peek, ack/nack redelivery, validation and descriptor");
	}
	private static ConnectorConfig config(){return configuration("amqps://rabbit.example:5671/vhost");}
	private static ConnectorConfig configuration(final String url){final ConnectorConfig c=new ConnectorConfig();c.setId("rabbit");c.setType("rabbitmq");c.setBaseUrl(url);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("ebx");c.setPassword("secret");c.setProperty("queue","ebx.master-data");return c;}
	private static void check(final boolean c,final String m){if(!c)throw new AssertionError(m);}
	private static final class Fake implements AmqpTransport {final Queue<Delivery> deliveries=new ArrayDeque<Delivery>();final List<Long>acked=new ArrayList<Long>(),nacked=new ArrayList<Long>();String route;public void publish(String e,String r,byte[]b){route=r;}public Delivery get(String q){return deliveries.poll();}public void ack(long t){acked.add(t);}public void nack(long t,boolean r){if(r)nacked.add(t);}public void close(){}}
}
