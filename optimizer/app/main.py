from fastapi import FastAPI

from app.fetchers import fetch_fx_rates, fetch_prices
from app.schemas import (
    FxRateFetchRequest,
    FxRateFetchResponse,
    PriceFetchRequest,
    PriceFetchResponse,
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
