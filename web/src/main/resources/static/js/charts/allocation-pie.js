document.addEventListener('DOMContentLoaded', function () {
    var canvas = document.getElementById('allocation-pie');
    if (!canvas) return;
    var ids = ChartUtils.getIdsFromUrl();
    if (!ids) return;

    fetch('/api/portfolios/' + ids.portfolioId + '/runs/' + ids.runId + '/allocation-data')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            var colors = generateColors(data.labels.length);
            new Chart(canvas, {
                type: 'doughnut',
                data: {
                    labels: data.labels,
                    datasets: [{
                        label: 'Optimized',
                        data: data.optimizedWeights.map(function (w) { return +(w * 100).toFixed(2); }),
                        backgroundColor: colors,
                    }]
                },
                options: {
                    responsive: true,
                    plugins: {
                        legend: { position: 'right', labels: { boxWidth: 12, font: { size: 11 } } },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    return ctx.label + ': ' + ctx.parsed.toFixed(2) + '%';
                                }
                            }
                        }
                    }
                }
            });
        });
});

function generateColors(count) {
    var palette = [
        '#3b82f6', '#ef4444', '#10b981', '#f59e0b', '#8b5cf6',
        '#ec4899', '#06b6d4', '#84cc16', '#f97316', '#6366f1',
        '#14b8a6', '#e11d48', '#a855f7', '#0ea5e9', '#22c55e',
        '#eab308', '#d946ef', '#2dd4bf', '#fb923c', '#818cf8',
    ];
    var colors = [];
    for (var i = 0; i < count; i++) {
        colors.push(palette[i % palette.length]);
    }
    return colors;
}
