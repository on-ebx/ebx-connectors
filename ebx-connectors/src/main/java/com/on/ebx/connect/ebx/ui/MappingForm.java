package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.UIButtonSpecJSAction;
import com.orchestranetworks.ui.UIHttpManagerComponent;
import com.orchestranetworks.ui.form.UIForm;
import com.orchestranetworks.ui.form.UIFormBody;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.form.UIFormPane;
import com.orchestranetworks.ui.form.UIFormPaneWithTabs;
import com.orchestranetworks.ui.form.UIFormPaneWriter;

/**
 * Record form of the Mapping table (declared by Connectors.xsd): the essentials on the home tab,
 * a prominent button opening the graphical designer, the rarely used sub-object and import
 * settings on their own tabs, and the associations (inputs, rules, sub-object mappings) as
 * tables. Fields are listed from the model, so a field added to the XSD shows up unchanged.
 */
public final class MappingForm extends UIForm
{
	/** Context path of the EBX manager web application (ebx.war). */
	static final String EBX_BASE_URI = "/ebx/";
	/** Modern EBX UI context; required when a component must return to an /ebx-ui route. */
	public static final String EBX_UI_BASE_URI = "/ebx-ui/";

	private static final Path[] HOME_WHAT = { Path.parse("./label"), Path.parse("./connector"), Path.parse("./operation"), Path.parse("./direction"),
		Path.parse("./active"), Path.parse("./description") };
	private static final Path[] HOME_WHERE = { Path.parse("./dataspace"), Path.parse("./dataset"), Path.parse("./tablePath"), Path.parse("./responseRoot") };
	private static final Path[] SUB_OBJECT = { Path.parse("./parent"), Path.parse("./collectionPath"), Path.parse("./parentLinkField"),
		Path.parse("./childKeyFields"), Path.parse("./childFilter"), Path.parse("./deleteMissing") };
	private static final Path[] FEED = { Path.parse("./itemsPath"), Path.parse("./keyPredicate"), Path.parse("./createIfMissing") };
	private static final Path[] SYNCHRONIZATION = { Path.parse("./loadMode"), Path.parse("./checkpointStrategy"), Path.parse("./checkpointInput"),
		Path.parse("./checkpointValuePath"), Path.parse("./checkpointInitialValue"), Path.parse("./checkpointBoundary") };
	private static final Path INPUTS = Path.parse("./inputs");
	private static final Path RULES = Path.parse("./rules");
	private static final Path CHILDREN = Path.parse("./children");

	@Override
	public void defineBody(final UIFormBody pBody, final UIFormContext pContext)
	{
		final Locale locale = pContext.getLocale();
		final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
		final UIFormPaneWithTabs tabs = new UIFormPaneWithTabs();
		tabs.addHomeTab(new HomePane());
		tabs.addTab(MappingForm.label(locale, "form.tab.inputs"), new FieldsPane(new Path[0], INPUTS, "form.inputs.hint"));
		tabs.addTab(MappingForm.label(locale, "form.tab.rules"), new FieldsPane(new Path[0], RULES, "form.rules.hint"));
		tabs.addTab(MappingForm.label(locale, "form.tab.subObjects"), new FieldsPane(SUB_OBJECT, CHILDREN, "form.subObjects.hint"));
		if (root.getNode(FEED[0]) != null)
		{
			tabs.addTab(MappingForm.label(locale, "form.tab.feed"), new FieldsPane(FEED, null, "form.feed.hint"));
		}
		if (root.getNode(SYNCHRONIZATION[0]) != null)
		{
			tabs.addTab(MappingForm.label(locale, "form.tab.synchronization"),
				new FieldsPane(SYNCHRONIZATION, null, null));
		}
		pBody.setContent(tabs);
	}

	static UserMessage label(final Locale pLocale, final String pKey)
	{
		return UserMessage.createInfo(Messages.get(MappingForm.class, pLocale, pKey));
	}

	static String text(final Locale pLocale, final String pKey)
	{
		return Messages.get(MappingForm.class, pLocale, pKey);
	}

	static String escape(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** Standard EBX rows for the paths that exist in the model. */
	static void rows(final UIFormPaneWriter pWriter, final UIFormContext pContext, final Path[] pPaths)
	{
		final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
		pWriter.startTableFormRow();
		for (final Path path : pPaths)
		{
			if (root.getNode(path) != null)
			{
				pWriter.addFormRow(path);
			}
		}
		pWriter.endTableFormRow();
	}

	static void section(final com.orchestranetworks.ui.UIBodyWriter pWriter, final String pTitle, final String pHint)
	{
		pWriter.add("<div class=\"ftmfSection\"><div class=\"ftmfTitle\">").add(MappingForm.escape(pTitle)).add("</div>");
		if (pHint != null && !pHint.isEmpty())
		{
			pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(pHint)).add("</div>");
		}
		pWriter.add("</div>");
	}

	static void styles(final com.orchestranetworks.ui.UIBodyWriter pWriter)
	{
		pWriter.add("<style>.ftmfSection{margin:14px 0 4px}.ftmfTitle{font-weight:600;font-size:13px;color:#202124;border-bottom:1px solid #dadce0;padding-bottom:4px}"
				+ ".ftmfHint{color:#5f6368;font-size:12px;margin:4px 0 6px;max-width:900px}"
				+ ".ftmfCta{display:flex;align-items:center;gap:14px;margin:6px 0 12px;padding:10px 14px;border:1px solid var(--ftc-accent-line,#c6dafc);border-radius:8px;background:var(--ftc-accent-soft,#e8f0fe)}"
				+ ".ftmfCta .ftmfCtaText{color:var(--ftc-accent-ink,#174ea6)}.ftmfCta .ebx_Button,.ftmfCta button{font-weight:600}"
				+ ".ftmfMuted{color:#5f6368;font-style:italic}</style>");
	}

	/**
	 * Close returns to the connector when this flow was opened from a flow card of the connector form
	 * (which stores {id, url} under {@link ConnectorForm#FLOW_RETURN_KEY}); otherwise EBX's own Close
	 * is left alone. Same binding as the connector form's return to the catalog.
	 */
	static void flowReturn(final UIFormPaneWriter pWriter, final Adaptation pRecord, final String pUserId)
	{
		if (pRecord == null) { return; }
		final String id = ConnectorForm.resolveMappingId(pRecord);
		if (id.isEmpty()) { return; }
		pWriter.addJS_cr(MappingForm.closeToConnectorScript(id, pUserId));
	}

	/**
	 * Binds the form's native Close to the connector page stored under {@link ConnectorForm#FLOW_RETURN_KEY}
	 * when that state belongs to the mapping {@code pId}, or is a pending state left by "Create mapping"
	 * (no id yet: the creation form, then the same form once saved), at most 30 minutes old.
	 */
	private static String closeToConnectorScript(final String pId, final String pUserId)
	{
		return StorageScope.purgeJs(pUserId) + "(function(){if(document.documentElement.dataset.ftmfReturnBound)return;"
			+ "var key=" + StorageScope.jsKey(pUserId, ConnectorForm.FLOW_RETURN_KEY) + ",topWindow=window.top,state;try{state=JSON.parse(sessionStorage.getItem(key)||'null');}catch(e){return;}"
			+ "if(!state||!state.url||(state.id!==" + FacetedPage.jsLiteral(pId) + "&&!(state.pending&&Date.now()-state.at<1800000)))return;"
			+ "var target;try{target=new URL(state.url,topWindow.location.origin);}catch(e){sessionStorage.removeItem(key);return;}"
			+ "if(target.origin!==topWindow.location.origin||target.pathname.indexOf('/ebx-ui/')!==0)return;"
			+ "document.documentElement.dataset.ftmfReturnBound='true';"
			+ "function bind(){var bar=document.querySelector('.ebx_ActionsBack');if(!bar)return false;"
			+ "var buttons=bar.querySelectorAll('button[type=button]');if(!buttons.length)return false;"
			+ "var close=buttons[buttons.length-1];close.addEventListener('click',function(event){"
			+ "event.preventDefault();event.stopImmediatePropagation();sessionStorage.removeItem(key);topWindow.location.href=target.href;},true);return true;}"
			+ "if(!bind()){var observer=new MutationObserver(function(){if(bind())observer.disconnect();});"
			+ "observer.observe(document.documentElement,{childList:true,subtree:true});}})();";
	}

	/** sessionStorage key name (per user, see {@link StorageScope}) set by the connector form's "Create mapping": {id, url, at} of that connector. */
	static final String CREATE_CONNECTOR_KEY = "ftcnCreateConnector";

	/**
	 * Presets the connector of a new mapping created after "Create mapping" on a connector form: the
	 * token is consumed once, ignored after 30 minutes, and never overrides a connector already chosen.
	 * ebx_form_setValue takes a foreign key as {key, label}; EBX reloads the label itself (seen live
	 * 2026-09-15, which also refreshes the operation list bound to ../connector). The connector page is
	 * kept as a pending return state, so Close comes back to it (see {@link #closeToConnectorScript}).
	 */
	static String createPrefill(final String pPrefixedPath, final String pUserId)
	{
		final String path = FacetedPage.jsLiteral(pPrefixedPath);
		return StorageScope.purgeJs(pUserId) + "(function(){var k=" + StorageScope.jsKey(pUserId, CREATE_CONNECTOR_KEY) + ",s=null;try{s=JSON.parse(sessionStorage.getItem(k)||'null');sessionStorage.removeItem(k);}catch(e){return;}"
			+ "if(!s||!s.id||(s.at&&Date.now()-s.at>1800000))return;"
			+ "if(s.url){try{sessionStorage.setItem(" + StorageScope.jsKey(pUserId, ConnectorForm.FLOW_RETURN_KEY) + ",JSON.stringify({id:'',url:s.url,pending:true,at:Date.now()}));}catch(e){}}"
			+ "try{if(ebx_form_getValue(" + path + "))return;ebx_form_setValue(" + path + ",{key:s.id,label:s.label||s.id});}catch(e){}})();";
	}

	/**
	 * Same return for a user service's own Close button (a {@code newCloseButton()} given the id
	 * {@code pButtonId}): when sessionStorage holds {id, url} under {@code pKey} (and the id matches
	 * {@code pId}, unless null), Close goes to that page instead of EBX's default target -- the target
	 * table for the run service, the flow record for the designer (both seen live 2026-09-15).
	 */
	static String closeReturnScript(final String pButtonId, final String pKey, final String pId, final String pUserId)
	{
		final String idCheck = pId == null ? "" : "||s.id!==" + FacetedPage.jsLiteral(pId);
		final String key = StorageScope.jsKey(pUserId, pKey);
		return StorageScope.purgeJs(pUserId) + "(function(){var b=document.getElementById(" + FacetedPage.jsLiteral(pButtonId) + ");if(!b||b.dataset.ftReturnBound)return;"
			+ "var t=window.top,s=null;try{s=JSON.parse(sessionStorage.getItem(" + key + ")||'null');}catch(e){return;}"
			+ "if(!s||!s.url" + idCheck + ")return;var u;try{u=new URL(s.url,t.location.origin);}catch(e){return;}"
			+ "if(u.origin!==t.location.origin||u.pathname.indexOf('/ebx-ui/')!==0)return;b.dataset.ftReturnBound='true';"
			+ "b.addEventListener('click',function(e){e.preventDefault();e.stopImmediatePropagation();"
			+ "sessionStorage.removeItem(" + key + ");t.location.href=u.href;},true);})();";
	}

	/** Home tab: what the mapping does, where it applies, and the designer button. */
	private static final class HomePane implements UIFormPane
	{
		@Override
		public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			MappingForm.styles(pWriter);
			MappingForm.flowReturn(pWriter, pContext.getCurrentRecord(), StorageScope.userId(pContext.getSession()));
			// call to action: the graphical designer, on an existing record only
			pWriter.add("<div class=\"ftmfCta\">");
			if (pContext.isCreatingRecord() || pContext.getCurrentRecord() == null)
			{
				pWriter.add("<span class=\"ftmfMuted\">").add(MappingForm.escape(MappingForm.text(locale, "form.designer.saveFirst"))).add("</span>");
				if (pContext.isCreatingRecord())
				{
					final String userId = StorageScope.userId(pContext.getSession());
					pWriter.addJS_cr(MappingForm.createPrefill(pWriter.getPrefixedPath(Path.parse("./connector")).format(), userId));
					pWriter.addJS_cr(MappingForm.closeToConnectorScript("", userId));
				}
			}
			else
			{
				final Adaptation record = pContext.getCurrentRecord();
				final UIHttpManagerComponent component = UIHttpManagerComponent.createWithBaseURI(MappingForm.EBX_BASE_URI);
				component.selectInstanceOrOccurrence(record);
				component.setService(ServiceKey.forName(MappingDesignerServiceDeclaration.SERVICE_NAME));
				final UIButtonSpecJSAction open = pWriter.buildButtonPreview(component.getURIWithParameters(), MappingForm.label(locale, "form.designer.open"));
				pWriter.addButtonJavaScript(open);
				pWriter.add("<span class=\"ftmfCtaText\">").add(MappingForm.escape(MappingForm.text(locale, "form.designer.hint"))).add("</span>");
			}
			pWriter.add("</div>");
			MappingForm.section(pWriter, MappingForm.text(locale, "form.section.what"), MappingForm.text(locale, "form.section.what.hint"));
			MappingForm.rows(pWriter, pContext, MappingForm.HOME_WHAT);
			MappingForm.section(pWriter, MappingForm.text(locale, "form.section.where"), MappingForm.text(locale, "form.section.where.hint"));
			MappingForm.rows(pWriter, pContext, MappingForm.HOME_WHERE);
		}
	}

	/** A tab of fields, optionally followed by an association shown as a table. */
	private static final class FieldsPane implements UIFormPane
	{
		private final Path[] fields;
		private final Path association;
		private final String hintKey;

		FieldsPane(final Path[] pFields, final Path pAssociation, final String pHintKey)
		{
			this.fields = pFields;
			this.association = pAssociation;
			this.hintKey = pHintKey;
		}

		@Override
		public void writePane(final UIFormPaneWriter pWriter, final UIFormContext pContext)
		{
			ThemeTokens.write(pWriter, pContext);
			final Locale locale = pContext.getLocale();
			MappingForm.styles(pWriter);
			MappingForm.flowReturn(pWriter, pContext.getCurrentRecord(), StorageScope.userId(pContext.getSession()));
			final String hint = this.hintKey == null
				? ("fr".equals(locale.getLanguage()) ? "Configure la stratégie incrémentale. Les points de reprise d'exécution sont stockés séparément."
					: "Configure the incremental strategy. Runtime checkpoints are stored separately.")
				: MappingForm.text(locale, this.hintKey);
			pWriter.add("<div class=\"ftmfHint\">").add(MappingForm.escape(hint)).add("</div>");
			if (this.fields.length > 0)
			{
				MappingForm.rows(pWriter, pContext, this.fields);
			}
			if (this.association != null)
			{
				final SchemaNode root = pContext.getCurrentTable().getTableOccurrenceRootNode();
				final SchemaNode node = root.getNode(this.association);
				if (node == null)
				{
					return;
				}
				if (pContext.isCreatingRecord())
				{
					pWriter.add("<div class=\"ftmfMuted\">").add(MappingForm.escape(MappingForm.text(locale, "form.association.saveFirst"))).add("</div>");
					return;
				}
				pWriter.addWidget(this.association);
			}
		}
	}
}
