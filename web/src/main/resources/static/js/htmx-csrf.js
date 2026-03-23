document.addEventListener('DOMContentLoaded', function() {
    const token = document.querySelector('meta[name="csrf-token"]')?.content;
    const header = document.querySelector('meta[name="csrf-header"]')?.content;
    if (token && header) {
        document.body.addEventListener('htmx:configRequest', function(event) {
            event.detail.headers[header] = token;
        });
    }
});
