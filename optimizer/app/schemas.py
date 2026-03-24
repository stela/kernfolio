"""Pydantic request/response schemas for the Kernfolio Optimizer API."""

from pydantic import BaseModel


# ── /fetch-prices ─────────────────────────────────────────────────────


class PriceFetchRequest(BaseModel):
    tickers: list[str]
    start_date: str
    end_date: str


class PriceEntry(BaseModel):
    date: str
    close: float


class TickerMetadata(BaseModel):
    currency: str
    name: str
    market_cap: int
    sector: str


class PriceFetchResponse(BaseModel):
    prices: dict[str, list[PriceEntry]]
    metadata: dict[str, TickerMetadata]
    errors: dict[str, str]


# ── /fetch-fx-rates ──────────────────────────────────────────────────


class FxRateFetchRequest(BaseModel):
    base: str
    currencies: list[str]
    start_date: str
    end_date: str


class FxRateFetchResponse(BaseModel):
    rates: dict[str, dict[str, float]]
