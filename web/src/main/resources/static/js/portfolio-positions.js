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
            var url = addBtn.dataset.newRowUrl;
            fetch(url, { headers: Csrf.headers() })
                .then(function (r) { return r.text(); })
                .then(function (html) {
                    var empty = document.getElementById('empty-state');
                    if (empty) empty.remove();
                    tbody.insertAdjacentHTML('beforeend', html);
                    initNewRow(tbody.lastElementChild);
                });
        });
    }

    // --- Delegated events on tbody ---
    tbody.addEventListener('click', function (e) {
        // Cancel button
        var cancelBtn = e.target.closest('.js-cancel-row');
        if (cancelBtn) {
            cancelBtn.closest('tr').remove();
            return;
        }

        // Save position button
        var saveBtn = e.target.closest('.js-save-position');
        if (saveBtn) {
            savePosition(saveBtn.closest('tr'));
            return;
        }

        // Delete position button
        var deleteBtn = e.target.closest('[data-delete-position]');
        if (deleteBtn) {
            if (!confirm('Remove this position?')) return;
            var url = deleteBtn.dataset.deletePosition;
            var tr = deleteBtn.closest('tr');
            fetch(url, { method: 'DELETE', headers: Csrf.headers() })
                .then(function () { tr.remove(); });
        }
    });

    function savePosition(tr) {
        var url = tr.dataset.submitUrl;
        var posType = tr.querySelector('[name="positionType"]').value;
        var currency = tr.querySelector('.js-currency').value;

        // Prepare hidden fields before submit
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
            formData.append('name', currency + ' Cash');
        } else {
            formData.append('ticker', tr.querySelector('.js-ticker-input').value);
            var nameInput = tr.querySelector('[name="name"]');
            if (nameInput && nameInput.value) formData.append('name', nameInput.value);
            var sectorInput = tr.querySelector('[name="sector"]');
            if (sectorInput && sectorInput.value) formData.append('sector', sectorInput.value);
        }

        fetch(url, {
            method: 'POST',
            headers: Csrf.headers(),
            body: formData,
        })
            .then(function (r) { return r.text(); })
            .then(function (html) {
                tr.outerHTML = html;
                // Update data displays after save
                if (typeof PortfolioEntry !== 'undefined') PortfolioEntry.updateDisplays();
            });
    }

    function initNewRow(tr) {
        if (!tr) return;
        var typeSelect = tr.querySelector('.js-pos-type');
        var currencyInput = tr.querySelector('.js-currency');

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
            var nameHidden = tr.querySelector('[name="cashName"]');
            if (nameHidden) nameHidden.value = cur + ' Cash';
        }

        typeSelect.addEventListener('change', updateVisibility);
        currencyInput.addEventListener('input', updateCashTicker);

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
    }
})();
