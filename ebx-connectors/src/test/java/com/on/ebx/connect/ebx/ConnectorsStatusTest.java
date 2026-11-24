package com.on.ebx.connect.ebx;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Retention;
import com.on.ebx.connect.ebx.rest.ConnectorsResource;

/**
 * The postures the status route reports follow the JVM properties they stand for, the pool figures add up,
 * and the answer carries nothing but counts and on/off values. The repository half of the answer (dataset,
 * model, running runs) needs an EBX repository and is not exercised here.
 */
public final class ConnectorsStatusTest
{
	private static final String[] PROPERTIES = { Egress.PROPERTY, Egress.REQUIRE_PROPERTY, Egress.BLOCK_PRIVATE_PROPERTY, Egress.PRIVATE_EXCEPTIONS_PROPERTY, Retention.DAYS_PROPERTY,
		"ebx.connectors.secrets.allowClear" };

	public static void main(final String[] pArgs) throws Exception
	{
		final String[] saved = new String[PROPERTIES.length];
		for (int i = 0; i < PROPERTIES.length; i++)
		{
			saved[i] = System.getProperty(PROPERTIES[i]);
			System.clearProperty(PROPERTIES[i]);
		}
		try
		{
			defaults();
			everythingSet();
			required();
			pool();
			route();
			version();
			outboundQueue();
			cdcFlows();
		}
		finally
		{
			for (int i = 0; i < PROPERTIES.length; i++)
			{
				if (saved[i] == null)
				{
					System.clearProperty(PROPERTIES[i]);
				}
				else
				{
					System.setProperty(PROPERTIES[i], saved[i]);
				}
			}
		}
		System.out.println("PASS: status route reports the postures the properties set, the pool figures, and nothing secret");
	}

	private static ObjectNode posture()
	{
		final ObjectNode out = Json.object();
		ConnectorsStatus.posture(out);
		return out;
	}

	private static void defaults()
	{
		final ObjectNode out = posture();
		check(!out.get("egress").get("allowListSet").asBoolean(), "no allow-list by default");
		check(!out.get("egress").get("requireAllowList").asBoolean(), "an allow-list is not required by default");
		check(out.get("egress").get("blockPrivateHosts").asBoolean() && out.get("egress").get("privateExceptions").asInt() == 0, "private hosts are blocked by default, without exception");
		check(out.get("retention").get("enabled").asBoolean() && out.get("retention").get("days").asInt() == 90 && out.get("retention").get("onByDefault").asBoolean(), "retention on by default, at 90 days");
		check(!out.get("secrets").get("clearValuesAllowed").asBoolean(), "clear secrets are refused by default");
	}

	private static void everythingSet()
	{
		System.setProperty(Egress.PROPERTY, "api.example.com");
		System.setProperty(Egress.REQUIRE_PROPERTY, "true");
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "FALSE");
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "localhost:9092, 10.0.0.0/8");
		System.setProperty(Retention.DAYS_PROPERTY, "30");
		System.setProperty("ebx.connectors.secrets.allowClear", "TRUE");
		final ObjectNode out = posture();
		check(out.get("egress").get("allowListSet").asBoolean(), "an allow-list is seen");
		check(out.get("egress").get("requireAllowList").asBoolean() && !out.get("egress").get("blockPrivateHosts").asBoolean(), "both flags are seen, whatever their case");
		check(out.get("egress").get("privateExceptions").asInt() == 2, "the exceptions are counted, not shown");
		check(!out.toString().contains("localhost:9092") && !out.toString().contains("10.0.0.0/8"), "the exception entries are not part of the answer");
		check(out.get("retention").get("enabled").asBoolean() && out.get("retention").get("days").asInt() == 30, "retention on, with its age");
		check(!out.get("retention").get("onByDefault").asBoolean(), "an age that is set is not the default");
		System.setProperty(Retention.DAYS_PROPERTY, "0");
		check(!posture().get("retention").get("enabled").asBoolean() && posture().get("retention").get("days").asInt() == 0, "retention turned off by 0");
		System.setProperty(Retention.DAYS_PROPERTY, "30");
		check(out.get("secrets").get("clearValuesAllowed").asBoolean(), "clear secrets accepted when asked for");
		// the answer names no value of the allow-list: only that there is one
		check(!out.toString().contains("api.example.com"), "the allow-list itself is not part of the answer");
	}

	private static void required()
	{
		System.clearProperty(Egress.PROPERTY);
		System.setProperty(Egress.REQUIRE_PROPERTY, "true");
		check(!posture().get("egress").get("allowListSet").asBoolean() && posture().get("egress").get("requireAllowList").asBoolean(),
			"required without a list is reported as such, not as an allow-list");
	}

	private static void pool()
	{
		final ObjectNode busy = Json.object();
		ConnectorsStatus.pool(busy, new int[] { 6, 4, 16 });
		check(busy.get("running").asInt() == 4 && busy.get("queued").asInt() == 2 && busy.get("free").asInt() == 14, "6 runs on 4+16 slots: " + busy);
		final ObjectNode idle = Json.object();
		ConnectorsStatus.pool(idle, new int[] { 0, 4, 16 });
		check(idle.get("running").asInt() == 0 && idle.get("queued").asInt() == 0 && idle.get("free").asInt() == 20, "an idle pool: " + idle);
		final ObjectNode full = Json.object();
		ConnectorsStatus.pool(full, new int[] { 25, 4, 16 });
		check(full.get("free").asInt() == 0 && full.get("queued").asInt() == 21, "never a negative number of free slots: " + full);
	}

	private static void version()
	{
		final ObjectNode out = Json.object();
		ConnectorsStatus.version(out);
		final JsonNode version = out.get("version");
		check(version != null && version.has("version") && version.has("commit") && version.has("builtAt"), "the status carries version, commit and builtAt: " + out);
		check(version.get("version").asText().equals(com.on.ebx.connect.core.ModuleVersion.version()), "the status says the version the module was built as");
	}

	/** Audit C3-12: the outbound queue is visible in the status (waiting, retrying, set aside, age of the oldest waiting row). */
	private static void outboundQueue()
	{
		final long now = 1_800_000_000_000L;
		final ObjectNode busy = Json.object();
		ConnectorsStatus.queueFigures(busy, 7, 3, 2, new java.util.Date(now - 125_000L), now);
		check(busy.get("known").asBoolean() && busy.get("pending").asInt() == 7 && busy.get("retrying").asInt() == 3 && busy.get("dead").asInt() == 2, "queue figures: " + busy);
		check(busy.get("oldestPendingAgeSeconds").asLong() == 125L, "age of the oldest waiting row: " + busy);
		check(busy.get("oldestPendingAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), "the time is written with its seconds: " + busy);
		final ObjectNode empty = Json.object();
		ConnectorsStatus.queueFigures(empty, 0, 0, 0, null, now);
		check(empty.get("pending").asInt() == 0 && !empty.has("oldestPendingAt") && !empty.has("oldestPendingAgeSeconds"), "an empty queue has no oldest row: " + empty);
		final ObjectNode future = Json.object();
		ConnectorsStatus.queueFigures(future, 1, 0, 0, new java.util.Date(now + 5_000L), now);
		check(future.get("oldestPendingAgeSeconds").asLong() == 0L, "a clock skew never gives a negative age: " + future);
		check(!busy.toString().toLowerCase().contains("error") && !busy.toString().contains("recordKey"), "the queue answer carries counts, not records: " + busy);
	}

	/** Audit C3-12: a CDC flow shows whether it runs, where it resumes and when that was saved. */
	private static void cdcFlows()
	{
		final long now = 1_800_000_000_000L;
		final ObjectNode saved = Json.object();
		ConnectorsStatus.cdcFlow(saved, 12, "Suppliers CDC", "Debezium", true, "run-1", true, "{\"erp.public.supplier:0\":4521}", new java.util.Date(now - 61_000L), 9L, now);
		check(saved.get("mapping").asInt() == 12 && saved.get("active").asBoolean() && saved.get("runningNow").asBoolean(), "flow identity and state: " + saved);
		check(saved.get("checkpointSaved").asBoolean() && saved.get("position").asText().contains("4521") && saved.get("saves").asLong() == 9L, "resume position: " + saved);
		check(saved.get("savedAtAgeSeconds").asLong() == 61L && !saved.get("positionCut").asBoolean(), "age of the last saved batch: " + saved);
		final ObjectNode fresh = Json.object();
		ConnectorsStatus.cdcFlow(fresh, 13, "New flow", "Debezium", false, null, false, null, null, 0L, now);
		check(!fresh.get("checkpointSaved").asBoolean() && !fresh.has("position") && !fresh.has("savedAt") && !fresh.get("runningNow").asBoolean(), "a flow that never applied a batch: " + fresh);
		final StringBuilder longPosition = new StringBuilder();
		for (int i = 0; i < ConnectorsStatus.MAX_POSITION_CHARS + 50; i++) longPosition.append('7');
		final ObjectNode cut = Json.object();
		ConnectorsStatus.cdcFlow(cut, 14, null, null, true, null, true, longPosition.toString(), null, 1L, now);
		check(cut.get("positionCut").asBoolean() && cut.get("position").asText().length() == ConnectorsStatus.MAX_POSITION_CHARS && !cut.has("savedAt"), "a long position is cut: " + cut);
	}

	private static void route() throws Exception
	{
		Method status = null;
		for (final Method method : ConnectorsResource.class.getDeclaredMethods())
		{
			if (method.getName().equals("status"))
			{
				status = method;
			}
		}
		check(status != null, "ConnectorsResource has no status route");
		check(status.isAnnotationPresent(jakarta.ws.rs.GET.class), "the status route is a GET");
		check("/status".equals(status.getAnnotation(jakarta.ws.rs.Path.class).value()), "the status route is /status");
		check(status.getParameterTypes().length == 0, "the status route takes no input");
		// nothing of a connector or a secret can be in the answer of a repository-less build
		final ObjectNode out = Json.object();
		ConnectorsStatus.posture(out);
		final List<String> names = new ArrayList<String>();
		collect(out, names);
		for (final String name : names)
		{
			final String lower = name.toLowerCase();
			check(!lower.contains("password") && !lower.contains("apikey") && !lower.contains("token") && !lower.contains("connector"), "unexpected key " + name);
		}
	}

	private static void collect(final JsonNode pNode, final List<String> pNames)
	{
		final java.util.Iterator<String> it = pNode.fieldNames();
		while (it.hasNext())
		{
			final String name = it.next();
			pNames.add(name);
			collect(pNode.get(name), pNames);
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
