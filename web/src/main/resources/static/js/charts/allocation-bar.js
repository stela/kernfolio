document.addEventListener('DOMContentLoaded', function () {
    var canvas = document.getElementById('allocation-bar');
    if (!canvas) return;
    var ids = ChartUtils.getIdsFromUrl();
    if (!ids) return;

    fetch('/api/portfolios/' + ids.portfolioId + '/runs/' + ids.runId + '/allocation-data')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            new Chart(canvas, {
                type: 'bar',
                data: {
                    labels: data.labels,
                    datasets: [
                        {
                            label: 'Current',
                            data: data.currentWeights.map(function (w) { return +(w * 100).toFixed(2); }),
                            backgroundColor: 'rgba(156, 163, 175, 0.6)',
                            borderColor: 'rgb(156, 163, 175)',
                            borderWidth: 1,
                        },
                        {
                            label: 'Optimized',
                            data: data.optimizedWeights.map(function (w) { return +(w * 100).toFixed(2); }),
                            backgroundColor: 'rgba(59, 130, 246, 0.6)',
                            borderColor: 'rgb(59, 130, 246)',
                            borderWidth: 1,
                        }
                    ]
                },
                options: {
                    responsive: true,
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: {
                                callback: function (value) { return value + '%'; }
                            },
                            title: { display: true, text: 'Weight (%)' }
                        },
                        x: {
                            ticks: { font: { size: 10 } }
                        }
                    },
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    return ctx.dataset.label + ': ' + ctx.parsed.y.toFixed(2) + '%';
                                }
                            }
                        }
                    }
                }
            });
        });
});
