"""Black-Litterman + Mean-Variance optimization logic."""

import time

import numpy as np
import pandas as pd
from pypfopt import BlackLittermanModel, EfficientFrontier, risk_models

from app.schemas import (
    Constraints,
    FrontierPoint,
    OptimizeMetrics,
    OptimizeRequest,
    OptimizeResponse,
)


def _build_prices_dataframe(
    prices: dict[str, list[str] | list[float]],
) -> pd.DataFrame:
    """Convert the request prices dict into a DataFrame with DatetimeIndex."""
    dates = pd.to_datetime(prices["dates"])
    ticker_data = {k: v for k, v in prices.items() if k != "dates"}
    df = pd.DataFrame(ticker_data, index=dates)
    df.sort_index(inplace=True)
    return df


def _compute_covariance(prices_df: pd.DataFrame, method: str) -> pd.DataFrame:
    """Compute the covariance matrix using the specified shrinkage method."""
    if method == "ledoit_wolf":
        return risk_models.CovarianceShrinkage(prices_df, frequency=252).ledoit_wolf()
    raise ValueError(f"Unsupported covariance method: {method}")


def _run_black_litterman(
    prices_df: pd.DataFrame,
    cov_matrix: pd.DataFrame,
    market_caps: dict[str, float],
    views: dict[str, float],
    confidences: dict[str, float],
    risk_free_rate: float,
    tau: float,
) -> tuple[pd.Series, pd.DataFrame]:
    """Run Black-Litterman with Idzorek confidence. Returns posterior returns and covariance."""
    # Filter views/confidences to tickers present in this universe
    filtered_views = {t: v for t, v in views.items() if t in cov_matrix.columns}
    view_confidences = [confidences[t] for t in filtered_views]

    bl = BlackLittermanModel(
        cov_matrix,
        pi="market",
        market_caps=market_caps,
        risk_free_rate=risk_free_rate,
        tau=tau,
        absolute_views=filtered_views,
        omega="idzorek",
        view_confidences=view_confidences,
    )

    posterior_returns = bl.bl_returns()
    posterior_cov = bl.bl_cov()
    return posterior_returns, posterior_cov


def _run_efficient_frontier(
    expected_returns: pd.Series,
    cov_matrix: pd.DataFrame,
    constraints: Constraints,
    risk_free_rate: float,
) -> tuple[dict[str, float], tuple[float, float, float]]:
    """Run max-Sharpe MVO. Returns (clean_weights, (ret, vol, sharpe))."""
    ef = EfficientFrontier(
        expected_returns,
        cov_matrix,
        weight_bounds=(constraints.min_weight, constraints.max_weight),
    )
    ef.max_sharpe(risk_free_rate=risk_free_rate)
    weights = ef.clean_weights()
    performance = ef.portfolio_performance(
        verbose=False, risk_free_rate=risk_free_rate
    )
    return dict(weights), performance


def _merge_weights(
    bl_weights: dict[str, float],
    no_mc_tickers: list[str],
    min_weight: float,
    max_weight: float,
) -> dict[str, float]:
    """Merge BL-optimized weights with min-weight allocations for tickers without market caps."""
    if not no_mc_tickers:
        return bl_weights

    satellite_total = len(no_mc_tickers) * min_weight
    core_budget = 1.0 - satellite_total

    bl_sum = sum(bl_weights.values())
    merged = {}
    if bl_sum > 0:
        for ticker, w in bl_weights.items():
            scaled = w * core_budget / bl_sum
            merged[ticker] = max(min_weight, min(max_weight, scaled))
    else:
        equal = core_budget / len(bl_weights)
        for ticker in bl_weights:
            merged[ticker] = equal

    for ticker in no_mc_tickers:
        merged[ticker] = min_weight

    # Normalize to sum to 1.0
    total = sum(merged.values())
    if total > 0:
        merged = {t: w / total for t, w in merged.items()}

    return merged


def _compute_cvar_95(prices_df: pd.DataFrame, weights: dict[str, float]) -> float:
    """Compute CVaR at 95% confidence from historical returns."""
    tickers = [t for t in weights if t in prices_df.columns]
    returns_df = prices_df[tickers].pct_change().dropna()
    weight_series = pd.Series({t: weights[t] for t in tickers})
    portfolio_returns = returns_df.dot(weight_series)

    var_95 = np.percentile(portfolio_returns, 5)
    tail = portfolio_returns[portfolio_returns <= var_95]
    if len(tail) == 0:
        return float(var_95)
    return float(tail.mean())


def _compute_frontier_points(
    expected_returns: pd.Series,
    cov_matrix: pd.DataFrame,
    constraints: Constraints,
    risk_free_rate: float,
    n_points: int = 50,
) -> list[FrontierPoint]:
    """Compute ~n_points on the efficient frontier."""
    # Find return range
    ef_min = EfficientFrontier(
        expected_returns,
        cov_matrix,
        weight_bounds=(constraints.min_weight, constraints.max_weight),
    )
    ef_min.min_volatility()
    min_perf = ef_min.portfolio_performance(verbose=False, risk_free_rate=risk_free_rate)
    min_ret = min_perf[0]

    # Find the maximum feasible return (maximize return ignoring risk)
    ef_max_ret = EfficientFrontier(
        expected_returns,
        cov_matrix,
        weight_bounds=(constraints.min_weight, constraints.max_weight),
    )
    ef_max_ret.max_quadratic_utility(risk_aversion=0.0001)
    max_perf = ef_max_ret.portfolio_performance(verbose=False, risk_free_rate=risk_free_rate)
    max_ret = max_perf[0]

    # Slightly shrink upper bound to stay within feasible region
    target_returns = np.linspace(min_ret, max_ret * 0.999, n_points)

    points: list[FrontierPoint] = []
    for target in target_returns:
        try:
            ef = EfficientFrontier(
                expected_returns,
                cov_matrix,
                weight_bounds=(constraints.min_weight, constraints.max_weight),
            )
            ef.efficient_return(target)
            perf = ef.portfolio_performance(
                verbose=False, risk_free_rate=risk_free_rate
            )
            points.append(
                FrontierPoint(risk=round(perf[1], 6), ret=round(perf[0], 6))
            )
        except Exception:
            continue

    return points


def _compute_correlation_matrix(
    prices_df: pd.DataFrame,
) -> dict[str, dict[str, float]]:
    """Compute pairwise correlation matrix from daily returns."""
    returns_df = prices_df.pct_change().dropna()
    corr = returns_df.corr()
    return {
        t1: {t2: round(float(corr.loc[t1, t2]), 4) for t2 in corr.columns}
        for t1 in corr.index
    }


def run_optimization(request: OptimizeRequest) -> OptimizeResponse:
    """Orchestrate the full optimization pipeline."""
    start = time.perf_counter_ns()

    prices_df = _build_prices_dataframe(request.prices)
    # Catch obviously infeasible inputs up-front so the user gets a
    # clear message instead of pypfopt's "Solver status: infeasible"
    # or a divide-by-zero RuntimeWarning from numpy.
    n_assets = len(prices_df.columns)
    if n_assets < 1:
        raise ValueError("Portfolio optimization requires at least 1 asset; got 0")
    max_w = request.constraints.max_weight
    if n_assets * max_w < 1.0:
        min_feasible = 1.0 / n_assets
        raise ValueError(
            f"Constraints are infeasible: {n_assets} asset(s) with "
            f"max_weight={max_w} cannot sum to 1.0. Increase max_weight to "
            f"at least {min_feasible:.2f} or add more assets."
        )

    cov_matrix = _compute_covariance(prices_df, request.covariance_method)

    all_tickers = list(prices_df.columns)
    mc_tickers = [t for t in all_tickers if t in request.market_caps]
    no_mc_tickers = [t for t in all_tickers if t not in request.market_caps]

    if request.algorithm == "BLACK_LITTERMAN":
        # BL runs on the subset with market caps
        prices_mc = prices_df[mc_tickers]
        cov_mc = cov_matrix.loc[mc_tickers, mc_tickers]

        posterior_returns, posterior_cov = _run_black_litterman(
            prices_mc,
            cov_mc,
            request.market_caps,
            request.views,
            request.confidences,
            request.risk_free_rate,
            request.tau,
        )

        bl_weights, performance = _run_efficient_frontier(
            posterior_returns,
            posterior_cov,
            request.constraints,
            request.risk_free_rate,
        )

        weights = _merge_weights(
            bl_weights,
            no_mc_tickers,
            request.constraints.min_weight,
            request.constraints.max_weight,
        )

        frontier_points = _compute_frontier_points(
            posterior_returns,
            posterior_cov,
            request.constraints,
            request.risk_free_rate,
        )
    else:
        raise ValueError(f"Unsupported algorithm: {request.algorithm}")

    cvar_95 = _compute_cvar_95(prices_df, weights)
    correlation = _compute_correlation_matrix(prices_df)

    elapsed_ms = int((time.perf_counter_ns() - start) / 1_000_000)

    return OptimizeResponse(
        weights={t: round(w, 6) for t, w in sorted(weights.items())},
        metrics=OptimizeMetrics(
            expected_annual_return=round(performance[0], 6),
            annual_volatility=round(performance[1], 6),
            sharpe_ratio=round(performance[2], 4),
            cvar_95=round(cvar_95, 6),
        ),
        efficient_frontier=frontier_points,
        correlation_matrix=correlation,
        computation_ms=elapsed_ms,
    )
