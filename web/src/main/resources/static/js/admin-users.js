document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-toggle-user]');
    if (!btn) return;

    var userId = btn.dataset.toggleUser;
    var enabled = btn.dataset.enabled;
    var tr = btn.closest('tr');

    Http.text('/admin/users/' + userId + '/toggle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'enabled=' + encodeURIComponent(enabled),
    }).then(function (html) { tr.outerHTML = html; });
});
