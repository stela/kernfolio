"""Tests for POST /search-tickers with mocked yfinance.Search."""

from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def _mock_search(quotes):
    mock = MagicMock()
    mock.quotes = quotes
    return mock


@patch("app.fetchers.yf.Search")
def test_search_tickers_returns_filtered_results(mock_search_cls):
    mock_search_cls.return_value = _mock_search([
        {
            "symbol": "AAPL",
            "shortname": "Apple Inc.",
            "longname": "Apple Inc.",
            "exchDisp": "NASDAQ",
            "quoteType": "EQUITY",
            "currency": "USD",
        },
        {
            "symbol": "AAPL.MX",
            "shortname": "Apple Inc.",
            "exchDisp": "Mexico",
            "quoteType": "EQUITY",
            "currency": "MXN",
        },
        {
            "symbol": "BTC-USD",
            "shortname": "Bitcoin",
            "quoteType": "CRYPTOCURRENCY",
        },
    ])

    resp = client.post("/search-tickers", json={"query": "apple", "limit": 10})
    assert resp.status_code == 200
    data = resp.json()
    symbols = [r["symbol"] for r in data["results"]]
    assert symbols == ["AAPL", "AAPL.MX"]
    assert data["results"][0]["exchange"] == "NASDAQ"
    assert data["results"][0]["currency"] == "USD"


@patch("app.fetchers.yf.Search")
def test_search_tickers_honours_limit(mock_search_cls):
    mock_search_cls.return_value = _mock_search([
        {"symbol": f"T{i}", "quoteType": "EQUITY"} for i in range(30)
    ])

    resp = client.post("/search-tickers", json={"query": "x", "limit": 3})
    assert resp.status_code == 200
    assert len(resp.json()["results"]) == 3


def test_search_tickers_empty_query():
    resp = client.post("/search-tickers", json={"query": "  ", "limit": 10})
    assert resp.status_code == 200
    assert resp.json() == {"results": []}


@patch("app.fetchers.yf.Search", side_effect=RuntimeError("network down"))
def test_search_tickers_swallows_upstream_errors(_mock):
    resp = client.post("/search-tickers", json={"query": "apple", "limit": 10})
    assert resp.status_code == 200
    assert resp.json() == {"results": []}
