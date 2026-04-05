document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-toggle-user]');
    if (!btn) return;

    var userId = btn.dataset.toggleUser;
    var enabled = btn.dataset.enabled;
    var tr = btn.closest('tr');

    fetch('/admin/users/' + userId + '/toggle', {
        method: 'POST',
        headers: Csrf.headers({ 'Content-Type': 'application/x-www-form-urlencoded' }),
        body: 'enabled=' + encodeURIComponent(enabled),
    })
        .then(function (r) { return r.text(); })
        .then(function (html) { tr.outerHTML = html; });
});
