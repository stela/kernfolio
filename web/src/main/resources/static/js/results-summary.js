/**
 * Results page headline numbers: run timestamp, metric cards and the
 * optimized-vs-current weights table. The server renders these empty;
 * values come from the JSON API and are formatted via Format (format.js)
 * so they follow the viewer's locale and timezone.
 */
document.addEventListener('DOMContentLoaded', function () {
    var ids = ChartUtils.getIdsFromUrl();
    if (!ids) return;
    var base = '/api/portfolios/' + ids.portfolioId + '/runs/' + ids.runId;

    function setText(id, text) {
        var el = document.getElementById(id);
        if (el) el.textContent = text;
    }

    Http.json(base + '/summary-data').then(function (data) {
        setText('run-created-at', Format.dateTime(data.createdAt));
        setText('metric-expected-return', Format.pct(data.expectedAnnualReturn || 0));
        setText('metric-volatility', Format.pct(data.annualVolatility || 0));
        setText('metric-sharpe', Format.amount(data.sharpeRatio || 0));
        setText('metric-cvar95', Format.pct(data.cvar95 || 0));
    });

    var tbody = document.getElementById('weights-tbody');
    if (!tbody) return;

    function cell(text, classes) {
        var td = document.createElement('td');
        td.className = 'px-4 py-2 text-sm ' + classes;
        td.textContent = text;
        return td;
    }

    Http.json(base + '/allocation-data').then(function (data) {
        tbody.textContent = '';
        data.labels.forEach(function (ticker, i) {
            var optimized = data.optimizedWeights[i];
            var current = data.currentWeights[i];
            var delta = optimized - current;
            // Below display precision counts as "no change" so we never
            // show a coloured "+0.00%".
            var flat = Math.abs(delta) < 0.00005;
            var tone = flat ? 'text-gray-400' : delta > 0 ? 'text-green-600' : 'text-red-600';

            var tr = document.createElement('tr');
            tr.appendChild(cell(ticker, 'font-mono font-medium'));
            tr.appendChild(cell(Format.pct(optimized), 'text-right'));
            tr.appendChild(cell(Format.pct(current), 'text-right'));
            tr.appendChild(cell(flat ? Format.pct(0) : Format.signedPct(delta), 'text-right font-medium ' + tone));
            tbody.appendChild(tr);
        });
    });
});
