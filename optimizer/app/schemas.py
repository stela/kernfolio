"""Pydantic request/response schemas for the Kernfolio Optimizer API."""

from pydantic import BaseModel, ConfigDict, Field


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


# ── /search-tickers ─────────────────────────────────────────────────


class TickerSearchRequest(BaseModel):
    query: str
    limit: int = 10


class TickerSearchResult(BaseModel):
    symbol: str
    shortname: str | None = None
    longname: str | None = None
    exchange: str | None = None
    quote_type: str | None = None
    currency: str | None = None


class TickerSearchResponse(BaseModel):
    results: list[TickerSearchResult]


# ── /fetch-fx-rates ──────────────────────────────────────────────────


class FxRateFetchRequest(BaseModel):
    base: str
    currencies: list[str]
    start_date: str
    end_date: str


class FxRateFetchResponse(BaseModel):
    rates: dict[str, dict[str, float]]


# ── /optimize ───────────────────────────────────────────────────────


class Constraints(BaseModel):
    min_weight: float
    max_weight: float
    long_only: bool
    sector_constraints: dict[str, float] | None = None


class OptimizeRequest(BaseModel):
    algorithm: str
    prices: dict[str, list[str] | list[float]]
    market_caps: dict[str, float]
    views: dict[str, float]
    confidences: dict[str, float]
    risk_free_rate: float
    tau: float
    kelly_fraction: float
    constraints: Constraints
    sectors: dict[str, str]
    covariance_method: str


class FrontierPoint(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    risk: float
    ret: float = Field(alias="return", serialization_alias="return")


class OptimizeMetrics(BaseModel):
    expected_annual_return: float
    annual_volatility: float
    sharpe_ratio: float
    cvar_95: float


class OptimizeResponse(BaseModel):
    weights: dict[str, float]
    metrics: OptimizeMetrics
    efficient_frontier: list[FrontierPoint]
    correlation_matrix: dict[str, dict[str, float]]
    computation_ms: int


class OptimizationErrorResponse(BaseModel):
    error: str
    message: str
    detail: str
