/**
 * Ticker autocomplete — vanilla JS. CSP-safe: no inline handlers, no eval,
 * all DOM nodes built with createElement + textContent (never innerHTML with
 * server-supplied strings).
 *
 * Usage:
 *   TickerAutocomplete.attach(tickerInput, {
 *       listEl:   <ul>,        // sibling <ul> to render suggestions into
 *       statusEl: <div>,       // sibling <div> for short feedback lines
 *       onSelect: function (suggestion) { ... }
 *   });
 */
var TickerAutocomplete = (function () {
    var DEBOUNCE_MS = 250;
    var MIN_CHARS = 1;

    function attach(input, opts) {
        if (!input || !opts || !opts.listEl) return;
        var listEl = opts.listEl;
        var statusEl = opts.statusEl || null;
        var onSelect = opts.onSelect || function () {};

        var timer = null;
        var currentController = null;
        var activeIndex = -1;
        var lastResults = [];

        function closeList() {
            listEl.classList.add('hidden');
            input.setAttribute('aria-expanded', 'false');
            activeIndex = -1;
            window.removeEventListener('scroll', positionList, true);
            window.removeEventListener('resize', positionList);
        }

        function positionList() {
            // Fixed-position the dropdown anchored to the input's current
            // viewport rect. Avoids clipping by any ancestor with
            // overflow:hidden (e.g. the rounded card that wraps the
            // positions table).
            var rect = input.getBoundingClientRect();
            listEl.style.left = rect.left + 'px';
            listEl.style.top = (rect.bottom + 4) + 'px';
        }

        function openList() {
            if (lastResults.length === 0) {
                closeList();
                return;
            }
            positionList();
            listEl.classList.remove('hidden');
            input.setAttribute('aria-expanded', 'true');
            // `true` captures scrolls on any ancestor, including the
            // page scroll root.
            window.addEventListener('scroll', positionList, true);
            window.addEventListener('resize', positionList);
        }

        function setStatus(text, tone) {
            if (!statusEl) return;
            statusEl.textContent = text || '';
            statusEl.classList.remove('text-gray-500', 'text-emerald-700', 'text-amber-700');
            if (tone === 'ok') statusEl.classList.add('text-emerald-700');
            else if (tone === 'warn') statusEl.classList.add('text-amber-700');
            else statusEl.classList.add('text-gray-500');
        }

        function render(results) {
            lastResults = results;
            activeIndex = -1;
            while (listEl.firstChild) listEl.removeChild(listEl.firstChild);
            results.forEach(function (r, i) {
                var li = document.createElement('li');
                li.setAttribute('role', 'option');
                li.className = 'cursor-pointer px-3 py-2 text-sm hover:bg-blue-50 flex items-baseline gap-2';
                li.dataset.index = String(i);

                var ticker = document.createElement('strong');
                ticker.className = 'font-mono';
                ticker.textContent = r.ticker;
                li.appendChild(ticker);

                if (r.name) {
                    var name = document.createElement('span');
                    name.className = 'flex-1 truncate text-gray-700';
                    name.textContent = r.name;
                    li.appendChild(name);
                }

                var meta = [];
                if (r.exchange) meta.push(r.exchange);
                if (r.currency) meta.push(r.currency);
                if (meta.length > 0) {
                    var small = document.createElement('small');
                    small.className = 'text-gray-400';
                    small.textContent = meta.join(' · ');
                    li.appendChild(small);
                }

                // Use mousedown rather than click so the pick fires before the
                // input's blur handler closes the list.
                li.addEventListener('mousedown', function (e) {
                    e.preventDefault();
                    pick(i);
                });
                listEl.appendChild(li);
            });
            highlight();
            openList();
        }

        function highlight() {
            var items = listEl.querySelectorAll('li');
            for (var i = 0; i < items.length; i++) {
                if (i === activeIndex) items[i].classList.add('bg-blue-100');
                else items[i].classList.remove('bg-blue-100');
            }
        }

        function pick(i) {
            var r = lastResults[i];
            if (!r) return;
            input.value = r.ticker;
            closeList();
            onSelect(r);
        }

        function fetchSuggestions(q) {
            if (currentController) currentController.abort();
            currentController = (typeof AbortController !== 'undefined') ? new AbortController() : null;
            var init = currentController ? { signal: currentController.signal } : {};

            return Http.json('/api/instruments/search?q=' + encodeURIComponent(q), init)
                .then(function (data) {
                    if (!Array.isArray(data)) return [];
                    return data;
                })
                .catch(function (err) {
                    if (err && err.name === 'AbortError') return null;
                    return [];
                });
        }

        input.addEventListener('input', function () {
            var q = input.value.trim();
            if (timer) clearTimeout(timer);

            if (q.length < MIN_CHARS) {
                lastResults = [];
                closeList();
                setStatus('', 'neutral');
                return;
            }

            setStatus('Searching…', 'neutral');
            timer = setTimeout(function () {
                fetchSuggestions(q).then(function (results) {
                    if (results === null) return; // aborted
                    if (input.value.trim() !== q) return; // stale response
                    render(results);
                    if (results.length === 0) {
                        setStatus('No match — press Save to try fetching this ticker anyway.', 'warn');
                    } else {
                        setStatus('', 'neutral');
                    }
                });
            }, DEBOUNCE_MS);
        });

        input.addEventListener('keydown', function (e) {
            if (listEl.classList.contains('hidden')) return;
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (lastResults.length === 0) return;
                activeIndex = (activeIndex + 1) % lastResults.length;
                highlight();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (lastResults.length === 0) return;
                activeIndex = (activeIndex - 1 + lastResults.length) % lastResults.length;
                highlight();
            } else if (e.key === 'Enter') {
                if (activeIndex >= 0) {
                    e.preventDefault();
                    pick(activeIndex);
                }
            } else if (e.key === 'Escape') {
                closeList();
            }
        });

        input.addEventListener('blur', function () {
            // Delay so mousedown on a suggestion fires before close.
            setTimeout(closeList, 150);
        });

        input.addEventListener('focus', function () {
            if (lastResults.length > 0) openList();
        });
    }

    return { attach: attach };
})();
