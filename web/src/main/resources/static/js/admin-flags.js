document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-update-flag]');
    if (!btn) return;

    var flagId = btn.dataset.updateFlag;
    var form = btn.closest('form');
    var tr = btn.closest('tr');

    var enabled = form.querySelector('input[name="enabled"]').checked;
    var rolloutPct = form.querySelector('input[name="rolloutPct"]').value;

    Http.text('/admin/flags/' + flagId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'enabled=' + encodeURIComponent(enabled) + '&rolloutPct=' + encodeURIComponent(rolloutPct),
    }).then(function (html) { tr.outerHTML = html; });
});
