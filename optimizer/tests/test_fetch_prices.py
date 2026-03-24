"""Tests for POST /fetch-prices with mocked yfinance."""

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
FIXTURES = Path(__file__).parent / "fixtures"


def _make_multi_ticker_df(tickers_data: dict[str, list[float]], dates: list[str]):
    """Build a MultiIndex DataFrame like yf.download with group_by='ticker'."""
    index = pd.DatetimeIndex(dates)
    arrays = []
    for ticker, closes in tickers_data.items():
        for col in ["Close", "Volume"]:
            arrays.append((ticker, col))
    columns = pd.MultiIndex.from_tuples(arrays)
    data = {}
    for ticker, closes in tickers_data.items():
        data[(ticker, "Close")] = closes
        data[(ticker, "Volume")] = [1000] * len(closes)
    return pd.DataFrame(data, index=index, columns=columns)


def _make_single_ticker_df(closes: list[float], dates: list[str]):
    """Build a flat DataFrame like yf.download for a single ticker."""
    index = pd.DatetimeIndex(dates)
    return pd.DataFrame({"Close": closes, "Volume": [1000] * len(closes)}, index=index)


def _mock_ticker_info(ticker: str):
    """Return a mock yf.Ticker with .info populated."""
    info_map = {
        "GOOG": {
            "currency": "USD",
            "longName": "Alphabet Inc.",
            "marketCap": 1850000000000,
            "sector": "Technology",
        },
        "AMZN": {
            "currency": "USD",
            "longName": "Amazon.com, Inc.",
            "marketCap": 2100000000000,
            "sector": "Consumer Cyclical",
        },
        "4256.T": {
            "currency": "JPY",
            "longName": "CYND Co., Ltd.",
            "marketCap": 52000000000,
            "sector": "Technology",
        },
    }
    mock = MagicMock()
    mock.info = info_map.get(ticker, {
        "currency": "Unknown",
        "longName": "Unknown",
        "marketCap": 0,
        "sector": "Unknown",
    })
    return mock


DATES = ["2024-01-02", "2024-01-03", "2024-01-04"]


@patch("app.fetchers.yf.Ticker", side_effect=_mock_ticker_info)
@patch("app.fetchers.yf.download")
def test_fetch_prices_multiple_tickers(mock_download, _mock_ticker):
    mock_download.return_value = _make_multi_ticker_df(
        {"GOOG": [140.0, 141.5, 142.0], "AMZN": [150.0, 151.0, 152.0]},
        DATES,
    )
    resp = client.post("/fetch-prices", json={
        "tickers": ["GOOG", "AMZN"],
        "start_date": "2024-01-01",
        "end_date": "2024-01-05",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert set(data["prices"].keys()) == {"GOOG", "AMZN"}
    assert len(data["prices"]["GOOG"]) == 3
    assert data["prices"]["GOOG"][0] == {"date": "2024-01-02", "close": 140.0}
    assert set(data["metadata"].keys()) == {"GOOG", "AMZN"}
    assert data["metadata"]["GOOG"]["currency"] == "USD"
    assert data["metadata"]["GOOG"]["market_cap"] == 1850000000000
    assert data["errors"] == {}


@patch("app.fetchers.yf.Ticker", side_effect=_mock_ticker_info)
@patch("app.fetchers.yf.download")
def test_fetch_prices_single_ticker(mock_download, _mock_ticker):
    mock_download.return_value = _make_single_ticker_df(
        [140.0, 141.5, 142.0], DATES,
    )
    resp = client.post("/fetch-prices", json={
        "tickers": ["GOOG"],
        "start_date": "2024-01-01",
        "end_date": "2024-01-05",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "GOOG" in data["prices"]
    assert len(data["prices"]["GOOG"]) == 3
    assert data["metadata"]["GOOG"]["name"] == "Alphabet Inc."
    assert data["errors"] == {}


@patch("app.fetchers.yf.Ticker", side_effect=_mock_ticker_info)
@patch("app.fetchers.yf.download")
def test_fetch_prices_partial_failure(mock_download, _mock_ticker):
    df = _make_multi_ticker_df(
        {"GOOG": [140.0, 141.5, 142.0], "BAD": [np.nan, np.nan, np.nan]},
        DATES,
    )
    mock_download.return_value = df
    resp = client.post("/fetch-prices", json={
        "tickers": ["GOOG", "BAD"],
        "start_date": "2024-01-01",
        "end_date": "2024-01-05",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "GOOG" in data["prices"]
    assert "BAD" not in data["prices"]
    assert "BAD" in data["errors"]


@patch("app.fetchers.yf.download")
def test_fetch_prices_all_fail(mock_download):
    mock_download.return_value = pd.DataFrame()
    resp = client.post("/fetch-prices", json={
        "tickers": ["INVALID1", "INVALID2"],
        "start_date": "2024-01-01",
        "end_date": "2024-01-05",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert data["prices"] == {}
    assert "INVALID1" in data["errors"]
    assert "INVALID2" in data["errors"]


def test_fetch_prices_empty_tickers():
    resp = client.post("/fetch-prices", json={
        "tickers": [],
        "start_date": "2024-01-01",
        "end_date": "2024-01-05",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert data["prices"] == {}
    assert data["metadata"] == {}
    assert data["errors"] == {}


def test_fetch_prices_response_fixture_schema():
    """Verify the fixture file matches the response schema shape."""
    with open(FIXTURES / "fetch-prices-response.json") as f:
        fixture = json.load(f)
    assert "prices" in fixture
    assert "metadata" in fixture
    assert "errors" in fixture
    for ticker, entries in fixture["prices"].items():
        assert len(entries) > 0
        assert "date" in entries[0]
        assert "close" in entries[0]
    for ticker, meta in fixture["metadata"].items():
        assert "currency" in meta
        assert "name" in meta
        assert "market_cap" in meta
        assert "sector" in meta
