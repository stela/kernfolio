document.addEventListener('DOMContentLoaded', function () {
    // Advanced parameters toggle
    var toggle = document.getElementById('advanced-toggle');
    var panel = document.getElementById('advanced-panel');
    var icon = document.getElementById('advanced-icon');
    if (toggle && panel) {
        toggle.addEventListener('click', function () {
            var hidden = panel.classList.toggle('hidden');
            if (icon) icon.classList.toggle('rotate-90', !hidden);
        });
    }

    // Form submission via fetch
    var form = document.getElementById('optimize-form');
    var spinner = document.getElementById('spinner');
    var submitBtn = document.getElementById('optimize-submit');
    var resultsContainer = document.getElementById('results-container');
    if (!form) return;

    function setBusy(busy) {
        if (spinner) spinner.classList.toggle('hidden', !busy);
        if (submitBtn) submitBtn.disabled = busy;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        setBusy(true);

        Http.fetch(form.action, {
            method: 'POST',
            body: new FormData(form),
            redirect: 'follow',
        })
            .then(function (r) {
                // On successful optimization, the server 302s to the run's
                // results page; fetch auto-follows and we navigate the
                // browser there. Session-expiry 401 is already handled by
                // Http.fetch (reloads the page). Keep the spinner visible
                // through the navigation — setBusy(false) on redirect
                // would flash the page before unload.
                if (r.redirected) {
                    window.location.href = r.url;
                    return null;
                }
                return r.text();
            })
            .then(function (html) {
                if (html != null && resultsContainer) {
                    resultsContainer.innerHTML = html;
                }
                // Only clear busy if we're staying on the page (error case).
                if (html != null) setBusy(false);
            })
            .catch(function () {
                setBusy(false);
            });
    });
});
