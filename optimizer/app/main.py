import traceback

from fastapi import FastAPI, HTTPException

from app.fetchers import fetch_fx_rates, fetch_prices, search_tickers
from app.optimizer import run_optimization
from app.schemas import (
    FxRateFetchRequest,
    FxRateFetchResponse,
    OptimizeRequest,
    OptimizeResponse,
    PriceFetchRequest,
    PriceFetchResponse,
    TickerSearchRequest,
    TickerSearchResponse,
)

app = FastAPI(title="Kernfolio Optimizer", version="0.1.0")


@app.get("/health")
async def health():
    return {"status": "ok", "version": "0.1.0"}


@app.post("/fetch-prices", response_model=PriceFetchResponse)
def fetch_prices_endpoint(request: PriceFetchRequest):
    return fetch_prices(request)


@app.post("/fetch-fx-rates", response_model=FxRateFetchResponse)
async def fetch_fx_rates_endpoint(request: FxRateFetchRequest):
    return await fetch_fx_rates(request)


@app.post("/search-tickers", response_model=TickerSearchResponse)
def search_tickers_endpoint(request: TickerSearchRequest):
    return search_tickers(request)


@app.post("/optimize", response_model=OptimizeResponse)
def optimize_endpoint(request: OptimizeRequest):
    try:
        return run_optimization(request)
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail={
                "error": "OptimizationError",
                "message": str(exc),
                "detail": traceback.format_exc(),
            },
        ) from exc
