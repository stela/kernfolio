/**
 * Alpine.js component for client-side portfolio entry.
 *
 * Users enter share counts (equity) or cash amounts instead of weight percentages.
 * The browser computes weight_pct from current prices and total portfolio value,
 * stores absolute values in localStorage, and sends only percentages to the backend.
 */
function portfolioEntry() {
    return {
        portfolioId: '',
        baseCurrency: '',
        totalValue: 0,
        holdings: {},
        cash: {},
        positions: [],
        prices: {},
        fxRates: {},
        driftWarning: false,
        driftPositions: [],
        loaded: false,
        recovering: false,

        init: function () {
            // Extract portfolio ID from URL: /portfolios/{id}
            var match = window.location.pathname.match(/\/portfolios\/([^/]+)/);
            if (!match) return;
            this.portfolioId = match[1];

            this.loadFromLocalStorage();
            this.fetchEntryData();
        },

        storageKey: function () {
            return 'kernfolio_portfolio_' + this.portfolioId;
        },

        loadFromLocalStorage: function () {
            try {
                var data = JSON.parse(localStorage.getItem(this.storageKey()) || '{}');
                this.totalValue = data.totalValue || 0;
                this.holdings = data.holdings || {};
                this.cash = data.cash || {};
            } catch (e) {
                this.totalValue = 0;
                this.holdings = {};
                this.cash = {};
            }
        },

        saveToLocalStorage: function () {
            var data = {
                totalValue: this.totalValue,
                baseCurrency: this.baseCurrency,
                holdings: this.holdings,
                cash: this.cash,
            };
            localStorage.setItem(this.storageKey(), JSON.stringify(data));

            // Also write to discrete-allocation keys for compatibility
            localStorage.setItem('totalValue', String(this.totalValue));
            var currentHoldings = {};
            for (var ticker in this.holdings) {
                currentHoldings[ticker] = this.holdings[ticker].shares || 0;
            }
            localStorage.setItem('currentHoldings', JSON.stringify(currentHoldings));
        },

        fetchEntryData: function () {
            var self = this;
            fetch('/api/portfolios/' + this.portfolioId + '/entry-data')
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    self.baseCurrency = data.baseCurrency;
                    self.positions = data.positions;

                    // Collect tickers to fetch prices for
                    var tickers = data.positions
                        .filter(function (p) { return p.positionType === 'EQUITY'; })
                        .map(function (p) { return p.ticker; });

                    if (tickers.length === 0) {
                        self.loaded = true;
                        return;
                    }

                    return fetch('/api/prices/latest?tickers=' + encodeURIComponent(tickers.join(',')))
                        .then(function (r) { return r.json(); })
                        .then(function (priceData) {
                            self.prices = priceData;

                            // Collect non-base currencies for FX
                            var currencies = [];
                            for (var i = 0; i < tickers.length; i++) {
                                var t = tickers[i];
                                if (priceData[t] && priceData[t].currency && priceData[t].currency !== self.baseCurrency) {
                                    if (currencies.indexOf(priceData[t].currency) === -1) {
                                        currencies.push(priceData[t].currency);
                                    }
                                }
                            }
                            // Also add currencies from cash positions
                            data.positions.forEach(function (p) {
                                if (p.positionType === 'CASH' && p.currency !== self.baseCurrency) {
                                    if (currencies.indexOf(p.currency) === -1) currencies.push(p.currency);
                                }
                            });

                            if (currencies.length === 0) {
                                self.loaded = true;
                                self.checkReconciliation();
                                return;
                            }

                            return fetch('/api/fx/latest?base=' + encodeURIComponent(self.baseCurrency) +
                                '&currencies=' + encodeURIComponent(currencies.join(',')))
                                .then(function (r) { return r.json(); })
                                .then(function (fxData) {
                                    self.fxRates = fxData;
                                    self.loaded = true;
                                    self.checkReconciliation();
                                });
                        });
                });
        },

        priceInBaseCurrency: function (ticker) {
            var pd = this.prices[ticker];
            if (!pd || !pd.close) return 0;
            var nativePrice = parseFloat(pd.close);
            if (pd.currency === this.baseCurrency) return nativePrice;
            var fx = this.fxRates[pd.currency];
            if (!fx || !fx.rate) return 0;
            return nativePrice / parseFloat(fx.rate);
        },

        cashInBaseCurrency: function (currency, amount) {
            if (currency === this.baseCurrency) return amount;
            var fx = this.fxRates[currency];
            if (!fx || !fx.rate) return 0;
            return amount / parseFloat(fx.rate);
        },

        computeWeightPct: function (ticker, positionType, currency) {
            if (this.totalValue <= 0) return 0;
            if (positionType === 'CASH') {
                var cashData = this.cash[currency];
                if (!cashData) return 0;
                return this.cashInBaseCurrency(currency, cashData.amount) / this.totalValue;
            }
            var h = this.holdings[ticker];
            if (!h || !h.shares) return 0;
            var priceBase = this.priceInBaseCurrency(ticker);
            return (h.shares * priceBase) / this.totalValue;
        },

        computeCostBasisPct: function (ticker, currency) {
            if (this.totalValue <= 0) return 0;
            var h = this.holdings[ticker];
            if (!h || !h.costBasis) return 0;
            return this.cashInBaseCurrency(currency, h.costBasis) / this.totalValue;
        },

        formatWeight: function (ticker, positionType, currency) {
            var w = this.computeWeightPct(ticker, positionType, currency);
            return (w * 100).toFixed(2) + '%';
        },

        getShares: function (ticker) {
            return this.holdings[ticker] ? this.holdings[ticker].shares || 0 : 0;
        },

        getCostBasis: function (ticker) {
            return this.holdings[ticker] ? this.holdings[ticker].costBasis || '' : '';
        },

        getCashAmount: function (currency) {
            return this.cash[currency] ? this.cash[currency].amount || 0 : 0;
        },

        updateShares: function (ticker, shares, currency) {
            if (!this.holdings[ticker]) {
                this.holdings[ticker] = { shares: 0, costBasis: 0, currency: currency };
            }
            this.holdings[ticker].shares = parseFloat(shares) || 0;
            this.holdings[ticker].currency = currency;
            this.saveToLocalStorage();
        },

        updateCostBasis: function (ticker, costBasis, currency) {
            if (!this.holdings[ticker]) {
                this.holdings[ticker] = { shares: 0, costBasis: 0, currency: currency };
            }
            this.holdings[ticker].costBasis = parseFloat(costBasis) || 0;
            this.saveToLocalStorage();
        },

        updateCashAmount: function (currency, amount) {
            if (!this.cash[currency]) {
                this.cash[currency] = { amount: 0 };
            }
            this.cash[currency].amount = parseFloat(amount) || 0;
            this.saveToLocalStorage();
        },

        updateTotalValue: function (value) {
            this.totalValue = parseFloat(value) || 0;
            this.saveToLocalStorage();
        },

        // Populate hidden form fields before HTMX submits
        prepareSubmit: function (el, ticker, positionType, currency) {
            var weightPct = this.computeWeightPct(ticker, positionType, currency);
            var costBasisPct = this.computeCostBasisPct(ticker, currency);
            var row = el.closest('tr');
            var wpField = row.querySelector('input[name="weightPct"]');
            var cbField = row.querySelector('input[name="costBasisPct"]');
            if (wpField) wpField.value = weightPct.toFixed(6);
            if (cbField) cbField.value = costBasisPct > 0 ? costBasisPct.toFixed(6) : '';
        },

        // Reconciliation: check for weight drift
        checkReconciliation: function () {
            if (this.totalValue <= 0 || Object.keys(this.holdings).length === 0) return;

            var drifted = [];
            for (var i = 0; i < this.positions.length; i++) {
                var pos = this.positions[i];
                if (pos.positionType !== 'EQUITY') continue;

                var h = this.holdings[pos.ticker];
                if (!h || !h.shares) continue;

                var currentWeight = this.computeWeightPct(pos.ticker, 'EQUITY', pos.currency);
                var backendWeight = parseFloat(pos.weightPct);
                var drift = Math.abs(currentWeight - backendWeight);

                if (drift > 0.01) { // >1% drift
                    drifted.push({
                        ticker: pos.ticker,
                        backendWeight: (backendWeight * 100).toFixed(2),
                        currentWeight: (currentWeight * 100).toFixed(2),
                    });
                }
            }

            this.driftPositions = drifted;
            this.driftWarning = drifted.length > 0;
        },

        // Recovery: estimate shares from backend weights
        recoverShares: function () {
            if (this.totalValue <= 0) return;

            for (var i = 0; i < this.positions.length; i++) {
                var pos = this.positions[i];
                if (pos.positionType === 'CASH') {
                    var cashAmount = parseFloat(pos.weightPct) * this.totalValue;
                    var cashBase = this.cashInBaseCurrency(pos.currency, 1);
                    if (cashBase > 0) {
                        this.cash[pos.currency] = { amount: Math.round(cashAmount / cashBase * 100) / 100 };
                    }
                    continue;
                }

                var priceBase = this.priceInBaseCurrency(pos.ticker);
                if (priceBase <= 0) continue;

                var estimatedShares = Math.round(this.totalValue * parseFloat(pos.weightPct) / priceBase);
                this.holdings[pos.ticker] = {
                    shares: estimatedShares,
                    costBasis: 0,
                    currency: pos.currency,
                };
            }

            this.saveToLocalStorage();
            this.recovering = false;
        },

        hasLocalData: function () {
            return Object.keys(this.holdings).length > 0 || Object.keys(this.cash).length > 0;
        },
    };
}
