package com.on.ebx.connect.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.ebx.ConnectorPropertyValueAccessRule.Decision;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * Audit C-05: the value of a credential property is hidden from whoever does not manage connectors, and nothing else
 * changes: a designer, an administrator and the system user keep the field, the other rows stay readable, and the
 * edition rule of the designer role property survives on the field (EBX keeps one rule per terminal element).
 */
public final class ConnectorPropertyValueAccessRuleTest
{
	private ConnectorPropertyValueAccessRuleTest() { }

	public static void main(final String[] pArgs)
	{
		keys();
		decisions();
		throughTheRule();
		System.out.println("PASS: the value of a credential property is hidden from whoever does not manage connectors");
	}

	private static void keys()
	{
		for (final String key : new String[] { "oauth.clientSecret", "oauth.refreshToken", "oauth.privateKey", "privateKey", "refreshToken", "securityToken", "sessionToken",
			"resourceToken", "wsse.password", "tls.keystorePassword", "tls.keyPassword", "tls.truststorePassword", "serviceAccountJson", "sasl.jaas.config",
			"ssl.key.passphrase", "oauth.client_secret", "OAUTH.CLIENTSECRET", "storage.accountKey", "credentials" })
		{
			check(Redaction.isSecretKey(key), "a credential key must be secret: " + key);
		}
		for (final String key : new String[] { "tenant", "tenantId", "region", "schema", "max.rows", "oauth.flow", "oauth.scope", "oauth.clientId", "webhook.signature",
			"security.protocol", "sasl.mechanism", "driver", "jdbc.sslMode", "op.getByLei.path", "serviceAccountFile", "topic" })
		{
			check(!Redaction.isSecretKey(key), "an ordinary key must not be hidden: " + key);
		}
		check(!Redaction.isSecretKey(null) && !Redaction.isSecretKey(""), "no key, no secret");
	}

	private static void decisions()
	{
		// designers, administrators and the system user (MANAGE) keep the field, hiding on or off
		for (final boolean designerOnly : new boolean[] { false, true })
		{
			check(ConnectorPropertyValueAccessRule.decide(designerOnly, true, "oauth.clientSecret", true) == Decision.READ_WRITE, "MANAGE sees a secret");
			check(ConnectorPropertyValueAccessRule.decide(designerOnly, true, "tenant", true) == Decision.READ_WRITE, "MANAGE edits an ordinary value");
			// anybody else: secret hidden, whatever the designer property says
			check(ConnectorPropertyValueAccessRule.decide(designerOnly, false, "oauth.clientSecret", true) == Decision.HIDDEN, "a reader must not see a secret");
			check(ConnectorPropertyValueAccessRule.decide(designerOnly, false, "oauth.privateKey", true) == Decision.HIDDEN, "nor a private key");
		}
		// an ordinary value: unchanged behaviour (EBX permissions alone, or read-only when a designer role property is set)
		check(ConnectorPropertyValueAccessRule.decide(false, false, "tenant", true) == Decision.READ_WRITE, "without a designer property an ordinary value is as before");
		check(ConnectorPropertyValueAccessRule.decide(true, false, "tenant", true) == Decision.READ_ONLY, "with a designer property it is read-only, as before");
		// the switch
		check(ConnectorPropertyValueAccessRule.decide(false, false, "oauth.clientSecret", false) == Decision.READ_WRITE, "hiding off: as before");
		check(ConnectorPropertyValueAccessRule.decide(true, false, "oauth.clientSecret", false) == Decision.READ_ONLY, "hiding off, designer property: as before");
		// a creation form has no row yet
		check(ConnectorPropertyValueAccessRule.decide(false, false, null, true) == Decision.READ_WRITE, "nothing stored, nothing to hide");
	}

	private static void throughTheRule()
	{
		ConnectorsAccess.use(new ConnectorsAccess.Grants()
		{
			@Override
			public java.util.Map<Profile, ConnectorsAccess.Right> byProfile()
			{
				return java.util.Collections.emptyMap();
			}
		});
		final Session designer = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_DESIGNER));
		final Session operator = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR));
		final Session viewer = session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER));
		final Session stranger = session(false);
		final Session admin = session(false, Profile.ADMINISTRATOR);
		final Session system = session(true);
		final String secret = "oauth.clientSecret";
		final String plain = "tenant";
		final ConnectorPropertyValueAccessRule open = new ConnectorPropertyValueAccessRule(false);
		final ConnectorPropertyValueAccessRule designerOnly = new ConnectorPropertyValueAccessRule(true);
		for (final Session manager : new Session[] { designer, admin, system })
		{
			check(open.permission(secret, manager).isReadWrite() && designerOnly.permission(secret, manager).isReadWrite(),
				"a manager keeps the secret");
		}
		for (final Session reader : new Session[] { operator, viewer, stranger, null })
		{
			check(open.permission(secret, reader).isHidden() && designerOnly.permission(secret, reader).isHidden(), "everybody else loses it");
			check(open.permission(plain, reader).isReadWrite() && designerOnly.permission(plain, reader).isReadOnly(),
				"an ordinary row is as before");
		}
		check(open.permission(null, viewer).isReadWrite(), "no row: nothing to hide");
		System.setProperty(ConnectorPropertyValueAccessRule.PROPERTY_HIDE, "false");
		try
		{
			check(open.permission(secret, viewer).isReadWrite() && designerOnly.permission(secret, viewer).isReadOnly(), "the switch restores the old behaviour");
		}
		finally { System.clearProperty(ConnectorPropertyValueAccessRule.PROPERTY_HIDE); }
		check(open.permission(secret, viewer).isHidden(), "hidden again once the property is gone");
		ConnectorsAccess.use(null);
	}

	private static Session session(final boolean pSystem, final Profile... pRoles)
	{
		final Set<Profile> roles = new HashSet<Profile>(java.util.Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(ConnectorPropertyValueAccessRuleTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName())) return Boolean.valueOf(pSystem);
				if ("isUserInRole".equals(pMethod.getName())) return Boolean.valueOf(roles.contains(pArguments[0]));
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	private static void check(final boolean pPass, final String pMessage) { if (!pPass) throw new AssertionError(pMessage); }
}
