package com.on.ebx.connect.ebx;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.ebx.ConnectorsAccess.Right;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Session;

/**
 * The {@code value} of a {@code ConnectorProperty} row whose key carries a credential ({@code oauth.clientSecret},
 * {@code oauth.refreshToken}, {@code oauth.privateKey}...) is a plain string, so the user interface, Data Services and
 * the {@code data-access} MCP would show it to anyone who may read the table. This rule hides it from every session
 * that does not hold the MANAGE right (administrators, the system user and designers do, so the engine and the people
 * who configure connectors are unaffected: the engine reads the rows without a session's access rules anyway).
 * <p>
 * Since audit C-01 (tables) the record rule of the table ({@link ConnectorsTableAccessRule}) already makes every row read
 * only below MANAGE; EBX combines it with this node rule (the most restrictive wins), and the schema extensions declare
 * this rule with {@code designerOnly} true. The flag stays for the decision and its test.
 * <p>
 * {@value #PROPERTY_HIDE}{@code =false} switches the hiding off (the designer-role rule stays).
 */
public final class ConnectorPropertyValueAccessRule implements AccessRule
{
	/** JVM property: {@code false} shows the secret-bearing values to everybody who may read the table. Default: hidden. */
	public static final String PROPERTY_HIDE = "ebx.connectors.secrets.hideProperties";

	/** The field this rule is declared on. */
	public static final Path VALUE = Path.parse("/root/ConnectorProperty/value");

	static final UserMessage HIDDEN_REASON = UserMessage.createInfo("The value of a credential property is visible to those who manage connectors.");
	static final UserMessage DESIGNER_ONLY_REASON = UserMessage.createInfo("Only the designers of connectors may change the configuration.");

	/** What a session may do with the field; kept apart from EBX types so that the decision is testable. */
	public enum Decision
	{
		READ_WRITE, READ_ONLY, HIDDEN
	}

	private final boolean designerOnly;

	/** @param pDesignerOnly whether a designer role property restricts the edition of the configuration tables */
	public ConnectorPropertyValueAccessRule(final boolean pDesignerOnly)
	{
		this.designerOnly = pDesignerOnly;
	}

	/**
	 * @param pDesignerOnly a designer role property is set: only MANAGE may edit
	 * @param pManage the session holds the MANAGE right
	 * @param pKey the key of the row; null when there is no row yet (a creation form): nothing is stored, nothing to hide
	 * @param pHideEnabled {@value #PROPERTY_HIDE} is not {@code false}
	 */
	public static Decision decide(final boolean pDesignerOnly, final boolean pManage, final String pKey, final boolean pHideEnabled)
	{
		if (pManage)
		{
			return Decision.READ_WRITE;
		}
		if (pHideEnabled && pKey != null && Redaction.isSecretKey(pKey))
		{
			return Decision.HIDDEN;
		}
		return pDesignerOnly ? Decision.READ_ONLY : Decision.READ_WRITE;
	}

	/** True unless the JVM property switches the hiding off. */
	static boolean hideEnabled()
	{
		return !"false".equalsIgnoreCase(System.getProperty(PROPERTY_HIDE, "true").trim());
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return this.permission(pRecord != null && pRecord.isTableOccurrence() ? pRecord.getString(ConnectorsPaths.ConnectorProperty.KEY) : null, pSession);
	}

	/** The permission of a session on the value of the row with this key (null: no row). Apart from the row so that a test needs no EBX record. */
	AccessPermission permission(final String pKey, final Session pSession)
	{
		switch (ConnectorPropertyValueAccessRule.decide(this.designerOnly, ConnectorsAccess.has(pSession, Right.MANAGE), pKey, hideEnabled()))
		{
			case HIDDEN:
				return AccessPermission.getHidden(HIDDEN_REASON);
			case READ_ONLY:
				return AccessPermission.getReadOnly(DESIGNER_ONLY_REASON);
			default:
				return AccessPermission.getReadWrite();
		}
	}
}
