document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-update-flag]');
    if (!btn) return;

    var flagId = btn.dataset.updateFlag;
    var form = btn.closest('form');
    var tr = btn.closest('tr');

    var enabled = form.querySelector('input[name="enabled"]').checked;
    var rolloutPct = form.querySelector('input[name="rolloutPct"]').value;

    fetch('/admin/flags/' + flagId, {
        method: 'POST',
        headers: Csrf.headers({ 'Content-Type': 'application/x-www-form-urlencoded' }),
        body: 'enabled=' + encodeURIComponent(enabled) + '&rolloutPct=' + encodeURIComponent(rolloutPct),
    })
        .then(function (r) { return r.text(); })
        .then(function (html) { tr.outerHTML = html; });
});
