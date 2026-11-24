package com.on.ebx.connect.contract;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;

import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.on.ebx.connect.ebx.rest.ConnectorsResource;

/**
 * Volume lot 7b, task 4 - REST parity structural contract: {@code POST /sync/{mapping}} exposes
 * {@code async}/{@code resumeFrom} query parameters (previously MCP-only), {@code GET /runs/{runId}}
 * and {@code POST /runs/{runId}/stop} exist, and the run-state answer both {@code GET /runs/{runId}}
 * and the MCP {@code connectors_run_get} tool build comes from the one shared method
 * ({@code ConnectorsTools.runStatusJson}), not two drifting reimplementations. No EBX repository or
 * connector call is exercised here - a JAX-RS resource method needs container-injected
 * {@code SessionContext}/{@code Repository} to actually run, which this offline build cannot provide;
 * this test only checks the method signatures/annotations are wired as documented.
 */
public final class ConnectorsRunRestSurfaceContractTest
{
	private ConnectorsRunRestSurfaceContractTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Method sync = findMethod(ConnectorsResource.class, "sync");
		check(sync != null, "ConnectorsResource has no 'sync' method");
		check(hasPostPath(sync, "/sync/{mapping}"), "sync must be POST /sync/{mapping}");
		check(hasQueryParam(sync, "async"), "POST /sync/{mapping} must expose 'async' (volume lot 7b REST parity with connectors_sync)");
		check(hasQueryParam(sync, "resumeFrom"), "POST /sync/{mapping} must expose 'resumeFrom' (volume lot 7b REST parity with connectors_sync)");
		check(booleanDefault(sync, "async").equals("false"), "async must default to false, matching connectors_sync's own default");
		check(intDefault(sync, "resumeFrom").equals("0"), "resumeFrom must default to 0, matching connectors_sync's own default");

		final Method getRun = findGetMethod(ConnectorsResource.class, "/runs/{runId}");
		check(getRun != null, "ConnectorsResource has no GET method mapped to /runs/{runId}");

		final Method stopRun = findPostMethod(ConnectorsResource.class, "/runs/{runId}/stop");
		check(stopRun != null, "ConnectorsResource has no POST method mapped to /runs/{runId}/stop (REST parity with connectors_run_stop)");

		final Method runStatusJson = ConnectorsTools.class.getDeclaredMethod("runStatusJson",
			com.on.ebx.connect.core.ConnectorRepository.class, String.class);
		check(Modifier.isPublic(runStatusJson.getModifiers()) && Modifier.isStatic(runStatusJson.getModifiers()),
			"ConnectorsTools.runStatusJson must be public static so ConnectorsResource (a different package) can call it directly, "
				+ "instead of REST reimplementing connectors_run_get's execution{} logic separately");

		System.out.println("PASS: connectors_sync's async/resumeFrom, GET /runs/{runId}, POST /runs/{runId}/stop and the shared run-status builder are wired");
	}

	private static Method findMethod(final Class<?> pClass, final String pName)
	{
		for (final Method method : pClass.getDeclaredMethods())
		{
			if (method.getName().equals(pName))
			{
				return method;
			}
		}
		return null;
	}

	private static Method findGetMethod(final Class<?> pClass, final String pPath)
	{
		for (final Method method : pClass.getDeclaredMethods())
		{
			final jakarta.ws.rs.Path path = method.getAnnotation(jakarta.ws.rs.Path.class);
			final jakarta.ws.rs.GET get = method.getAnnotation(jakarta.ws.rs.GET.class);
			if (get != null && path != null && pPath.equals(path.value()))
			{
				return method;
			}
		}
		return null;
	}

	private static Method findPostMethod(final Class<?> pClass, final String pPath)
	{
		for (final Method method : pClass.getDeclaredMethods())
		{
			final jakarta.ws.rs.Path path = method.getAnnotation(jakarta.ws.rs.Path.class);
			final jakarta.ws.rs.POST post = method.getAnnotation(jakarta.ws.rs.POST.class);
			if (post != null && path != null && pPath.equals(path.value()))
			{
				return method;
			}
		}
		return null;
	}

	private static boolean hasPostPath(final Method pMethod, final String pPath)
	{
		final jakarta.ws.rs.Path path = pMethod.getAnnotation(jakarta.ws.rs.Path.class);
		final jakarta.ws.rs.POST post = pMethod.getAnnotation(jakarta.ws.rs.POST.class);
		return post != null && path != null && pPath.equals(path.value());
	}

	private static boolean hasQueryParam(final Method pMethod, final String pName)
	{
		return queryParamAnnotation(pMethod, pName) != null;
	}

	private static jakarta.ws.rs.QueryParam queryParamAnnotation(final Method pMethod, final String pName)
	{
		for (final Parameter parameter : pMethod.getParameters())
		{
			final jakarta.ws.rs.QueryParam qp = parameter.getAnnotation(jakarta.ws.rs.QueryParam.class);
			if (qp != null && pName.equals(qp.value()))
			{
				return qp;
			}
		}
		return null;
	}

	private static String booleanDefault(final Method pMethod, final String pName)
	{
		return defaultValueOf(pMethod, pName);
	}

	private static String intDefault(final Method pMethod, final String pName)
	{
		return defaultValueOf(pMethod, pName);
	}

	private static String defaultValueOf(final Method pMethod, final String pName)
	{
		for (final Parameter parameter : pMethod.getParameters())
		{
			final jakarta.ws.rs.QueryParam qp = parameter.getAnnotation(jakarta.ws.rs.QueryParam.class);
			if (qp != null && pName.equals(qp.value()))
			{
				final jakarta.ws.rs.DefaultValue dv = parameter.getAnnotation(jakarta.ws.rs.DefaultValue.class);
				return dv == null ? null : dv.value();
			}
		}
		return null;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
