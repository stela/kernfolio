"""Pydantic request/response schemas for the Kernfolio Optimizer API."""

from pydantic import BaseModel, ConfigDict, Field, model_validator


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
    # Expected annual return per ticker (fraction), and the std-dev of the
    # user's scenario outcomes for that return. Every view needs both.
    views: dict[str, float]
    view_stddevs: dict[str, float]
    risk_free_rate: float
    tau: float = Field(gt=0)
    # 1.0 = full Kelly, 0.5 = half Kelly. Lower holds more cash.
    kelly_fraction: float = Field(gt=0, le=1)
    constraints: Constraints
    sectors: dict[str, str]
    covariance_method: str

    @model_validator(mode="after")
    def _views_have_positive_stddevs(self) -> "OptimizeRequest":
        for ticker in self.views:
            stddev = self.view_stddevs.get(ticker)
            if stddev is None or stddev <= 0:
                raise ValueError(f"View on {ticker} needs a positive view_stddevs entry")
        return self


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
    # Fractions of the whole portfolio: weights sum to 1 - cash_weight.
    weights: dict[str, float]
    cash_weight: float
    # Weight each view got against the market prior (0..1), for display.
    view_confidences: dict[str, float]
    metrics: OptimizeMetrics
    efficient_frontier: list[FrontierPoint]
    correlation_matrix: dict[str, dict[str, float]]
    computation_ms: int


class OptimizationErrorResponse(BaseModel):
    error: str
    message: str
    detail: str
