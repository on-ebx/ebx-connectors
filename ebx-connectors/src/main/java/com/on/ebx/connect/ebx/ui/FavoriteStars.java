package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.ebx.Favorites;

/**
 * The favorite star of the connector explorer: the button of a card, the client script that
 * toggles it through the page's AJAX endpoint ({@code #ftcxFavEndpoint}), and the localized error texts.
 */
final class FavoriteStars
{
	private FavoriteStars()
	{
	}

	/** The star of one card. {@code pType} is a {@link Favorites} kind, {@code pKey} the primary key of the target. */
	static String button(final Locale pLocale, final String pType, final String pKey, final boolean pOn)
	{
		final String add = Messages.get(FavoriteStars.class, pLocale, "favorite.add");
		final String remove = Messages.get(FavoriteStars.class, pLocale, "favorite.remove");
		return "<button type=\"button\" class=\"ftcx-fav" + (pOn ? " on" : "") + "\" data-type=\"" + MappingForm.escape(pType) + "\" data-key=\"" + MappingForm.escape(pKey) + "\" aria-pressed=\"" + pOn
			+ "\" title=\"" + MappingForm.escape(pOn ? remove : add) + "\" data-add=\"" + MappingForm.escape(add) + "\" data-remove=\"" + MappingForm.escape(remove) + "\">" + (pOn ? "&#9733;" : "&#9734;") + "</button>";
	}

	/** Only the limit message takes an argument: MessageFormat would eat the apostrophes of the others. */
	static String errorText(final Locale pLocale, final String pCode)
	{
		final String key = "favorite.error." + pCode;
		return Favorites.ERR_LIMIT.equals(pCode) ? Messages.get(FavoriteStars.class, pLocale, key, Integer.valueOf(Favorites.MAX_PER_USER)) : Messages.get(FavoriteStars.class, pLocale, key);
	}

	/**
	 * Script fragment for a page whose script has {@code root} (the page element) and {@code F} (its {@code ftFacets}) in
	 * scope: a click on a star calls the endpoint with {@code type} and {@code key}, then flips the star, the card's
	 * {@code data-fav} and re-applies the filters.
	 */
	static final String JS = "root.querySelectorAll('.ftcx-fav').forEach(function(b){b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(b.disabled)return;b.disabled=true;"
		+ "var card=b.closest('.ftcc-card'),ep=document.getElementById('ftcxFavEndpoint'),u=new URL(ep.dataset.url,window.top.location.href);u.searchParams.set('key',b.dataset.key);u.searchParams.set('type',b.dataset.type);"
		+ "var h=new EBX_AJAXResponseHandler();"
		+ "h.handleAjaxResponseSuccess=function(t){b.disabled=false;var d;try{d=JSON.parse(t);}catch(x){return;}"
		+ "if(d.error){window.alert(d.error);return;}"
		+ "b.classList.toggle('on',d.favorite);b.setAttribute('aria-pressed',d.favorite?'true':'false');b.textContent=d.favorite?'\\u2605':'\\u2606';b.title=d.favorite?b.dataset.remove:b.dataset.add;card.dataset.fav=d.favorite?'1':'0';F.changed();};"
		+ "h.handleAjaxResponseFailed=function(){b.disabled=false;};h.sendRequest(u.href);});});";
}
