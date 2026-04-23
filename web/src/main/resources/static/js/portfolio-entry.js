/**
 * Client-side portfolio entry — vanilla JS (replaces Alpine.js component).
 *
 * Users enter share counts (equity) or cash amounts instead of weight percentages.
 * The browser computes weight_pct from current prices and total portfolio value,
 * stores absolute values in localStorage, and sends only percentages to the backend.
 */
var PortfolioEntry = (function () {
    var portfolioId = '';
    var baseCurrency = '';
    var totalValue = 0;
    var holdings = {};
    var cash = {};
    var positions = [];
    var prices = {};
    // fxTable pins the reference base currency. rates[target] is the full
    // server entry { rate, asOf, fetchedAt } — rate is target-currency units
    // per 1 base unit (e.g. base='EUR', rate=1.08 ⇔ 1 EUR = 1.08 USD); asOf is
    // the calendar date the rate is effective; fetchedAt is the UTC instant we
    // cached it (ISO-8601 with Z suffix, rendered in browser-local time).
    var fxTable = { base: null, rates: {} };
    var pendingFxFetches = {};
    var pendingPriceFetches = {};
    var loaded = false;

    function init() {
        var main = document.getElementById('portfolio-main');
        if (!main) return;
        portfolioId = main.dataset.portfolioId;
        baseCurrency = main.dataset.baseCurrency;
        if (!portfolioId) return;

        loadFromLocalStorage();
        setupTotalValueInput();
        setupRecoveryButton();
        fetchEntryData();
    }

    function storageKey() {
        return 'kernfolio_portfolio_' + portfolioId;
    }

    function loadFromLocalStorage() {
        try {
            var data = JSON.parse(localStorage.getItem(storageKey()) || '{}');
            totalValue = data.totalValue || 0;
            holdings = data.holdings || {};
            cash = data.cash || {};
        } catch (e) {
            totalValue = 0;
            holdings = {};
            cash = {};
        }
        var input = document.getElementById('total-value-input');
        if (input && totalValue > 0) input.value = totalValue;
    }

    function saveToLocalStorage() {
        var data = {
            totalValue: totalValue,
            baseCurrency: baseCurrency,
            holdings: holdings,
            cash: cash,
        };
        localStorage.setItem(storageKey(), JSON.stringify(data));
        localStorage.setItem('totalValue', String(totalValue));
        var currentHoldings = {};
        for (var ticker in holdings) {
            currentHoldings[ticker] = holdings[ticker].shares || 0;
        }
        localStorage.setItem('currentHoldings', JSON.stringify(currentHoldings));
    }

    function setupTotalValueInput() {
        var input = document.getElementById('total-value-input');
        if (!input) return;
        input.addEventListener('input', function () {
            totalValue = parseFloat(input.value) || 0;
            saveToLocalStorage();
            updateDisplays();
        });
    }

    function setupRecoveryButton() {
        var btn = document.getElementById('recover-shares-btn');
        if (btn) {
            btn.addEventListener('click', function () {
                recoverShares();
            });
        }
    }

    function fetchEntryData() {
        Http.json('/api/portfolios/' + portfolioId + '/entry-data').then(function (data) {
            baseCurrency = data.baseCurrency;
            positions = data.positions;

            var tickers = data.positions
                .filter(function (p) { return p.positionType === 'EQUITY'; })
                .map(function (p) { return p.ticker; });

            if (tickers.length === 0) {
                loaded = true;
                updateDisplays();
                return;
            }

            return Http.json('/api/prices/latest?tickers=' + encodeURIComponent(tickers.join(',')))
                .then(function (priceData) {
                    prices = priceData;

                    var currencies = [];
                    for (var i = 0; i < tickers.length; i++) {
                        var t = tickers[i];
                        if (priceData[t] && priceData[t].currency && priceData[t].currency !== baseCurrency) {
                            if (currencies.indexOf(priceData[t].currency) === -1) currencies.push(priceData[t].currency);
                        }
                    }
                    data.positions.forEach(function (p) {
                        if (p.positionType === 'CASH' && p.currency !== baseCurrency) {
                            if (currencies.indexOf(p.currency) === -1) currencies.push(p.currency);
                        }
                    });

                    if (currencies.length === 0) {
                        loaded = true;
                        checkReconciliation();
                        updateDisplays();
                        return;
                    }

                    return Http.json('/api/fx/latest?base=' + encodeURIComponent(baseCurrency) +
                        '&currencies=' + encodeURIComponent(currencies.join(',')))
                        .then(function (fxData) {
                            resetFxTable(baseCurrency);
                            Object.keys(fxData).forEach(function (cur) {
                                if (fxData[cur] && fxData[cur].rate) {
                                    fxTable.rates[cur] = fxData[cur];
                                }
                            });
                            loaded = true;
                            checkReconciliation();
                            updateDisplays();
                        });
                });
        });
    }

    function resetFxTable(newBase) {
        if (fxTable.base !== newBase) {
            fxTable = { base: newBase, rates: {} };
            pendingFxFetches = {};
        }
    }

    function getFxRateAgainstBase(currency) {
        if (currency === fxTable.base) return 1;
        var entry = fxTable.rates[currency];
        return entry && entry.rate ? parseFloat(entry.rate) : null;
    }

    function ensureFxRate(currency) {
        resetFxTable(baseCurrency);
        // Defensive: callers may pass the user's raw input; normalise so a
        // stray lowercase or surrounding whitespace can't sneak past the
        // same-currency short-circuit below.
        currency = (currency || '').trim().toUpperCase();
        if (!currency || currency === fxTable.base) return Promise.resolve();
        if (fxTable.rates[currency]) return Promise.resolve();
        if (pendingFxFetches[currency]) return pendingFxFetches[currency];

        var base = fxTable.base;
        var url = '/api/fx/latest?base=' + encodeURIComponent(base) +
            '&currencies=' + encodeURIComponent(currency);
        var p = Http.json(url)
            .then(function (data) {
                if (fxTable.base !== base) return;
                var entry = data[currency];
                if (entry && entry.rate) {
                    fxTable.rates[currency] = entry;
                    updateDisplays();
                } else {
                    Flash.error('Could not fetch ' + base + '/' + currency +
                        ' exchange rate. ' + currency + ' positions will show 0% until the rate is available.');
                }
            })
            .finally(function () { delete pendingFxFetches[currency]; });
        pendingFxFetches[currency] = p;
        return p;
    }

    function ensurePrice(ticker) {
        // Equity counterpart to ensureFxRate: when a ticker is added after
        // page load, fetch its price (and, if denominated in a non-base
        // currency, the relevant FX rate) on demand. Without this, the
        // weight display for a freshly-added equity stays at 0%.
        ticker = (ticker || '').trim();
        if (!ticker) return Promise.resolve();
        if (prices[ticker] && prices[ticker].close) return Promise.resolve();
        if (pendingPriceFetches[ticker]) return pendingPriceFetches[ticker];

        var p = Http.json('/api/prices/latest?tickers=' + encodeURIComponent(ticker))
            .then(function (data) {
                var entry = data[ticker];
                if (!entry || !entry.close) {
                    Flash.error('No price available for ' + ticker +
                        '. Weight will show 0% until a price is cached.');
                    return;
                }
                prices[ticker] = entry;
                if (entry.currency && entry.currency !== baseCurrency) {
                    return ensureFxRate(entry.currency).then(updateDisplays);
                }
                updateDisplays();
            })
            .finally(function () { delete pendingPriceFetches[ticker]; });
        pendingPriceFetches[ticker] = p;
        return p;
    }

    function priceInBaseCurrency(ticker) {
        var pd = prices[ticker];
        if (!pd || !pd.close) return 0;
        var nativePrice = parseFloat(pd.close);
        var rate = getFxRateAgainstBase(pd.currency);
        if (rate === null) return 0;
        return nativePrice / rate;
    }

    function cashInBaseCurrency(currency, amount) {
        var rate = getFxRateAgainstBase(currency);
        if (rate === null) return 0;
        return amount / rate;
    }

    function computeWeightPct(ticker, positionType, currency) {
        if (totalValue <= 0) return 0;
        if (positionType === 'CASH') {
            var cashData = cash[currency];
            if (!cashData) return 0;
            return cashInBaseCurrency(currency, cashData.amount) / totalValue;
        }
        var h = holdings[ticker];
        if (!h || !h.shares) return 0;
        var priceBase = priceInBaseCurrency(ticker);
        return (h.shares * priceBase) / totalValue;
    }

    function computeCostBasisPct(ticker, currency) {
        if (totalValue <= 0) return 0;
        var h = holdings[ticker];
        if (!h || !h.costBasis) return 0;
        return cashInBaseCurrency(currency, h.costBasis) / totalValue;
    }

    function filledInBaseCurrency() {
        // Sum of every position's value expressed in the portfolio's base
        // currency. Positions for currencies whose FX rate isn't loaded yet
        // contribute 0 — the aggregate will jump once ensureFxRate resolves
        // and updateDisplays re-runs. Same for equities whose price isn't
        // cached yet.
        var sum = 0;
        Object.keys(cash).forEach(function (cur) {
            var amount = (cash[cur] && cash[cur].amount) || 0;
            sum += cashInBaseCurrency(cur, amount);
        });
        Object.keys(holdings).forEach(function (ticker) {
            var h = holdings[ticker];
            if (!h || !h.shares) return;
            sum += h.shares * priceInBaseCurrency(ticker);
        });
        return sum;
    }

    function ensureTotalCoversPositions() {
        // Called right before we compute a weight for persistence. If the
        // user hasn't entered a total, or the sum of positions has grown
        // past what they entered, auto-bump the total to match. Prevents
        // saving nonsense weights (0% or >100%) and gives the user a sane
        // default if they never bothered to set a total up-front.
        var filled = filledInBaseCurrency();
        if (filled > totalValue + 0.005) {  // small tolerance for fp noise
            totalValue = Math.round(filled * 100) / 100;
            var input = document.getElementById('total-value-input');
            if (input) input.value = String(totalValue);
            saveToLocalStorage();
            return true;
        }
        return false;
    }

    function updateAllocationProgress() {
        var container = document.getElementById('allocation-progress');
        var bar = document.getElementById('allocation-progress-bar');
        var text = document.getElementById('allocation-progress-text');
        if (!container || !bar || !text) return;
        if (totalValue <= 0) {
            container.classList.add('hidden');
            return;
        }
        var filled = filledInBaseCurrency();
        var pct = (filled / totalValue) * 100;
        bar.style.width = Math.min(Math.max(pct, 0), 100).toFixed(2) + '%';
        if (filled <= 0) {
            text.textContent = 'No positions saved yet — 0% allocated of ' +
                totalValue.toFixed(2) + ' ' + baseCurrency + '.';
        } else if (Math.abs(filled - totalValue) < 0.01) {
            text.textContent = '100% allocated (' + totalValue.toFixed(2) + ' ' + baseCurrency + ').';
        } else if (filled < totalValue) {
            var remaining = totalValue - filled;
            var remainingPct = 100 - pct;
            text.textContent = pct.toFixed(2) + '% allocated — ' +
                remaining.toFixed(2) + ' ' + baseCurrency + ' (' + remainingPct.toFixed(2) + '%) remaining.';
        } else {
            // Shouldn't happen post-save because we auto-bump, but covers
            // transient in-flight states.
            text.textContent = 'Over-allocated by ' + (filled - totalValue).toFixed(2) + ' ' + baseCurrency + '.';
        }
        container.classList.remove('hidden');
    }

    function formatWeight(ticker, positionType, currency) {
        // Without a total, weights are undefined — not zero. Showing "0.00%"
        // when the user hasn't entered their portfolio total yet is actively
        // misleading ("did my 200 EUR turn into nothing?").
        if (totalValue <= 0) return '—';
        var w = computeWeightPct(ticker, positionType, currency);
        return (w * 100).toFixed(2) + '%';
    }

    function getShares(ticker) {
        return holdings[ticker] ? holdings[ticker].shares || 0 : 0;
    }

    function getCashAmount(currency) {
        return cash[currency] ? cash[currency].amount || 0 : 0;
    }

    function updateShares(ticker, shares, currency) {
        if (!holdings[ticker]) {
            holdings[ticker] = { shares: 0, costBasis: 0, currency: currency };
        }
        holdings[ticker].shares = parseFloat(shares) || 0;
        holdings[ticker].currency = currency;
        saveToLocalStorage();
    }

    function updateCashAmount(currency, amount) {
        if (!cash[currency]) {
            cash[currency] = { amount: 0 };
        }
        cash[currency].amount = parseFloat(amount) || 0;
        saveToLocalStorage();
    }

    function updateDisplays() {
        // Update shares displays
        document.querySelectorAll('[data-shares-ticker]').forEach(function (el) {
            el.textContent = getShares(el.dataset.sharesTicker);
        });
        // Update cash displays
        document.querySelectorAll('[data-cash-currency]').forEach(function (el) {
            el.textContent = getCashAmount(el.dataset.cashCurrency);
        });

        updateFreshnessIndicator();
        updateAllocationProgress();

        // Show/hide recovery prompt
        var recoveryPrompt = document.getElementById('recovery-prompt');
        if (recoveryPrompt) {
            var hasLocal = Object.keys(holdings).length > 0 || Object.keys(cash).length > 0;
            var show = loaded && !hasLocal && positions.length > 0 && totalValue > 0;
            recoveryPrompt.classList.toggle('hidden', !show);
        }
    }

    function updateFreshnessIndicator() {
        var container = document.getElementById('fx-freshness');
        if (!container) return;
        var asOfEl = document.getElementById('fx-freshness-asof');

        // Pick the oldest asOf across all loaded rates so the "as of" we show
        // is never optimistic. fetchedAt (when we cached it locally) is not
        // shown: it's operational metadata, not a property of the rate itself
        // — the rate is "the ECB rate for day X" no matter when we pulled it.
        var oldestAsOf = null;
        Object.keys(fxTable.rates).forEach(function (cur) {
            var e = fxTable.rates[cur];
            if (!e || !e.asOf) return;
            if (!oldestAsOf || e.asOf < oldestAsOf) oldestAsOf = e.asOf;
        });

        if (!oldestAsOf) {
            container.classList.add('hidden');
            return;
        }

        // asOf is a plain ECB calendar date ("2026-04-22"). Render it in
        // Europe/Berlin so the label matches ECB's publication-day notion;
        // locally-formatted per user locale, but the day-of-month is stable
        // across the globe because the underlying date has no timezone.
        if (asOfEl) {
            var asOfDate = new Date(oldestAsOf + 'T12:00:00Z');
            asOfEl.textContent = asOfDate.toLocaleDateString(undefined, {
                year: 'numeric', month: 'short', day: 'numeric', timeZone: 'Europe/Berlin',
            });
        }
        container.classList.remove('hidden');
    }

    function checkReconciliation() {
        if (totalValue <= 0 || Object.keys(holdings).length === 0) return;

        var drifted = [];
        for (var i = 0; i < positions.length; i++) {
            var pos = positions[i];
            if (pos.positionType !== 'EQUITY') continue;
            var h = holdings[pos.ticker];
            if (!h || !h.shares) continue;
            var currentWeight = computeWeightPct(pos.ticker, 'EQUITY', pos.currency);
            var backendWeight = parseFloat(pos.weightPct);
            var drift = Math.abs(currentWeight - backendWeight);
            if (drift > 0.01) {
                drifted.push({
                    ticker: pos.ticker,
                    backendWeight: (backendWeight * 100).toFixed(2),
                    currentWeight: (currentWeight * 100).toFixed(2),
                });
            }
        }

        var warning = document.getElementById('drift-warning');
        var list = document.getElementById('drift-list');
        if (warning && list) {
            if (drifted.length > 0) {
                list.innerHTML = '';
                drifted.forEach(function (d) {
                    var li = document.createElement('li');
                    li.textContent = d.ticker + ': backend ' + d.backendWeight + '% vs. current ' + d.currentWeight + '%';
                    list.appendChild(li);
                });
                warning.classList.remove('hidden');
            } else {
                warning.classList.add('hidden');
            }
        }
    }

    function recoverShares() {
        if (totalValue <= 0) return;

        for (var i = 0; i < positions.length; i++) {
            var pos = positions[i];
            if (pos.positionType === 'CASH') {
                var cashAmount = parseFloat(pos.weightPct) * totalValue;
                var cashBase = cashInBaseCurrency(pos.currency, 1);
                if (cashBase > 0) {
                    cash[pos.currency] = { amount: Math.round(cashAmount / cashBase * 100) / 100 };
                }
                continue;
            }

            var priceBase = priceInBaseCurrency(pos.ticker);
            if (priceBase <= 0) continue;

            var estimatedShares = Math.round(totalValue * parseFloat(pos.weightPct) / priceBase);
            holdings[pos.ticker] = {
                shares: estimatedShares,
                costBasis: 0,
                currency: pos.currency,
            };
        }

        saveToLocalStorage();
        updateDisplays();

        var recoveryPrompt = document.getElementById('recovery-prompt');
        if (recoveryPrompt) recoveryPrompt.classList.add('hidden');
    }

    document.addEventListener('DOMContentLoaded', init);

    return {
        computeWeightPct: computeWeightPct,
        computeCostBasisPct: computeCostBasisPct,
        formatWeight: formatWeight,
        getShares: getShares,
        getCashAmount: getCashAmount,
        updateShares: updateShares,
        updateCashAmount: updateCashAmount,
        updateDisplays: updateDisplays,
        ensureFxRate: ensureFxRate,
        ensurePrice: ensurePrice,
        ensureTotalCoversPositions: ensureTotalCoversPositions,
    };
})();
