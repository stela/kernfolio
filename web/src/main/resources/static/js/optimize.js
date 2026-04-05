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
    var resultsContainer = document.getElementById('results-container');
    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (spinner) spinner.classList.remove('hidden');

        fetch(form.action, {
            method: 'POST',
            headers: Csrf.headers(),
            body: new FormData(form),
            redirect: 'follow',
        })
            .then(function (r) {
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
                if (spinner) spinner.classList.add('hidden');
            })
            .catch(function () {
                if (spinner) spinner.classList.add('hidden');
            });
    });
});
