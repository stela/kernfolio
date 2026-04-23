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

    // Shared backstop: any Promise rejection that no `.catch` consumed (e.g.
    // a backend 5xx from Http.json, a network error, a throw inside a `.then`)
    // surfaces in the banner automatically. Individual call sites only need
    // a `.catch` when they want *different* behaviour from "show the message".
    window.addEventListener('unhandledrejection', function (event) {
        var reason = event.reason;
        var msg = reason && reason.message ? reason.message
            : (typeof reason === 'string' ? reason : 'An unexpected error occurred');
        // Http.fetch's session-expired sentinel triggers a page reload, no
        // point flashing a message the user won't see.
        if (msg === 'session-expired') { event.preventDefault(); return; }
        error(msg);
        event.preventDefault();
    });

    return { error: error, dismiss: dismiss };
})();
