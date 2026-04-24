/**
 * Handles position CRUD via fetch — replaces HTMX interactions.
 * Also handles new-row form logic (position type switching) — replaces Alpine.js.
 */
(function () {
    var tbody = document.querySelector('#positions-table tbody');
    if (!tbody) return;

    // --- Add position row ---
    var addBtn = document.getElementById('add-position-btn');
    if (addBtn) {
        addBtn.addEventListener('click', function () {
            Http.text(addBtn.dataset.newRowUrl).then(function (html) {
                var empty = document.getElementById('empty-state');
                if (empty) empty.remove();
                tbody.insertAdjacentHTML('beforeend', html);
                initNewRow(tbody.lastElementChild);
            });
        });
    }

    // Snapshot of the original saved-row HTML, keyed by the edit-row's
    // submit URL, so Cancel can restore the row exactly as it was
    // rendered by the server — no extra round-trip needed.
    var originalRowHtml = {};

    // --- Delegated events on tbody ---
    tbody.addEventListener('click', function (e) {
        // Cancel button
        var cancelBtn = e.target.closest('.js-cancel-row');
        if (cancelBtn) {
            var tr = cancelBtn.closest('tr');
            var submitUrl = tr.dataset.submitUrl;
            if (tr.hasAttribute('data-edit-row') && originalRowHtml[submitUrl]) {
                tr.outerHTML = originalRowHtml[submitUrl];
                delete originalRowHtml[submitUrl];
            } else {
                tr.remove();
            }
            return;
        }

        // Save position button
        var saveBtn = e.target.closest('.js-save-position');
        if (saveBtn) {
            savePosition(saveBtn.closest('tr'));
            return;
        }

        // Edit position button
        var editBtn = e.target.closest('[data-edit-position]');
        if (editBtn) {
            var editUrl = editBtn.dataset.editPosition;
            var row = editBtn.closest('tr');
            Http.text(editUrl).then(function (html) {
                // Stash the original saved-row so Cancel can restore it.
                // The edit-row template sets data-submit-url to the
                // position's canonical URL — we key on that.
                var tmp = document.createElement('tbody');
                tmp.innerHTML = html;
                var newRow = tmp.firstElementChild;
                if (!newRow) return;
                originalRowHtml[newRow.dataset.submitUrl] = row.outerHTML;
                row.replaceWith(newRow);
                initNewRow(newRow);
            });
            return;
        }

        // Delete position button
        var deleteBtn = e.target.closest('[data-delete-position]');
        if (deleteBtn) {
            if (!confirm('Remove this position?')) return;
            var url = deleteBtn.dataset.deletePosition;
            var tr = deleteBtn.closest('tr');
            Http.fetch(url, { method: 'DELETE' }).then(function () {
                tr.remove();
                if (typeof PortfolioEntry !== 'undefined') {
                    // Deleting redistributes implied weights across the
                    // remaining positions, so re-sync all of them.
                    if (PortfolioEntry.rebalanceNow) PortfolioEntry.rebalanceNow();
                    else if (PortfolioEntry.refresh) PortfolioEntry.refresh();
                }
            });
        }
    });

    function savePosition(tr) {
        var url = tr.dataset.submitUrl;
        var method = (tr.dataset.submitMethod || 'POST').toUpperCase();
        var posType = tr.querySelector('[name="positionType"]').value;
        var currency = (tr.querySelector('.js-currency').value || '').trim().toUpperCase();
        if (!/^[A-Z]{3}$/.test(currency)) {
            if (typeof Flash !== 'undefined') {
                Flash.error('Currency must be a three-letter ISO code (e.g. EUR, USD). Got: "' + currency + '"');
            }
            return;
        }
        // Ensure FX (and, for equities, price) are loaded before computing
        // weight — otherwise we'd persist 0% for a non-base-currency position
        // or a freshly-added ticker whose price isn't in the client's map yet.
        var tickerForFetch = posType === 'EQUITY'
            ? tr.querySelector('.js-ticker-input').value.trim()
            : null;
        var prereqs = [];
        if (typeof PortfolioEntry !== 'undefined') {
            prereqs.push(PortfolioEntry.ensureFxRate(currency));
            if (tickerForFetch) prereqs.push(PortfolioEntry.ensurePrice(tickerForFetch));
        }
        var ready = Promise.all(prereqs);

        ready.then(function () {
            if (typeof PortfolioEntry !== 'undefined') {
                var ticker;
                if (posType === 'CASH') {
                    ticker = 'CASH.' + currency;
                    var amountInput = tr.querySelector('.js-cash-amount');
                    if (amountInput) PortfolioEntry.updateCashAmount(currency, amountInput.value);
                } else {
                    ticker = tr.querySelector('.js-ticker-input').value;
                    var sharesInput = tr.querySelector('.js-shares');
                    if (sharesInput && ticker) PortfolioEntry.updateShares(ticker, sharesInput.value, currency);
                }
                // If the incoming position pushes the filled sum above the
                // declared total (or the user never set a total), bump total
                // up so weight_pct lands in a sane range.
                PortfolioEntry.ensureTotalCoversPositions();
                var weightPct = PortfolioEntry.computeWeightPct(ticker, posType, currency);
                var costBasisPct = PortfolioEntry.computeCostBasisPct(ticker, currency);
                var wpField = tr.querySelector('[name="weightPct"]');
                var cbField = tr.querySelector('[name="costBasisPct"]');
                if (wpField) wpField.value = weightPct.toFixed(6);
                if (cbField) cbField.value = costBasisPct > 0 ? costBasisPct.toFixed(6) : '';
            }

            var formData = new FormData();
            formData.append('positionType', posType);
            formData.append('currency', currency);
            formData.append('weightPct', tr.querySelector('[name="weightPct"]').value);

            var cbVal = tr.querySelector('[name="costBasisPct"]').value;
            if (cbVal) formData.append('costBasisPct', cbVal);

            if (posType === 'CASH') {
                formData.append('ticker', 'CASH.' + currency);
            } else {
                formData.append('ticker', tr.querySelector('.js-ticker-input').value);
                var sectorInput = tr.querySelector('[name="sector"]');
                if (sectorInput && sectorInput.value) formData.append('sector', sectorInput.value);
            }

            return Http.text(url, { method: method, body: formData });
        }).then(function (html) {
            // Successful save ends the edit session; drop any stashed
            // original so subsequent edits start fresh.
            if (tr.dataset.submitUrl) delete originalRowHtml[tr.dataset.submitUrl];
            tr.outerHTML = html;
            if (typeof PortfolioEntry !== 'undefined') {
                // rebalanceNow syncs every position's stored weight to its
                // current live value, then refreshes. Covers the case
                // where the save also implicitly bumped the total (via
                // ensureTotalCoversPositions) and every *other* position
                // now drifts too.
                if (PortfolioEntry.rebalanceNow) PortfolioEntry.rebalanceNow();
                else if (PortfolioEntry.refresh) PortfolioEntry.refresh();
                else PortfolioEntry.updateDisplays();
            }
        });
    }

    function initNewRow(tr) {
        if (!tr) return;
        var typeSelect = tr.querySelector('.js-pos-type');
        var currencyInput = tr.querySelector('.js-currency');
        var tickerInput = tr.querySelector('.js-ticker-input');
        var tickerList = tr.querySelector('.js-ticker-suggest');
        var tickerStatus = tr.querySelector('.js-ticker-status');
        var sectorInput = tr.querySelector('input[name="sector"]');
        var fxFetchTimer = null;
        var priceFetchTimer = null;

        function setStatus(text, tone) {
            if (!tickerStatus) return;
            tickerStatus.classList.remove('text-gray-500', 'text-emerald-700', 'text-amber-700');
            tickerStatus.textContent = text;
            if (tone === 'ok') tickerStatus.classList.add('text-emerald-700');
            else if (tone === 'warn') tickerStatus.classList.add('text-amber-700');
            else tickerStatus.classList.add('text-gray-500');
        }

        function renderPriceStatus(entry, ticker) {
            if (entry) {
                var parts = [ticker];
                if (entry.close) {
                    parts.push(parseFloat(entry.close).toLocaleString(undefined, {
                        minimumFractionDigits: 2,
                        maximumFractionDigits: 2,
                    }));
                }
                if (entry.currency) parts.push(entry.currency);
                setStatus(parts.join(' · '), 'ok');
            } else {
                setStatus('No price data — we’ll try to fetch it when you save.', 'warn');
            }
        }

        function refreshPriceForTicker(ticker) {
            // Eager, on-demand yfinance fetch so the user sees the real
            // price and currency as soon as they pick a suggestion — instead
            // of waiting for the nightly scheduled refresh.
            setStatus('Fetching price for ' + ticker + '…', 'neutral');
            return Http.json('/api/prices/refresh', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ticker: ticker }),
            }).then(function (data) {
                var entry = data && data[ticker];
                return PortfolioEntry.ensurePrice(ticker, function (cached) {
                    renderPriceStatus(cached || entry, ticker);
                });
            }).catch(function () {
                setStatus('Could not fetch price for ' + ticker + ' right now.', 'warn');
            });
        }

        function updateVisibility() {
            var isCash = typeSelect.value === 'CASH';
            tr.querySelectorAll('.js-equity-field').forEach(function (el) {
                el.classList.toggle('hidden', isCash);
                if (isCash) el.removeAttribute('required');
            });
            tr.querySelectorAll('.js-cash-field').forEach(function (el) {
                el.classList.toggle('hidden', !isCash);
            });
            updateCashTicker();
        }

        function updateCashTicker() {
            var cur = currencyInput.value || 'USD';
            var span = tr.querySelector('.js-cash-ticker');
            var hidden = tr.querySelector('.js-cash-ticker-hidden');
            if (span) span.textContent = 'CASH.' + cur;
            if (hidden) hidden.value = 'CASH.' + cur;
        }

        function scheduleFxFetch() {
            if (typeof PortfolioEntry === 'undefined' || !PortfolioEntry.ensureFxRate) return;
            var cur = currencyInput.value;
            if (!cur || cur.length !== 3) return;
            if (fxFetchTimer) clearTimeout(fxFetchTimer);
            fxFetchTimer = setTimeout(function () {
                PortfolioEntry.ensureFxRate(cur.toUpperCase()).then(updateWeight);
            }, 300);
        }

        function schedulePriceFetch() {
            if (typeof PortfolioEntry === 'undefined' || !PortfolioEntry.ensurePrice) return;
            if (!tickerInput || typeSelect.value !== 'EQUITY') return;
            var t = tickerInput.value.trim();
            if (!t) return;
            if (priceFetchTimer) clearTimeout(priceFetchTimer);
            priceFetchTimer = setTimeout(function () {
                PortfolioEntry.ensurePrice(t, function (entry) {
                    renderPriceStatus(entry, t);
                }).then(updateWeight);
            }, 400);
        }

        typeSelect.addEventListener('change', updateVisibility);
        var autocompleteWired = false;
        if (tickerInput && typeof TickerAutocomplete !== 'undefined' && tickerList) {
            TickerAutocomplete.attach(tickerInput, {
                listEl: tickerList,
                statusEl: tickerStatus,
                onSelect: function (suggestion) {
                    // Suggestion overrides current-row metadata. Name is no
                    // longer stored on positions — we only overwrite the
                    // fields that are still editable (currency, sector),
                    // and kick a price fetch so the weight reflects reality.
                    if (priceFetchTimer) clearTimeout(priceFetchTimer);
                    if (currencyInput && suggestion.currency) {
                        currencyInput.value = suggestion.currency;
                        updateCashTicker();
                        scheduleFxFetch();
                    }
                    if (sectorInput) sectorInput.value = suggestion.sector || '';
                    refreshPriceForTicker(suggestion.ticker).then(updateWeight);
                },
            });
            autocompleteWired = true;
        }
        // Fallback for environments where the autocomplete module didn't load:
        // fetch price + show inline status directly from keystrokes.
        if (tickerInput && !autocompleteWired) {
            tickerInput.addEventListener('input', schedulePriceFetch);
        }
        currencyInput.addEventListener('input', function () {
            // Normalise to upper-case in place so the displayed value matches
            // what we submit and what the server stores. Without this, typing
            // "eur" would ask the server for an EUR/eur cross-rate — which is
            // the same currency, but the case-sensitive short-circuit misses.
            var upper = currencyInput.value.toUpperCase();
            if (currencyInput.value !== upper) {
                var pos = currencyInput.selectionStart;
                currencyInput.value = upper;
                currencyInput.setSelectionRange(pos, pos);
            }
            updateCashTicker();
            scheduleFxFetch();
        });

        // Wire up weight display
        var sharesInput = tr.querySelector('.js-shares');
        var cashInput = tr.querySelector('.js-cash-amount');
        var weightDisplay = tr.querySelector('.js-weight-display');

        function updateWeight() {
            if (typeof PortfolioEntry === 'undefined' || !weightDisplay) return;
            var posType = typeSelect.value;
            var currency = currencyInput.value;
            if (posType === 'CASH') {
                if (cashInput) PortfolioEntry.updateCashAmount(currency, cashInput.value);
                weightDisplay.textContent = PortfolioEntry.formatWeight('CASH.' + currency, 'CASH', currency);
            } else {
                var ticker = tr.querySelector('.js-ticker-input').value;
                if (sharesInput && ticker) PortfolioEntry.updateShares(ticker, sharesInput.value, currency);
                if (ticker) {
                    weightDisplay.textContent = PortfolioEntry.formatWeight(ticker, 'EQUITY', currency);
                }
            }
        }

        if (sharesInput) sharesInput.addEventListener('input', updateWeight);
        if (cashInput) cashInput.addEventListener('input', updateWeight);

        updateVisibility();
        // Edit-row case: the shares / cash amount lives only in
        // localStorage (privacy), so the server can't pre-fill it for us.
        // Pull it from PortfolioEntry so the user sees their real values
        // instead of a blank input.
        if (tr.hasAttribute('data-edit-row') && typeof PortfolioEntry !== 'undefined') {
            if (typeSelect.value === 'EQUITY' && sharesInput && tickerInput && tickerInput.value) {
                var shares = PortfolioEntry.getShares(tickerInput.value);
                if (shares) sharesInput.value = shares;
            } else if (typeSelect.value === 'CASH' && cashInput && currencyInput.value) {
                var amount = PortfolioEntry.getCashAmount(currencyInput.value);
                if (amount) cashInput.value = amount;
            }
            updateWeight();
        }
        // Prime FX for the row's default currency (e.g. "USD") so the weight
        // display becomes correct as soon as the user starts typing an amount,
        // without waiting for the save click.
        scheduleFxFetch();
    }
})();

// Confirm dialogs for forms with data-confirm attribute (CSP-safe replacement for inline onsubmit)
document.querySelectorAll('form[data-confirm]').forEach(function (form) {
    form.addEventListener('submit', function (e) {
        if (!confirm(form.dataset.confirm)) {
            e.preventDefault();
        }
    });
});
