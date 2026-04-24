"""Tests for the POST /optimize endpoint (Black-Litterman + MVO)."""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
FIXTURES = Path(__file__).parent / "fixtures"


def _load_fixture(name: str):
    with open(FIXTURES / name) as f:
        return json.load(f)


# ── Integration test with full fixture ────────────────────────────────


def test_optimize_bl_from_fixture():
    request = _load_fixture("optimize-request.json")
    resp = client.post("/optimize", json=request)
    assert resp.status_code == 200, resp.text
    data = resp.json()

    # Weights
    price_tickers = set(request["prices"].keys()) - {"dates"}
    assert set(data["weights"].keys()) == price_tickers
    assert abs(sum(data["weights"].values()) - 1.0) < 0.001

    min_w = request["constraints"]["min_weight"]
    max_w = request["constraints"]["max_weight"]
    for ticker, w in data["weights"].items():
        assert w >= min_w - 0.001, f"{ticker} weight {w} below min {min_w}"
        assert w <= max_w + 0.001, f"{ticker} weight {w} above max {max_w}"

    # Metrics
    m = data["metrics"]
    assert m["sharpe_ratio"] > 0
    assert m["cvar_95"] < 0
    assert 0 < m["annual_volatility"] < 1.0
    assert -0.5 < m["expected_annual_return"] < 1.0

    # Efficient frontier
    assert len(data["efficient_frontier"]) >= 20
    for pt in data["efficient_frontier"]:
        assert "risk" in pt
        assert "return" in pt

    # Correlation matrix
    assert len(data["correlation_matrix"]) == len(price_tickers)
    first_ticker = next(iter(data["correlation_matrix"]))
    assert len(data["correlation_matrix"][first_ticker]) == len(price_tickers)
    # Diagonal should be 1.0
    for t in data["correlation_matrix"]:
        assert abs(data["correlation_matrix"][t][t] - 1.0) < 0.0001

    assert data["computation_ms"] > 0


# ── Golden file regression ────────────────────────────────────────────


def test_optimize_bl_matches_golden():
    request = _load_fixture("optimize-request.json")
    golden = _load_fixture("golden-optimize-bl-response.json")

    resp = client.post("/optimize", json=request)
    assert resp.status_code == 200
    data = resp.json()

    # Compare weights within ±0.5%
    for ticker, expected_w in golden["weights"].items():
        actual_w = data["weights"].get(ticker, 0.0)
        assert abs(actual_w - expected_w) < 0.005, (
            f"{ticker}: expected {expected_w}, got {actual_w}"
        )

    # Compare metrics within 10% relative tolerance
    for key in ["expected_annual_return", "annual_volatility", "sharpe_ratio"]:
        expected = golden["metrics"][key]
        actual = data["metrics"][key]
        if abs(expected) > 0.001:
            assert abs(actual - expected) / abs(expected) < 0.10, (
                f"metrics.{key}: expected {expected}, got {actual}"
            )


# ── Small synthetic portfolio ─────────────────────────────────────────


def test_optimize_bl_small_portfolio():
    """Minimal 3-ticker test to verify optimizer works with simple data."""
    import numpy as np

    rng = np.random.default_rng(42)
    n_days = 252
    dates = [f"2024-{(i // 22) + 1:02d}-{(i % 22) + 1:02d}" for i in range(n_days)]

    # Generate random walk prices
    prices_a = (100 * np.cumprod(1 + rng.normal(0.0004, 0.015, n_days))).tolist()
    prices_b = (80 * np.cumprod(1 + rng.normal(0.0003, 0.012, n_days))).tolist()
    prices_c = (50 * np.cumprod(1 + rng.normal(0.0005, 0.02, n_days))).tolist()

    request = {
        "algorithm": "BLACK_LITTERMAN",
        "prices": {"dates": dates, "AAA": prices_a, "BBB": prices_b, "CCC": prices_c},
        "market_caps": {"AAA": 1e12, "BBB": 5e11, "CCC": 2e11},
        "views": {"AAA": 0.10},
        "confidences": {"AAA": 0.8},
        "risk_free_rate": 0.035,
        "tau": 0.05,
        "kelly_fraction": 0.5,
        "constraints": {"min_weight": 0.05, "max_weight": 0.60, "long_only": True},
        "sectors": {"AAA": "Tech", "BBB": "Finance", "CCC": "Health"},
        "covariance_method": "ledoit_wolf",
    }

    resp = client.post("/optimize", json=request)
    assert resp.status_code == 200, resp.text
    data = resp.json()

    assert set(data["weights"].keys()) == {"AAA", "BBB", "CCC"}
    assert abs(sum(data["weights"].values()) - 1.0) < 0.001
    assert len(data["efficient_frontier"]) >= 10
    assert len(data["correlation_matrix"]) == 3


# ── Validation / error tests ──────────────────────────────────────────


def test_optimize_missing_required_field():
    resp = client.post("/optimize", json={"algorithm": "BLACK_LITTERMAN"})
    assert resp.status_code == 422


def test_optimize_invalid_algorithm():
    request = _load_fixture("optimize-request.json")
    request["algorithm"] = "UNSUPPORTED"
    resp = client.post("/optimize", json=request)
    assert resp.status_code == 500
    # Body is the raw {error, message, detail} dict — see main.py for why
    # we deliberately don't wrap it under "detail".
    body = resp.json()
    assert body["error"] == "OptimizationError"
    assert "Unsupported algorithm" in body["message"]


# ── Algorithm × asset-count coverage ──────────────────────────────────
#
# Each supported algorithm gets tested against 0, 1, and 2+ non-currency
# assets. The purpose is to guarantee that no call path silently swallows
# an error or produces garbage — every case must either return a valid
# response or fail with a clear, user-actionable message.


def _synthetic_request(algorithm: str, n_tickers: int, max_weight: float = 0.60) -> dict:
    """Build an optimize request with n_tickers randomly-walked price series."""
    import numpy as np

    rng = np.random.default_rng(42)
    n_days = 252
    dates = [
        f"2024-{(i // 22) + 1:02d}-{(i % 22) + 1:02d}" for i in range(n_days)
    ]
    tickers = [f"T{i}" for i in range(n_tickers)]

    prices: dict = {"dates": dates}
    market_caps: dict = {}
    sectors: dict = {}
    for i, t in enumerate(tickers):
        prices[t] = (100 * np.cumprod(1 + rng.normal(0.0004, 0.015, n_days))).tolist()
        market_caps[t] = (10 ** (11 + i))
        sectors[t] = "Tech"

    return {
        "algorithm": algorithm,
        "prices": prices,
        "market_caps": market_caps,
        "views": {},
        "confidences": {},
        "risk_free_rate": 0.03,
        "tau": 0.05,
        "kelly_fraction": 0.5,
        "constraints": {
            "min_weight": 0.0,
            "max_weight": max_weight,
            "long_only": True,
        },
        "sectors": sectors,
        "covariance_method": "ledoit_wolf",
    }


# ── BLACK_LITTERMAN ─────────────────


def test_bl_zero_tickers_returns_clear_error():
    resp = client.post("/optimize", json=_synthetic_request("BLACK_LITTERMAN", 0))
    assert resp.status_code == 500
    body = resp.json()
    assert "at least 1 asset" in body["message"].lower() or "requires at least" in body["message"].lower()


def test_bl_one_ticker_with_fractional_cap_is_infeasible():
    # Default max_weight=0.40 with 1 asset can't sum to 1.0. Must fail
    # with a user-friendly message, not the raw solver's "infeasible" cry.
    resp = client.post(
        "/optimize", json=_synthetic_request("BLACK_LITTERMAN", 1, max_weight=0.40)
    )
    assert resp.status_code == 500
    body = resp.json()
    msg = body["message"].lower()
    assert "infeasible" in msg
    assert "max_weight" in msg


def test_bl_one_ticker_with_full_cap_succeeds():
    # max_weight=1.0 permits 100% allocation; 1-asset portfolio is
    # mathematically degenerate but should not error — you get 100%
    # weight in the single asset.
    resp = client.post(
        "/optimize", json=_synthetic_request("BLACK_LITTERMAN", 1, max_weight=1.0)
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert set(data["weights"].keys()) == {"T0"}
    assert abs(data["weights"]["T0"] - 1.0) < 0.001


def test_bl_two_tickers_succeeds():
    resp = client.post("/optimize", json=_synthetic_request("BLACK_LITTERMAN", 2))
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert set(data["weights"].keys()) == {"T0", "T1"}
    assert abs(sum(data["weights"].values()) - 1.0) < 0.001


# ── MAX_SHARPE / MIN_VARIANCE / RISK_PARITY: unsupported today ──────
#
# These are listed in the UI form's algorithm dropdown but the Python
# optimizer only implements BLACK_LITTERMAN. Each must fail fast with
# an "Unsupported algorithm" message — not hang, crash, or silently
# accept and return zeros.


@pytest.mark.parametrize(
    "algorithm",
    ["MAX_SHARPE", "MIN_VARIANCE", "RISK_PARITY"],
)
def test_unsupported_algorithm_with_valid_tickers_fails_cleanly(algorithm):
    resp = client.post("/optimize", json=_synthetic_request(algorithm, 3))
    assert resp.status_code == 500
    body = resp.json()
    assert body["error"] == "OptimizationError"
    assert "Unsupported algorithm" in body["message"]
    assert algorithm in body["message"]


@pytest.mark.parametrize(
    "algorithm",
    ["MAX_SHARPE", "MIN_VARIANCE", "RISK_PARITY"],
)
def test_unsupported_algorithm_with_zero_tickers_fails_cleanly(algorithm):
    # Feasibility guard runs before the algorithm dispatch, so the 0-asset
    # case reports "at least 1 asset" regardless of which unsupported
    # algorithm was requested. That's fine — the first error encountered
    # is still user-actionable.
    resp = client.post("/optimize", json=_synthetic_request(algorithm, 0))
    assert resp.status_code == 500
    body = resp.json()
    assert body["error"] == "OptimizationError"
