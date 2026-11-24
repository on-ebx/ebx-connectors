package com.on.ebx.connect.ebx.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guards the faceted connector explorer: every facet has its card attribute, its labels exist in both languages, and the star stays out of the link. */
public final class ConnectorCatalogFacetsContractTest
{
	private ConnectorCatalogFacetsContractTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final Path root = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String ui = "src/main/java/com/on/ebx/connect/ebx/ui/";
		final String catalog = read(root.resolve(ui + "ConnectorCatalogUserService.java"));
		final String page = read(root.resolve(ui + "FacetedPage.java"));
		final String stars = read(root.resolve(ui + "FavoriteStars.java"));
		final String xsd = read(root.resolve("src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd"));
		final String extensions = read(root.resolve("src/main/java/com/on/ebx/connect/ebx/ConnectorsSchemaExtensions.java"));
		final String en = read(root.resolve("src/main/resources/com/on/ebx/connect/ebx/ui/Messages_en.properties"));
		final String fr = read(root.resolve("src/main/resources/com/on/ebx/connect/ebx/ui/Messages_fr.properties"));
		check(Files.exists(root.resolve("src/main/webapp/www/common/stylesheets/connect/facets.css")), "facets.css is not packaged with the module");

		// a filter without its data-<filter> attribute on the cards would silently match nothing
		final Matcher filters = Pattern.compile("page\\.(?:facet|pillRow)\\(pWriter, \"(\\w+)\"").matcher(catalog);
		int count = 0;
		while (filters.find())
		{
			count++;
			check(catalog.contains("data-" + filters.group(1) + "=\\\""), "no card attribute for the filter '" + filters.group(1) + "'");
		}
		check(count >= 5, "the explorer lost its facets (favorites, category, status, connection, advanced filters)");
		final Matcher sorts = Pattern.compile("sorts\\.put\\(\"(\\w+)\"").matcher(catalog);
		while (sorts.find()) check(catalog.contains("data-" + sorts.group(1) + "=\\\""), "no card attribute for the sort '" + sorts.group(1) + "'");

		// the star is a button: it must follow the link, never sit inside it, and the page must publish its endpoint
		check(catalog.contains("</a>\").add(FavoriteStars.button("), "the favorite star is not a sibling of the card link");
		check(catalog.contains("id=\\\"ftcxFavEndpoint\\\"") && stars.contains("getElementById('ftcxFavEndpoint')"), "the star has no AJAX endpoint to call");
		check(stars.contains("e.preventDefault();e.stopPropagation()") && stars.contains("closest('.ftcc-card')"), "a click on the star must not open the connector nor leave a return token");
		check(xsd.contains("<xs:element name=\"Favorite\"") && xsd.contains("<primaryKeys>/userLogin /targetType /targetKey </primaryKeys>"), "the Favorite table is missing from the model");
		check(extensions.contains("setAccessRuleOnOccurrence(com.on.ebx.connect.core.ConnectorsPaths.FAVORITE, new FavoriteAccessRule())"), "favorites are not restricted to their owner");

		final Matcher keys = Pattern.compile("message\\(\"([\\w.]+)\"\\)").matcher(catalog + page);
		while (keys.find()) label(en, fr, keys.group(1));
		for (final String key : new String[] { "favorite.add", "favorite.remove", "favorite.error.LIMIT", "favorite.error.UNKNOWN_TARGET", "favorite.error.BAD_REQUEST", "favorite.error.FAILED",
			"catalog.auth.NONE", "catalog.auth.BASIC", "catalog.auth.API_KEY_HEADER", "catalog.auth.BEARER", "catalog.auth.OAUTH2",
			"catalog.pricing.FREE", "catalog.pricing.FREEMIUM", "catalog.pricing.PAID", "catalog.pricing.UNKNOWN",
			"catalog.mapping", "catalog.mappings", "catalog.countFiltered" })
			label(en, fr, key);
		// the pricing badge and its data-pricing facet must exist together, and the connector's model
		// field and the config seed must all agree on the same four-value vocabulary.
		check(catalog.contains("data-pricing=\\\""), "the pricing facet has no card attribute");
		check(xsd.contains("<xs:element name=\"pricing\"") && xsd.contains("<xs:enumeration value=\"FREE\">")
				&& xsd.contains("<xs:enumeration value=\"FREEMIUM\">") && xsd.contains("<xs:enumeration value=\"PAID\">")
				&& xsd.contains("<xs:enumeration value=\"UNKNOWN\">"),
			"the pricing field or its FREE/FREEMIUM/PAID/UNKNOWN enumeration is missing from the model");
		System.out.println("PASS: every explorer filter and sort has its card attribute and EN/FR labels, and the favorite star stays outside the link");
	}
	private static void label(final String pEn, final String pFr, final String pKey)
	{
		final Pattern line = Pattern.compile("(?m)^" + Pattern.quote(pKey) + "\\s*=");
		check(line.matcher(pEn).find() && line.matcher(pFr).find(), "missing EN/FR label: " + pKey);
	}
	private static String read(final Path pPath) throws Exception { return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8); }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
