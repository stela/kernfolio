/**
 * Browser-side discrete allocation using greedy largest-remainder algorithm.
 *
 * Reads totalValue and currentHoldings from localStorage, fetches latest
 * prices and FX rates, computes share counts (integer or fractional per
 * instrument), and renders allocation + trade list into the DOM.
 */
document.addEventListener('DOMContentLoaded', function () {
    var container = document.getElementById('discrete-allocation');
    if (!container) return;

    var ids = ChartUtils.getIdsFromUrl();
    if (!ids) return;

    var totalValue = parseFloat(localStorage.getItem('totalValue'));
    if (!totalValue || isNaN(totalValue) || totalValue <= 0) {
        container.innerHTML =
            '<p class="text-sm text-gray-500 italic">' +
            'Set your total portfolio value in localStorage to see discrete allocation.' +
            '<br><code class="text-xs bg-gray-100 px-1 rounded">localStorage.setItem(\'totalValue\', \'100000\')</code>' +
            '</p>';
        return;
    }

    var currentHoldings = {};
    try {
        var stored = localStorage.getItem('currentHoldings');
        if (stored) currentHoldings = JSON.parse(stored);
    } catch (e) {
        currentHoldings = {};
    }

    // Step 1: Fetch discrete-data (weights, baseCurrency, tickers, fractional flags)
    Http.json('/api/portfolios/' + ids.portfolioId + '/runs/' + ids.runId + '/discrete-data')
        .then(function (discreteData) {
            var tickers = discreteData.tickers;
            if (tickers.length === 0) return;

            // Step 2: Fetch latest prices
            return Http.json('/api/prices/latest?tickers=' + encodeURIComponent(tickers.join(',')))
                .then(function (priceData) {
                    // Collect currencies we need rates for
                    var currencies = [];
                    for (var i = 0; i < tickers.length; i++) {
                        var t = tickers[i];
                        if (priceData[t] && priceData[t].currency && priceData[t].currency !== discreteData.baseCurrency) {
                            if (currencies.indexOf(priceData[t].currency) === -1) {
                                currencies.push(priceData[t].currency);
                            }
                        }
                    }

                    // Step 3: Fetch FX rates (base = portfolio baseCurrency)
                    var fxPromise;
                    if (currencies.length > 0) {
                        fxPromise = Http.json('/api/fx/latest?base=' + encodeURIComponent(discreteData.baseCurrency) +
                            '&currencies=' + encodeURIComponent(currencies.join(',')));
                    } else {
                        fxPromise = Promise.resolve({});
                    }

                    return fxPromise.then(function (fxData) {
                        // Step 4: Convert prices to base currency
                        var basePrices = {};
                        for (var i = 0; i < tickers.length; i++) {
                            var t = tickers[i];
                            if (!priceData[t] || !priceData[t].close) continue;
                            var nativePrice = parseFloat(priceData[t].close);
                            var priceCurrency = priceData[t].currency;

                            if (priceCurrency === discreteData.baseCurrency) {
                                basePrices[t] = nativePrice;
                            } else if (fxData[priceCurrency] && fxData[priceCurrency].rate) {
                                // rate is base/native, so basePrice = nativePrice / rate
                                basePrices[t] = nativePrice / parseFloat(fxData[priceCurrency].rate);
                            }
                        }

                        // Step 5: Run discrete allocation
                        var result = discreteAllocation(
                            discreteData.weights, basePrices, totalValue, discreteData.fractional
                        );

                        // Step 6: Compute trades and render
                        var trades = computeTrades(result.shares, currentHoldings);
                        renderAllocation(container, result, trades, basePrices, totalValue, discreteData.baseCurrency);
                    });
                });
        })
        .catch(function (err) {
            container.innerHTML =
                '<p class="text-sm text-red-500">Failed to load discrete allocation data.</p>';
        });
});

function discreteAllocation(weights, basePrices, totalValue, fractionalFlags) {
    var tickers = Object.keys(weights);
    var idealShares = {};
    var resultShares = {};
    var fractionalSpent = 0;

    // Step 1: Compute ideal shares; for fractional instruments, use exact amount
    for (var i = 0; i < tickers.length; i++) {
        var t = tickers[i];
        var price = basePrices[t];
        if (!price || price <= 0) {
            idealShares[t] = 0;
            resultShares[t] = 0;
            continue;
        }
        idealShares[t] = (weights[t] * totalValue) / price;

        if (fractionalFlags && fractionalFlags[t]) {
            // Fractional: use exact ideal shares (rounded to 4 decimals)
            resultShares[t] = Math.round(idealShares[t] * 10000) / 10000;
            fractionalSpent += resultShares[t] * price;
        } else {
            resultShares[t] = Math.floor(idealShares[t]);
        }
    }

    // Step 2: Compute remaining cash after flooring integer instruments
    var spent = fractionalSpent;
    for (var i = 0; i < tickers.length; i++) {
        var t = tickers[i];
        if (fractionalFlags && fractionalFlags[t]) continue;
        spent += resultShares[t] * (basePrices[t] || 0);
    }
    var remaining = totalValue - spent;

    // Step 3: Greedy largest-remainder for integer instruments only
    var integerTickers = tickers.filter(function (t) {
        return !(fractionalFlags && fractionalFlags[t]);
    });
    var remainders = integerTickers
        .map(function (t) {
            return { ticker: t, remainder: idealShares[t] - Math.floor(idealShares[t]) };
        })
        .sort(function (a, b) { return b.remainder - a.remainder; });

    for (var i = 0; i < remainders.length; i++) {
        var ticker = remainders[i].ticker;
        var price = basePrices[ticker] || 0;
        if (price > 0 && remaining >= price) {
            resultShares[ticker] += 1;
            remaining -= price;
        }
    }

    return { shares: resultShares, leftoverCash: remaining };
}

function computeTrades(targetShares, currentHoldings) {
    var trades = [];
    var allTickers = Object.keys(targetShares);

    for (var i = 0; i < allTickers.length; i++) {
        var t = allTickers[i];
        var target = targetShares[t] || 0;
        var current = currentHoldings[t] || 0;
        var delta = target - current;
        if (Math.abs(delta) > 0.0001) {
            trades.push({
                ticker: t,
                current: current,
                target: target,
                delta: delta,
                action: delta > 0 ? 'BUY' : 'SELL',
            });
        }
    }

    // Sells for tickers in currentHoldings but not in targetShares
    var currentTickers = Object.keys(currentHoldings);
    for (var i = 0; i < currentTickers.length; i++) {
        var t = currentTickers[i];
        if (!(t in targetShares) && currentHoldings[t] > 0) {
            trades.push({
                ticker: t,
                current: currentHoldings[t],
                target: 0,
                delta: -currentHoldings[t],
                action: 'SELL',
            });
        }
    }

    trades.sort(function (a, b) {
        if (a.action !== b.action) return a.action === 'BUY' ? -1 : 1;
        return a.ticker.localeCompare(b.ticker);
    });

    return trades;
}

function renderAllocation(container, result, trades, basePrices, totalValue, baseCurrency) {
    var html = '';

    html += '<div class="mb-6">';
    html += '<h3 class="text-sm font-medium text-gray-700 mb-2">Discrete Allocation</h3>';
    html += '<p class="text-xs text-gray-500 mb-3">Total value: ' +
        formatNum(totalValue) + ' ' + esc(baseCurrency) +
        ' &mdash; Leftover cash: ' + formatNum(result.leftoverCash) + ' ' + esc(baseCurrency) + '</p>';

    html += '<table class="min-w-full divide-y divide-gray-200">';
    html += '<thead class="bg-gray-50"><tr>';
    html += th('Ticker') + th('Shares', true) + th('Price (' + esc(baseCurrency) + ')', true) +
            th('Value', true) + th('Weight', true);
    html += '</tr></thead><tbody class="divide-y divide-gray-200">';

    var tickers = Object.keys(result.shares).sort();
    for (var i = 0; i < tickers.length; i++) {
        var t = tickers[i];
        var shares = result.shares[t];
        var price = basePrices[t] || 0;
        var value = shares * price;
        var weight = totalValue > 0 ? (value / totalValue * 100) : 0;

        html += '<tr>';
        html += td(esc(t), 'font-mono font-medium');
        html += td(formatShares(shares), 'text-right');
        html += td(formatNum(price), 'text-right');
        html += td(formatNum(value), 'text-right');
        html += td(weight.toFixed(2) + '%', 'text-right');
        html += '</tr>';
    }
    html += '</tbody></table></div>';

    if (trades.length > 0) {
        html += '<div>';
        html += '<h3 class="text-sm font-medium text-gray-700 mb-2">Trade List</h3>';
        html += '<table class="min-w-full divide-y divide-gray-200">';
        html += '<thead class="bg-gray-50"><tr>';
        html += th('Action') + th('Ticker') + th('Current', true) + th('Target', true) +
                th('Delta', true) + th('Est. Value', true);
        html += '</tr></thead><tbody class="divide-y divide-gray-200">';

        for (var i = 0; i < trades.length; i++) {
            var trade = trades[i];
            var price = basePrices[trade.ticker] || 0;
            var estValue = Math.abs(trade.delta) * price;
            var isBuy = trade.action === 'BUY';
            var rowCls = isBuy ? 'bg-green-50' : 'bg-red-50';
            var badgeCls = isBuy ? 'text-green-700 bg-green-100' : 'text-red-700 bg-red-100';
            var deltaCls = isBuy ? 'text-green-600' : 'text-red-600';

            html += '<tr class="' + rowCls + '">';
            html += '<td class="px-4 py-2 text-sm"><span class="inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ' + badgeCls + '">' + trade.action + '</span></td>';
            html += td(esc(trade.ticker), 'font-mono font-medium');
            html += td(formatShares(trade.current), 'text-right');
            html += td(formatShares(trade.target), 'text-right');
            html += td((isBuy ? '+' : '') + formatShares(trade.delta), 'text-right font-medium ' + deltaCls);
            html += td(formatNum(estValue) + ' ' + esc(baseCurrency), 'text-right');
            html += '</tr>';
        }
        html += '</tbody></table></div>';
    } else {
        html += '<p class="text-sm text-gray-500 italic">No trades needed — current holdings match target allocation.</p>';
    }

    container.innerHTML = html;
}

function th(label, right) {
    return '<th class="px-4 py-3 text-' + (right ? 'right' : 'left') + ' text-xs font-medium uppercase text-gray-500">' + label + '</th>';
}

function td(content, extraCls) {
    return '<td class="px-4 py-2 text-sm ' + (extraCls || '') + '">' + content + '</td>';
}

function formatNum(value) {
    return value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatShares(value) {
    if (Number.isInteger(value)) return String(value);
    return value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

function esc(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
