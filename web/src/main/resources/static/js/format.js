/**
 * Locale-aware display formatters, shared by every page (loaded from
 * layout/base.kte). All user-visible numbers and timestamps go through
 * these so they follow the viewer's browser locale — e.g. an sv-SE
 * browser sees "1 234,56" and "12,50 %" instead of "1,234.56" / "12.50%".
 * The server never renders formatted numbers.
 *
 * `toFixed` is locale-blind (always emits "."), so reserve it for
 * serialization (form fields, JSON bodies, CSS lengths) where a dot is
 * required.
 */
var Format = (function () {
    'use strict';

    function amount(value, digits) {
        if (typeof value !== 'number' || !isFinite(value)) return '';
        if (digits == null) digits = 2;
        return value.toLocaleString(undefined, {
            minimumFractionDigits: digits,
            maximumFractionDigits: digits,
        });
    }

    function qty(value) {
        // Whole shares render compactly ("100"); fractional shares (ETFs,
        // reinvested dividends) up to 4 decimals, trailing zeros stripped.
        if (typeof value !== 'number' || !isFinite(value)) return '';
        if (Number.isInteger(value)) return value.toLocaleString();
        return value.toLocaleString(undefined, { maximumFractionDigits: 4 });
    }

    function percent(fraction, digits, signDisplay) {
        if (typeof fraction !== 'number' || !isFinite(fraction)) return '';
        if (digits == null) digits = 2;
        return fraction.toLocaleString(undefined, {
            style: 'percent',
            minimumFractionDigits: digits,
            maximumFractionDigits: digits,
            signDisplay: signDisplay,
        });
    }

    // Takes a 0–1 fraction, not a ×100 value. The percent sign and its
    // spacing come from the locale ("12.50%" en-US, "12,50 %" sv-SE).
    function pct(fraction, digits) {
        return percent(fraction, digits, 'auto');
    }

    // Axis-tick style: no forced decimals ("10 %", "2,5 %").
    function pctCompact(fraction) {
        if (typeof fraction !== 'number' || !isFinite(fraction)) return '';
        return fraction.toLocaleString(undefined, { style: 'percent', maximumFractionDigits: 2 });
    }

    // Like pct, with an explicit "+" on positive values (deltas).
    function signedPct(fraction, digits) {
        return percent(fraction, digits, 'exceptZero');
    }

    // ISO-8601 instant → date + time in the viewer's locale and timezone.
    function dateTime(iso) {
        if (!iso) return '';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
    }

    return {
        amount: amount,
        qty: qty,
        pct: pct,
        pctCompact: pctCompact,
        signedPct: signedPct,
        dateTime: dateTime,
    };
})();
