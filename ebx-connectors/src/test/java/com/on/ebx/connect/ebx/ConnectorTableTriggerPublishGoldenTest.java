package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.PreviewFeatures;
import com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger;
import com.orchestranetworks.schema.trigger.TriggerSetupContext;
import com.orchestranetworks.service.OperationException;

/**
 * Audit C3-05 (1.4.2, bilan 1.4.1 gap 1): a trigger with {@code publish=golden} is refused when it is registered (model compilation) and when it
 * fires, while {@code cdcMatching} is closed (or named without {@code cdc}). A {@code record} trigger is never asked for the word.
 */
public final class ConnectorTableTriggerPublishGoldenTest
{
	private ConnectorTableTriggerPublishGoldenTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String saved = System.getProperty(PreviewFeatures.PROPERTY);
		try
		{
			for (final String words : new String[] { "", "documents,redshift", "cdc", "cdcMatching" })
			{
				if (words.isEmpty()) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, words);
				final List<String> errors = new ArrayList<String>();
				trigger("golden").setup(context(errors));
				check(errors.size() == 1 && errors.get(0).contains("[" + PreviewFeatures.CODE + "]") && errors.get(0).contains("cdcMatching"),
					"registration refused with '" + words + "': " + errors);
				final List<String> recordErrors = new ArrayList<String>();
				trigger("record").setup(context(recordErrors));
				check(recordErrors.isEmpty(), "a record trigger is never refused: " + recordErrors);
				try
				{
					fire(trigger("golden"));
					throw new AssertionError("the golden trigger queued a send with '" + words + "'");
				}
				catch (final InvocationTargetException expected)
				{
					check(expected.getCause() instanceof OperationException && expected.getCause().getMessage().contains(PreviewFeatures.CODE),
						"refused at execution with '" + words + "': " + expected.getCause());
				}
			}
			System.setProperty(PreviewFeatures.PROPERTY, "cdc,cdcMatching");
			final List<String> open = new ArrayList<String>();
			trigger("golden").setup(context(open));
			check(open.isEmpty(), "opened: accepted at registration: " + open);
			try
			{
				fire(trigger("golden"));
			}
			catch (final InvocationTargetException later)
			{
				// past the guard the trigger needs a record, which this test does not have: anything but the preview refusal is fine
				check(!(later.getCause() instanceof OperationException), "opened: not refused at execution: " + later.getCause());
			}
		}
		finally
		{
			if (saved == null) System.clearProperty(PreviewFeatures.PROPERTY); else System.setProperty(PreviewFeatures.PROPERTY, saved);
		}
		System.out.println("PASS: publish=golden is refused at registration and at execution unless cdc and cdcMatching are open");
	}

	private static ConnectorTableTrigger trigger(final String pPublish)
	{
		final ConnectorTableTrigger trigger = new ConnectorTableTrigger();
		trigger.setConnector("Kafka");
		trigger.setOperation("publish");
		trigger.setPublish(pPublish);
		return trigger;
	}

	private static void fire(final ConnectorTableTrigger pTrigger) throws Exception
	{
		final Method sync = ConnectorTableTrigger.class.getDeclaredMethod("sync", com.orchestranetworks.service.ProcedureContext.class, com.onwbp.adaptation.Adaptation.class);
		sync.setAccessible(true);
		sync.invoke(pTrigger, null, null);
	}

	private static TriggerSetupContext context(final List<String> pErrors)
	{
		return (TriggerSetupContext) Proxy.newProxyInstance(ConnectorTableTriggerPublishGoldenTest.class.getClassLoader(), new Class<?>[] { TriggerSetupContext.class },
			new InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
				{
					if ("addError".equals(pMethod.getName())) pErrors.add(String.valueOf(pArguments[0]));
					return null;
				}
			});
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
