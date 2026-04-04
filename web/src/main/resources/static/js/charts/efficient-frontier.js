document.addEventListener('DOMContentLoaded', function () {
    var canvas = document.getElementById('efficient-frontier');
    if (!canvas) return;
    var portfolioId = canvas.dataset.portfolioId;
    var runId = canvas.dataset.runId;

    fetch('/api/portfolios/' + portfolioId + '/runs/' + runId + '/frontier-data')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            var datasets = [
                {
                    label: 'Efficient Frontier',
                    data: data.frontier.map(function (p) {
                        return { x: +(p.risk * 100).toFixed(2), y: +(p.ret * 100).toFixed(2) };
                    }),
                    showLine: true,
                    borderColor: 'rgb(59, 130, 246)',
                    backgroundColor: 'rgba(59, 130, 246, 0.1)',
                    fill: false,
                    pointRadius: 2,
                    borderWidth: 2,
                    tension: 0.3,
                    order: 1,
                }
            ];

            if (data.optimized) {
                datasets.push({
                    label: 'Optimized Portfolio',
                    data: [{ x: +(data.optimized.risk * 100).toFixed(2), y: +(data.optimized.ret * 100).toFixed(2) }],
                    pointRadius: 8,
                    pointBackgroundColor: 'rgb(239, 68, 68)',
                    pointBorderColor: '#fff',
                    pointBorderWidth: 2,
                    showLine: false,
                    order: 0,
                });
            }

            new Chart(canvas, {
                type: 'scatter',
                data: { datasets: datasets },
                options: {
                    responsive: true,
                    scales: {
                        x: {
                            title: { display: true, text: 'Risk (Volatility %)' },
                            ticks: { callback: function (v) { return v + '%'; } }
                        },
                        y: {
                            title: { display: true, text: 'Expected Return (%)' },
                            ticks: { callback: function (v) { return v + '%'; } }
                        }
                    },
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    return ctx.dataset.label + ': Risk ' + ctx.parsed.x.toFixed(2) + '%, Return ' + ctx.parsed.y.toFixed(2) + '%';
                                }
                            }
                        }
                    }
                }
            });
        });
});
