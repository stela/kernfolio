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
