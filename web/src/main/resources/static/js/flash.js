var Flash = (function () {
    function error(msg) {
        var banner = document.getElementById('global-error-banner');
        var textEl = document.getElementById('global-error-message');
        if (!banner || !textEl) return;
        textEl.textContent = msg;
        banner.classList.remove('hidden');
    }

    function dismiss() {
        var banner = document.getElementById('global-error-banner');
        if (banner) banner.classList.add('hidden');
    }

    document.addEventListener('DOMContentLoaded', function () {
        var btn = document.getElementById('global-error-dismiss');
        if (btn) btn.addEventListener('click', dismiss);
    });

    return { error: error, dismiss: dismiss };
})();

// Detect a session-expiry redirect (fetch follows the 302 to /login and
// returns 200 with the login page HTML — if we naively stitched that into
// the DOM we'd end up with a sign-in form inside a table row) and recover
// by hard-reloading so the server can route the user to /login properly.
var FetchSession = (function () {
    function assertNotExpired(response) {
        if (response.redirected || response.url.indexOf('/login') !== -1) {
            window.location.reload();
            throw new Error('session-expired');
        }
        return response;
    }
    return { assertNotExpired: assertNotExpired };
})();
