#!/usr/bin/env python3
"""
Generate deterministic test fixtures for the Kernfolio project.

All data is synthetic, produced via seeded geometric Brownian motion.
Run: python scripts/generate_fixtures.py (from project root)

Outputs are written to:
  - optimizer/tests/fixtures/
  - web/src/test/resources/fixtures/  (+ wiremock/ stubs)
"""

import csv
import json
import math
import os
from datetime import date, timedelta
from pathlib import Path

import numpy as np

SEED = 42
START_DATE = date(2023, 3, 15)
END_DATE = date(2026, 3, 13)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OPTIMIZER_FIXTURES = PROJECT_ROOT / "optimizer" / "tests" / "fixtures"
WEB_FIXTURES = PROJECT_ROOT / "web" / "src" / "test" / "resources" / "fixtures"
WIREMOCK_MAPPINGS = WEB_FIXTURES / "wiremock" / "mappings"
WIREMOCK_FILES = WEB_FIXTURES / "wiremock" / "__files"

# ---------------------------------------------------------------------------
# Portfolio definition (Appendix B + Appendix C ticker mapping)
# ---------------------------------------------------------------------------

POSITIONS = [
    {"ticker": "8PSB", "yfinance_ticker": "8PSB.L", "name": "Physical Silver ETC", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 8.4},
    {"ticker": "EGLN", "yfinance_ticker": "EGLN.L", "name": "Physical Gold ETC", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 6.6},
    {"ticker": "CSU.TO", "yfinance_ticker": "CSU.TO", "name": "Constellation Software", "currency": "CAD", "position_type": "EQUITY", "weight_pct": 12.4},
    {"ticker": "LULU", "yfinance_ticker": "LULU", "name": "Lululemon Athletica", "currency": "USD", "position_type": "EQUITY", "weight_pct": 9.3},
    {"ticker": "JCU", "yfinance_ticker": "4975.T", "name": "JCU Corporation", "currency": "JPY", "position_type": "EQUITY", "weight_pct": 8.3},
    {"ticker": "CYND", "yfinance_ticker": "4256.T", "name": "CYND Co., Ltd.", "currency": "JPY", "position_type": "EQUITY", "weight_pct": 5.6},
    {"ticker": "AMPX", "yfinance_ticker": "AMPX", "name": "Amprius Technologies", "currency": "USD", "position_type": "EQUITY", "weight_pct": 4.0},
    {"ticker": "PRE.L", "yfinance_ticker": "PRE.L", "name": "Pensana", "currency": "GBP", "position_type": "EQUITY", "weight_pct": 4.2},
    {"ticker": "HY9H", "yfinance_ticker": "HY9H.DE", "name": "SK Hynix GDR", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 3.6},
    {"ticker": "GMEXICOB.MX", "yfinance_ticker": "GMEXICOB.MX", "name": "Grupo Mexico", "currency": "MXN", "position_type": "EQUITY", "weight_pct": 3.5},
    {"ticker": "GOOG", "yfinance_ticker": "GOOG", "name": "Alphabet", "currency": "USD", "position_type": "EQUITY", "weight_pct": 3.4},
    {"ticker": "NVDA", "yfinance_ticker": "NVDA", "name": "NVIDIA", "currency": "USD", "position_type": "EQUITY", "weight_pct": 3.2},
    {"ticker": "SKM", "yfinance_ticker": "SKM", "name": "SK Telecom ADR", "currency": "USD", "position_type": "EQUITY", "weight_pct": 2.5},
    {"ticker": "MOH", "yfinance_ticker": "MOH", "name": "Molina Healthcare", "currency": "USD", "position_type": "EQUITY", "weight_pct": 2.3},
    {"ticker": "ODET.PA", "yfinance_ticker": "ODET.PA", "name": "Compagnie de l'Odet", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 2.3},
    {"ticker": "HOOD", "yfinance_ticker": "HOOD", "name": "Robinhood Markets", "currency": "USD", "position_type": "EQUITY", "weight_pct": 2.0},
    {"ticker": "CHTR", "yfinance_ticker": "CHTR", "name": "Charter Communications", "currency": "USD", "position_type": "EQUITY", "weight_pct": 1.5},
    {"ticker": "FFH.TO", "yfinance_ticker": "FFH.TO", "name": "Fairfax Financial", "currency": "CAD", "position_type": "EQUITY", "weight_pct": 1.5},
    {"ticker": "ANIC.L", "yfinance_ticker": "ANIC.L", "name": "Agronomics Ltd", "currency": "GBP", "position_type": "EQUITY", "weight_pct": 1.4},
    {"ticker": "BE", "yfinance_ticker": "BE", "name": "Bloom Energy", "currency": "USD", "position_type": "EQUITY", "weight_pct": 1.4},
    {"ticker": "DFND.L", "yfinance_ticker": "DFND.L", "name": "iShares Global Defense", "currency": "USD", "position_type": "EQUITY", "weight_pct": 1.3},
    {"ticker": "BRK.B", "yfinance_ticker": "BRK-B", "name": "Berkshire Hathaway", "currency": "USD", "position_type": "EQUITY", "weight_pct": 1.3},
    {"ticker": "H4N.HE", "yfinance_ticker": "H4N.HE", "name": "Solar Foods", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 1.1},
    {"ticker": "FAST.AS", "yfinance_ticker": "FAST.AS", "name": "Fastned", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 1.0},
    {"ticker": "MTH", "yfinance_ticker": "MTH", "name": "Meritage Homes", "currency": "USD", "position_type": "EQUITY", "weight_pct": 0.9},
    {"ticker": "JOBY", "yfinance_ticker": "JOBY", "name": "Joby Aviation", "currency": "USD", "position_type": "EQUITY", "weight_pct": 0.7},
    {"ticker": "9880.HK", "yfinance_ticker": "9880.HK", "name": "UBTECH Robotics", "currency": "HKD", "position_type": "EQUITY", "weight_pct": 0.6},
    {"ticker": "FLXT", "yfinance_ticker": "FLXT", "name": "Franklin FTSE Taiwan", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 0.5},
    {"ticker": "BCH", "yfinance_ticker": "BCH", "name": "Banco de Chile ADR", "currency": "USD", "position_type": "EQUITY", "weight_pct": 0.5},
    {"ticker": "CEC", "yfinance_ticker": "CEC", "name": "Amundi Eastern Europe", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 0.5},
    {"ticker": "TUR", "yfinance_ticker": "TUR", "name": "Amundi Turkey", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 0.5},
    {"ticker": "BOTZ", "yfinance_ticker": "BOTZ", "name": "GX Robotics & AI", "currency": "EUR", "position_type": "EQUITY", "weight_pct": 0.5},
    {"ticker": "CIB", "yfinance_ticker": "CIB", "name": "Bancolombia ADR", "currency": "USD", "position_type": "EQUITY", "weight_pct": 0.4},
    {"ticker": "CASH.USD", "yfinance_ticker": None, "name": "US Dollar Cash", "currency": "USD", "position_type": "CASH", "weight_pct": 1.1},
    {"ticker": "CASH.EUR", "yfinance_ticker": None, "name": "Euro Cash", "currency": "EUR", "position_type": "CASH", "weight_pct": 0.4},
    {"ticker": "CASH.JPY", "yfinance_ticker": None, "name": "Japanese Yen Cash", "currency": "JPY", "position_type": "CASH", "weight_pct": 0.7},
    {"ticker": "CASH.CAD", "yfinance_ticker": None, "name": "Canadian Dollar Cash", "currency": "CAD", "position_type": "CASH", "weight_pct": 0.5},
]

# (start_price, annual_drift, annual_volatility, sector, market_cap)
TICKER_PARAMS: dict[str, tuple[float, float, float, str, int]] = {
    "8PSB.L":       (25.0,   0.05, 0.25, "Commodities",       0),
    "EGLN.L":       (155.0,  0.06, 0.18, "Commodities",       0),
    "CSU.TO":       (3200.0, 0.12, 0.22, "Technology",         70_000_000_000),
    "LULU":         (380.0,  0.10, 0.28, "Consumer Cyclical",  48_000_000_000),
    "4975.T":       (3500.0, 0.08, 0.30, "Technology",         180_000_000_000),
    "4256.T":       (1250.0, 0.07, 0.32, "Technology",         52_000_000_000),
    "AMPX":         (5.50,   0.10, 0.50, "Industrials",        800_000_000),
    "PRE.L":        (8.00,   0.05, 0.45, "Basic Materials",    200_000_000),
    "HY9H.DE":      (22.0,   0.08, 0.35, "Technology",         100_000_000_000),
    "GMEXICOB.MX":  (115.0,  0.09, 0.28, "Basic Materials",    30_000_000_000),
    "GOOG":         (140.0,  0.10, 0.25, "Technology",         1_850_000_000_000),
    "NVDA":         (270.0,  0.15, 0.35, "Technology",         1_200_000_000_000),
    "SKM":          (22.0,   0.06, 0.22, "Communication",      12_000_000_000),
    "MOH":          (310.0,  0.08, 0.25, "Healthcare",         18_000_000_000),
    "ODET.PA":      (1500.0, 0.07, 0.30, "Communication",      5_000_000_000),
    "HOOD":         (10.0,   0.12, 0.45, "Financial Services",  8_000_000_000),
    "CHTR":         (380.0,  0.07, 0.28, "Communication",      55_000_000_000),
    "FFH.TO":       (950.0,  0.10, 0.22, "Financial Services",  25_000_000_000),
    "ANIC.L":       (0.10,   0.05, 0.40, "Healthcare",         100_000_000),
    "BE":           (15.0,   0.08, 0.42, "Industrials",        3_000_000_000),
    "DFND.L":       (6.50,   0.07, 0.18, "Financial Services",  0),
    "BRK-B":        (340.0,  0.08, 0.15, "Financial Services",  780_000_000_000),
    "H4N.HE":       (4.50,   0.06, 0.45, "Consumer Defensive", 300_000_000),
    "FAST.AS":      (25.0,   0.10, 0.40, "Industrials",        600_000_000),
    "MTH":          (150.0,  0.08, 0.30, "Consumer Cyclical",  6_000_000_000),
    "JOBY":         (6.00,   0.10, 0.50, "Industrials",        4_000_000_000),
    "9880.HK":      (55.0,   0.08, 0.40, "Technology",         15_000_000_000),
    "FLXT":         (30.0,   0.07, 0.20, "Financial Services",  0),
    "BCH":          (22.0,   0.06, 0.25, "Financial Services",  10_000_000_000),
    "CEC":          (18.0,   0.05, 0.30, "Financial Services",  0),
    "TUR":          (40.0,   0.06, 0.35, "Financial Services",  0),
    "BOTZ":         (28.0,   0.09, 0.22, "Technology",          0),
    "CIB":          (30.0,   0.07, 0.28, "Financial Services",  8_000_000_000),
}

# EUR-based FX rates: (start_rate, annual_volatility)
FX_PARAMS: dict[str, tuple[float, float]] = {
    "USD": (1.19, 0.07),
    "CAD": (1.50, 0.06),
    "JPY": (130.0, 0.08),
    "GBP": (0.86, 0.06),
    "MXN": (20.5, 0.10),
    "HKD": (9.30, 0.07),
}

# Currency for each yfinance ticker (derived from POSITIONS)
TICKER_CURRENCY = {
    p["yfinance_ticker"]: p["currency"]
    for p in POSITIONS
    if p["yfinance_ticker"] is not None
}


def generate_trading_days(start: date, end: date) -> list[date]:
    """Generate weekday-only dates between start and end (inclusive)."""
    days = []
    current = start
    while current <= end:
        if current.weekday() < 5:  # Mon-Fri
            days.append(current)
        current += timedelta(days=1)
    return days


def generate_gbm_series(
    rng: np.random.Generator,
    start_price: float,
    mu: float,
    sigma: float,
    n_days: int,
) -> list[float]:
    """Geometric Brownian Motion price series."""
    dt = 1.0 / 252.0
    prices = [start_price]
    for _ in range(n_days - 1):
        z = rng.standard_normal()
        prices.append(prices[-1] * math.exp((mu - 0.5 * sigma**2) * dt + sigma * math.sqrt(dt) * z))
    return prices


def round_price(price: float, currency: str) -> float:
    """Round price: 0 decimals for JPY, 2 otherwise."""
    if currency in ("JPY",):
        return round(price, 0)
    return round(price, 2)


def build_portfolio_json() -> dict:
    currencies = sorted(set(p["currency"] for p in POSITIONS))
    equity_count = sum(1 for p in POSITIONS if p["position_type"] == "EQUITY")
    cash_count = sum(1 for p in POSITIONS if p["position_type"] == "CASH")
    return {
        "positions": POSITIONS,
        "metadata": {
            "total_positions": len(POSITIONS),
            "currencies": currencies,
            "equity_positions": equity_count,
            "cash_positions": cash_count,
            "generated_by": "scripts/generate_fixtures.py",
        },
    }


def build_prices_csv(
    rng: np.random.Generator, dates: list[date]
) -> tuple[dict[str, list[float]], list[str]]:
    """Returns {yfinance_ticker: [prices...]}, [date_strings]."""
    date_strings = [d.isoformat() for d in dates]
    all_prices: dict[str, list[float]] = {}
    for yticker, (start, mu, sigma, _sector, _mcap) in TICKER_PARAMS.items():
        currency = TICKER_CURRENCY[yticker]
        raw = generate_gbm_series(rng, start, mu, sigma, len(dates))
        all_prices[yticker] = [round_price(p, currency) for p in raw]
    return all_prices, date_strings


def build_fx_rates(
    rng: np.random.Generator, dates: list[date]
) -> dict[str, dict[str, float]]:
    """Returns {date_string: {currency: rate}}."""
    n = len(dates)
    series: dict[str, list[float]] = {}
    for currency, (start_rate, sigma) in FX_PARAMS.items():
        raw = generate_gbm_series(rng, start_rate, 0.0, sigma, n)
        decimals = 0 if currency == "JPY" else 4
        series[currency] = [round(r, decimals) for r in raw]

    rates: dict[str, dict[str, float]] = {}
    for i, d in enumerate(dates):
        rates[d.isoformat()] = {cur: series[cur][i] for cur in FX_PARAMS}
    return rates


def build_fetch_prices_response(
    prices: dict[str, list[float]], date_strings: list[str]
) -> dict:
    """Build /fetch-prices response shape."""
    prices_section: dict[str, list[dict]] = {}
    metadata_section: dict[str, dict] = {}

    for yticker, price_list in prices.items():
        prices_section[yticker] = [
            {"date": date_strings[i], "close": price_list[i]}
            for i in range(len(date_strings))
        ]
        params = TICKER_PARAMS[yticker]
        metadata_section[yticker] = {
            "currency": TICKER_CURRENCY[yticker],
            "name": next(p["name"] for p in POSITIONS if p["yfinance_ticker"] == yticker),
            "market_cap": params[4],
            "sector": params[3],
        }

    return {"prices": prices_section, "metadata": metadata_section, "errors": {}}


def build_optimize_request(
    prices: dict[str, list[float]],
    date_strings: list[str],
    fx_rates: dict[str, dict[str, float]],
) -> dict:
    """Build /optimize request with EUR-converted prices."""
    # Convert all prices to EUR
    eur_prices: dict[str, list[float]] = {}
    for yticker, price_list in prices.items():
        currency = TICKER_CURRENCY[yticker]
        if currency == "EUR":
            eur_prices[yticker] = price_list
        else:
            converted = []
            for i, ds in enumerate(date_strings):
                fx_rate = fx_rates[ds][currency]
                converted.append(round(price_list[i] / fx_rate, 4))
            eur_prices[yticker] = converted

    # Prices dict for /optimize: flat lists + dates key
    opt_prices: dict[str, list] = {"dates": date_strings}
    for yticker, plist in eur_prices.items():
        opt_prices[yticker] = plist

    # Market caps
    market_caps = {
        yticker: params[4]
        for yticker, params in TICKER_PARAMS.items()
        if params[4] > 0
    }

    # Views for a subset of tickers (partial views): expected annual return,
    # plus the std-dev of the scenario outcomes behind it.
    views = {
        "GOOG": 0.09,
        "NVDA": 0.15,
        "LULU": 0.08,
        "CSU.TO": 0.12,
        "BRK-B": 0.075,
        "MOH": 0.07,
        "HOOD": 0.14,
        "4975.T": 0.10,
    }
    view_stddevs = {
        "GOOG": 0.25,
        "NVDA": 0.45,
        "LULU": 0.35,
        "CSU.TO": 0.22,
        "BRK-B": 0.15,
        "MOH": 0.30,
        "HOOD": 0.60,
        "4975.T": 0.30,
    }

    return {
        "algorithm": "BLACK_LITTERMAN",
        "prices": opt_prices,
        "market_caps": market_caps,
        "views": views,
        "view_stddevs": view_stddevs,
        "risk_free_rate": 0.035,
        "tau": 0.05,
        "kelly_fraction": 0.5,
        "constraints": {
            "min_weight": 0.01,
            "max_weight": 0.08,
            "long_only": True,
        },
        "sectors": {yticker: params[3] for yticker, params in TICKER_PARAMS.items()},
        "covariance_method": "ledoit_wolf",
    }


def build_golden_optimize_response(tickers: list[str]) -> dict:
    """Placeholder golden /optimize response. To be regenerated in Block 1d."""
    n = len(tickers)
    # Equal weights as placeholder, summing to 1.0
    base_weight = round(1.0 / n, 6)
    weights = {t: base_weight for t in tickers}
    # Adjust last ticker to ensure exact sum
    remainder = round(1.0 - base_weight * (n - 1), 6)
    weights[tickers[-1]] = remainder

    # Synthetic efficient frontier (50 points)
    frontier = [
        {"risk": round(0.05 + i * 0.006, 4), "return": round(0.02 + i * 0.004, 4)}
        for i in range(50)
    ]

    return {
        "weights": weights,
        "metrics": {
            "expected_annual_return": 0.092,
            "annual_volatility": 0.178,
            "sharpe_ratio": 0.32,
            "cvar_95": -0.028,
        },
        "cash_weight": 0.0,
        "view_confidences": {},
        "efficient_frontier": frontier,
        "correlation_matrix": {},
        "computation_ms": 0,
        "_generated_by": "block0-placeholder -- regenerate in block 1d",
    }


def build_wiremock_stubs() -> list[tuple[str, dict]]:
    """Returns [(relative_path, json_content)] for WireMock mapping files."""
    stubs = []

    # optimize 200
    stubs.append((
        "mappings/optimize-200.json",
        {
            "request": {
                "method": "POST",
                "urlPath": "/optimize",
                "headers": {"Content-Type": {"equalTo": "application/json"}},
            },
            "response": {
                "status": 200,
                "bodyFileName": "optimize-response.json",
                "headers": {"Content-Type": "application/json"},
            },
        },
    ))

    # optimize 422
    stubs.append((
        "mappings/optimize-422.json",
        {
            "priority": 2,
            "request": {
                "method": "POST",
                "urlPath": "/optimize",
                "bodyPatterns": [{"contains": "\"INVALID\""}],
            },
            "response": {
                "status": 422,
                "bodyFileName": "optimize-error-response.json",
                "headers": {"Content-Type": "application/json"},
            },
        },
    ))

    # fetch-prices 200
    stubs.append((
        "mappings/fetch-prices-200.json",
        {
            "request": {
                "method": "POST",
                "urlPath": "/fetch-prices",
                "headers": {"Content-Type": {"equalTo": "application/json"}},
            },
            "response": {
                "status": 200,
                "bodyFileName": "fetch-prices-response.json",
                "headers": {"Content-Type": "application/json"},
            },
        },
    ))

    # fetch-fx-rates 200
    stubs.append((
        "mappings/fetch-fx-rates-200.json",
        {
            "request": {
                "method": "POST",
                "urlPath": "/fetch-fx-rates",
                "headers": {"Content-Type": {"equalTo": "application/json"}},
            },
            "response": {
                "status": 200,
                "bodyFileName": "fetch-fx-rates-response.json",
                "headers": {"Content-Type": "application/json"},
            },
        },
    ))

    return stubs


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print(f"  wrote {path.relative_to(PROJECT_ROOT)}")


def write_csv(path: Path, header: list[str], rows: list[list]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(rows)
    print(f"  wrote {path.relative_to(PROJECT_ROOT)}")


def write_to_both(filename: str, data: dict) -> None:
    """Write JSON to both optimizer and web fixture directories."""
    write_json(OPTIMIZER_FIXTURES / filename, data)
    write_json(WEB_FIXTURES / filename, data)


def write_csv_to_both(
    filename: str, header: list[str], rows: list[list]
) -> None:
    write_csv(OPTIMIZER_FIXTURES / filename, header, rows)
    write_csv(WEB_FIXTURES / filename, header, rows)


def main() -> None:
    print("Generating Kernfolio test fixtures (seed=42)...\n")
    rng = np.random.default_rng(SEED)

    # 1. Trading days
    dates = generate_trading_days(START_DATE, END_DATE)
    date_strings = [d.isoformat() for d in dates]
    print(f"Trading days: {len(dates)} ({dates[0]} to {dates[-1]})")

    # 2. Portfolio JSON
    portfolio = build_portfolio_json()
    write_to_both("test-portfolio.json", portfolio)

    # 3. Price series
    prices, _ = build_prices_csv(rng, dates)
    equity_tickers = sorted(prices.keys())
    header = ["date"] + equity_tickers
    rows = []
    for i, ds in enumerate(date_strings):
        rows.append([ds] + [prices[t][i] for t in equity_tickers])
    write_csv_to_both("prices.csv", header, rows)

    # 4. FX rates
    fx_rates = build_fx_rates(rng, dates)
    write_to_both("fx-rates.json", {"rates": fx_rates})

    # 5. Fetch-prices response
    fetch_prices_resp = build_fetch_prices_response(prices, date_strings)
    write_to_both("fetch-prices-response.json", fetch_prices_resp)

    # 6. Optimize request
    optimize_req = build_optimize_request(prices, date_strings, fx_rates)
    write_to_both("optimize-request.json", optimize_req)

    # 7. Golden optimize response (placeholder)
    golden = build_golden_optimize_response(equity_tickers)
    write_to_both("golden-optimize-bl-response.json", golden)

    # 8. WireMock stubs (web only)
    stubs = build_wiremock_stubs()
    for rel_path, content in stubs:
        write_json(WEB_FIXTURES / "wiremock" / rel_path, content)

    # WireMock __files (response bodies)
    write_json(WIREMOCK_FILES / "optimize-response.json", golden)
    write_json(WIREMOCK_FILES / "optimize-error-response.json", {
        "error": "OptimizationError",
        "message": "Covariance matrix is singular. Need more price history.",
        "detail": "Matrix condition number exceeds threshold.",
    })
    write_json(WIREMOCK_FILES / "fetch-prices-response.json", fetch_prices_resp)
    write_json(WIREMOCK_FILES / "fetch-fx-rates-response.json", {"rates": fx_rates})

    print("\nDone. All fixtures generated successfully.")


if __name__ == "__main__":
    main()
