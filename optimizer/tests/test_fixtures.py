"""Verify that all Block 0 fixture files are valid and correctly shaped."""

import csv
import json
from pathlib import Path

FIXTURES = Path(__file__).parent / "fixtures"

EXPECTED_EQUITY_TICKERS = 33
EXPECTED_CASH_POSITIONS = 4
EXPECTED_TOTAL_POSITIONS = 37
EXPECTED_CURRENCIES = sorted(["CAD", "EUR", "GBP", "HKD", "JPY", "MXN", "USD"])
EXPECTED_FX_CURRENCIES = sorted(["CAD", "GBP", "HKD", "JPY", "MXN", "USD"])
MIN_TRADING_DAYS = 750


# ── Portfolio JSON ──────────────────────────────────────────────────────


def _load_portfolio():
    with open(FIXTURES / "test-portfolio.json") as f:
        return json.load(f)


def test_portfolio_json_loads():
    data = _load_portfolio()
    assert "positions" in data
    assert "metadata" in data


def test_portfolio_has_36_positions():
    data = _load_portfolio()
    assert len(data["positions"]) == EXPECTED_TOTAL_POSITIONS


def test_portfolio_weights_sum():
    data = _load_portfolio()
    total = sum(p["weight_pct"] for p in data["positions"])
    assert abs(total - 100.0) < 1.0, f"Weights sum to {total}, expected ~100"


def test_portfolio_currencies():
    data = _load_portfolio()
    currencies = sorted(set(p["currency"] for p in data["positions"]))
    assert currencies == EXPECTED_CURRENCIES


def test_portfolio_cash_positions():
    data = _load_portfolio()
    cash = [p for p in data["positions"] if p["position_type"] == "CASH"]
    assert len(cash) == EXPECTED_CASH_POSITIONS
    for p in cash:
        assert p["ticker"].startswith("CASH.")
        assert p["yfinance_ticker"] is None


def test_portfolio_equity_positions_have_yfinance_ticker():
    data = _load_portfolio()
    equities = [p for p in data["positions"] if p["position_type"] == "EQUITY"]
    assert len(equities) == EXPECTED_EQUITY_TICKERS
    for p in equities:
        assert p["yfinance_ticker"] is not None


def test_portfolio_required_fields():
    data = _load_portfolio()
    required = {"ticker", "yfinance_ticker", "name", "currency", "position_type", "weight_pct"}
    for p in data["positions"]:
        assert required.issubset(p.keys()), f"Missing fields in {p['ticker']}"


# ── Prices CSV ──────────────────────────────────────────────────────────


def _load_prices_csv():
    with open(FIXTURES / "prices.csv", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    return reader.fieldnames, rows


def test_prices_csv_loads():
    header, rows = _load_prices_csv()
    assert header is not None
    # date column + 32 equity tickers
    assert len(header) == 1 + EXPECTED_EQUITY_TICKERS
    assert header[0] == "date"
    assert len(rows) >= MIN_TRADING_DAYS


def test_prices_csv_no_empty_values():
    _, rows = _load_prices_csv()
    for i, row in enumerate(rows):
        for col, val in row.items():
            assert val != "", f"Empty value at row {i}, column {col}"


def test_prices_csv_positive_prices():
    header, rows = _load_prices_csv()
    for row in rows:
        for col in header[1:]:  # skip date
            assert float(row[col]) > 0, f"Non-positive price for {col} on {row['date']}"


# ── FX Rates JSON ──────────────────────────────────────────────────────


def _load_fx_rates():
    with open(FIXTURES / "fx-rates.json") as f:
        return json.load(f)


def test_fx_rates_json_loads():
    data = _load_fx_rates()
    assert "rates" in data
    assert len(data["rates"]) >= MIN_TRADING_DAYS


def test_fx_rates_currencies():
    data = _load_fx_rates()
    first_date = next(iter(data["rates"]))
    currencies = sorted(data["rates"][first_date].keys())
    assert currencies == EXPECTED_FX_CURRENCIES


def test_fx_rates_positive():
    data = _load_fx_rates()
    for date_str, rates in data["rates"].items():
        for cur, rate in rates.items():
            assert rate > 0, f"Non-positive FX rate for {cur} on {date_str}"


# ── Fetch-prices response ──────────────────────────────────────────────


def _load_fetch_prices_response():
    with open(FIXTURES / "fetch-prices-response.json") as f:
        return json.load(f)


def test_fetch_prices_response_schema():
    data = _load_fetch_prices_response()
    assert "prices" in data
    assert "metadata" in data
    assert "errors" in data
    assert len(data["prices"]) == EXPECTED_EQUITY_TICKERS
    assert len(data["metadata"]) == EXPECTED_EQUITY_TICKERS


def test_fetch_prices_response_metadata_fields():
    data = _load_fetch_prices_response()
    required = {"currency", "name", "market_cap", "sector"}
    for ticker, meta in data["metadata"].items():
        assert required.issubset(meta.keys()), f"Missing metadata fields for {ticker}"


def test_fetch_prices_response_entry_format():
    data = _load_fetch_prices_response()
    for ticker, entries in data["prices"].items():
        assert len(entries) >= MIN_TRADING_DAYS, f"{ticker} has only {len(entries)} entries"
        assert "date" in entries[0]
        assert "close" in entries[0]


# ── Optimize request ───────────────────────────────────────────────────


def _load_optimize_request():
    with open(FIXTURES / "optimize-request.json") as f:
        return json.load(f)


def test_optimize_request_schema():
    data = _load_optimize_request()
    assert data["algorithm"] == "BLACK_LITTERMAN"
    assert "prices" in data
    assert "dates" in data["prices"]
    assert "market_caps" in data
    assert "views" in data
    assert "confidences" in data
    assert "constraints" in data
    assert data["covariance_method"] == "ledoit_wolf"


def test_optimize_request_views_subset_of_prices():
    data = _load_optimize_request()
    price_tickers = set(data["prices"].keys()) - {"dates"}
    for ticker in data["views"]:
        assert ticker in price_tickers, f"View ticker {ticker} not in prices"
    for ticker in data["confidences"]:
        assert ticker in price_tickers, f"Confidence ticker {ticker} not in prices"


def test_optimize_request_prices_all_same_length():
    data = _load_optimize_request()
    n_dates = len(data["prices"]["dates"])
    for ticker, values in data["prices"].items():
        if ticker == "dates":
            continue
        assert len(values) == n_dates, f"{ticker} has {len(values)} prices, expected {n_dates}"


# ── Golden optimize response ──────────────────────────────────────────


def _load_golden_response():
    with open(FIXTURES / "golden-optimize-bl-response.json") as f:
        return json.load(f)


def test_golden_response_schema():
    data = _load_golden_response()
    assert "weights" in data
    assert "metrics" in data
    assert "efficient_frontier" in data
    assert "correlation_matrix" in data
    assert "computation_ms" in data


def test_golden_weights_sum_to_one():
    data = _load_golden_response()
    total = sum(data["weights"].values())
    assert abs(total - 1.0) < 0.001, f"Weights sum to {total}, expected ~1.0"


def test_golden_metrics_fields():
    data = _load_golden_response()
    required = {"expected_annual_return", "annual_volatility", "sharpe_ratio", "cvar_95"}
    assert required.issubset(data["metrics"].keys())


def test_golden_efficient_frontier():
    data = _load_golden_response()
    frontier = data["efficient_frontier"]
    assert len(frontier) == 50
    for point in frontier:
        assert "risk" in point
        assert "return" in point
