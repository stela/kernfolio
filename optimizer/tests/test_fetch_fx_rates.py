"""Tests for POST /fetch-fx-rates with mocked httpx2."""

import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx2
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
FIXTURES = Path(__file__).parent / "fixtures"

SAMPLE_FRANKFURTER_RESPONSE = {
    "amount": 1,
    "base": "EUR",
    "start_date": "2024-01-02",
    "end_date": "2024-01-04",
    "rates": {
        "2024-01-02": {"USD": 1.1050, "CAD": 1.4620, "JPY": 156.50},
        "2024-01-03": {"USD": 1.1055, "CAD": 1.4630, "JPY": 156.80},
        "2024-01-04": {"USD": 1.1048, "CAD": 1.4615, "JPY": 156.42},
    },
}

FX_REQUEST = {
    "base": "EUR",
    "currencies": ["USD", "CAD", "JPY"],
    "start_date": "2024-01-02",
    "end_date": "2024-01-04",
}


def _mock_httpx2_client(response_json, status_code=200):
    """Create a mock httpx2.AsyncClient context manager."""
    mock_response = MagicMock()
    mock_response.status_code = status_code
    mock_response.json.return_value = response_json
    if status_code >= 400:
        mock_response.raise_for_status.side_effect = httpx2.HTTPStatusError(
            message=f"HTTP {status_code}",
            request=MagicMock(),
            response=mock_response,
        )
    else:
        mock_response.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.get = AsyncMock(return_value=mock_response)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    return mock_client


@patch("app.fetchers.httpx2.AsyncClient")
def test_fetch_fx_rates_success(mock_client_cls):
    mock_client_cls.return_value = _mock_httpx2_client(SAMPLE_FRANKFURTER_RESPONSE)
    resp = client.post("/fetch-fx-rates", json=FX_REQUEST)
    assert resp.status_code == 200
    data = resp.json()
    assert "rates" in data
    assert len(data["rates"]) == 3
    assert set(data["rates"]["2024-01-02"].keys()) == {"USD", "CAD", "JPY"}
    assert data["rates"]["2024-01-02"]["USD"] == 1.1050


@patch("app.fetchers.httpx2.AsyncClient")
def test_fetch_fx_rates_matches_fixture(mock_client_cls):
    with open(FIXTURES / "fx-rates.json") as f:
        fixture = json.load(f)
    frankfurter_response = {
        "amount": 1,
        "base": "EUR",
        "start_date": "2023-03-15",
        "end_date": "2026-03-15",
        "rates": fixture["rates"],
    }
    mock_client_cls.return_value = _mock_httpx2_client(frankfurter_response)
    resp = client.post("/fetch-fx-rates", json={
        "base": "EUR",
        "currencies": ["USD", "CAD", "JPY", "GBP", "MXN", "HKD"],
        "start_date": "2023-03-15",
        "end_date": "2026-03-15",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert data["rates"] == fixture["rates"]


@patch("app.fetchers.httpx2.AsyncClient")
def test_fetch_fx_rates_upstream_error(mock_client_cls):
    mock_client_cls.return_value = _mock_httpx2_client({}, status_code=500)
    resp = client.post("/fetch-fx-rates", json=FX_REQUEST)
    assert resp.status_code == 502
    assert "500" in resp.json()["detail"]


@patch("app.fetchers.httpx2.AsyncClient")
def test_fetch_fx_rates_connection_error(mock_client_cls):
    mock_client = AsyncMock()
    mock_client.get = AsyncMock(side_effect=httpx2.ConnectError("Connection refused"))
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client_cls.return_value = mock_client
    resp = client.post("/fetch-fx-rates", json=FX_REQUEST)
    assert resp.status_code == 502
    assert "Frankfurter" in resp.json()["detail"]


@patch("app.fetchers.httpx2.AsyncClient")
def test_fetch_fx_rates_configurable_url(mock_client_cls, monkeypatch):
    monkeypatch.setenv("FRANKFURTER_BASE_URL", "http://localhost:8082")
    # Reload the module to pick up the new env var
    import importlib
    import app.fetchers
    importlib.reload(app.fetchers)

    mock_client = _mock_httpx2_client(SAMPLE_FRANKFURTER_RESPONSE)
    mock_client_cls.return_value = mock_client
    resp = client.post("/fetch-fx-rates", json=FX_REQUEST)
    assert resp.status_code == 200

    # Verify the URL used starts with the configured base
    call_args = mock_client.get.call_args
    url = call_args[0][0]
    assert url.startswith("http://localhost:8082/v1/")

    # Restore default
    importlib.reload(app.fetchers)
