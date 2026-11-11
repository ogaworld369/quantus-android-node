package app.quantusnode.node;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The diagnostic share text (versionCode 28): everything about the
 * text the user sends from the "share log" action that can be
 * decided without Android, kept pure so it is exercised on a JVM
 * against the real code. {@link MainActivity} gathers the facts
 * (package info, battery, storage, the service snapshot) and the
 * masking inputs (the device's own addresses, the app-private
 * paths); this class owns:
 *
 *  - the summary block prepended to the shared log: app and device,
 *    the state at share time, device resources, session history,
 *    and facts about the log itself. Labels are fixed English —
 *    the text's readers are whoever the user pastes it to (an
 *    issue tracker, a maintainer), whatever their language. A
 *    value the app cannot know is written as "n/a", never guessed:
 *    the finalized height (the app never queries it), the bundled
 *    binary's own version (the app cannot ask it), the genesis
 *    hash (never queried either);
 *  - the masking applied when the user leaves it on (the default):
 *    the display name, the device's own IP addresses and the
 *    app-private path prefixes are replaced in the log body, so a
 *    log pasted in public keeps its diagnostic structure
 *    (versions, states, counts) without the identifying values.
 *    The summary block itself carries none of those values by
 *    construction, and it is never masked: masking it could only
 *    damage its fixed labels and fixed values (a display name of
 *    "off" against the "Telemetry: off" line is the standing
 *    example), never hide anything;
 *  - the log statistics the summary reports (timestamp range of
 *    the shared excerpt, WARN / ERROR line counts) and the network
 *    transport label the service stamps into the log when the
 *    connection type changes.
 */
public final class DiagShare {

    private DiagShare() {
    }

    /** Replacement for the display name when masking. */
    static final String NAME_MASK = "***";
    /** Replacement for one of the device's own IP addresses. */
    static final String IP_MASK = "<ip>";
    /** Replacement for an app-private path prefix. */
    static final String PATH_MASK = "<app-dir>";
    /** Written for every value the app cannot know. */
    static final String NA = "n/a";

    // ------------------------------------------------------------------
    // Facts
    // ------------------------------------------------------------------

    /**
     * The summary's inputs, gathered by the Android side. Every
     * field's sentinel (null, -1, 0 for the change stamps) means
     * "not known" and renders as {@link #NA}.
     */
    public static final class Facts {
        // A1: app and device
        public String appVersionName;
        public int appVersionCode = -1;
        public String nodeBinaryVersion;
        public String androidRelease;
        public int androidApi = -1;
        public String deviceModel;
        public String deviceMaker;
        public String chainName;
        public String genesisShort;
        public long installMs = -1;
        public long updateMs = -1;
        // A2: state at share time
        public NodeState.Status status = NodeState.Status.STOPPED;
        public long sessionUptimeMs = -1;
        public long bestBlock = -1;
        public long highestBlock = -1;
        public long peers = -1;
        public long lastRpcOkWallMs = -1;
        public boolean telemetryOn;
        /**
         * True when {@link #telemetryOn} is the saved setting
         * rather than the value the running session was launched
         * with (no launched value exists): the summary marks the
         * line as a setting, so a reader does not mistake it for
         * what the node is actually running with.
         */
        public boolean telemetryIsSetting;
        // A3: device resources
        public long nodeDataBytes = -1;
        public long freeStorageBytes = -1;
        public int batteryPercent = -1;
        public Integer batteryTempTenths;
        public long freeMemBytes = -1;
        public Boolean batteryOptExempt;
        public Boolean notificationsGranted;
        // A4: sessions and history
        public NodeState.StopKind lastStopKind = NodeState.StopKind.NONE;
        public Integer lastExitCode;
        public int startsLast24h = -1;
        /**
         * True when the start-stamp record sits at its retention
         * cap: the 24 h count is then a lower bound (older stamps
         * were dropped), and the summary says so with a "+".
         */
        public boolean startsCapped;
        public long trackUptimeMs = -1;
        public long trackBlocks = -1;
        public long trackMaxPeers = -1;
        public long telemetryChangedMs = 0;
        public long nameChangedMs = 0;
        public long languageChangedMs = 0;
        // A5: QTC price fetch (internal.23). Until this record
        // existed, a stopped price was undiagnosable: the fetch
        // loop kept its failures in memory only and discarded
        // the exception itself.
        /** Wall time of the last successful price fetch; 0 =
         *  never (renders as n/a). */
        public long priceFetchedMs = 0;
        /** Kind of the last failed price fetch — an exception
         *  class name, never a message (null when the last
         *  attempt succeeded or none ran). Rendered through
         *  {@link DiagShare#errorKind} regardless, so a legacy
         *  "ClassName: message" value is reduced at render. */
        public String priceError;
        /** Wall time of that failure; 0 when none. */
        public long priceErrorMs = 0;
    }

    // ------------------------------------------------------------------
    // Summary block
    // ------------------------------------------------------------------

    /**
     * The summary block for one share: the facts, then the log's
     * own statistics computed from the excerpt actually being
     * shared (after the tail cut, before any truncation note is
     * prepended). Ends with a newline; the caller places it ahead
     * of the log body, outside the body's character cap.
     */
    public static String buildSummary(Facts f, String sharedLogBody,
                                      boolean truncated) {
        if (f == null) {
            f = new Facts();
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== Quantus Node diagnostic summary ===\n");
        // A1
        line(sb, "App version", f.appVersionName == null
                ? NA : f.appVersionName + (f.appVersionCode >= 0
                        ? " (versionCode " + f.appVersionCode + ")" : ""));
        line(sb, "Node binary version", orNa(f.nodeBinaryVersion));
        line(sb, "Android", f.androidRelease == null ? NA
                : f.androidRelease + (f.androidApi >= 0
                        ? " (API " + f.androidApi + ")" : ""));
        line(sb, "Device", f.deviceModel == null ? NA
                : f.deviceModel + (f.deviceMaker == null
                        ? "" : " (" + f.deviceMaker + ")"));
        line(sb, "Chain", orNa(f.chainName));
        line(sb, "Genesis hash", orNa(f.genesisShort));
        line(sb, "Installed", tsOrNa(f.installMs));
        line(sb, "Last updated", tsOrNa(f.updateMs));
        // A2
        line(sb, "Status", statusWord(f.status));
        line(sb, "Session uptime", f.sessionUptimeMs < 0
                ? NA : formatDurationEn(f.sessionUptimeMs));
        line(sb, "Best block", numOrNa(f.bestBlock));
        line(sb, "Highest block", numOrNa(f.highestBlock));
        // The app never queries the finalized height (its sync
        // judgement runs on best/highest), so there is no value to
        // report — stated, not filled in.
        line(sb, "Finalized block", NA);
        line(sb, "Peers", numOrNa(f.peers));
        line(sb, "Last RPC response", tsOrNa(f.lastRpcOkWallMs));
        line(sb, "Telemetry", (f.telemetryOn ? "on" : "off")
                + (f.telemetryIsSetting ? " (saved setting)" : ""));
        // A3
        line(sb, "Node data size", bytesOrNa(f.nodeDataBytes));
        line(sb, "Free storage", bytesOrNa(f.freeStorageBytes));
        line(sb, "Battery level", f.batteryPercent < 0
                ? NA : f.batteryPercent + "%");
        line(sb, "Battery temperature", f.batteryTempTenths == null
                ? NA : formatTenthsC(f.batteryTempTenths));
        line(sb, "Free memory", bytesOrNa(f.freeMemBytes));
        line(sb, "Battery optimization", f.batteryOptExempt == null
                ? NA : (f.batteryOptExempt ? "exempt" : "not exempt"));
        line(sb, "Notifications", f.notificationsGranted == null
                ? NA : (f.notificationsGranted ? "granted" : "denied"));
        // A4
        line(sb, "Previous session ended", previousEnd(f));
        line(sb, "Starts in the last 24 h", f.startsLast24h < 0
                ? NA : UiLogic.grouped(f.startsLast24h)
                        + (f.startsCapped ? "+" : ""));
        line(sb, "Cumulative uptime", f.trackUptimeMs < 0
                ? NA : formatDurationEn(f.trackUptimeMs));
        line(sb, "Blocks observed (cumulative)", numOrNa(f.trackBlocks));
        line(sb, "Max peers (cumulative)", numOrNa(f.trackMaxPeers));
        line(sb, "Telemetry setting changed", tsOrNa(f.telemetryChangedMs));
        line(sb, "Display name changed", tsOrNa(f.nameChangedMs));
        line(sb, "Language setting changed", tsOrNa(f.languageChangedMs));
        line(sb, "QTC price fetched", tsOrNa(f.priceFetchedMs));
        line(sb, "QTC price fetch error", priceErrorLine(f));
        // A5
        String first = firstLogTimestamp(sharedLogBody);
        String last = lastLogTimestamp(sharedLogBody);
        line(sb, "Log range (shared excerpt)", first == null
                ? NA : first + " .. " + last);
        line(sb, "Log truncated for sharing", truncated ? "yes" : "no");
        line(sb, "WARN lines", String.valueOf(
                countLevelLines(sharedLogBody, "WARN")));
        line(sb, "ERROR lines", String.valueOf(
                countLevelLines(sharedLogBody, "ERROR")));
        sb.append("=== end of summary ===\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(label).append(": ").append(value).append('\n');
    }

    /** The price-fetch error line's value: the error's KIND
     *  and, when known, its time — never the raw recorded
     *  text. */
    private static String priceErrorLine(Facts f) {
        if (f.priceError == null) {
            return NA;
        }
        String kind = errorKind(f.priceError);
        if (kind == null) {
            kind = "unknown";
        }
        return f.priceErrorMs > 0
                ? kind + " (at "
                        + formatTimestamp(f.priceErrorMs) + ")"
                : kind;
    }

    /**
     * Reduce a recorded error text to its kind: the leading
     * class-name token. Internal.26 persists kinds alone
     * (PriceStore), but values written by earlier builds are
     * "ClassName: message" — and a network failure's message
     * can carry the device's own address (audit F-09), while
     * the summary historically printed the text verbatim,
     * outside the masking applied to the log body. Reducing
     * here makes every stored value safe at render time,
     * whichever build wrote it. A text that does not start
     * with a class-name token (letters/digits/_/./$, not
     * solely digits and dots — an address is not a kind)
     * yields null; the caller renders a placeholder, so the
     * failure's existence and time still show.
     */
    public static String errorKind(String raw) {
        if (raw == null) {
            return null;
        }
        int end = 0;
        boolean hasLetter = false;
        boolean allDigitsAndDots = true;
        while (end < raw.length()) {
            char c = raw.charAt(end);
            boolean letter = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (!letter && !digit && c != '_' && c != '.'
                    && c != '$') {
                break;
            }
            if (letter) {
                hasLetter = true;
                allDigitsAndDots = false;
            } else if (!digit && c != '.') {
                allDigitsAndDots = false;
            }
            end++;
        }
        if (end < 3 || !hasLetter || allDigitsAndDots) {
            return null;
        }
        return raw.substring(0, end);
    }

    private static String orNa(String s) {
        return (s == null || s.isEmpty()) ? NA : s;
    }

    private static String tsOrNa(long wallMs) {
        return wallMs <= 0 ? NA : formatTimestamp(wallMs);
    }

    private static String numOrNa(long v) {
        return v < 0 ? NA : UiLogic.grouped(v);
    }

    private static String bytesOrNa(long bytes) {
        return bytes < 0 ? NA : formatBytesEn(bytes);
    }

    /**
     * Fill a Facts' previous-session-end fields from the state
     * snapshot: the live classification while it still describes
     * the most recently ended session, and — once a new session
     * has been established and the live fields reset for it — the
     * snapshot the service kept of the session before it (its
     * prevStopKind / prevExitCode). Keeping the ended session's
     * record in a separate snapshot is what lets it survive the
     * next start without disturbing the live fields' reset and
     * first-writer semantics.
     */
    static void fillPreviousEnd(Facts f, NodeState.StopKind liveKind,
                                Integer liveExitCode,
                                NodeState.StopKind previousKind,
                                Integer previousExitCode) {
        if (liveKind != null && liveKind != NodeState.StopKind.NONE) {
            f.lastStopKind = liveKind;
            f.lastExitCode = liveExitCode;
        } else {
            f.lastStopKind = previousKind == null
                    ? NodeState.StopKind.NONE : previousKind;
            f.lastExitCode = previousExitCode;
        }
    }

    private static String previousEnd(Facts f) {
        if (f.lastStopKind == null
                || f.lastStopKind == NodeState.StopKind.NONE) {
            return NA;
        }
        String w = stopKindWord(f.lastStopKind);
        if (f.lastExitCode != null) {
            w += " (exit code " + f.lastExitCode + ")";
        }
        return w;
    }

    static String statusWord(NodeState.Status s) {
        if (s == null) {
            return "stopped";
        }
        switch (s) {
            case STARTING:
                return "starting";
            case SYNCING:
                return "syncing";
            case SYNCED:
                return "synced";
            case STOPPED:
            default:
                return "stopped";
        }
    }

    static String stopKindWord(NodeState.StopKind k) {
        switch (k) {
            case START_FAILED:
                return "start failed";
            case UNEXPECTED:
                return "unexpected exit";
            case TIME_LIMIT:
                return "system time limit";
            case USER:
                return "user stop";
            case BINARY_MISSING:
                return "node binary missing";
            case NONE:
            default:
                return NA;
        }
    }

    // ------------------------------------------------------------------
    // Formatting (fixed English, ASCII digits)
    // ------------------------------------------------------------------

    /** "2026-11-06 22:03:11 +0900" — the node log's own time shape. */
    public static String formatTimestamp(long wallMs) {
        SimpleDateFormat fmt = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new java.util.Date(wallMs));
    }

    /** "2d 3h 4m" / "12m" / "<1m" / "0m" — whole minutes, days first. */
    public static String formatDurationEn(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        if (ms > 0 && ms < 60_000L) {
            return "<1m";
        }
        long totalMinutes = ms / 60_000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append('d');
        }
        if (hours > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(hours).append('h');
        }
        if (minutes > 0 || sb.length() == 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(minutes).append('m');
        }
        return sb.toString();
    }

    /** 1024-based, at most two decimals ("1.21 GB", "512 B"). */
    public static String formatBytesEn(long bytes) {
        if (bytes < 0) {
            return NA;
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024.0 && u < units.length - 1) {
            v /= 1024.0;
            u++;
        }
        if (u == 0) {
            return bytes + " B";
        }
        String num = String.format(Locale.US, "%.2f", v);
        if (num.contains(".")) {
            while (num.endsWith("0")) {
                num = num.substring(0, num.length() - 1);
            }
            if (num.endsWith(".")) {
                num = num.substring(0, num.length() - 1);
            }
        }
        return num + " " + units[u];
    }

    /** Battery temperature is reported by the platform in tenths of a degree. */
    static String formatTenthsC(int tenths) {
        // Sign, whole and fraction are taken from the absolute
        // value: -5 tenths is -0.5, and an int division that
        // truncates toward zero would otherwise print it as 0.5.
        int abs = Math.abs(tenths);
        return (tenths < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10)
                + " C";
    }

    // ------------------------------------------------------------------
    // Log statistics
    // ------------------------------------------------------------------

    /**
     * The timestamp prefix of a node log line ("2026-11-06 21:09:31"),
     * or null when the line does not start with one. The app's own
     * "[app]" lines carry no timestamp and never match — the range
     * therefore describes the node's lines, which is what a reader
     * dates the excerpt by.
     */
    static String lineTimestamp(String line) {
        if (line == null || line.length() < 19) {
            return null;
        }
        for (int i = 0; i < 19; i++) {
            char c = line.charAt(i);
            boolean ok;
            switch (i) {
                case 4:
                case 7:
                    ok = c == '-';
                    break;
                case 10:
                    ok = c == ' ';
                    break;
                case 13:
                case 16:
                    ok = c == ':';
                    break;
                default:
                    ok = c >= '0' && c <= '9';
                    break;
            }
            if (!ok) {
                return null;
            }
        }
        return line.substring(0, 19);
    }

    /** First timestamped line's stamp in the text, or null. */
    public static String firstLogTimestamp(String text) {
        if (text == null) {
            return null;
        }
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 ? text.length() : nl;
            String ts = lineTimestamp(text.substring(from, end));
            if (ts != null) {
                return ts;
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return null;
    }

    /** Last timestamped line's stamp in the text, or null. */
    public static String lastLogTimestamp(String text) {
        if (text == null) {
            return null;
        }
        int upto = text.length();
        while (upto > 0) {
            int nl = text.lastIndexOf('\n', upto - 1);
            int start = nl < 0 ? 0 : nl + 1;
            String ts = lineTimestamp(text.substring(start, upto));
            if (ts != null) {
                return ts;
            }
            if (nl < 0) {
                break;
            }
            upto = nl;
        }
        return null;
    }

    /**
     * Lines whose level token stands where a log level stands:
     * at the very start of the line, or — on one of the node's
     * timestamped lines — in the slot right after the 19-char
     * timestamp ("2026-11-06 21:01:00 WARN ..."). The position is
     * the point: the same word appearing later in a line's text —
     * a display name carrying "ERROR", a message mentioning a
     * warning — is content, not a level, and must not inflate the
     * summary's counts.
     */
    public static int countLevelLines(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 ? text.length() : nl;
            if (lineHasAnchoredToken(text, from, end, token)) {
                count++;
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return count;
    }

    private static boolean lineHasAnchoredToken(String text, int start,
                                                int end, String token) {
        if (tokenWordAt(text, start, end, start, token)) {
            return true;
        }
        // The level slot of a timestamped line: the timestamp is
        // 19 chars, so the level begins at 20, give or take the
        // spacing variants of the node's log format.
        if (end - start > 20
                && lineTimestamp(text.substring(start, end)) != null) {
            for (int pos = start + 20; pos <= start + 24
                    && pos + token.length() <= end; pos++) {
                if (tokenWordAt(text, start, end, pos, token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The token at exactly {@code pos}, as a word (no letters around it). */
    private static boolean tokenWordAt(String text, int lineStart,
                                       int lineEnd, int pos, String token) {
        int tlen = token.length();
        if (pos + tlen > lineEnd
                || !text.regionMatches(pos, token, 0, tlen)) {
            return false;
        }
        boolean beforeOk = pos == lineStart
                || !isAsciiLetter(text.charAt(pos - 1));
        int after = pos + tlen;
        boolean afterOk = after == lineEnd
                || !isAsciiLetter(text.charAt(after));
        return beforeOk && afterOk;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    // ------------------------------------------------------------------
    // Masking
    // ------------------------------------------------------------------

    /** Boundary classes for {@link #replaceBounded}. */
    private static final int CLASS_NAME = 0;
    private static final int CLASS_IPV4 = 1;
    private static final int CLASS_IPV6 = 2;

    /**
     * Replace the identifying values in a share text (the log
     * body — the summary block is assembled separately and never
     * passed through here): app-private path prefixes (longest
     * first, so a deeper prefix is not shadowed by its parent),
     * the device's own IP addresses, and the display name(s).
     * Everything else — versions, states, counts, other nodes'
     * data — is left byte-for-byte intact.
     *
     * Names and addresses are replaced only as whole tokens,
     * never as substrings: an occurrence flanked by the
     * characters that could extend the value (letters/digits for
     * a name, address characters for an address) is not the
     * value — the peer at 10.0.0.10 is not this device at
     * 10.0.0.1, and must survive the masking untouched. Whole-
     * token replacement is also what makes short names safe to
     * mask: a 1–2 character name is hidden where it stands as a
     * word and nowhere else, instead of shredding every word
     * that contains it. The caller passes every name the log can
     * carry (the running session's launched name and the current
     * setting's), and omits the generic default name, which
     * identifies the app, not the person.
     *
     * An IPv6 address is matched in every textual shape it can
     * take in a log — the collected form, the canonical form and
     * the expanded forms — because the same address is written
     * compressed by one component and expanded by another.
     */
    public static String maskSensitive(String text, List<String> nodeNames,
                                       List<String> ownIps,
                                       List<String> pathPrefixes) {
        if (text == null) {
            return null;
        }
        String out = text;
        if (pathPrefixes != null) {
            List<String> paths = new ArrayList<>(pathPrefixes);
            Collections.sort(paths, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            for (String p : paths) {
                if (p != null && p.length() >= 4) {
                    out = out.replace(p, PATH_MASK);
                }
            }
        }
        if (ownIps != null) {
            for (String ip : ownIps) {
                for (String variant : ipVariants(ip)) {
                    out = replaceBounded(out, variant, IP_MASK,
                            variant.indexOf(':') >= 0
                                    ? CLASS_IPV6 : CLASS_IPV4);
                }
            }
        }
        if (nodeNames != null) {
            // Longest first: a longer name containing a shorter
            // one must be masked whole, not clipped by it.
            List<String> names = new ArrayList<>();
            for (String n : nodeNames) {
                if (n != null && !n.isEmpty() && !names.contains(n)) {
                    names.add(n);
                }
            }
            Collections.sort(names, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            for (String n : names) {
                out = replaceBounded(out, n, NAME_MASK, CLASS_NAME);
            }
        }
        return out;
    }

    /**
     * Replace every whole-token occurrence of {@code token}: an
     * occurrence counts only when the characters around it cannot
     * be part of a longer value of the same kind (see
     * {@link #inBoundaryClass}). Replaced regions are not
     * rescanned.
     */
    private static String replaceBounded(String text, String token,
                                         String replacement,
                                         int boundaryClass) {
        if (token == null || token.isEmpty()) {
            return text;
        }
        StringBuilder out = null;
        // Two cursors, deliberately separate: "emitted" is how
        // much of the text has been copied to the output, "search"
        // is where the next lookup starts. A rejected occurrence
        // advances only the search — its text stays unemitted and
        // is copied by the next accepted replacement (or the
        // final append); sharing one cursor between the two jobs
        // would silently drop the text around every rejection.
        int emitted = 0;
        int search = 0;
        while (true) {
            int idx = text.indexOf(token, search);
            if (idx < 0) {
                break;
            }
            int after = idx + token.length();
            boolean beforeOk = idx == 0 || !inBoundaryClass(
                    text.charAt(idx - 1), boundaryClass);
            boolean afterOk = after == text.length() || !inBoundaryClass(
                    text.charAt(after), boundaryClass);
            if (beforeOk && afterOk) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, emitted, idx).append(replacement);
                emitted = after;
                search = after;
            } else {
                search = idx + 1;
            }
        }
        if (out == null) {
            return text;
        }
        out.append(text, emitted, text.length());
        return out.toString();
    }

    private static boolean inBoundaryClass(char c, int boundaryClass) {
        switch (boundaryClass) {
            case CLASS_IPV4:
                // A digit or a dot would make a longer address;
                // anything else (a port's ":", a bracket, a
                // space) ends it.
                return (c >= '0' && c <= '9') || c == '.';
            case CLASS_IPV6:
                return (c >= '0' && c <= '9') || c == ':'
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F') || c == '.';
            case CLASS_NAME:
            default:
                return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9');
        }
    }

    /**
     * The textual shapes one of the device's own addresses can
     * take in the log: the collected string itself, the canonical
     * form, and for IPv6 the fully expanded forms (zero-padded
     * and plain) plus the dotted-tail forms an IPv4-mapped
     * address is printed in. Unparseable input yields just the
     * collected string — masking then covers what was collected,
     * exactly as before.
     */
    static List<String> ipVariants(String ip) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ip == null) {
            return new ArrayList<>(out);
        }
        String raw = ip;
        int zone = raw.indexOf('%');
        if (zone >= 0) {
            raw = raw.substring(0, zone);
        }
        if (raw.isEmpty()) {
            return new ArrayList<>(out);
        }
        out.add(raw);
        boolean numeric = raw.indexOf(':') >= 0;
        if (!numeric) {
            numeric = true;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (!((c >= '0' && c <= '9') || c == '.')) {
                    numeric = false;
                    break;
                }
            }
        }
        if (numeric) {
            try {
                InetAddress addr = InetAddress.getByName(raw);
                String canon = addr.getHostAddress();
                if (canon != null) {
                    int cz = canon.indexOf('%');
                    out.add(cz >= 0 ? canon.substring(0, cz) : canon);
                }
                byte[] b = addr.getAddress();
                if (b != null && b.length == 16) {
                    out.add(expandIpv6(b, true));
                    out.add(expandIpv6(b, false));
                    // Generated here, not taken from
                    // getHostAddress(): whether that compresses
                    // is platform-dependent (the desktop JDK does
                    // not), and the compressed form is the one
                    // logs most often carry.
                    out.add(compressIpv6(b));
                    if (isV4Mapped(b)) {
                        String tail = (b[12] & 0xff) + "."
                                + (b[13] & 0xff) + "." + (b[14] & 0xff)
                                + "." + (b[15] & 0xff);
                        out.add("::ffff:" + tail);
                        out.add("0:0:0:0:0:ffff:" + tail);
                    }
                }
            } catch (Exception ignored) {
                // The collected string above still applies.
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    /**
     * The "::"-compressed form: the longest run of two or more
     * zero groups (first one on a tie) collapses to "::", groups
     * in plain lowercase hex — the shape the node's own log lines
     * and the platform both print.
     */
    private static String compressIpv6(byte[] b) {
        int[] g = new int[8];
        for (int i = 0; i < 8; i++) {
            g[i] = ((b[i * 2] & 0xff) << 8) | (b[i * 2 + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < 8; i++) {
            if (g[i] == 0) {
                if (curStart < 0) {
                    curStart = i;
                    curLen = 1;
                } else {
                    curLen++;
                }
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                }
            } else {
                curStart = -1;
                curLen = 0;
            }
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder(39);
        int i = 0;
        boolean needColon = false;
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen;
                needColon = false;
                continue;
            }
            if (needColon) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(g[i]));
            needColon = true;
            i++;
        }
        return sb.toString();
    }

    /** All eight groups written out, zero-padded or plain. */
    private static String expandIpv6(byte[] b, boolean padded) {
        StringBuilder sb = new StringBuilder(39);
        for (int g = 0; g < 8; g++) {
            if (g > 0) {
                sb.append(':');
            }
            int v = ((b[g * 2] & 0xff) << 8) | (b[g * 2 + 1] & 0xff);
            String hex = Integer.toHexString(v);
            if (padded) {
                for (int i = hex.length(); i < 4; i++) {
                    sb.append('0');
                }
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Network label (spec B)
    // ------------------------------------------------------------------

    /**
     * The transport word the service stamps into the log, in the
     * spec's four classes: "wifi", "cellular", "other" for
     * anything else connected (ethernet, VPN, …) and "none" when
     * there is no default network. Ethernet folds into "other" —
     * the label answers "what kind of connection is the node
     * riding on" for a phone, and a wired transport needs no
     * class of its own there.
     */
    public static String networkLabel(boolean connected, boolean wifi,
                                      boolean ethernet, boolean cellular) {
        if (!connected) {
            return "none";
        }
        if (wifi) {
            return "wifi";
        }
        if (cellular) {
            return "cellular";
        }
        return "other";
    }
}
