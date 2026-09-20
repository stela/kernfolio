"""Business logic for /fetch-prices and /fetch-fx-rates endpoints."""

import os

import httpx2
import pandas as pd
import yfinance as yf
from fastapi import HTTPException

from app.schemas import (
    FxRateFetchRequest,
    FxRateFetchResponse,
    PriceEntry,
    PriceFetchRequest,
    PriceFetchResponse,
    TickerMetadata,
    TickerSearchRequest,
    TickerSearchResponse,
    TickerSearchResult,
)

FRANKFURTER_BASE_URL = os.environ.get(
    "FRANKFURTER_BASE_URL", "https://api.frankfurter.dev"
)


# ── /fetch-prices ─────────────────────────────────────────────────────


def fetch_prices(request: PriceFetchRequest) -> PriceFetchResponse:
    """Fetch historical close prices and metadata from yfinance."""
    prices: dict[str, list[PriceEntry]] = {}
    metadata: dict[str, TickerMetadata] = {}
    errors: dict[str, str] = {}

    if not request.tickers:
        return PriceFetchResponse(prices=prices, metadata=metadata, errors=errors)

    df = yf.download(
        tickers=request.tickers,
        start=request.start_date,
        end=request.end_date,
        group_by="ticker",
        auto_adjust=True,
        progress=False,
    )

    if df.empty:
        for ticker in request.tickers:
            errors[ticker] = f"No data found for ticker {ticker}"
        return PriceFetchResponse(prices=prices, metadata=metadata, errors=errors)

    multi = isinstance(df.columns, pd.MultiIndex)

    for ticker in request.tickers:
        try:
            if multi:
                if ticker not in df.columns.get_level_values(0):
                    errors[ticker] = f"No data found for ticker {ticker}"
                    continue
                series = df[ticker]["Close"]
            else:
                # Single-ticker download returns flat columns
                if len(request.tickers) == 1:
                    series = df["Close"]
                else:
                    errors[ticker] = f"No data found for ticker {ticker}"
                    continue

            series = series.dropna()
            if series.empty:
                errors[ticker] = f"No data found for ticker {ticker}"
                continue

            prices[ticker] = [
                PriceEntry(date=dt.strftime("%Y-%m-%d"), close=round(float(val), 2))
                for dt, val in series.items()
            ]
        except Exception as exc:
            errors[ticker] = str(exc)

    # Fetch metadata for successful tickers
    for ticker in prices:
        try:
            info = yf.Ticker(ticker).info
            metadata[ticker] = TickerMetadata(
                currency=info.get("currency", "Unknown"),
                name=info.get("longName") or info.get("shortName", "Unknown"),
                market_cap=info.get("marketCap", 0) or 0,
                sector=info.get("sector", "Unknown"),
            )
        except Exception:
            metadata[ticker] = TickerMetadata(
                currency="Unknown", name="Unknown", market_cap=0, sector="Unknown"
            )

    return PriceFetchResponse(prices=prices, metadata=metadata, errors=errors)


# ── /search-tickers ─────────────────────────────────────────────────

_SEARCH_QUOTE_TYPES = {"EQUITY", "ETF", "MUTUALFUND", "INDEX"}


def search_tickers(request: TickerSearchRequest) -> TickerSearchResponse:
    """Search Yahoo Finance for tickers matching a free-text query."""
    query = request.query.strip()
    if not query:
        return TickerSearchResponse(results=[])

    limit = max(1, min(request.limit, 20))

    try:
        search = yf.Search(query, max_results=limit)
        raw_quotes = getattr(search, "quotes", None) or []
    except Exception:
        return TickerSearchResponse(results=[])

    results: list[TickerSearchResult] = []
    for quote in raw_quotes:
        quote_type = (quote.get("quoteType") or "").upper()
        if quote_type and quote_type not in _SEARCH_QUOTE_TYPES:
            continue
        symbol = quote.get("symbol")
        if not symbol:
            continue
        results.append(TickerSearchResult(
            symbol=symbol,
            shortname=quote.get("shortname"),
            longname=quote.get("longname"),
            exchange=quote.get("exchDisp") or quote.get("exchange"),
            quote_type=quote_type or None,
            currency=quote.get("currency"),
        ))
        if len(results) >= limit:
            break

    return TickerSearchResponse(results=results)


# ── /fetch-fx-rates ──────────────────────────────────────────────────


async def fetch_fx_rates(request: FxRateFetchRequest) -> FxRateFetchResponse:
    """Fetch historical FX rates from the Frankfurter API."""
    currencies_csv = ",".join(request.currencies)
    url = (
        f"{FRANKFURTER_BASE_URL}/v1/"
        f"{request.start_date}..{request.end_date}"
        f"?from={request.base}&to={currencies_csv}"
    )

    try:
        async with httpx2.AsyncClient() as client:
            resp = await client.get(url, timeout=30.0)
            resp.raise_for_status()
    except httpx2.HTTPStatusError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Frankfurter API returned {exc.response.status_code}",
        ) from exc
    except httpx2.HTTPError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Failed to reach Frankfurter API: {exc}",
        ) from exc

    data = resp.json()
    return FxRateFetchResponse(rates=data["rates"])
