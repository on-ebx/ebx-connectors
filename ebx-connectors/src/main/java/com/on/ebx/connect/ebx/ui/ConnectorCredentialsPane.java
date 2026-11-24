package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.CredentialGuide;
import com.on.ebx.connect.core.CredentialGuide.State;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConnectorConfig.AuthType;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPaneWriter;
import com.orchestranetworks.ui.form.UIFormRow;

/**
 * "Endpoint & credentials" block of the connector form: the native credential rows with a stable
 * id each, the authentication modes as cards, the properties the mode expects, the readiness
 * strip, and the guide of the selected mode (what to enter, where to get it, what the module
 * sends). Everything is rendered server-side for every mode the type offers; the page script
 * ({@code connect/connector-credentials.js}) only follows the native "Authentication" widget and
 * shows the matching pane, so the texts stay in the {@code Messages} bundles and the native EBX
 * widgets keep their behaviour (validation, permissions, encryption, Save/Revert).
 */
final class ConnectorCredentialsPane
{
	private static final String PREFIX = "credentials.";
	private static final Path[] ROWS = { ConnectorsPaths.Connector.BASE_URL, ConnectorsPaths.Connector.AUTH_TYPE,
		ConnectorsPaths.Connector.USERNAME, ConnectorsPaths.Connector.PASSWORD, ConnectorsPaths.Connector.API_KEY_HEADER,
		ConnectorsPaths.Connector.API_KEY };

	private final UIFormContext context;
	private final Locale locale;
	private final Adaptation record;
	private final String type;
	private final ConnectorDescriptor descriptor;
	private final Map<String, Object> values;
	private final List<AuthType> allowed;
	private final AuthType current;
	private final String flow;
	private final List<String> rows = new ArrayList<String>();

	ConnectorCredentialsPane(final UIFormContext pContext, final ConnectorDescriptor pDescriptor, final Map<String, Object> pValues)
	{
		this.context = pContext;
		this.locale = pContext.getLocale();
		this.record = pContext.getCurrentRecord();
		this.type = value(ConnectorsPaths.Connector.TYPE);
		this.descriptor = pDescriptor;
		this.values = pValues;
		this.allowed = CredentialGuide.modesFor(pDescriptor);
		this.current = mode(value(ConnectorsPaths.Connector.AUTH_TYPE));
		this.flow = CredentialGuide.normalizeFlow(pValues.get("oauth.flow") == null ? null : String.valueOf(pValues.get("oauth.flow")));
		final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
		for (final Path path : ROWS) if (root.getNode(path) != null) this.rows.add(key(path));
	}

	private AuthType mode(final String pStored)
	{
		if (pStored != null && pStored.trim().length() > 0)
		{
			try { return AuthType.valueOf(pStored.trim()); }
			catch (final IllegalArgumentException ignored) { /* unknown value: fall back below */ }
		}
		return this.allowed.contains(AuthType.NONE) || this.allowed.isEmpty() ? AuthType.NONE : this.allowed.get(0);
	}

	// ------------------------------------------------------------------ main column

	/** Native rows (stable ids), mode cards, expected properties, secret meter and readiness strip. */
	void writeFields(final UIFormPaneWriter pWriter)
	{
		final String hint = optional(PREFIX + "type." + this.type + ".hint");
		if (this.descriptor != null)
		{
			pWriter.add("<div class=\"ftcc-type\"><b>").add(esc(this.descriptor.getLabel())).add("</b>");
			if (hint != null) pWriter.add("<span>").add(esc(hint)).add("</span>");
			pWriter.add("</div>");
		}
		// the modes and the meaning of the selected one first, then the native rows they annotate
		this.writeModes(pWriter);
		final SchemaNode root = this.context.getCurrentTable().getTableOccurrenceRootNode();
		pWriter.add("<div id=\"ftcc-rows\">");
		pWriter.startTableFormRow();
		for (final Path path : ROWS)
		{
			if (root.getNode(path) == null) continue;
			final UIFormRow row = pWriter.newFormRow(path);
			row.setRowId("ftcc_" + key(path));
			pWriter.addFormRow(row);
		}
		pWriter.endTableFormRow();
		pWriter.add("</div>");
		pWriter.add("<div id=\"ftcc-meter\" class=\"ftcc-meter\" hidden></div>");
		for (final AuthType mode : this.allowed) this.writeProperties(pWriter, mode);
		pWriter.add("<div id=\"ftcc-ready\" class=\"ftcc-ready\"></div>");
		pWriter.add("<div class=\"ftcc-legend\">").add(esc(text(PREFIX + "legend")));
		for (final State state : State.values()) pWriter.add("<span class=\"ftcc-state ").add(state.code()).add("\">").add(esc(text(PREFIX + "state." + state.code()))).add("</span>");
		pWriter.add("</div>");
	}

	private void writeModes(final UIFormPaneWriter pWriter)
	{
		pWriter.add("<div class=\"ftcc-modes\">");
		for (final AuthType mode : AuthType.values())
		{
			final boolean offered = this.allowed.contains(mode);
			pWriter.add("<button type=\"button\" class=\"ftcc-mode").add(mode == this.current ? " on" : "").add("\" data-mode=\"").add(mode.name()).add("\"");
			if (!offered) pWriter.add(" disabled title=\"").add(esc(text(PREFIX + "mode.notOffered"))).add("\"");
			pWriter.add("><b>").add(esc(text(PREFIX + "mode." + mode.name() + ".name"))).add("</b><span>")
				.add(esc(text(PREFIX + "mode." + mode.name() + ".short"))).add("</span></button>");
		}
		pWriter.add("</div>");
		for (final AuthType mode : this.allowed)
		{
			pWriter.add("<div class=\"ftcc-desc\" data-ftcc-mode=\"").add(mode.name()).add("\"").add(mode == this.current ? "" : " hidden").add(">")
				.add(esc(text(PREFIX + "mode." + mode.name() + ".desc"))).add("</div>");
		}
	}

	private void writeProperties(final UIFormPaneWriter pWriter, final AuthType pMode)
	{
		final List<CredentialGuide.Property> properties = CredentialGuide.properties(this.type, pMode, this.flow);
		if (properties.isEmpty()) return;
		pWriter.add("<div class=\"ftcc-props\" data-ftcc-mode=\"").add(pMode.name()).add("\"").add(pMode == this.current ? "" : " hidden").add("><header><b>")
			.add(esc(text(PREFIX + "props.title"))).add("</b><span>").add(esc(text(PREFIX + "props.help"))).add("</span></header>");
		for (final CredentialGuide.Property property : properties)
		{
			final String name = property.getName();
			final boolean configured = present(this.values.get(name));
			pWriter.add("<div class=\"ftcc-prop\"><code>").add(esc(name)).add("</code><div class=\"v\">");
			if (property.getState() == State.DERIVED && !configured)
				pWriter.add("<span class=\"derived\">").add(esc(this.resolve(property.getDerivedValue()))).add("</span>");
			else if (configured)
				pWriter.add("<span>").add(esc(secret(name) ? text(PREFIX + "props.configured") : String.valueOf(this.values.get(name)))).add("</span>");
			else if ("oauth.flow".equals(name))
				pWriter.add("<span class=\"derived\">").add(esc(CredentialGuide.DEFAULT_FLOW)).add("</span>");
			final String hint = this.propertyHint(name);
			if (hint != null) pWriter.add("<small>").add(esc(hint)).add("</small>");
			pWriter.add("</div><span class=\"ftcc-state ").add(property.getState().code()).add("\">")
				.add(esc(text(PREFIX + "state." + property.getState().code()))).add("</span></div>");
		}
		pWriter.add("</div>");
	}

	private String propertyHint(final String pName)
	{
		if (this.descriptor != null)
			for (final ConfigurationPropertyDefinition property : this.descriptor.getConfigurationProperties())
				if (pName.equals(property.getName()) && property.getDescription() != null && property.getDescription().trim().length() > 0)
					return property.getDescription();
		final String specific = optional(PREFIX + "type." + this.type + ".prop." + pName);
		return specific != null ? specific : optional(PREFIX + "prop." + pName);
	}

	// ------------------------------------------------------------------ aside

	/** Three panels (what to enter, where to get it, what is sent), one pane per offered mode in each. */
	void writeGuide(final UIFormPaneWriter pWriter)
	{
		pWriter.add("<section class=\"ftcn-panel ftcc-guide\"><header><span class=\"ftcn-icon\">&#9998;</span><div><h2>")
			.add(esc(text(PREFIX + "guide.title"))).add("</h2><p>").add(esc(text(PREFIX + "guide.help"))).add("</p></div></header>");
		for (final AuthType mode : this.allowed) this.writeSteps(pWriter, mode);
		pWriter.add("</section><section class=\"ftcn-panel ftcc-obtain\"><header><span class=\"ftcn-icon\">&#128273;</span><div><h2>")
			.add(esc(text(PREFIX + "obtain.title"))).add("</h2><p>").add(esc(text(PREFIX + "obtain.help"))).add("</p></div></header>");
		for (final AuthType mode : this.allowed) this.writeObtain(pWriter, mode);
		pWriter.add("</section><section class=\"ftcn-panel ftcc-send\"><header><span class=\"ftcn-icon\">&#8644;</span><div><h2>")
			.add(esc(text(PREFIX + "send.title"))).add("</h2><p>").add(esc(text(PREFIX + "send.help"))).add("</p></div></header>");
		for (final AuthType mode : this.allowed) this.writeSend(pWriter, mode);
		pWriter.add("</section>");
	}

	private void pane(final UIFormPaneWriter pWriter, final AuthType pMode)
	{
		pWriter.add("<div data-ftcc-mode=\"").add(pMode.name()).add("\"").add(pMode == this.current ? "" : " hidden").add(">");
	}

	private void writeSteps(final UIFormPaneWriter pWriter, final AuthType pMode)
	{
		pane(pWriter, pMode);
		final List<String[]> steps = new ArrayList<String[]>();
		final String typed = PREFIX + "type." + this.type + "." + pMode.name() + ".step.";
		final String generic = PREFIX + "mode." + pMode.name() + ".step.";
		collectSteps(steps, optional(typed + "1.title") != null ? typed : generic);
		if (pMode == AuthType.OAUTH2 && !CredentialGuide.DEFAULT_FLOW.equals(this.flow)) collectSteps(steps, PREFIX + "flow." + this.flow + ".step.");
		if (steps.isEmpty()) pWriter.add("<div class=\"ftcc-none\">").add(esc(text(PREFIX + "guide.none"))).add("</div>");
		else
		{
			pWriter.add("<ol>");
			int n = 1;
			for (final String[] step : steps)
				pWriter.add("<li><span class=\"n\">").add(String.valueOf(n++)).add("</span><div><b>").add(esc(step[0])).add("</b><span>").add(rich(step[1])).add("</span></div></li>");
			pWriter.add("</ol>");
		}
		String warn = optional(PREFIX + "type." + this.type + "." + pMode.name() + ".warn");
		if (warn == null) warn = optional(PREFIX + "mode." + pMode.name() + ".warn");
		if (warn != null)
		{
			final int bar = warn.indexOf('|');
			final String level = bar > 0 ? warn.substring(0, bar).trim() : "warn";
			final String message = bar > 0 ? warn.substring(bar + 1).trim() : warn;
			final String icon = "crit".equals(level) ? "&#10006;" : "good".equals(level) ? "&#10003;" : "&#9888;";
			pWriter.add("<div class=\"ftcc-notice ").add(esc(level)).add("\"><b>").add(icon).add("</b><span>").add(esc(message)).add("</span></div>");
		}
		pWriter.add("</div>");
	}

	private void collectSteps(final List<String[]> pSteps, final String pPrefix)
	{
		for (int i = 1; i < 20; i++)
		{
			final String title = optional(pPrefix + i + ".title");
			if (title == null) return;
			final String body = optional(pPrefix + i + ".text");
			pSteps.add(new String[] { title, body == null ? "" : body });
		}
	}

	private void writeObtain(final UIFormPaneWriter pWriter, final AuthType pMode)
	{
		pane(pWriter, pMode);
		final String typed = PREFIX + "type." + this.type + "." + pMode.name() + ".obtain.";
		final String shared = PREFIX + "type." + this.type + ".obtain.";
		final String prefix = optional(typed + "1") != null ? typed : shared;
		boolean any = false;
		for (int i = 1; i < 20; i++)
		{
			final String item = optional(prefix + i);
			if (item == null) break;
			if (!any) pWriter.add("<ul>");
			pWriter.add("<li>").add(rich(item)).add("</li>");
			any = true;
		}
		if (any) pWriter.add("</ul>");
		else pWriter.add("<div class=\"ftcc-none\">").add(esc(text(PREFIX + (pMode == AuthType.NONE ? "obtain.nothing" : "obtain.none")))).add("</div>");
		final String note = optional(prefix + "note");
		if (note != null) pWriter.add("<div class=\"src\">").add(esc(note)).add("</div>");
		pWriter.add("</div>");
	}

	private void writeSend(final UIFormPaneWriter pWriter, final AuthType pMode)
	{
		pane(pWriter, pMode);
		final String typed = PREFIX + "type." + this.type + "." + pMode.name() + ".";
		String sent = optional(typed + "send");
		String code = optional(typed + "code");
		if (sent == null && code == null && pMode == AuthType.OAUTH2 && !CredentialGuide.DEFAULT_FLOW.equals(this.flow))
			code = optional(PREFIX + "flow." + this.flow + ".code");
		if (sent == null) sent = text(PREFIX + "mode." + pMode.name() + ".send");
		if (code == null) code = optional(PREFIX + "mode." + pMode.name() + ".code");
		pWriter.add("<p>").add(esc(sent)).add("</p>");
		if (code != null) pWriter.add("<pre>").add(esc(this.resolve(code))).add("</pre>");
		pWriter.add("</div>");
	}

	// ------------------------------------------------------------------ page script

	/** The model the page script needs, then the script itself (same pattern as the mapping designer). */
	void writeScript(final UIFormPaneWriter pWriter)
	{
		final StringBuilder json = new StringBuilder("{");
		json.append("\"current\":").append(json(this.current.name()));
		json.append(",\"limit\":").append(CredentialGuide.SECRET_MAX_LENGTH);
		json.append(",\"rows\":[");
		for (int i = 0; i < this.rows.size(); i++) json.append(i > 0 ? "," : "").append(json(this.rows.get(i)));
		json.append("],\"stored\":{");
		json.append("\"baseUrl\":").append(value(ConnectorsPaths.Connector.BASE_URL).trim().length() > 0);
		json.append(",\"password\":").append(secretStored(ConnectorsPaths.Connector.PASSWORD));
		json.append(",\"apiKey\":").append(secretStored(ConnectorsPaths.Connector.API_KEY));
		json.append("},\"fieldLabels\":{");
		final SchemaNode root = this.context.getCurrentTable().getTableOccurrenceRootNode();
		boolean first = true;
		for (final String field : CredentialGuide.FIELDS)
		{
			final SchemaNode node = root.getNode(Path.parse("./" + field));
			if (node == null) continue;
			json.append(first ? "" : ",").append(json(field)).append(':').append(json(node.getLabel(this.locale)));
			first = false;
		}
		json.append("},\"modes\":{");
		first = true;
		for (final AuthType mode : this.allowed)
		{
			json.append(first ? "" : ",").append(json(mode.name())).append(":{\"fields\":{");
			first = false;
			final Map<String, State> states = CredentialGuide.fieldStates(this.type, this.descriptor, mode, this.flow);
			final Map<String, String> labels = new LinkedHashMap<String, String>();
			boolean firstField = true;
			for (final Map.Entry<String, State> entry : states.entrySet())
			{
				if (!this.rows.contains(entry.getKey())) continue;
				json.append(firstField ? "" : ",").append(json(entry.getKey())).append(':').append(json(entry.getValue().code()));
				firstField = false;
				final String label = this.contextualLabel(entry.getKey(), mode);
				if (label != null && entry.getValue() != State.UNUSED) labels.put(entry.getKey(), label);
			}
			json.append("},\"labels\":{");
			boolean firstLabel = true;
			for (final Map.Entry<String, String> entry : labels.entrySet())
			{
				json.append(firstLabel ? "" : ",").append(json(entry.getKey())).append(':').append(json(entry.getValue()));
				firstLabel = false;
			}
			json.append("},\"props\":[");
			boolean firstProp = true;
			for (final CredentialGuide.Property property : CredentialGuide.properties(this.type, mode, this.flow))
			{
				final Object stored = this.values.get(property.getName());
				final boolean configured = present(stored);
				json.append(firstProp ? "" : ",").append("{\"name\":").append(json(property.getName())).append(",\"state\":")
					.append(json(property.getState().code())).append(",\"configured\":").append(configured).append('}');
				firstProp = false;
			}
			json.append("]}");
		}
		json.append("},\"text\":{");
		final String[] keys = { "required", "optional", "derived", "unused" };
		for (final String key : keys) json.append('"').append(key).append("\":").append(json(text(PREFIX + "state." + key))).append(',');
		json.append("\"unusedPlaceholder\":").append(json(text(PREFIX + "unusedPlaceholder")));
		json.append(",\"encrypted\":").append(json(text(PREFIX + "encrypted")));
		json.append(",\"counter\":").append(json(text(PREFIX + "counter")));
		json.append(",\"tooLong\":").append(json(text(PREFIX + "tooLong")));
		json.append(",\"endpoint\":").append(json(text(PREFIX + "ready.endpoint")));
		json.append(",\"credentials\":").append(json(text(PREFIX + "ready.credentials")));
		json.append(",\"missing\":").append(json(text(PREFIX + "ready.missing")));
		json.append(",\"none\":").append(json(text(PREFIX + "ready.none")));
		json.append("}}");
		pWriter.addJS_cr("var ftccModel = " + com.on.ebx.connect.core.Json.forScript(json.toString()) + ";");
		pWriter.addJS_cr("(function(){ var s = document.createElement('script'); s.type = 'text/javascript'; s.src = '"
			+ pWriter.getURLForResource(ResourceType.JSCRIPT, "connect/connector-credentials.js").replace("'", "%27")
			+ "?v=1'; (document.body || document.head).appendChild(s); })();");
	}

	// ------------------------------------------------------------------ helpers

	private String contextualLabel(final String pField, final AuthType pMode)
	{
		final String specific = optional(PREFIX + "type." + this.type + "." + pMode.name() + "." + pField);
		return specific != null ? specific : optional(PREFIX + "field." + pField + "." + pMode.name());
	}

	/** Replaces {apiKeyHeader}, {clientId}, {tenantId}, {baseUrl} and {oauth.tokenUrl} with what the record holds. */
	private String resolve(final String pText)
	{
		if (pText == null) return "";
		String text = pText;
		final String header = value(ConnectorsPaths.Connector.API_KEY_HEADER).trim();
		text = text.replace("{apiKeyHeader}", header.isEmpty() ? "X-API-Key" : header);
		final String baseUrl = value(ConnectorsPaths.Connector.BASE_URL).trim();
		text = text.replace("{baseUrl}", baseUrl.isEmpty() ? "https://…" : baseUrl);
		text = text.replace("{clientId}", stringValue("clientId", "…"));
		text = text.replace("{tenantId}", stringValue("tenantId", "{tenantId}"));
		String tokenUrl = stringValue("oauth.tokenUrl", null);
		if (tokenUrl == null)
		{
			for (final CredentialGuide.Property property : CredentialGuide.properties(this.type, AuthType.OAUTH2, this.flow))
				if ("oauth.tokenUrl".equals(property.getName()) && property.getDerivedValue() != null) tokenUrl = property.getDerivedValue();
			if (tokenUrl == null) tokenUrl = "https://…/oauth2/token";
			tokenUrl = tokenUrl.replace("{tenantId}", stringValue("tenantId", "{tenantId}")).replace("{baseUrl}", baseUrl.isEmpty() ? "https://…" : baseUrl);
		}
		return text.replace("{oauth.tokenUrl}", tokenUrl);
	}

	private String stringValue(final String pProperty, final String pFallback)
	{
		final Object value = this.values.get(pProperty);
		return present(value) ? String.valueOf(value).trim() : pFallback;
	}

	/** A property counts as set when it holds something other than blanks or the seed placeholder TO_BE_SET. */
	private static boolean present(final Object pValue)
	{
		if (pValue == null) return false;
		final String value = String.valueOf(pValue).trim();
		return value.length() > 0 && !"TO_BE_SET".equals(value);
	}

	private static boolean secret(final String pName)
	{
		final String name = pName.toLowerCase(Locale.ENGLISH);
		return name.contains("secret") || name.contains("password") || name.contains("privatekey") || name.contains("refreshtoken") || name.contains("token") && !name.contains("tokenurl");
	}

	private boolean secretStored(final Path pPath)
	{
		final String stored = value(pPath).trim();
		return stored.length() > 0 && !"TO_BE_SET".equals(stored);
	}

	private String value(final Path pPath)
	{
		if (this.record == null) return "";
		final Object value = this.record.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}

	private static String key(final Path pPath) { return pPath.getLastStep().format(); }

	private String text(final String pKey, final Object... pArgs) { return Messages.get(ConnectorForm.class, this.locale, pKey, pArgs); }

	/** The bundle text, or null when neither the locale nor the English bundle defines the key. */
	private String optional(final String pKey)
	{
		final String text = Messages.get(ConnectorForm.class, this.locale, pKey);
		return text == null || text.equals(pKey) ? null : text;
	}

	private static String esc(final String pText) { return MappingForm.escape(pText == null ? "" : pText); }

	/** Escaped text where `code` spans become inline code. */
	private static String rich(final String pText)
	{
		final String escaped = esc(pText);
		final StringBuilder out = new StringBuilder();
		boolean open = false;
		for (int i = 0; i < escaped.length(); i++)
		{
			final char c = escaped.charAt(i);
			if (c == '`')
			{
				out.append(open ? "</code>" : "<code>");
				open = !open;
			}
			else out.append(c);
		}
		if (open) out.append("</code>");
		return out.toString();
	}

	private static String json(final String pText)
	{
		if (pText == null) return "null";
		final StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			switch (c)
			{
				case '"': out.append("\\\"");
				break;
				case '\\': out.append("\\\\");
				break;
				case '\n': out.append("\\n");
				break;
				case '\r': out.append("\\r");
				break;
				case '\t': out.append("\\t");
				break;
				case '<': out.append("\\u003c");
				break;
				case '>': out.append("\\u003e");
				break;
				case '&': out.append("\\u0026");
				break;
				default: if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
				else out.append(c);
			}
		}
		return out.append('"').toString();
	}
}
