/**
 * Centralised fetch wrapper. Every AJAX call in the app goes through here,
 * so the cross-cutting concerns (X-Requested-With tagging, CSRF headers for
 * state-changing requests, 401 session-expiry recovery, non-OK rejection)
 * are defined exactly once instead of plastered on every `.then`.
 *
 * Server-side: `ApiAwareAuthenticationEntryPoint` returns 401 when it sees
 * the X-Requested-With header, instead of 302-redirecting to /login. The
 * wrapper catches 401 and hard-reloads, letting the server route the user
 * to /login properly — no more "sign-in form accidentally spliced into a
 * table row" hazard.
 */
var Http = (function () {
    function withDefaults(opts) {
        opts = opts || {};
        var headers = {};
        if (opts.headers) {
            Object.keys(opts.headers).forEach(function (k) { headers[k] = opts.headers[k]; });
        }
        headers['X-Requested-With'] = 'XMLHttpRequest';
        var method = (opts.method || 'GET').toUpperCase();
        if (method !== 'GET' && method !== 'HEAD' && typeof Csrf !== 'undefined') {
            var csrf = Csrf.headers();
            Object.keys(csrf).forEach(function (k) {
                if (!(k in headers)) headers[k] = csrf[k];
            });
        }
        opts.headers = headers;
        return opts;
    }

    function request(url, opts) {
        return fetch(url, withDefaults(opts)).then(function (r) {
            if (r.status === 401) {
                window.location.reload();
                // Return a promise that never resolves so callers' downstream
                // `.then`/`.catch` don't race the reload. Reload always wins.
                return new Promise(function () { });
            }
            if (!r.ok) {
                throw new Error('HTTP ' + r.status + ' ' + r.statusText);
            }
            return r;
        });
    }

    return {
        fetch: request,
        json: function (url, opts) { return request(url, opts).then(function (r) { return r.json(); }); },
        text: function (url, opts) { return request(url, opts).then(function (r) { return r.text(); }); },
    };
})();
