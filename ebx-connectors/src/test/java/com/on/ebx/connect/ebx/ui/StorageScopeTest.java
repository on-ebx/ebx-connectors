package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Browser storage keyed by the EBX user (security lot, wave 13). The key composition, the attribute writer and the purge script are
 * checked here; the purge itself runs in the browser (no JavaScript engine in the test JDK), see docs/REFERENCE.md "Browser storage" for the
 * manual check. A source scan guarantees that no screen writes, reads or removes a storage key that is a bare literal.
 */
public final class StorageScopeTest
{
	private StorageScopeTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// keys: <prefix>:<encodeURIComponent(user)>, the form the data catalog's ftdcStore writes (shared ftServiceReturn, ftcxRail, ftcxMoreOpen)
		check("ftccRecordOrigin:alice".equals(StorageScope.key("alice", "ftccRecordOrigin")), "key = <prefix>:<user>");
		check("ftServiceReturn:alice".equals(StorageScope.key("alice", "ftServiceReturn")), "the shared return key is the catalog's form");
		check(!StorageScope.key("alice", "x").equals(StorageScope.key("bob", "x")), "two users, two keys");
		// encodeURIComponent, byte for byte: what JavaScript leaves alone stays, everything else is UTF-8 percent-encoded in upper case
		check("a%3Ab".equals(StorageScope.encode("a:b")), "':' is encoded, a login can never contain the separator");
		check("a%20b".equals(StorageScope.encode("a b")) && "a%2Bb".equals(StorageScope.encode("a+b")), "space is %20 (not '+'), '+' is %2B");
		check("A-z_0.9!~*'()".equals(StorageScope.encode("A-z_0.9!~*'()")), "the characters encodeURIComponent keeps are kept");
		check("jos%C3%A9%40x.fr".equals(StorageScope.encode("jos\u00e9@x.fr")), "UTF-8 bytes, upper-case hex");
		check("%E2%82%AC".equals(StorageScope.encode("\u20ac")) && "%25%2F%5C%22%3C".equals(StorageScope.encode("%/\\\"<")), "euro sign and reserved characters");
		check(StorageScope.key("x\\y", "n").indexOf('\\') < 0 && StorageScope.key("a\"b", "n").indexOf('"') < 0, "no backslash or double quote survives in a key");
		check("ftServiceReturn:".equals(StorageScope.key("", "ftServiceReturn")) && StorageScope.key(null, "x").equals(StorageScope.key("", "x")), "no user gives the empty id");
		check("".equals(StorageScope.userId(null)), "no session gives no user");
		check("'ftcxRail:alice'".equals(StorageScope.jsKey("alice", "ftcxRail")), "jsKey is a single-quoted literal");
		check("'ftcxRail:o%27brien'".equals(StorageScope.jsKey("o\u0027brien", "ftcxRail")) || StorageScope.jsKey("o'brien", "ftcxRail").contains("\\'"), "a quote in a login cannot end the literal");

		// attribute writer
		check(" data-user=\"alice\"".equals(StorageScope.attribute("alice")), "attribute names the user");
		check(StorageScope.attribute("a\"b<>").indexOf('<') < 0 && StorageScope.attribute("a\"b<>").indexOf("b\"") < 0, "attribute value is escaped");
		check(" data-user=\"\"".equals(StorageScope.attribute(null)), "attribute with no user is empty");

		// purge script: for each prefix, removes the bare name and the other users' keys, from both stores, keeps the user's own
		final String purge = StorageScope.purgeJs("alice");
		check(purge.contains("u='alice'"), "purge knows the user's encoded id");
		check(purge.contains("sessionStorage") && purge.contains("localStorage"), "purge covers both stores");
		check(purge.contains("k===p||(k.indexOf(p+':')===0&&k!==own&&k.indexOf(own+':')!==0)"), "purge removes the bare prefix and the other users' keys, never the user's own");
		for (final String prefix : StorageScope.PREFIXES) { check(purge.contains("'" + prefix + "'"), "purge covers the prefix " + prefix); }
		check(purge.contains("removeItem") && purge.contains("catch(e){}"), "purge removes and never throws (private window)");
		check(!StorageScope.purgeJs("bob").equals(purge), "purge is per user");

		// the prefixes the two modules share are exactly the ones the data catalog's helper purges and writes
		final Path catalogHelper = root0().resolve("../../ebx-data-catalog-w13/ebx-data-catalog/src/main/resources/com/on/ebx/catalog/ebx/ui/user-storage.js").normalize();
		if (Files.exists(catalogHelper)) {
			final String helper = new String(Files.readAllBytes(catalogHelper), StandardCharsets.UTF_8);
			for (final String shared : new String[] { "ftServiceReturn", "ftcxRail", "ftcxMoreOpen" }) {
				check(helper.contains("'" + shared + "'"), "the data catalog's ftdcStore does not declare the shared prefix " + shared);
			}
			check(helper.contains("encodeURIComponent(user") && helper.contains("prefix + ':' + id + (extra ? ':' + extra : '')"), "the catalog's key form changed: update StorageScope");
		}

		// every storage call of the screens goes through StorageScope: no bare literal key, and a user id reaches each class that touches storage
		final Path root = root0();
		final Pattern literal = Pattern.compile("(session|local)Storage\\.(get|set|remove)Item\\(\\s*'");
		int classes = 0;
		try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
			for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				final String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				if (src.indexOf("sessionStorage") < 0 && src.indexOf("localStorage") < 0) continue;
				if (file.getFileName().toString().equals("StorageScope.java")) continue;
				classes++;
				final Matcher m = literal.matcher(src);
				check(!m.find(), file.getFileName() + " uses a storage key that is a bare literal: " + (m.hitEnd() ? "" : src.substring(m.start(), Math.min(src.length(), m.start() + 60))));
				check(src.contains("StorageScope."), file.getFileName() + " touches browser storage without StorageScope");
			}
		}
		check(classes >= 5, "the scan found the storage classes (" + classes + ")");
		// no script file and no resource touches storage on its own
		try (Stream<Path> files = Files.walk(root.resolve("src/main/webapp/www/common/jscripts"))) {
			for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".js"))::iterator) {
				final String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				check(src.indexOf("sessionStorage") < 0 && src.indexOf("localStorage") < 0 && src.indexOf("indexedDB") < 0 && src.indexOf("document.cookie") < 0,
					file.getFileName() + " uses browser storage: key it with StorageScope");
			}
		}
		final String catalog = new String(Files.readAllBytes(root.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorCatalogUserService.java")), StandardCharsets.UTF_8);
		check(catalog.contains("StorageScope.attribute(userId)") && catalog.contains("StorageScope.purgeJs(userId)"), "the catalog writes data-user and purges other users' keys at opening");
		System.out.println("PASS: browser storage keys are per EBX user, purged at opening, and never bare literals");
	}

	private static Path root0() { return Paths.get(System.getProperty("ebx.connectors.moduleDir", ".")).toAbsolutePath().normalize(); }

	private static void check(final boolean pCondition, final String pMessage)
	{ if (!pCondition) throw new AssertionError(pMessage); }
}
