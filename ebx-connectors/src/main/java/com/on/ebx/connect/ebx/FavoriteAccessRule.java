package com.on.ebx.connect.ebx;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * A favorite belongs to one user: everybody sees only their own rows, an administrator sees all of them, and
 * nobody edits them by hand (the star of the connector explorer writes them, with all privileges).
 */
public final class FavoriteAccessRule implements AccessRule
{
	static final UserMessage REASON = UserMessage.createInfo("Favorites are managed with the star of the connector explorer.");

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		if (pRecord == null || !pRecord.isTableOccurrence()) return AccessPermission.getReadOnly(REASON);
		if (pSession.isUserInRole(Profile.ADMINISTRATOR)) return AccessPermission.getReadOnly(REASON);
		final String login = Favorites.login(pSession);
		return login != null && login.equals(pRecord.getString(ConnectorsPaths.Favorite.USER_LOGIN)) ? AccessPermission.getReadOnly(REASON) : AccessPermission.getHidden();
	}
}
