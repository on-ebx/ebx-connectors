package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.SampleDataUrl;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.form.widget.UISimpleCustomWidget;
import com.orchestranetworks.ui.form.widget.UIWidgetFactory;
import com.orchestranetworks.ui.form.widget.WidgetDisplayContext;
import com.orchestranetworks.ui.form.widget.WidgetFactoryContext;
import com.orchestranetworks.ui.form.widget.WidgetFactorySetupContext;
import com.orchestranetworks.ui.form.widget.WidgetWriter;

/**
 * Repository-backed sample file upload, for the {@code sample-file} connector type: a CSV, TSV,
 * JSON or NDJSON sample stands in for a real connection until one exists. The bounded data URL is
 * edited by EBX like any other field, so form permissions, validation, Save and Revert remain
 * authoritative. Same interaction pattern as {@link UploadedLogoWidgetFactory}, but the MIME
 * prefix is derived from the file extension client-side rather than trusted from {@code file.type}
 * (browsers report csv/ndjson MIME types unreliably).
 * <p>
 * The stored value also carries the uploaded file's original name as a {@code name} parameter (see
 * {@link SampleDataUrl}), so the catalog asset scanned from it is named after that file rather than
 * generically ({@code sample}) — every sample-file source used to give the same table name.
 * sample-file-name lot (2026-09-23).
 */
public final class UploadedSampleFileWidgetFactory implements UIWidgetFactory<UISimpleCustomWidget>
{
	static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
	private static final String[] ACCEPTED_MIMES = {
		"text/csv", "text/tab-separated-values", "application/json", "application/x-ndjson"
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
				final String fileName = accepted ? fileNameOf(value) : null;
				if (pContext.isDisplayedInTable())
				{
					pWriter.add(accepted ? "<span>" + MappingForm.escape(fileName == null ? kindLabel(value) : kindLabel(value) + " · " + fileName) + "</span>"
						: "<span style=\"color:#7b8498\">&#8212;</span>");
					return;
				}
				final boolean editable = pContext.getPermission().isReadWrite();
				final String token = "ftcnSample" + Math.abs(pWriter.getWidgetName().hashCode());

				pWriter.add("<div class=\"ftcn-sample-upload\" id=\"").add(token).add("\"><div class=\"ftcn-sample-drop\" tabindex=\"0\" role=\"button\" aria-label=\"Upload sample file\">")
					.add("<span class=\"ftcn-sample-mark\">").add(accepted ? MappingForm.escape(kindLabel(value)) : "&#8942;").add("</span>")
					.add("<div class=\"ftcn-sample-copy\"><strong>Drop a sample file here or browse</strong><span>CSV, TSV, JSON or NDJSON &middot; maximum 2 MB</span><span class=\"ftcn-sample-status\">")
					.add(accepted ? (fileName == null ? "Sample file uploaded" : MappingForm.escape(fileName)) : "No sample file uploaded yet").add("</span></div></div>");
				if (editable)
				{
					pWriter.add("<input class=\"ftcn-sample-file\" type=\"file\" accept=\".csv,.tsv,.json,.ndjson,.jsonl,.txt\" hidden/>")
						.add("<div class=\"ftcn-sample-buttons\"><button type=\"button\" class=\"ftcn-sample-browse\">Browse files</button><button type=\"button\" class=\"ftcn-sample-clear\">Remove sample</button></div>");
				}
				pWriter.add("<div class=\"ftcn-sample-native\">");
				pWriter.addWidget(Path.SELF);
				pWriter.add("</div></div>");

				if (editable)
				{
					final String widgetName = js(pWriter.getWidgetName());
					pWriter.addJS_cr("(function(){var root=document.getElementById('" + js(token) + "');if(!root)return;var drop=root.querySelector('.ftcn-sample-drop'),file=root.querySelector('.ftcn-sample-file'),mark=root.querySelector('.ftcn-sample-mark'),status=root.querySelector('.ftcn-sample-status'),native=document.getElementsByName('" + widgetName + "')[0]||document.getElementById('" + widgetName + "');"
						+ "var mimeByExt={csv:'text/csv',txt:'text/csv',tsv:'text/tab-separated-values',json:'application/json',ndjson:'application/x-ndjson',jsonl:'application/x-ndjson'};"
						+ "function extOf(n){var i=n.lastIndexOf('.');return i<0?'':n.slice(i+1).toLowerCase();}"
						+ "function labelOf(m){return m==='text/csv'?'CSV':m==='text/tab-separated-values'?'TSV':m==='application/x-ndjson'?'NDJSON':'JSON';}"
						+ "function setValue(v){if(!native)return;native.value=v;native.dispatchEvent(new Event('input',{bubbles:true}));native.dispatchEvent(new Event('change',{bubbles:true}));}"
						+ "function fail(m){status.textContent=m;status.classList.add('ftcn-sample-error');}"
						+ "function choose(f){if(!f)return;var ext=extOf(f.name),mime=mimeByExt[ext];if(!mime){fail('Choose a CSV, TSV, JSON or NDJSON file.');return;}if(f.size>" + MAX_FILE_BYTES + "){fail('Sample file exceeds the 2 MB limit.');return;}"
						+ "var reader=new FileReader();reader.onload=function(){var raw=String(reader.result||''),comma=raw.indexOf(',');if(comma<0){fail('The sample file could not be read.');return;}"
						// sample-file-name lot (2026-09-23): "name" carries the original file name (RFC 2397 parameter), so
						// the catalog asset scanned from this sample is named after the file, not generically "sample".
						+ "var v='data:'+mime+';name='+encodeURIComponent(f.name)+';base64,'+raw.slice(comma+1);setValue(v);mark.textContent=labelOf(mime);status.textContent=f.name+' \\u00b7 '+Math.ceil(f.size/1024)+' KB';status.classList.remove('ftcn-sample-error');};"
						+ "reader.onerror=function(){fail('The sample file could not be read.');};reader.readAsDataURL(f);}"
						+ "drop.addEventListener('click',function(){file.click();});drop.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();file.click();}});"
						+ "root.querySelector('.ftcn-sample-browse').addEventListener('click',function(){file.click();});"
						+ "root.querySelector('.ftcn-sample-clear').addEventListener('click',function(){setValue('');mark.textContent='\\u22ee';status.textContent='No sample file uploaded yet';status.classList.remove('ftcn-sample-error');});"
						+ "file.addEventListener('change',function(){choose(file.files&&file.files[0]);});"
						+ "['dragenter','dragover'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.classList.add('is-dragging');});});"
						+ "['dragleave','drop'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.classList.remove('is-dragging');});});"
						+ "drop.addEventListener('drop',function(e){choose(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files[0]);});})();");
				}
			}
		};
	}

	public static boolean isAcceptedDataUrl(final String pValue)
	{
		if (pValue == null || pValue.length() > 3000000) { return false; }
		final SampleDataUrl parsed = SampleDataUrl.parse(pValue);
		if (parsed == null || parsed.payload().isEmpty()) { return false; }
		for (final String mime : ACCEPTED_MIMES)
		{
			if (mime.equals(parsed.mime())) { return true; }
		}
		return false;
	}

	/**
	 * {@code CSV}, {@code TSV}, {@code NDJSON} or {@code JSON}, for the accepted value's MIME — tolerant
	 * of any parameter the data URL carries (e.g. {@code name}), not just a bare {@code ;base64,} prefix.
	 */
	public static String kindLabel(final String pValue)
	{
		final SampleDataUrl parsed = pValue == null ? null : SampleDataUrl.parse(pValue);
		if (parsed == null) { return ""; }
		if ("text/csv".equals(parsed.mime())) { return "CSV"; }
		if ("text/tab-separated-values".equals(parsed.mime())) { return "TSV"; }
		if ("application/x-ndjson".equals(parsed.mime())) { return "NDJSON"; }
		if ("application/json".equals(parsed.mime())) { return "JSON"; }
		return "";
	}

	/** The uploaded file's original name, when the stored value carries one; else null. sample-file-name lot (2026-09-23). */
	public static String fileNameOf(final String pValue)
	{
		final SampleDataUrl parsed = pValue == null ? null : SampleDataUrl.parse(pValue);
		return parsed == null ? null : parsed.name();
	}

	private static String js(final String pValue)
	{
		return pValue == null ? "" : pValue.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
	}
}
