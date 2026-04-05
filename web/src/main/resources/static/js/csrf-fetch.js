var Csrf = (function () {
    var token = document.querySelector('meta[name="csrf-token"]')?.content;
    var header = document.querySelector('meta[name="csrf-header"]')?.content;

    function headers(extra) {
        var h = {};
        if (token && header) h[header] = token;
        if (extra) {
            for (var k in extra) h[k] = extra[k];
        }
        return h;
    }

    return { headers: headers, token: token, header: header };
})();
