"""Black-Litterman views + fractional-Kelly sizing."""

import logging
import time
import warnings

import cvxpy as cp
import numpy as np
import pandas as pd
from pypfopt import BlackLittermanModel, EfficientFrontier, black_litterman, risk_models

from app.schemas import (
    Constraints,
    FrontierPoint,
    OptimizeMetrics,
    OptimizeRequest,
    OptimizeResponse,
)

log = logging.getLogger(__name__)

# Clarabel (interior point, ships with cvxpy) rather than cvxpy's default for
# QPs, OSQP: OSQP's loose first-order tolerances returned optimal_inaccurate or
# hit its iteration limit on ~2% of frontier solves (worst near the frontier's
# ends), and those points were silently dropped. Clarabel in turn can stall on
# near-degenerate problems (e.g. views making all expected returns almost
# equal); HiGHS (active set, also shipped with cvxpy) solves those exactly but
# is far slower in general, so it is only the fallback.
SOLVER = cp.CLARABEL
FALLBACK_SOLVER = cp.HIGHS


def _build_prices_dataframe(
    prices: dict[str, list[str] | list[float]],
) -> pd.DataFrame:
    """Convert the request prices dict into a DataFrame with DatetimeIndex."""
    dates = pd.to_datetime(prices["dates"])
    ticker_data = {k: v for k, v in prices.items() if k != "dates"}
    return pd.DataFrame(ticker_data, index=dates).sort_index()


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
    view_stddevs: dict[str, float],
    risk_free_rate: float,
    tau: float,
) -> tuple[pd.Series, pd.DataFrame, dict[str, float]]:
    """Blend market-implied returns with the user's views.

    Returns (posterior returns, posterior covariance, implied confidence per view).

    A view is an expected annual return plus the std-dev of the user's own
    scenario outcomes. That spread is the same kind of quantity as the
    asset's volatility, whereas BL's prior uncertainty about the *mean* is
    tau * Sigma — so the view uncertainty is scaled by tau as well
    (He-Litterman convention): omega_k = tau * s_k^2. The weight a view gets
    against the market prior is then about sigma_k^2 / (sigma_k^2 + s_k^2):
    a spread equal to the stock's own volatility is a 50/50 blend.
    """
    # Filter views to tickers present in this universe
    filtered_views = {t: v for t, v in views.items() if t in cov_matrix.columns}
    if not filtered_views:
        # No opinions: the market-implied prior is the answer. (risk_aversion=1
        # is what BlackLittermanModel itself defaults to below.)
        prior = black_litterman.market_implied_prior_returns(
            pd.Series(market_caps)[list(cov_matrix.columns)], 1, cov_matrix, risk_free_rate
        )
        return prior, cov_matrix, {}

    view_variances = [view_stddevs[t] ** 2 for t in filtered_views]
    omega = np.diag([tau * v for v in view_variances])

    bl = BlackLittermanModel(
        cov_matrix,
        pi="market",
        market_caps=market_caps,
        risk_free_rate=risk_free_rate,
        tau=tau,
        absolute_views=filtered_views,
        omega=omega,
    )

    implied_confidences = {
        t: float(cov_matrix.loc[t, t] / (cov_matrix.loc[t, t] + var))
        for t, var in zip(filtered_views, view_variances)
    }
    return bl.bl_returns(), bl.bl_cov(), implied_confidences


def _run_kelly(
    expected_returns: pd.Series,
    cov_matrix: pd.DataFrame,
    constraints: Constraints,
    risk_free_rate: float,
    kelly_fraction: float,
    budget: float,
) -> dict[str, float]:
    """Fractional-Kelly sizing with the full covariance matrix.

    maximise  w.(mu - rf) - 1/(2f) * w' Sigma w
    s.t.      sum(w) <= budget,  min_weight <= w <= max_weight

    Unconstrained this is w = f * Sigma^-1 (mu - rf): the max-Sharpe mix,
    scaled. Unlike max_sharpe(), total exposure is an output — whatever is
    not allocated stays in cash.
    """
    tickers = list(expected_returns.index)
    mu = expected_returns.to_numpy(dtype=float) - risk_free_rate
    sigma = cov_matrix.loc[tickers, tickers].to_numpy(dtype=float)

    w = cp.Variable(len(tickers))
    objective = cp.Maximize(mu @ w - (1.0 / (2.0 * kelly_fraction)) * cp.quad_form(w, cp.psd_wrap(sigma)))
    problem = cp.Problem(
        objective,
        [cp.sum(w) <= budget, w >= constraints.min_weight, w <= constraints.max_weight],
    )
    for solver in (SOLVER, FALLBACK_SOLVER):
        try:
            problem.solve(solver=solver)
        except cp.SolverError:
            continue
        if problem.status == cp.OPTIMAL and w.value is not None:
            break
    else:
        if problem.status != cp.OPTIMAL_INACCURATE or w.value is None:
            raise ValueError(f"Kelly sizing failed (solver status: {problem.status})")
        log.warning("Kelly sizing solved only to reduced accuracy")

    # Same clean-up pypfopt's clean_weights() applies: drop solver dust.
    return {t: (0.0 if abs(v) < 1e-4 else float(v)) for t, v in zip(tickers, w.value)}


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
    """Compute ~n_points on the fully-invested efficient frontier.

    Context for the chart only. It needs weights that can sum to 1.0 within
    the bounds; Kelly sizing doesn't, so an infeasible frontier is just empty.
    """
    if len(expected_returns) * constraints.max_weight < 1.0:
        return []

    def frontier(solver: str = SOLVER) -> EfficientFrontier:
        return EfficientFrontier(
            expected_returns,
            cov_matrix,
            weight_bounds=(constraints.min_weight, constraints.max_weight),
            solver=solver,
        )

    def point(ef: EfficientFrontier) -> tuple[float, FrontierPoint]:
        ret, risk, _ = ef.portfolio_performance(verbose=False, risk_free_rate=risk_free_rate)
        return ret, FrontierPoint(risk=round(risk, 6), ret=round(ret, 6))

    # Both ends are portfolios in their own right: minimum volatility, and
    # maximum return (a negligible risk penalty breaks ties). They're used
    # as-is rather than re-solved as efficient_return targets: a target at the
    # very edge of what the bounds allow is barely feasible, and the old
    # `max_ret * 0.999` pulled the target *outside* that range whenever views
    # made the maximum return negative.
    ef_min = frontier()
    ef_min.min_volatility()
    min_ret, low = point(ef_min)
    ef_max = frontier()
    ef_max.max_quadratic_utility(risk_aversion=0.0001)
    max_ret, high = point(ef_max)
    if max_ret - min_ret <= 1e-9:
        return [low]  # the bounds leave a single efficient portfolio

    points = [low]
    failed = 0
    for target in np.linspace(min_ret, max_ret, n_points)[1:-1]:
        for solver in (SOLVER, FALLBACK_SOLVER):
            ef = frontier(solver)
            try:
                # pypfopt only warns on optimal_inaccurate; treat it as a failure.
                with warnings.catch_warnings():
                    warnings.filterwarnings("error", "Solution may be inaccurate")
                    ef.efficient_return(target)
                points.append(point(ef)[1])
                break
            except Exception:
                continue
        else:
            failed += 1
    points.append(high)
    if failed:
        log.warning("Efficient frontier: %d of %d interior points failed to solve", failed, n_points - 2)
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
    min_w = request.constraints.min_weight
    if n_assets * min_w > 1.0:
        raise ValueError(
            f"Constraints are infeasible: {n_assets} asset(s) with "
            f"min_weight={min_w} already exceed 100%. Lower min_weight to "
            f"at most {1.0 / n_assets:.4f} or remove positions."
        )

    cov_matrix = _compute_covariance(prices_df, request.covariance_method)

    all_tickers = list(prices_df.columns)
    mc_tickers = [t for t in all_tickers if t in request.market_caps]
    no_mc_tickers = [t for t in all_tickers if t not in request.market_caps]

    if request.algorithm == "BLACK_LITTERMAN":
        # BL runs on the subset with market caps
        prices_mc = prices_df[mc_tickers]
        cov_mc = cov_matrix.loc[mc_tickers, mc_tickers]

        posterior_returns, posterior_cov, view_confidences = _run_black_litterman(
            prices_mc,
            cov_mc,
            request.market_caps,
            request.views,
            request.view_stddevs,
            request.risk_free_rate,
            request.tau,
        )

        # Tickers without a market cap can't take part in BL; they are held
        # at min_weight and the Kelly budget is what remains.
        satellite_total = len(no_mc_tickers) * min_w
        weights = _run_kelly(
            posterior_returns,
            posterior_cov,
            request.constraints,
            request.risk_free_rate,
            request.kelly_fraction,
            budget=1.0 - satellite_total,
        )
        for ticker in no_mc_tickers:
            weights[ticker] = min_w

        frontier_points = _compute_frontier_points(
            posterior_returns,
            posterior_cov,
            request.constraints,
            request.risk_free_rate,
        )
    else:
        raise ValueError(f"Unsupported algorithm: {request.algorithm}")

    # Weights are fractions of the whole portfolio; the rest is cash earning rf.
    cash_weight = max(0.0, 1.0 - sum(weights.values()))
    w_bl = pd.Series({t: weights[t] for t in posterior_returns.index})
    expected_return = float(w_bl @ posterior_returns) + cash_weight * request.risk_free_rate
    # Satellites carry no modelled return; count them at rf like cash.
    expected_return += sum(weights[t] for t in no_mc_tickers) * request.risk_free_rate
    w_all = pd.Series({t: weights.get(t, 0.0) for t in all_tickers})
    volatility = float(np.sqrt(w_all @ cov_matrix.loc[all_tickers, all_tickers] @ w_all))
    sharpe = (expected_return - request.risk_free_rate) / volatility if volatility > 0 else 0.0
    performance = (expected_return, volatility, sharpe)

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
        cash_weight=round(cash_weight, 6),
        view_confidences={t: round(c, 4) for t, c in sorted(view_confidences.items())},
        efficient_frontier=frontier_points,
        correlation_matrix=correlation,
        computation_ms=elapsed_ms,
    )
