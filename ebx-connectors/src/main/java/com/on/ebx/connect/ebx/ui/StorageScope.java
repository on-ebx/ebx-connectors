package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;

import com.orchestranetworks.service.Session;

/**
 * Browser storage of the module's custom screens belongs to the EBX user, not to the browser tab (security lot, wave 13).
 * {@code sessionStorage} is kept per TAB and survives an EBX logout; {@code localStorage} survives closing the browser. A key
 * that does not name the user therefore hands one user's state (the page a flow card came from, a connector chosen for a
 * mapping to create, the filters of the catalog) to the next user who logs in in the same browser.
 *
 * <p>Key form, shared with the data catalog module ({@code ftdcStore} in its {@code user-storage.js}), which reads and writes the
 * same {@code ftServiceReturn}, {@code ftcxRail} and {@code ftcxMoreOpen} entries on the same origin:
 * {@code <prefix>:<encodeURIComponent(user id)>[:<extra>]}, the value a JSON text. The encoding is JavaScript's
 * {@code encodeURIComponent} ({@link #encode}), so both modules compose the same key for the same login.
 *
 * <p>Rule applied by every screen that writes a {@code sessionStorage} / {@code localStorage} key:
 * <ol>
 * <li>the key is {@code <prefix>:<user>} ({@link #key}); the user id is the one of the session that renders the screen
 * ({@link #userId});</li>
 * <li>at opening, the screen runs {@link #purgeJs}: for each prefix of the module ({@link #PREFIXES}), the keys that belong to
 * ANOTHER user and the bare, user-less names of the versions before this lot are removed from both stores;</li>
 * <li>a value passed from one page to another is read then removed at once, or, when it has to survive the redisplay of the
 * target form (a return link), it is bound to the user and consumed by the click that uses it.</li>
 * </ol>
 * The user id is also written as {@code data-user} on the container of the catalog ({@link #attribute}). Keys are always composed
 * on the server by {@link #key}: no page script builds one on its own.
 */
final class StorageScope
{
	/**
	 * Every storage prefix written by the module. The first three are Connectors' own hand-overs; the last three are also written
	 * by the data catalog (same name, same key form), so each module's purge keeps the other's keys of the current user.
	 */
	static final String[] PREFIXES = { "ftccRecordOrigin", "ftcnFlowReturn", "ftcnCreateConnector", "ftServiceReturn", "ftcxRail", "ftcxMoreOpen" };

	private StorageScope() { }

	/** The EBX login of the session, or an empty string when there is none. */
	static String userId(final Session pSession)
	{
		return pSession == null || pSession.getUserReference() == null || pSession.getUserReference().getUserId() == null ? "" : pSession.getUserReference().getUserId();
	}

	/** JavaScript's {@code encodeURIComponent}: UTF-8 percent-encoding of everything but {@code A-Z a-z 0-9 - _ . ! ~ * ' ( )}. */
	static String encode(final String pText)
	{
		final byte[] bytes = (pText == null ? "" : pText).getBytes(StandardCharsets.UTF_8);
		final StringBuilder out = new StringBuilder();
		for (final byte b : bytes)
		{
			final int c = b & 0xff;
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) { out.append((char) c); }
			else { out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16))); }
		}
		return out.toString();
	}

	/** The storage key of {@code pPrefix} for the user: {@code <prefix>:<encoded user>}. */
	static String key(final String pUserId, final String pPrefix)
	{
		return pPrefix + ":" + encode(pUserId);
	}

	/** The key as a single-quoted JavaScript literal, ready to be concatenated into a script. */
	static String jsKey(final String pUserId, final String pPrefix)
	{
		return "'" + jsEscape(key(pUserId, pPrefix)) + "'";
	}

	/** {@code  data-user="<login>"} for the container of a pane (leading space included). */
	static String attribute(final String pUserId)
	{
		return " data-user=\"" + MappingForm.escape(pUserId == null ? "" : pUserId) + "\"";
	}

	/**
	 * A self-contained script that removes, from {@code sessionStorage} and {@code localStorage}, for each prefix of the module, the
	 * bare legacy key and the keys of another user, and keeps the current user's own. Safe to run several times in the same page.
	 */
	static String purgeJs(final String pUserId)
	{
		final StringBuilder prefixes = new StringBuilder();
		for (final String name : PREFIXES) { prefixes.append(prefixes.length() == 0 ? "" : ",").append('\'').append(name).append('\''); }
		return "(function(){var u='" + jsEscape(encode(pUserId)) + "',P=[" + prefixes + "];['sessionStorage','localStorage'].forEach(function(n){try{var s=window[n],d=[];"
			+ "for(var i=0;i<s.length;i++){var k=s.key(i);if(!k)continue;for(var j=0;j<P.length;j++){var p=P[j],own=p+':'+u;"
			+ "if(k===p||(k.indexOf(p+':')===0&&k!==own&&k.indexOf(own+':')!==0)){d.push(k);break;}}}"
			+ "d.forEach(function(k){s.removeItem(k);});}catch(e){}});})();";
	}

	private static String jsEscape(final String pText)
	{
		return pText.replace("\\", "\\\\").replace("'", "\\'");
	}
}
