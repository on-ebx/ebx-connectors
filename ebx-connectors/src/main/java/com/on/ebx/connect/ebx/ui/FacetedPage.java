package com.on.ebx.connect.ebx.ui;

import java.util.Locale;
import java.util.Map;

import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.userservice.UserServicePaneWriter;

/**
 * The faceted layout of the connector explorer, the same one as in the data catalog module (kept as a copy: the two
 * modules ship separately): check-box facets in a left rail, advanced
 * filters folded under the search line, a line of removable active filters, and card groups that show two full rows
 * before a "show more" button. The page writes its own search row and cards; this class writes the frame around them
 * and the client side that filters them.
 * <p>
 * Page order: search row ({@link #railToggle}, search box, {@link #sortControl}, {@link #advancedToggle}) →
 * {@link #openAdvanced} → {@link #pillRow}s → {@link #openRail} → {@link #facet}s → {@link #openMain} → groups ({@link #openGroup}, heading, {@link #closeGroupHeading}, cards,
 * {@link #closeGroup}) → {@link #closeMain} → {@link #script}.
 * <p>
 * Cards carry one {@code data-<filter>} attribute per filter; the attributes named in {@code pMultiValued} hold
 * space-separated values. Filters combine as OR inside a filter and AND between filters; each facet counts the cards that
 * pass every other filter, so a value that would give nothing is dimmed.
 */
final class FacetedPage
{
	/** Full rows of cards shown per group before its "show more" button, and rows in list mode. */
	static final int GROUP_CARD_ROWS = 2;
	static final int GROUP_LIST_ROWS = 12;

	private final Locale locale;
	private final String userId;

	FacetedPage(final Locale pLocale, final String pUserId)
	{
		this.locale = pLocale;
		this.userId = pUserId;
	}

	String message(final String pKey)
	{
		return Messages.get(FacetedPage.class, this.locale, pKey);
	}

	static String esc(final String pText)
	{
		return MappingForm.escape(pText);
	}

	/** The content of {@link #jsLiteral} without its quotes, to splice inside a literal that is already open. */
	static String unquote(final String pText)
	{
		final String literal = FacetedPage.jsLiteral(pText);
		return literal.substring(1, literal.length() - 1);
	}

	/**
	 * A value as a single-quoted JavaScript string literal, quotes included, safe both in a script element and in an HTML attribute
	 * (audit C2-09): backslash, both quotes, {@code <}, {@code >}, {@code &}, every control character, U+2028 and U+2029 are written as
	 * {@code \\uXXXX}, so nothing in the value can end the string, the script element or the attribute. Never splice a value into a
	 * script by escaping only a quote and a backslash: {@code </script>}, a line end or U+2028 would get through.
	 */
	static String jsLiteral(final String pText)
	{
		final String text = pText == null ? "" : pText;
		final StringBuilder out = new StringBuilder(text.length() + 8).append('\'');
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (c < 0x20 || c == '\\' || c == '\'' || c == '"' || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029' || c == 0x7f)
			{
				out.append("\\u").append(String.format("%04x", Integer.valueOf(c)));
			}
			else
			{
				out.append(c);
			}
		}
		return out.append('\'').toString();
	}

	/**
	 * Client-side search: every word typed must appear, in any order, ignoring case, accents, spaces, punctuation and a
	 * final plural "s". Declares ftTerms(input) and ftMatch(card, terms) in the enclosing script.
	 */
	static final String SEARCH_JS = "function ftFold(s){return (s||'').normalize('NFD').replace(/\\p{M}/gu,'').toLowerCase();}"
		+ "function ftTerms(s){return ftFold(s).split(/[^\\p{L}\\p{N}]+/u).filter(function(w){return w;}).map(function(w){return w.length>3&&w.charAt(w.length-1)==='s'?w.slice(0,-1):w;});}"
		+ "function ftMatch(c,terms){var t=c.ftSearch;if(t===undefined)t=c.ftSearch=ftFold(c.dataset.search).replace(/[^\\p{L}\\p{N}]+/gu,'');for(var i=0;i<terms.length;i++){if(t.indexOf(terms[i])<0)return false;}return true;}";

	static void stylesheets(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "connect/connector-catalog.css")).add("?v=3\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "connect/facets.css")).add("?v=2\"/>");
	}

	/** The button that hides or shows the facet rail (remembered per browser), with a badge counting its active filters. */
	void railToggle(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<button type=\"button\" id=\"ftcxRailToggle\" class=\"ftcx-rail-toggle\" aria-controls=\"ftcxRail\" aria-expanded=\"true\" data-show=\"").add(FacetedPage.esc(this.message("explorer.showFilters")))
			.add("\" data-hide=\"").add(FacetedPage.esc(this.message("explorer.hideFilters"))).add("\" title=\"").add(FacetedPage.esc(this.message("explorer.hideFilters")))
			.add("\"><i aria-hidden=\"true\"></i><span>").add(FacetedPage.esc(this.message("explorer.filters"))).add("</span><b hidden></b></button>");
	}

	/**
	 * A compact sort: the criterion (short labels, value → label) and a button that flips the direction. Names sort A→Z
	 * and figures highest first until the button is used; choosing another criterion restores its natural direction.
	 */
	void sortControl(final UserServicePaneWriter pWriter, final String pId, final Map<String, String> pOptions)
	{
		pWriter.add("<span class=\"ftcx-sort\"><select id=\"").add(pId).add("\" aria-label=\"").add(FacetedPage.esc(this.message("explorer.sort.by"))).add("\" title=\"").add(FacetedPage.esc(this.message("explorer.sort.by"))).add("\">");
		for (final Map.Entry<String, String> e : pOptions.entrySet()) pWriter.add("<option value=\"").add(FacetedPage.esc(e.getKey())).add("\">").add(FacetedPage.esc(e.getValue())).add("</option>");
		pWriter.add("</select><button type=\"button\" id=\"").add(pId).add("Dir\" class=\"ftcx-dir\" data-asc=\"").add(FacetedPage.esc(this.message("explorer.sort.asc"))).add("\" data-desc=\"")
			.add(FacetedPage.esc(this.message("explorer.sort.desc"))).add("\"></button></span>");
	}

	/** The button that unfolds the advanced filters, with a badge counting the active ones. */
	void advancedToggle(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<button type=\"button\" id=\"ftcxMoreToggle\" class=\"ftcx-more-toggle\" aria-expanded=\"false\" aria-controls=\"ftcxMore\" data-more=\"").add(FacetedPage.esc(this.message("explorer.moreFilters")))
			.add("\" data-less=\"").add(FacetedPage.esc(this.message("explorer.lessFilters"))).add("\"><span>").add(FacetedPage.esc(this.message("explorer.moreFilters"))).add("</span><b id=\"ftcxActive\" hidden></b></button>");
	}

	/** Opens the folded advanced panel, right after the search row (still inside {@code .ftcc-tools}). */
	void openAdvanced(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<div class=\"ftcx-more\" id=\"ftcxMore\" hidden>");
	}

	/** An advanced filter: a row of toggle pills. */
	void pillRow(final UserServicePaneWriter pWriter, final String pAttr, final String pLabel, final Map<String, String> pValues)
	{
		if (pValues.isEmpty()) return;
		pWriter.add("<div class=\"ftcx-pills\" data-filter=\"").add(pAttr).add("\" data-label=\"").add(FacetedPage.esc(pLabel)).add("\"><span class=\"ftcc-filter-label\">").add(FacetedPage.esc(pLabel)).add("</span>")
			.add("<button type=\"button\" class=\"active\" data-value=\"\">").add(FacetedPage.esc(this.message("catalog.all"))).add("</button>");
		for (final Map.Entry<String, String> e : pValues.entrySet())
		{
			pWriter.add("<button type=\"button\" data-value=\"").add(FacetedPage.esc(e.getKey())).add("\" data-label=\"").add(FacetedPage.esc(e.getValue())).add("\" aria-pressed=\"false\">")
				.add(FacetedPage.esc(e.getValue())).add(" <i></i></button>");
		}
		pWriter.add("</div>");
	}

	/**
	 * Closes the advanced panel and the tools (active filters, then the page's links and the counter on the right), then
	 * opens the facet rail. {@code pLinksHtml} is ready-made HTML, possibly empty.
	 */
	void openRail(final UserServicePaneWriter pWriter, final String pLinksHtml)
	{
		pWriter.add("</div><div class=\"ftcx-activebar\"><span id=\"ftcxChips\" class=\"ftcx-chips\"></span><button type=\"button\" id=\"ftcxClear\" class=\"ftcx-clear\" hidden>")
			.add(FacetedPage.esc(this.message("explorer.clearAll"))).add("</button><span class=\"ftcx-links\">").add(pLinksHtml == null ? "" : pLinksHtml).add("</span><span id=\"ftcxCount\" class=\"ftcc-count\"></span></div></div>")
			.add("<div class=\"ftcx-body\"><aside class=\"ftcx-rail\" id=\"ftcxRail\" aria-label=\"").add(FacetedPage.esc(this.message("explorer.filters"))).add("\">");
	}

	/** A facet of the rail: check boxes, the most frequent values first, the rest behind a "show N more" link. */
	void facet(final UserServicePaneWriter pWriter, final String pAttr, final String pLabel, final Map<String, String> pValues, final int pTop)
	{
		if (pValues.isEmpty()) return;
		pWriter.add("<section class=\"ftcx-facet\" data-filter=\"").add(pAttr).add("\" data-label=\"").add(FacetedPage.esc(pLabel)).add("\" data-top=\"").add(String.valueOf(pTop))
			.add("\"><button type=\"button\" class=\"ftcx-facet-h\" aria-expanded=\"true\"><span>").add(FacetedPage.esc(pLabel)).add("</span><b class=\"ftcx-facet-n\"></b><i aria-hidden=\"true\"></i></button>");
		if (pValues.size() > 8) pWriter.add("<input type=\"search\" class=\"ftcx-find\" placeholder=\"").add(FacetedPage.esc(this.message("explorer.facet.find"))).add("\"/>");
		pWriter.add("<div class=\"ftcx-opts\">");
		int order = 0;
		for (final Map.Entry<String, String> e : pValues.entrySet())
		{
			pWriter.add("<label class=\"ftcx-opt\" data-value=\"").add(FacetedPage.esc(e.getKey())).add("\" data-label=\"").add(FacetedPage.esc(e.getValue())).add("\" data-order=\"").add(String.valueOf(order++))
				.add("\"><input type=\"checkbox\"/><span>").add(FacetedPage.esc(e.getValue())).add("</span><i></i></label>");
		}
		pWriter.add("</div><button type=\"button\" class=\"ftcx-moreopts\" data-tpl=\"").add(FacetedPage.esc(this.message("explorer.facet.more"))).add("\" data-less=\"")
			.add(FacetedPage.esc(this.message("explorer.facet.less"))).add("\" hidden></button></section>");
	}

	/** Closes the rail and opens the result column (empty-state first, then the groups). */
	void openMain(final UserServicePaneWriter pWriter, final String pEmptyMessage)
	{
		// the probe is an always-displayed empty grid: the script reads the column count on it, whatever group is hidden
		pWriter.add("</aside><div class=\"ftcx-main\"><div class=\"ftcc-grid ftcx-probe\" aria-hidden=\"true\"><i></i></div><div id=\"ftccEmpty\" class=\"ftcc-empty\">").add(FacetedPage.esc(pEmptyMessage)).add("</div>");
	}

	/**
	 * Opens a result group and its heading title; the caller writes the title. Groups keep {@code pOrder} (the display
	 * order of the connector categories), pinned groups first.
	 */
	void openGroup(final UserServicePaneWriter pWriter, final String pGroup, final int pOrder, final boolean pPinned)
	{
		pWriter.add("<section class=\"ftcc-group\" data-group=\"").add(FacetedPage.esc(pGroup)).add("\" data-order=\"").add(String.valueOf(pOrder)).add("\" data-pin=\"").add(pPinned ? "1" : "0")
			.add("\"><div class=\"ftcc-heading ftcx-heading\"><div><h2>");
	}

	void closeGroupHeading(final UserServicePaneWriter pWriter, final String pWord, final String pDescription)
	{
		pWriter.add(" <span class=\"ftcx-groupcount\"><span data-groupcount=\"1\"></span> ").add(FacetedPage.esc(pWord)).add("</span></h2>");
		if (pDescription != null && !pDescription.isEmpty()) pWriter.add("<p>").add(FacetedPage.esc(pDescription)).add("</p>");
		pWriter.add("</div></div><div class=\"ftcc-grid\">");
	}

	void closeGroup(final UserServicePaneWriter pWriter)
	{
		pWriter.add("</div><button type=\"button\" class=\"ftcx-showmore\" data-tpl=\"").add(FacetedPage.esc(this.message("explorer.showMore"))).add("\" hidden></button></section>");
	}

	/** Closes the result column and the body. */
	void closeMain(final UserServicePaneWriter pWriter)
	{
		pWriter.add("</div></div>");
	}

	/**
	 * The client side. Runs on the element matching {@code pRoot} and exposes {@code root.ftFacets} ({@code select(filter,
	 * value)}, {@code changed()}, {@code apply()}) for the page's own script. Sort values other than {@code name} are
	 * numeric card attributes, highest first.
	 */
	String script(final String pRoot, final String pSearchId, final String pSortId, final String... pMultiValued)
	{
		final StringBuilder multi = new StringBuilder();
		for (final String m : pMultiValued) multi.append(multi.length() == 0 ? "" : ",").append(m).append(":1");
		return "(function(){var root=document.querySelector('" + pRoot + "');if(!root)return;var q=document.getElementById('" + pSearchId + "'),sort=document.getElementById('" + pSortId + "'),main=root.querySelector('.ftcx-main');"
			+ "var cards=Array.prototype.slice.call(root.querySelectorAll('.ftcc-card')),groups=Array.prototype.slice.call(root.querySelectorAll('.ftcc-group'));"
			+ "var filters=Array.prototype.slice.call(root.querySelectorAll('[data-filter]')),state={},limits={},expanded={},MULTI={" + multi + "};"
			+ "filters.forEach(function(f){state[f.dataset.filter]=[];});"
			+ FacetedPage.SEARCH_JS
			+ "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
			+ "function vals(c,k){var v=c.dataset[k]||'';return MULTI[k]?v.split(' '):[v];}"
			+ "function has(a,v){return a.indexOf(v)>=0;}"
			+ "function passes(c,terms,skip){if(!ftMatch(c,terms))return false;for(var k in state){if(k===skip||!state[k].length)continue;var cv=vals(c,k),ok=false;for(var i=0;i<state[k].length;i++){if(has(cv,state[k][i])){ok=true;break;}}if(!ok)return false;}return true;}"
			+ "function opts(f){return Array.prototype.slice.call(f.querySelectorAll('[data-value]')).filter(function(o){return o.dataset.value!=='';});}"
			+ "var dirBtn=document.getElementById('" + pSortId + "Dir'),dir=0;"
			+ "function sdir(){return dir||(sort.value==='name'?1:-1);}"
			+ "function showDir(){if(!dirBtn)return;var up=sdir()>0,t=up?dirBtn.dataset.asc:dirBtn.dataset.desc;dirBtn.textContent=up?'\u2191':'\u2193';dirBtn.title=t;dirBtn.setAttribute('aria-label',t);}"
			+ "function per(){if(root.classList.contains('ftcx-list'))return " + FacetedPage.GROUP_LIST_ROWS + ";var p=root.querySelector('.ftcx-probe'),t=p?getComputedStyle(p).gridTemplateColumns:'',n=t&&t!=='none'&&t.indexOf('repeat')<0?t.split(' ').length:3;return " + FacetedPage.GROUP_CARD_ROWS + "*Math.max(1,n);}"
			+ "function apply(){var terms=ftTerms(q.value),shown=0;"
			+ "cards.forEach(function(c){var ok=passes(c,terms,null);c.hidden=!ok;if(ok)shown++;});"
			+ "filters.forEach(function(f){var k=f.dataset.filter,counts={},all=f.querySelector('[data-value=\"\"]'),os=opts(f);"
			+ "cards.forEach(function(c){if(passes(c,terms,k))vals(c,k).forEach(function(v){counts[v]=(counts[v]||0)+1;});});"
			+ "os.forEach(function(o){var v=o.dataset.value,n=counts[v]||0,on=has(state[k],v),i=o.querySelector('i'),cb=o.querySelector('input');o.dataset.n=n;if(i)i.textContent=n;o.classList.toggle('ftcx-zero',!n&&!on);o.classList.toggle('active',on);if(cb)cb.checked=on;else o.setAttribute('aria-pressed',on?'true':'false');});"
			+ "if(all)all.classList.toggle('active',!state[k].length);"
			+ "var hn=f.querySelector('.ftcx-facet-n');if(hn)hn.textContent=state[k].length?state[k].length:'';"
			+ "var list=f.querySelector('.ftcx-opts');if(!list)return;var find=f.querySelector('.ftcx-find'),ft=find?ftTerms(find.value):[],top=+f.dataset.top||6,i=0,extra=0;"
			+ "os.sort(function(a,b){return (has(state[k],b.dataset.value)-has(state[k],a.dataset.value))||((+b.dataset.n)-(+a.dataset.n))||((+a.dataset.order)-(+b.dataset.order));});"
			+ "os.forEach(function(o){list.appendChild(o);var l=ftFold(o.dataset.label),m=ft.every(function(t){return l.indexOf(t)>=0;});var vis=m&&(ft.length>0||expanded[k]||i<top||has(state[k],o.dataset.value));if(m){i++;if(!vis)extra++;}o.hidden=!vis;});"
			+ "var mb=f.querySelector('.ftcx-moreopts');mb.hidden=ft.length>0||(!extra&&!expanded[k]);mb.textContent=expanded[k]?mb.dataset.less:mb.dataset.tpl.replace('{0}',extra);});"
			+ "groups.forEach(function(g){g.hidden=!g.querySelector('.ftcc-card:not([hidden])');});"
			+ "var s=sort.value,d=sdir(),lim0=per();"
			+ "groups.forEach(function(g){var grid=g.querySelector('.ftcc-grid'),v=Array.prototype.slice.call(grid.querySelectorAll('.ftcc-card:not([hidden])'));"
			+ "v.sort(function(a,b){if(s==='name')return d*(a.dataset.name<b.dataset.name?-1:a.dataset.name>b.dataset.name?1:0);return d*((+(a.dataset[s]||0))-(+(b.dataset[s]||0)));});"
			+ "v.forEach(function(c){grid.appendChild(c);});var lim=limits[g.dataset.group]||lim0;v.forEach(function(c,i){c.classList.toggle('ftcx-cut',i>=lim);});"
			+ "var n=g.querySelector('[data-groupcount]');if(n)n.textContent=v.length;g.hidden=!v.length;g.dataset.n=v.length;"
			+ "var b=g.querySelector('.ftcx-showmore'),rest=v.length-lim;b.hidden=rest<=0;if(rest>0)b.textContent=b.dataset.tpl.replace('{0}',Math.min(3*lim0,rest)).replace('{1}',rest);});"
			+ "groups.slice().sort(function(a,b){return ((+b.dataset.pin)-(+a.dataset.pin))||((+a.dataset.order)-(+b.dataset.order));}).forEach(function(g){main.appendChild(g);});"
			+ "var html='',nf=0;filters.forEach(function(f){var k=f.dataset.filter;state[k].forEach(function(v){var o=opts(f).filter(function(x){return x.dataset.value===v;})[0];nf++;"
			+ "html+='<span class=\"ftcx-chip\">'+esc(f.dataset.label)+' : '+esc(o?o.dataset.label:v)+'<button type=\"button\" data-remove=\"'+esc(k)+'\" data-remove-value=\"'+esc(v)+'\" aria-label=\"" + FacetedPage.unquote(FacetedPage.esc(this.message("explorer.removeFilter"))) + "\">&#215;</button></span>';});});"
			+ "document.getElementById('ftcxChips').innerHTML=html;document.getElementById('ftcxClear').hidden=!(nf||q.value.trim());"
			+ "document.getElementById('ftccEmpty').style.display=shown?'none':'block';document.getElementById('ftcxCount').textContent=shown+' / '+cards.length+' '+(cards.length>1?'" + FacetedPage.unquote(this.message("explorer.results")) + "':'" + FacetedPage.unquote(this.message("explorer.result")) + "');badgeCount();}"
			+ "function changed(){limits={};apply();}"
			+ "function toggle(k,v,on){var a=state[k],i=a.indexOf(v);if(on&&i<0)a.push(v);if(!on&&i>=0)a.splice(i,1);changed();}"
			+ "function select(k,v){if(state[k]===undefined)return;state[k]=[v];}"
			+ "root.addEventListener('change',function(e){var t=e.target,o=t.closest?t.closest('.ftcx-opt'):null;if(!o)return;toggle(o.closest('[data-filter]').dataset.filter,o.dataset.value,t.checked);});"
			+ "root.addEventListener('input',function(e){if(e.target.classList.contains('ftcx-find'))apply();});"
			+ "root.addEventListener('click',function(e){var b=e.target.closest?e.target.closest('button'):null;if(!b||!root.contains(b))return;var f=b.closest('[data-filter]');"
			+ "if(b.dataset.remove!==undefined){toggle(b.dataset.remove,b.dataset.removeValue,false);}"
			+ "else if(b.id==='ftcxClear'){for(var k in state)state[k]=[];q.value='';root.querySelectorAll('.ftcx-find').forEach(function(x){x.value='';});changed();}"
			+ "else if(b.classList.contains('ftcx-showmore')){var g=b.closest('.ftcc-group').dataset.group;limits[g]=(limits[g]||per())+3*per();apply();}"
			+ "else if(b.classList.contains('ftcx-moreopts')){expanded[f.dataset.filter]=!expanded[f.dataset.filter];apply();}"
			+ "else if(b.classList.contains('ftcx-facet-h')){var c=f.classList.toggle('ftcx-folded');b.setAttribute('aria-expanded',c?'false':'true');}"
			+ "else if(f&&f.classList.contains('ftcx-pills')&&b.dataset.value!==undefined){var k2=f.dataset.filter;if(b.dataset.value==='')state[k2]=[];else{toggle(k2,b.dataset.value,!has(state[k2],b.dataset.value));return;}changed();}});"
			+ "var more=document.getElementById('ftcxMore'),tog=document.getElementById('ftcxMoreToggle'),badge=document.getElementById('ftcxActive');"
			+ "function setMore(open){if(!more||!tog)return;more.hidden=!open;tog.setAttribute('aria-expanded',open?'true':'false');tog.querySelector('span').textContent=open?tog.dataset.less:tog.dataset.more;try{localStorage.setItem(" + StorageScope.jsKey(this.userId, "ftcxMoreOpen") + ",open?'1':'0');}catch(e){}}"
			+ "function badgeCount(){var rb=railBtn?railBtn.querySelector('b'):null;if(rb&&rail){var m=0;rail.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)m++;});rb.textContent=m;rb.hidden=!m;}"
			+ "if(!badge||!more)return;var n=0;more.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)n++;});badge.textContent=n;badge.hidden=!n;}"
			+ "if(!more||!more.querySelector('[data-filter]')){if(tog)tog.hidden=true;more=null;}else if(tog){tog.addEventListener('click',function(){setMore(more.hidden);});var open=false;try{open=localStorage.getItem(" + StorageScope.jsKey(this.userId, "ftcxMoreOpen") + ")==='1';}catch(e){}setMore(open);}"
			+ "q.addEventListener('input',changed);sort.addEventListener('change',function(){dir=0;showDir();apply();});"
			+ "if(dirBtn)dirBtn.addEventListener('click',function(){dir=-sdir();showDir();apply();});showDir();"
			+ "var rail=document.getElementById('ftcxRail'),railBtn=document.getElementById('ftcxRailToggle');"
			+ "function setRail(show,save){root.classList.toggle('ftcx-norail',!show);if(railBtn){railBtn.setAttribute('aria-expanded',show?'true':'false');railBtn.title=show?railBtn.dataset.hide:railBtn.dataset.show;railBtn.classList.toggle('active',show);}if(save){try{localStorage.setItem(" + StorageScope.jsKey(this.userId, "ftcxRail") + ",show?'1':'0');}catch(e){}}}"
			+ "if(railBtn&&rail){railBtn.addEventListener('click',function(){setRail(root.classList.contains('ftcx-norail'),true);changed();});var shown=true;try{shown=localStorage.getItem(" + StorageScope.jsKey(this.userId, "ftcxRail") + ")!=='0';}catch(e){}setRail(shown,false);}"
			// the workspace is resized whenever the EBX menu folds: the two full rows of a group follow the new column count
			+ "var rz=0;window.addEventListener('resize',function(){clearTimeout(rz);rz=setTimeout(apply,150);});"
			+ "root.ftFacets={select:function(k,v){select(k,v);if(more&&more.querySelector('[data-filter=\"'+k+'\"]'))setMore(true);},changed:changed,apply:apply};apply();})();";
	}
}
