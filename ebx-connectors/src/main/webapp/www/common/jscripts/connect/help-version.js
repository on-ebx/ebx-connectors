/*
 * The version of the module in the top bar of the generated help pages (tools/build_help.py). The pages are committed
 * and do not depend on the build; the About page links to them with ?version=<version>, written here with textContent.
 * Only a plain version string is shown (letters, digits and . _ + -); anything else leaves the bar as it is.
 */
(function () {
	var version = '';
	try { version = new URLSearchParams(window.location.search).get('version') || ''; } catch (e) { return; }
	if (!/^[0-9A-Za-z._+-]{1,64}$/.test(version)) { return; }
	var spots = document.querySelectorAll('[data-module-version]');
	for (var i = 0; i < spots.length; i++) {
		spots[i].textContent = ' · version ' + version;
	}
})();
