package app.quantusnode.node;

import android.content.Context;
import android.content.SharedPreferences;

import app.quantusnode.node.depin.core.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The QTC price for the vision home (design section 2.4, spec
 * section 4.1): one HTTPS GET to CoinGecko's public API (asset
 * id "quantus", USD, with the 24 h change in the same response),
 * fetched at most every five minutes while the app runs — the
 * callers are the activity's UI tick and, since internal.23,
 * the node service's poller, so the price keeps refreshing
 * while the node runs even with no UI open — with a failure
 * backoff of 5, then 15 minutes (capped; see {@link PriceLogic}).
 * The last successful value and its fetch time persist
 * on-device, so a cold start shows the old value WITH its age,
 * never as if it were new (design section 2.5), and the last
 * failure persists too, so a stopped price is diagnosable.
 *
 * The price is never estimated from anything else the app knows:
 * no value means the UI shows a dash. This class is the app's
 * only outbound HTTP path of its own; the node process's traffic
 * is separate and untouched by it.
 */
public final class PriceStore {

    private static final String PREFS = "price_store";
    private static final String KEY_USD = "usd";
    private static final String KEY_CHANGE = "change24h";
    private static final String KEY_FETCHED_MS = "fetched_wall_ms";
    private static final String KEY_HAS_CHANGE = "has_change";
    private static final String KEY_UPDATED_UNIX = "updated_unix";
    /** The most recent fetch failure, kept until the next
     *  success: what failed (exception kind, with the HTTP
     *  status when there was one) and when. Until internal.23
     *  a failure was recorded NOWHERE — the exception was
     *  swallowed by the scheduling catch — so an on-device
     *  failure (a CoinGecko rate limit, a network error) was
     *  indistinguishable from the app never trying. */
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_ERROR_MS = "last_error_wall_ms";

    private static final String ENDPOINT =
            "https://api.coingecko.com/api/v3/simple/price"
                    + "?ids=quantus&vs_currencies=usd"
                    + "&include_24hr_change=true"
                    + "&include_last_updated_at=true";
    /** Foreground refresh cadence after a success. */
    private static final long REFRESH_MS = 5L * 60 * 1000;
    /** A cached value older than this is presented as stale. */
    private static final long STALE_MS = 10L * 60 * 1000;

    private static final java.util.concurrent.atomic.AtomicBoolean
            IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long nextAttemptWallMs;
    private static volatile int failures;
    /** Whether the most recent attempt of THIS process failed. */
    private static volatile boolean lastAttemptFailed;

    private PriceStore() {
    }

    /** An immutable view of what the UI may show right now. */
    public static final class Snapshot {
        public final boolean hasValue;
        public final double usd;
        public final double change24h;
        /** Whether the source actually provided a 24 h change
         *  with the fetch that produced this value. "Not
         *  provided" and "0.0%" are different facts and the UI
         *  must be able to tell them apart (spec S1-08): when
         *  this is false the change renders as a dash, never
         *  as a computed-looking 0.0. Saves written before this
         *  field existed load as true (the value they hold was
         *  presented as a change), per the spec's migration
         *  rule. */
        public final boolean hasChange;
        /** CoinGecko's own last-update time for the price
         *  (unix seconds), for the source tag; -1 when the
         *  fetch did not carry one. The staleness judgement
         *  keeps using the device fetch time below. */
        public final long updatedUnix;
        /** Wall-clock millis of the successful fetch; 0 if none. */
        public final long fetchedWallMs;
        /** True when the value should be presented as not fresh
         *  (the last attempt failed, or the value is old). */
        public final boolean stale;

        Snapshot(boolean hasValue, double usd, double change24h,
                 boolean hasChange, long updatedUnix,
                 long fetchedWallMs, boolean stale) {
            this.hasValue = hasValue;
            this.usd = usd;
            this.change24h = change24h;
            this.hasChange = hasChange;
            this.updatedUnix = updatedUnix;
            this.fetchedWallMs = fetchedWallMs;
            this.stale = stale;
        }
    }

    public static Snapshot snapshot(Context ctx) {
        SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(KEY_USD)) {
            return new Snapshot(false, 0, 0, true, -1, 0, false);
        }
        double usd = Double.longBitsToDouble(p.getLong(KEY_USD, 0));
        double change =
                Double.longBitsToDouble(p.getLong(KEY_CHANGE, 0));
        boolean hasChange = p.getBoolean(KEY_HAS_CHANGE, true);
        long updatedUnix = p.getLong(KEY_UPDATED_UNIX, -1);
        long fetched = p.getLong(KEY_FETCHED_MS, 0);
        boolean stale = PriceLogic.isStale(fetched,
                System.currentTimeMillis(), STALE_MS, lastAttemptFailed);
        return new Snapshot(true, usd, change, hasChange,
                updatedUnix, fetched, stale);
    }

    /** The most recent fetch failure's KIND — the exception's
     *  class name alone (e.g. "SocketTimeoutException"), or
     *  null when the last attempt succeeded or none has run
     *  yet. Persisted, so the diagnostic summary can show it
     *  after the fact. The exception MESSAGE is deliberately
     *  never persisted (internal.26, F-09): a network failure
     *  message can carry the device's own address, and this
     *  text is bound for a shareable summary. Values written
     *  by earlier builds ("ClassName: message") are reduced
     *  to the kind again at render time (DiagShare). */
    public static String lastErrorText(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_ERROR, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** Wall-clock millis of the most recent fetch failure;
     *  0 when {@link #lastErrorText} is null. */
    public static long lastErrorWallMs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_ERROR_MS, 0);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    /**
     * Fetch if due. Called from the activity's 1-second tick
     * and from the node service's poller; the cadence and the
     * backoff live here, so callers never think about them.
     * At most one request is ever in flight.
     */
    public static void maybeFetch(Context ctx) {
        long now = System.currentTimeMillis();
        // Residual F-25: the in-flight flag used to be a
        // volatile check-then-set, so two callers (the UI
        // tick and the service poller) could both pass and
        // fetch twice. The claim is atomic now.
        if (now < nextAttemptWallMs
                || !IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Fetched v = fetch();
                SharedPreferences p = app.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
                p.edit()
                        .putLong(KEY_USD, Double.doubleToLongBits(v.usd))
                        .putLong(KEY_CHANGE,
                                Double.doubleToLongBits(v.change24h))
                        .putBoolean(KEY_HAS_CHANGE, v.hasChange)
                        .putLong(KEY_UPDATED_UNIX, v.updatedUnix)
                        .putLong(KEY_FETCHED_MS,
                                System.currentTimeMillis())
                        .remove(KEY_LAST_ERROR)
                        .remove(KEY_LAST_ERROR_MS)
                        .apply();
                failures = 0;
                lastAttemptFailed = false;
                nextAttemptWallMs =
                        System.currentTimeMillis() + REFRESH_MS;
            } catch (Exception e) {
                failures++;
                lastAttemptFailed = true;
                // The failure is a fact the diagnostic summary
                // (and only it) needs later; persist it before
                // scheduling the retry. Only the exception's
                // KIND is kept — never its message (F-09): a
                // connect failure's message names the host it
                // dialed and the device's own address it came
                // from, and this record is printed into a
                // shareable text.
                String errorText = e.getClass().getSimpleName();
                try {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_LAST_ERROR, errorText)
                            .putLong(KEY_LAST_ERROR_MS,
                                    System.currentTimeMillis())
                            .apply();
                } catch (Throwable ignored) {
                    // Recording the failure must never become
                    // a second failure.
                }
                nextAttemptWallMs = System.currentTimeMillis()
                        + PriceLogic.backoffMs(failures);
            } finally {
                IN_FLIGHT.set(false);
            }
        }, "price-fetch");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            // Final check round 4 (I-12): the in-flight claim
            // is released by the worker's finally — which
            // never runs when the worker cannot start (thread
            // creation can fail under memory pressure). The
            // flag then stayed set for the rest of the
            // process and no fetch ever ran again.
            IN_FLIGHT.set(false);
        }
    }

    /** One fetch's parsed result. */
    private static final class Fetched {
        double usd;
        double change24h;
        boolean hasChange;
        long updatedUnix = -1;
    }

    /** One request; returns the parsed values or throws. */
    private static Fetched fetch() throws Exception {
        HttpURLConnection c =
                (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", "QuantusNode/0.3");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException(
                        "HTTP " + c.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 64 * 1024) {
                        throw new IllegalStateException("oversized body");
                    }
                }
            }
            Map<String, Object> root = Json.parseObject(sb.toString());
            Object q = root.get("quantus");
            if (!(q instanceof Map)) {
                throw new IllegalStateException("no quantus entry");
            }
            Object usd = ((Map<?, ?>) q).get("usd");
            Object change = ((Map<?, ?>) q).get("usd_24h_change");
            Object updated = ((Map<?, ?>) q).get("last_updated_at");
            if (!(usd instanceof Number)) {
                throw new IllegalStateException("no usd price");
            }
            Fetched f = new Fetched();
            f.usd = ((Number) usd).doubleValue();
            // A missing change is NOT 0.0: keep the distinction
            // (the old code substituted 0.0 here, which made
            // "not provided" indistinguishable from a real flat
            // reading — spec S1-08).
            f.hasChange = change instanceof Number;
            f.change24h = f.hasChange
                    ? ((Number) change).doubleValue() : 0.0;
            if (updated instanceof Number) {
                f.updatedUnix = ((Number) updated).longValue();
            }
            return f;
        } finally {
            c.disconnect();
        }
    }
}
