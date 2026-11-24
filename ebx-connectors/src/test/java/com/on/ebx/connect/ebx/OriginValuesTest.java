package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.OriginVisibility;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Audit C2-03: values the module keeps about a record are shown only to a session that may read the record; the log keeps
 * no values unless {@code ebx.connectors.log.storeChanges=true}. The visibility predicate is injected (no repository).
 */
public final class OriginValuesTest
{
	private OriginValuesTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final OriginVisibility.Probe previous = OriginVisibility.use(new OriginVisibility.Probe()
		{
			@Override
			public boolean canRead(final Session pSession, final String pDataspace, final String pDataset, final String pTable, final String pKey)
			{
				return !"/root/Salary".equals(pTable); // the session may read every table but this one
			}
		});
		try
		{
			final Session reader = session(false, false);
			final Session admin = session(false, true);
			final Session system = session(true, false);

			// the values of a closed origin are masked, others pass, null stays null
			check(OriginVisibility.MASK.equals(OriginVisibility.mask(reader, "B", "D", "/root/Salary", "1", "iban FR76")), "closed origin: masked");
			check("x".equals(OriginVisibility.mask(reader, "B", "D", "/root/Person", "1", "x")), "readable origin: shown");
			check(OriginVisibility.mask(reader, "B", "D", "/root/Salary", "1", null) == null, "null stays null");
			check("x".equals(OriginVisibility.mask(admin, "B", "D", "/root/Salary", "1", "x")) && "x".equals(OriginVisibility.mask(system, "B", "D", "/root/Salary", "1", "x"))
				&& "x".equals(OriginVisibility.mask(null, "B", "D", "/root/Salary", "1", "x")), "administrator, system user and the platform see");
			check(OriginVisibility.canRead(reader, "B", null, "/root/Person", "1") && !OriginVisibility.canRead(reader, "B", null, "/root/Salary", "1"),
				"a log line names no dataset: the table decides");
			check(OriginVisibility.canRead(reader, "B", "D", "/root/Salary", null), "no origin record: nothing to protect");
			OriginVisibility.use(new OriginVisibility.Probe()
			{
				@Override
				public boolean canRead(final Session pSession, final String pDataspace, final String pDataset, final String pTable, final String pKey)
				{
					throw new IllegalStateException("lookup failed");
				}
			});
			check(!OriginVisibility.canRead(reader, "B", "D", "/root/Person", "1"), "a lookup that fails hides");
			OriginVisibility.use(new OriginVisibility.Probe()
			{
				@Override
				public boolean canRead(final Session pSession, final String pDataspace, final String pDataset, final String pTable, final String pKey)
				{
					return !"/root/Salary".equals(pTable);
				}
			});

			// the node rule on the log, provenance, conflicts and outbound states
			final OriginValueAccessRule rule = new OriginValueAccessRule(ConnectorsPaths.SyncLog.DATASPACE, null, ConnectorsPaths.SyncLog.TABLE_PATH, ConnectorsPaths.SyncLog.RECORD_KEY);
			check(rule.permission(reader, "B", null, "/root/Salary", "1").isHidden(), "READ reader: changes of a closed table hidden");
			check(rule.permission(reader, "B", null, "/root/Person", "1").isReadWrite(), "READ reader: changes of an open table shown");
			check(!rule.permission(admin, "B", null, "/root/Salary", "1").isHidden(), "administrator: shown");
			// the field paths the rule is declared on are absolute paths of the model
			check(Path.parse(ConnectorsPaths.SYNC_LOG.format() + "/" + ConnectorsPaths.SyncLog.CHANGES.format().substring(2)).format().equals("/root/SyncLog/changes"), "field path");

			// storeChanges: off by default, on only when asked
			final String old = System.getProperty("ebx.connectors.log.storeChanges");
			try
			{
				System.clearProperty("ebx.connectors.log.storeChanges");
				check(!ConnectorRepository.storeChanges(), "default: no values in the log");
				System.setProperty("ebx.connectors.log.storeChanges", "false");
				check(!ConnectorRepository.storeChanges(), "false: no values");
				System.setProperty("ebx.connectors.log.storeChanges", "true");
				check(ConnectorRepository.storeChanges(), "true: values stored");
			}
			finally
			{
				if (old == null) System.clearProperty("ebx.connectors.log.storeChanges"); else System.setProperty("ebx.connectors.log.storeChanges", old);
			}
		}
		finally
		{
			OriginVisibility.use(previous);
		}
		System.out.println("PASS: stored values follow the permissions of the origin record; the log keeps none by default");
	}

	private static Session session(final boolean pSystem, final boolean pAdmin)
	{
		return (Session) Proxy.newProxyInstance(OriginValuesTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName())) return Boolean.valueOf(pSystem);
				if ("isUserInRole".equals(pMethod.getName())) return Boolean.valueOf(pAdmin && Profile.ADMINISTRATOR.equals(pArguments[0]));
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass) throw new AssertionError(pMessage);
	}
}
