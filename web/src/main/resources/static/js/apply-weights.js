/**
 * "Apply to portfolio" on the results page.
 *
 * Reads the optimized weights (from /allocation-data) and the live
 * positions (from /entry-data), then POSTs a weights-by-position-id
 * map to the existing /rebalance endpoint.
 *
 * Cash positions are left untouched: the optimizer runs only on
 * equities, so its weights sum to 1.0 over the equity subset. Zeroing
 * cash here would overallocate; omitting cash from the rebalance
 * request preserves its stored weight via the partial-update path.
 *
 * Equity positions the optimizer dropped below its floor are
 * explicitly zeroed so the user's view ends up matching the table.
 *
 * After the server-side weights succeed, we also recompute and persist
 * local share counts via PortfolioEntry.applyWeights so the drift
 * banner doesn't appear on next portfolio-detail visit.
 *
 * Gating: requires a totalValue in the portfolio's localStorage blob
 * (set on the detail page). Without it, we can't derive shares from a
 * weight, so the button is disabled with an inline pointer back.
 */
(function () {
    var btn = document.getElementById('apply-weights-btn');
    if (!btn) return;

    var wrapper = document.getElementById('apply-weights-wrapper');
    var status = document.getElementById('apply-weights-status');
    var container = btn.closest('[data-portfolio-id]');
    if (!container) return;

    var portfolioId = container.dataset.portfolioId;
    var runId = container.dataset.runId;

    function setStatus(text, tone) {
        if (!status) return;
        status.classList.remove('text-gray-500', 'text-emerald-700', 'text-red-600');
        status.textContent = text;
        if (tone === 'ok') status.classList.add('text-emerald-700');
        else if (tone === 'err') status.classList.add('text-red-600');
        else status.classList.add('text-gray-500');
        status.classList.remove('hidden');
    }

    // Read totalValue out of the same key portfolio-entry.js uses. We do
    // it directly (not via PortfolioEntry) so the gate works even before
    // PortfolioEntry's async init finishes — the storage read is sync.
    function readStoredTotalValue() {
        try {
            var raw = localStorage.getItem('kernfolio_portfolio_' + portfolioId);
            if (!raw) return 0;
            var data = JSON.parse(raw);
            return parseFloat(data.totalValue) || 0;
        } catch (e) {
            return 0;
        }
    }

    if (readStoredTotalValue() <= 0) {
        btn.disabled = true;
        if (status) {
            status.innerHTML =
                'Set Total Portfolio Value on the ' +
                '<a href="/portfolios/' + portfolioId + '" class="font-medium text-blue-600 hover:text-blue-500">portfolio page</a> ' +
                'first, then come back to apply.';
            status.classList.remove('hidden');
            status.classList.add('text-gray-500');
        }
        return;
    }

    function buildWeightsMap(allocationData, entryData) {
        // allocationData: { labels: [ticker], optimizedWeights: [frac], currentWeights: [frac] }
        // entryData:      { positions: [{ id, ticker, positionType, ... }] }
        // Returns { byPositionId: {...}, byTicker: {...}, equityTickers: [...] }.
        // The server wants positionId-keyed; PortfolioEntry.applyWeights
        // wants ticker-keyed; ensurePrice loops over the ticker list.
        var optimizedByTicker = {};
        for (var i = 0; i < allocationData.labels.length; i++) {
            optimizedByTicker[allocationData.labels[i]] = allocationData.optimizedWeights[i];
        }

        var byPositionId = {};
        var byTicker = {};
        var equityTickers = [];
        entryData.positions.forEach(function (pos) {
            if (pos.positionType !== 'EQUITY') return;
            var target = optimizedByTicker[pos.ticker];
            // undefined → ticker not in allocation-data at all (shouldn't
            // happen — allocation-data unions optimizer output and
            // portfolio tickers — but skip defensively).
            // 0 → dropped by the optimizer; include so it gets zeroed.
            if (typeof target !== 'number') return;
            byPositionId[pos.id] = target.toFixed(6);
            byTicker[pos.ticker] = target;
            equityTickers.push(pos.ticker);
        });
        return { byPositionId: byPositionId, byTicker: byTicker, equityTickers: equityTickers };
    }

    btn.addEventListener('click', function () {
        btn.disabled = true;
        setStatus('Applying…', 'neutral');

        Promise.all([
            Http.json('/api/portfolios/' + portfolioId + '/runs/' + runId + '/allocation-data'),
            Http.json('/api/portfolios/' + portfolioId + '/entry-data'),
        ]).then(function (results) {
            var maps = buildWeightsMap(results[0], results[1]);
            if (Object.keys(maps.byPositionId).length === 0) {
                setStatus('No equity positions to update.', 'err');
                btn.disabled = false;
                return;
            }
            // Prime prices + FX for every ticker we're about to apply,
            // so PortfolioEntry.applyWeights can compute non-zero shares
            // for each. Without this, freshly-loaded results page has
            // no prices cached and applyWeights silently skips them.
            var priceFetches = (typeof PortfolioEntry !== 'undefined' && PortfolioEntry.ensurePrice)
                ? maps.equityTickers.map(function (t) { return PortfolioEntry.ensurePrice(t); })
                : [];
            return Promise.all(priceFetches).then(function () {
                return Http.fetch('/api/portfolios/' + portfolioId + '/rebalance', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ weights: maps.byPositionId }),
                });
            }).then(function () {
                if (typeof PortfolioEntry !== 'undefined' && PortfolioEntry.applyWeights) {
                    PortfolioEntry.applyWeights(maps.byTicker);
                    // Refresh re-reads /entry-data and re-runs the drift
                    // check so anything that didn't reconcile (e.g. an
                    // equity whose price never loaded) becomes visible.
                    if (PortfolioEntry.refresh) PortfolioEntry.refresh();
                }
                if (wrapper) {
                    // Replace the button with a confirmation + link so
                    // the user has an obvious next step. Keep it inline
                    // instead of auto-reloading — the displayed delta
                    // column is still accurate as a historical record
                    // of what the optimizer recommended against the
                    // weights at optimization time.
                    wrapper.innerHTML =
                        '<span class="text-sm text-emerald-700">Applied ✓</span> ' +
                        '<a href="/portfolios/' + portfolioId + '" ' +
                        'class="text-sm font-medium text-blue-600 hover:text-blue-500">' +
                        'View updated portfolio</a>';
                }
            });
        }).catch(function (err) {
            btn.disabled = false;
            setStatus((err && err.message) || 'Could not apply weights.', 'err');
        });
    });
})();
