package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.form.widget.UISimpleCustomWidget;
import com.orchestranetworks.ui.form.widget.UIWidgetFactory;
import com.orchestranetworks.ui.form.widget.WidgetDisplayContext;
import com.orchestranetworks.ui.form.widget.WidgetFactoryContext;
import com.orchestranetworks.ui.form.widget.WidgetFactorySetupContext;
import com.orchestranetworks.ui.form.widget.WidgetWriter;

/**
 * Repository-backed connector logo upload. The bounded data URL is edited by EBX like any other
 * field, so form permissions, validation, Save and Revert remain authoritative.
 */
public final class UploadedLogoWidgetFactory implements UIWidgetFactory<UISimpleCustomWidget>
{
	static final int MAX_FILE_BYTES = 256 * 1024;
	private static final String[] ACCEPTED_PREFIXES = {
		"data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,"
	};

	@Override public void setup(final WidgetFactorySetupContext pContext) { }

	@Override
	public UISimpleCustomWidget newInstance(final WidgetFactoryContext pContext)
	{
		return new UISimpleCustomWidget(pContext)
		{
			@Override
			public void write(final WidgetWriter pWriter, final WidgetDisplayContext pContext)
			{
				final Object raw = pContext.getValueContext().getValue();
				final String value = raw == null ? "" : String.valueOf(raw);
				final boolean accepted = isAcceptedDataUrl(value);
				if (pContext.isDisplayedInTable())
				{
					if (accepted)
					{
						pWriter.add("<img alt=\"Custom connector logo\" src=\"").add(MappingForm.escape(value))
							.add("\" style=\"display:block;width:32px;height:32px;object-fit:contain;margin:auto\"/>");
					}
					else
					{
						pWriter.add("<span style=\"color:#7b8498\">&#8212;</span>");
					}
					return;
				}
				final boolean editable = pContext.getPermission().isReadWrite();
				final String token = "ftcnLogo" + Math.abs(pWriter.getWidgetName().hashCode());
				final String preview = accepted ? value : Logos.defaultUrl();

				pWriter.add("<div class=\"ftcn-logo-upload\" id=\"").add(token).add("\"><div class=\"ftcn-logo-drop\" tabindex=\"0\" role=\"button\" aria-label=\"Upload connector logo\">")
					.add("<img class=\"ftcn-logo-preview\" alt=\"Connector logo preview\" src=\"").add(MappingForm.escape(preview)).add("\"/>")
					.add("<div class=\"ftcn-logo-copy\"><strong>Drop a logo here or browse</strong><span>PNG, JPEG or WebP · maximum 256 KB</span><span class=\"ftcn-logo-status\">")
					.add(accepted ? "Custom logo selected" : "Packaged logo remains active").add("</span></div></div>");
				if (editable)
				{
					pWriter.add("<input class=\"ftcn-logo-file\" type=\"file\" accept=\"image/png,image/jpeg,image/webp\" hidden/>")
						.add("<div class=\"ftcn-logo-buttons\"><button type=\"button\" class=\"ftcn-logo-browse\">Browse files</button><button type=\"button\" class=\"ftcn-logo-clear\">Use packaged logo</button></div>");
				}
				pWriter.add("<div class=\"ftcn-logo-native\">");
				pWriter.addWidget(Path.SELF);
				pWriter.add("</div></div>");

				if (editable)
				{
					final String widgetName = js(pWriter.getWidgetName());
					pWriter.addJS_cr("(function(){var root=document.getElementById('" + js(token) + "');if(!root)return;var drop=root.querySelector('.ftcn-logo-drop'),file=root.querySelector('.ftcn-logo-file'),preview=root.querySelector('.ftcn-logo-preview'),status=root.querySelector('.ftcn-logo-status'),native=document.getElementsByName('" + widgetName + "')[0]||document.getElementById('" + widgetName + "');function setValue(v){if(!native)return;native.value=v;native.dispatchEvent(new Event('input',{bubbles:true}));native.dispatchEvent(new Event('change',{bubbles:true}));}function fail(m){status.textContent=m;status.classList.add('ftcn-logo-error');}function choose(f){if(!f)return;if(['image/png','image/jpeg','image/webp'].indexOf(f.type)<0){fail('Choose a PNG, JPEG or WebP image.');return;}if(f.size>" + MAX_FILE_BYTES + "){fail('Logo exceeds the 256 KB limit.');return;}var reader=new FileReader();reader.onload=function(){var v=String(reader.result||'');setValue(v);preview.src=v;status.textContent=f.name+' · '+Math.ceil(f.size/1024)+' KB';status.classList.remove('ftcn-logo-error');};reader.onerror=function(){fail('The logo could not be read.');};reader.readAsDataURL(f);}drop.addEventListener('click',function(){file.click();});drop.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();file.click();}});root.querySelector('.ftcn-logo-browse').addEventListener('click',function(){file.click();});root.querySelector('.ftcn-logo-clear').addEventListener('click',function(){setValue('');preview.src='" + js(Logos.defaultUrl()) + "';status.textContent='Packaged logo will be used after Save';status.classList.remove('ftcn-logo-error');});file.addEventListener('change',function(){choose(file.files&&file.files[0]);});['dragenter','dragover'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.classList.add('is-dragging');});});['dragleave','drop'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.classList.remove('is-dragging');});});drop.addEventListener('drop',function(e){choose(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files[0]);});})();");
				}
			}
		};
	}

	public static boolean isAcceptedDataUrl(final String pValue)
	{
		if (pValue == null || pValue.length() > 360000) { return false; }
		for (final String prefix : ACCEPTED_PREFIXES)
		{
			if (pValue.startsWith(prefix) && pValue.length() > prefix.length()) { return true; }
		}
		return false;
	}

	private static String js(final String pValue)
	{
		return pValue == null ? "" : pValue.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
	}
}
