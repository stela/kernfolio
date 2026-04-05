/**
 * Shared utilities for chart and allocation scripts on the results page.
 * Parses portfolio/run IDs from the URL path instead of data attributes.
 */
var ChartUtils = (function () {
    function getIdsFromUrl() {
        var match = window.location.pathname.match(/\/portfolios\/([^/]+)\/results\/([^/]+)/);
        if (!match) return null;
        return { portfolioId: match[1], runId: match[2] };
    }

    return { getIdsFromUrl: getIdsFromUrl };
})();
