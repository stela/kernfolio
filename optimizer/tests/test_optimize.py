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
    detail = resp.json()["detail"]
    assert detail["error"] == "OptimizationError"
