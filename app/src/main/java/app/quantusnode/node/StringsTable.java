package app.quantusnode.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * All user-visible text of the app, per language — deliberately pure
 * Java (no Android imports) so the tables can be checked mechanically
 * on a JVM: every key must exist in every language, and format
 * placeholders must match the source. The Android side resolves which
 * language to use via {@link I18n}; this class only holds the words.
 *
 * Japanese (ja) is the source language: the original strings were
 * written in Japanese, and any key missing from another language falls
 * back to Japanese — the mechanical check exists to keep that fallback
 * from ever being needed.
 *
 * Number policy (decided, do not change casually): digits are always
 * ASCII 0-9. {@link I18n#format} formats with Locale.US, so e.g. Arabic
 * and Hindi do not switch to local digit shapes. Strings that take a
 * %1$s receive a number the CALLER has already formatted as a string;
 * for the values the versionCode 26 UI shows (block heights, peer
 * counts, track counts, remaining-block counts) the caller formats
 * with 3-digit grouping ({@code UiLogic.grouped}), while %1$d formats
 * (diagnostic records, notification) stay ungrouped, as before.
 *
 * Quantity (plural) strings: a quantity base key such as
 * {@code duration_minutes} has one entry per plural category, stored
 * under {@code <base>.zero/.one/.two/.few/.many/.other} in EVERY
 * language (the key sets stay identical; a language whose grammar
 * never selects a category simply repeats its "other" text there).
 * {@link #quantity(String, String, long)} picks the category with
 * {@link #pluralCategory(String, long)} from the long count itself —
 * the count is never narrowed to int just to pick a wording.
 *
 * Language names in the picker are shown in each language's own script
 * (the platform convention) and are NOT translated per UI language;
 * they live in {@link #LANGUAGE_NAMES}.
 *
 * To fix or improve a translation, edit this file and docs/I18N.md
 * (the table there is generated from these maps) — pull requests
 * from native speakers are very welcome.
 */
public final class StringsTable {

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    public static final String APP_SUBTITLE = "app_subtitle";
    public static final String STATUS_STOPPED = "status_stopped";
    public static final String STATUS_STARTING = "status_starting";
    public static final String STATUS_SYNCING = "status_syncing";
    public static final String STATUS_SYNCED = "status_synced";
    public static final String LABEL_BEST_BLOCK = "label_best_block";
    public static final String LABEL_HIGHEST_BLOCK = "label_highest_block";
    public static final String LABEL_PEERS = "label_peers";
    public static final String BUTTON_START_NODE = "button_start_node";
    public static final String BUTTON_STOP_NODE = "button_stop_node";
    public static final String SECTION_TRACK = "section_track";
    public static final String LABEL_UPTIME_TOTAL = "label_uptime_total";
    public static final String LABEL_BLOCKS_OBSERVED = "label_blocks_observed";
    public static final String LABEL_MAX_PEERS = "label_max_peers";
    public static final String SECTION_SETTINGS = "section_settings";
    public static final String SETTINGS_TELEMETRY = "settings_telemetry";
    public static final String SETTINGS_TELEMETRY_NOTE = "settings_telemetry_note";
    public static final String SETTINGS_DISPLAY_NAME = "settings_display_name";
    public static final String SETTINGS_LANGUAGE = "settings_language";
    public static final String LANGUAGE_FOLLOW_SYSTEM = "language_follow_system";
    public static final String SETTINGS_LOCK = "settings_lock";
    /** Always-visible one-liner under the settings-lock switch. */
    public static final String SETTINGS_LOCK_NOTE = "settings_lock_note";
    /** Format: %1$d = exit code. */
    public static final String NOTE_LAST_EXIT_CODE = "note_last_exit_code";
    public static final String NOTIF_CHANNEL_NAME = "notif_channel_name";
    public static final String NOTIF_CHANNEL_DESC = "notif_channel_desc";
    /** Format: %1$d = peer count. Joined to the status text with " · ". */
    public static final String NOTIF_PEERS = "notif_peers";
    public static final String NOTIF_ACTION_STOP = "notif_action_stop";
    public static final String NOTE_FGS_TIMEOUT = "note_fgs_timeout";
    public static final String NOTE_BINARY_MISSING = "note_binary_missing";
    /** Format: %1$s = error message from the OS. */
    public static final String NOTE_START_FAILED = "note_start_failed";
    /** Heading above the per-attempt start records (evidence build). */
    public static final String ATTEMPTS_TITLE = "attempts_title";
    /** Launch method label: the binary executed directly. */
    public static final String ATTEMPT_METHOD_DIRECT = "attempt_method_direct";
    /** Launch method label: the binary run through /system/bin/linker64. */
    public static final String ATTEMPT_METHOD_LINKER = "attempt_method_linker";
    /** Format: %1$d = attempt number, %2$s = method, %3$d = exit code, %4$d = lived ms. */
    public static final String ATTEMPT_SUMMARY = "attempt_summary";
    /** Format: %1$d = attempt number, %2$s = method, %3$d = lived ms. */
    public static final String ATTEMPT_SUMMARY_NO_EXIT = "attempt_summary_no_exit";
    /** Shown under an attempt whose process printed nothing at all. */
    public static final String ATTEMPT_NO_OUTPUT = "attempt_no_output";
    public static final String BUTTON_SHARE_LOG = "button_share_log";
    /** Subject line of the shared log (a product name; kept as-is in every language). */
    public static final String SHARE_LOG_SUBJECT = "share_log_subject";
    /** Toast shown when there is no log file (or it is empty) to share. */
    public static final String LOG_MISSING_NOTE = "log_missing_note";
    public static final String STATUS_START_FAILED = "status_start_failed";
    public static final String STATUS_UNEXPECTED_STOP = "status_unexpected_stop";
    public static final String MESSAGE_START_FAILED = "message_start_failed";
    public static final String MESSAGE_UNEXPECTED_STOP = "message_unexpected_stop";
    public static final String MESSAGE_TIME_LIMIT_STOP = "message_time_limit_stop";
    public static final String MESSAGE_BINARY_MISSING = "message_binary_missing";
    public static final String BUTTON_RETRY = "button_retry";
    public static final String BUTTON_CANCEL_STARTUP = "button_cancel_startup";
    public static final String BUTTON_STOPPING = "button_stopping";
    public static final String ACTION_FAILURE_DETAILS = "action_failure_details";
    public static final String ACTION_STOP_DETAILS = "action_stop_details";
    public static final String LABEL_UPTIME_SESSION = "label_uptime_session";
    public static final String MESSAGE_SYNC_TARGET_UNKNOWN = "message_sync_target_unknown";
    public static final String MESSAGE_NO_PEERS = "message_no_peers";
    public static final String MESSAGE_WAITING_BLOCK = "message_waiting_block";
    public static final String VALUE_UNAVAILABLE = "value_unavailable";
    public static final String OBSERVATION_JUST_NOW = "observation_just_now";
    public static final String SCREEN_NODE_DETAILS = "screen_node_details";
    public static final String SECTION_DIAGNOSTICS = "section_diagnostics";
    public static final String LABEL_SYNC_GAP = "label_sync_gap";
    public static final String SYNC_GAP_NOTE = "sync_gap_note";
    public static final String STOPPED_CUMULATIVE_NOTE = "stopped_cumulative_note";
    public static final String TRACK_LOCAL_NOTE = "track_local_note";
    public static final String RPC_LOCAL_NOTE = "rpc_local_note";
    public static final String LABEL_LOG_LOCATION = "label_log_location";
    public static final String DIAGNOSTICS_EMPTY = "diagnostics_empty";
    public static final String MESSAGE_USER_STOP = "message_user_stop";
    /** Format: %1$s = version name (e.g. 0.2.0). */
    public static final String VERSION_FORMAT = "version_format";
    public static final String SETTINGS_NODE_RESTART_NOTE = "settings_node_restart_note";
    public static final String SETTINGS_LANGUAGE_IMMEDIATE = "settings_language_immediate";
    public static final String SETTINGS_PENDING_LINK = "settings_pending_link";
    public static final String SETTINGS_PENDING_STATUS = "settings_pending_status";
    /** Format: %1$s = the value the running node was launched with. */
    public static final String SETTINGS_APPLIED_VALUE = "settings_applied_value";
    /** A switch state shown as a value (e.g. inside "In use: …"). */
    public static final String VALUE_ON = "value_on";
    /** A switch state shown as a value (e.g. inside "In use: …"). */
    public static final String VALUE_OFF = "value_off";
    public static final String SETTINGS_NAME_DEFAULT = "settings_name_default";
    public static final String SETTINGS_NAME_EMPTY_HINT = "settings_name_empty_hint";
    /** Format: %1$s = the name as it will be saved (after sanitizing). */
    public static final String SETTINGS_NAME_SAVED_PREVIEW = "settings_name_saved_preview";
    public static final String SETTINGS_NAME_LOCKED = "settings_name_locked";
    /** Format: %1$s = current code-point count, %2$s = the limit (64). */
    public static final String CHARACTER_COUNTER_FORMAT = "character_counter_format";
    public static final String BUTTON_SAVE = "button_save";
    public static final String A11Y_BACK = "a11y_back";
    public static final String A11Y_EXPANDED = "a11y_expanded";
    public static final String A11Y_COLLAPSED = "a11y_collapsed";
    /** Joins two duration parts: %1$s and %2$s. */
    public static final String DURATION_JOIN_TWO = "duration_join_two";
    /** Joins three duration parts: %1$s, %2$s and %3$s. */
    public static final String DURATION_JOIN_THREE = "duration_join_three";

    // Quantity base keys: the stored keys are "<base>.<category>";
    // resolve them through quantity(), never with get() directly.
    public static final String SYNC_REMAINING_BLOCKS = "sync_remaining_blocks";
    public static final String OBSERVATION_SECONDS_AGO = "observation_seconds_ago";
    public static final String OBSERVATION_MINUTES_AGO = "observation_minutes_ago";
    public static final String OBSERVATION_HOURS_AGO = "observation_hours_ago";
    public static final String OBSERVATION_DAYS_AGO = "observation_days_ago";
    public static final String DURATION_LESS_THAN_MINUTES = "duration_less_than_minutes";
    public static final String DURATION_MINUTES = "duration_minutes";
    public static final String DURATION_HOURS = "duration_hours";
    public static final String DURATION_DAYS = "duration_days";
    public static final String DISPLAY_NAME_LIMIT = "display_name_limit";
    public static final String DISPLAY_NAME_COUNT_A11Y = "display_name_count_a11y";

    /** Supported language tags, in picker order. "zh" is Simplified Chinese. */
    public static final String[] TAGS = {
            "ja", "en", "zh", "es", "hi", "ar", "ko", "ru", "fr"
    };

    /** The source language every key is written in first. */
    public static final String SOURCE_TAG = "ja";

    /** Plural categories, in table order. */
    private static final String[] CATEGORIES = {
            "zero", "one", "two", "few", "many", "other"
    };

    /** Each language's name in its own script (picker labels, not translated). */
    public static final Map<String, String> LANGUAGE_NAMES;

    private static final Map<String, Map<String, String>> TABLES;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("ja", "日本語");
        names.put("en", "English");
        names.put("zh", "简体中文");
        names.put("es", "Español");
        names.put("hi", "हिन्दी");
        names.put("ar", "العربية");
        names.put("ko", "한국어");
        names.put("ru", "Русский");
        names.put("fr", "Français");
        LANGUAGE_NAMES = Collections.unmodifiableMap(names);

        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        all.put("ja", ja());
        all.put("en", en());
        all.put("zh", zh());
        all.put("es", es());
        all.put("hi", hi());
        all.put("ar", ar());
        all.put("ko", ko());
        all.put("ru", ru());
        all.put("fr", fr());
        TABLES = Collections.unmodifiableMap(all);
    }

    private StringsTable() {
    }

    /** The full table for a language tag (source table for unknown tags). */
    public static Map<String, String> table(String tag) {
        Map<String, String> t = TABLES.get(tag);
        return t != null ? t : TABLES.get(SOURCE_TAG);
    }

    /** All keys, in definition order (the source table's key set). */
    public static Set<String> keys() {
        return TABLES.get(SOURCE_TAG).keySet();
    }

    /** One string: language table first, Japanese fallback, key as last resort. */
    public static String get(String tag, String key) {
        String v = table(tag).get(key);
        if (v != null) {
            return v;
        }
        v = TABLES.get(SOURCE_TAG).get(key);
        return v != null ? v : key;
    }

    /**
     * The plural category of {@code n} in {@code tag}, following the
     * CLDR cardinal rules for the nine supported languages. Counts
     * are non-negative in every caller; a negative value is judged
     * by its absolute value rather than falling out of the rules.
     */
    public static String pluralCategory(String tag, long n) {
        long a = n < 0 ? -n : n;
        switch (tag) {
            case "ja":
            case "zh":
            case "ko":
                return "other";
            case "en":
            case "es":
                return a == 1 ? "one" : "other";
            case "fr":
            case "hi":
                return (a == 0 || a == 1) ? "one" : "other";
            case "ar": {
                if (a == 0) {
                    return "zero";
                }
                if (a == 1) {
                    return "one";
                }
                if (a == 2) {
                    return "two";
                }
                long m100 = a % 100;
                if (m100 >= 3 && m100 <= 10) {
                    return "few";
                }
                if (m100 >= 11 && m100 <= 99) {
                    return "many";
                }
                return "other";
            }
            case "ru": {
                long m10 = a % 10;
                long m100 = a % 100;
                if (m10 == 1 && m100 != 11) {
                    return "one";
                }
                if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) {
                    return "few";
                }
                if (m10 == 0 || (m10 >= 5 && m10 <= 9)
                        || (m100 >= 11 && m100 <= 14)) {
                    return "many";
                }
                return "other";
            }
            default:
                return a == 1 ? "one" : "other";
        }
    }

    /**
     * One quantity string: the entry for the category
     * {@link #pluralCategory} selects for {@code n}, falling back to
     * the language's "other" entry, then to the source language.
     * The returned text still carries its %1$s (and %2$s) format
     * placeholders; the caller formats it with the already-formatted
     * number string, so the wording choice and the rendered number
     * stay separate (and the count itself stays a long).
     */
    public static String quantity(String tag, String baseKey, long n) {
        String cat = pluralCategory(tag, n);
        Map<String, String> t = table(tag);
        String v = t.get(baseKey + "." + cat);
        if (v == null && !"other".equals(cat)) {
            v = t.get(baseKey + ".other");
        }
        if (v == null) {
            v = get(tag, baseKey + ".other");
        }
        return v;
    }

    private static Map<String, String> m(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("odd key/value count");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------
    // 日本語 (source)
    // ------------------------------------------------------------------

    private static Map<String, String> ja() {
        return m(
                APP_SUBTITLE, "mainnet · フルノード",
                STATUS_STOPPED, "停止中",
                STATUS_STARTING, "起動中",
                STATUS_SYNCING, "同期中",
                STATUS_SYNCED, "同期完了",
                LABEL_BEST_BLOCK, "ベストブロック（この端末）",
                LABEL_HIGHEST_BLOCK, "最高ブロック（観測値）",
                LABEL_PEERS, "接続ピア数",
                BUTTON_START_NODE, "ノードを開始",
                BUTTON_STOP_NODE, "ノードを停止",
                SECTION_TRACK, "航跡",
                LABEL_UPTIME_TOTAL, "累計稼働時間",
                LABEL_BLOCKS_OBSERVED, "観測した新ブロック数",
                LABEL_MAX_PEERS, "観測したピア数の最大値",
                SECTION_SETTINGS, "設定",
                SETTINGS_TELEMETRY, "テレメトリに表示する",
                SETTINGS_TELEMETRY_NOTE, "オンにすると、ノード名と稼働状態が公式テレメトリ（telemetry.quantus.com）で誰でも見える状態になります。既定はオフです。",
                SETTINGS_DISPLAY_NAME, "表示名",
                SETTINGS_LANGUAGE, "言語",
                LANGUAGE_FOLLOW_SYSTEM, "端末の設定に従う",
                SETTINGS_LOCK, "設定をロック",
                SETTINGS_LOCK_NOTE, "テレメトリ・表示名・言語の誤変更を防ぎます。開始・停止はロックされません。",
                NOTE_LAST_EXIT_CODE, "前回の終了コード: %1$d",
                NOTIF_CHANNEL_NAME, "ノードの状態",
                NOTIF_CHANNEL_DESC, "Quantus ノードの稼働状態",
                NOTIF_PEERS, "ピア %1$d",
                NOTIF_ACTION_STOP, "停止",
                NOTE_FGS_TIMEOUT, "システムの時間制限により停止しました",
                NOTE_BINARY_MISSING, "ノードバイナリが見つかりません",
                NOTE_START_FAILED, "起動に失敗しました: %1$s",
                ATTEMPTS_TITLE, "直近の起動試行",
                ATTEMPT_METHOD_DIRECT, "直接実行",
                ATTEMPT_METHOD_LINKER, "linker64 経由",
                ATTEMPT_SUMMARY, "試行 %1$d（%2$s）: 終了コード %3$d · %4$d ミリ秒",
                ATTEMPT_SUMMARY_NO_EXIT, "試行 %1$d（%2$s）: 起動できませんでした · %3$d ミリ秒",
                ATTEMPT_NO_OUTPUT, "（出力なし）",
                BUTTON_SHARE_LOG, "ログを共有",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "ログはまだありません",
                STATUS_START_FAILED, "起動できませんでした",
                STATUS_UNEXPECTED_STOP, "予期せず停止しました",
                MESSAGE_START_FAILED, "起動時の記録を確認できます。",
                MESSAGE_UNEXPECTED_STOP, "終了時の記録を確認できます。",
                MESSAGE_TIME_LIMIT_STOP, "システムの時間制限により停止しました。",
                MESSAGE_BINARY_MISSING, "ノードを起動するためのファイルが見つかりません。",
                BUTTON_RETRY, "再試行",
                BUTTON_CANCEL_STARTUP, "起動を中止",
                BUTTON_STOPPING, "停止しています…",
                ACTION_FAILURE_DETAILS, "失敗の詳細",
                ACTION_STOP_DETAILS, "停止の詳細",
                LABEL_UPTIME_SESSION, "今回の稼働時間",
                MESSAGE_SYNC_TARGET_UNKNOWN, "同期先を確認しています。",
                MESSAGE_NO_PEERS, "ピア接続がありません。",
                MESSAGE_WAITING_BLOCK, "新しいブロックの観測を待っています。",
                VALUE_UNAVAILABLE, "未取得",
                OBSERVATION_JUST_NOW, "最終観測：たった今",
                SCREEN_NODE_DETAILS, "ノード詳細",
                SECTION_DIAGNOSTICS, "診断",
                LABEL_SYNC_GAP, "同期差（目安）",
                SYNC_GAP_NOTE, "同期差は最高ブロックとベストブロックの差です。差が0でも、同期完了はノードの状態で判定します。",
                STOPPED_CUMULATIVE_NOTE, "停止中は累計記録を表示しています。",
                TRACK_LOCAL_NOTE, "この端末内の記録です。送信されません。",
                RPC_LOCAL_NOTE, "RPCはこの端末内でのみ利用できます。",
                LABEL_LOG_LOCATION, "ログの保存場所",
                DIAGNOSTICS_EMPTY, "表示できる診断記録はありません。",
                MESSAGE_USER_STOP, "ユーザーの操作で停止しました。",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "テレメトリと表示名の変更は、次のノード起動時に反映されます。",
                SETTINGS_LANGUAGE_IMMEDIATE, "選択するとすぐに表示言語が変わります。",
                SETTINGS_PENDING_LINK, "未適用の設定を確認",
                SETTINGS_PENDING_STATUS, "次のノード起動で反映",
                SETTINGS_APPLIED_VALUE, "適用中：%1$s",
                VALUE_ON, "オン",
                VALUE_OFF, "オフ",
                SETTINGS_NAME_DEFAULT, "既定の名前",
                SETTINGS_NAME_EMPTY_HINT, "空欄で既定の名前を使用します。",
                SETTINGS_NAME_SAVED_PREVIEW, "保存される名前：%1$s",
                SETTINGS_NAME_LOCKED, "設定ロック中は編集できません。",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "保存",
                A11Y_BACK, "戻る",
                A11Y_EXPANDED, "展開中",
                A11Y_COLLAPSED, "折りたたみ",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "あと%1$sブロック（目安）",
                SYNC_REMAINING_BLOCKS + ".one", "あと%1$sブロック（目安）",
                SYNC_REMAINING_BLOCKS + ".two", "あと%1$sブロック（目安）",
                SYNC_REMAINING_BLOCKS + ".few", "あと%1$sブロック（目安）",
                SYNC_REMAINING_BLOCKS + ".many", "あと%1$sブロック（目安）",
                SYNC_REMAINING_BLOCKS + ".other", "あと%1$sブロック（目安）",
                OBSERVATION_SECONDS_AGO + ".zero", "最終観測：%1$s秒前",
                OBSERVATION_SECONDS_AGO + ".one", "最終観測：%1$s秒前",
                OBSERVATION_SECONDS_AGO + ".two", "最終観測：%1$s秒前",
                OBSERVATION_SECONDS_AGO + ".few", "最終観測：%1$s秒前",
                OBSERVATION_SECONDS_AGO + ".many", "最終観測：%1$s秒前",
                OBSERVATION_SECONDS_AGO + ".other", "最終観測：%1$s秒前",
                OBSERVATION_MINUTES_AGO + ".zero", "最終観測：%1$s分前",
                OBSERVATION_MINUTES_AGO + ".one", "最終観測：%1$s分前",
                OBSERVATION_MINUTES_AGO + ".two", "最終観測：%1$s分前",
                OBSERVATION_MINUTES_AGO + ".few", "最終観測：%1$s分前",
                OBSERVATION_MINUTES_AGO + ".many", "最終観測：%1$s分前",
                OBSERVATION_MINUTES_AGO + ".other", "最終観測：%1$s分前",
                OBSERVATION_HOURS_AGO + ".zero", "最終観測：%1$s時間前",
                OBSERVATION_HOURS_AGO + ".one", "最終観測：%1$s時間前",
                OBSERVATION_HOURS_AGO + ".two", "最終観測：%1$s時間前",
                OBSERVATION_HOURS_AGO + ".few", "最終観測：%1$s時間前",
                OBSERVATION_HOURS_AGO + ".many", "最終観測：%1$s時間前",
                OBSERVATION_HOURS_AGO + ".other", "最終観測：%1$s時間前",
                OBSERVATION_DAYS_AGO + ".zero", "最終観測：%1$s日前",
                OBSERVATION_DAYS_AGO + ".one", "最終観測：%1$s日前",
                OBSERVATION_DAYS_AGO + ".two", "最終観測：%1$s日前",
                OBSERVATION_DAYS_AGO + ".few", "最終観測：%1$s日前",
                OBSERVATION_DAYS_AGO + ".many", "最終観測：%1$s日前",
                OBSERVATION_DAYS_AGO + ".other", "最終観測：%1$s日前",
                DURATION_LESS_THAN_MINUTES + ".zero", "%1$s分未満",
                DURATION_LESS_THAN_MINUTES + ".one", "%1$s分未満",
                DURATION_LESS_THAN_MINUTES + ".two", "%1$s分未満",
                DURATION_LESS_THAN_MINUTES + ".few", "%1$s分未満",
                DURATION_LESS_THAN_MINUTES + ".many", "%1$s分未満",
                DURATION_LESS_THAN_MINUTES + ".other", "%1$s分未満",
                DURATION_MINUTES + ".zero", "%1$s分",
                DURATION_MINUTES + ".one", "%1$s分",
                DURATION_MINUTES + ".two", "%1$s分",
                DURATION_MINUTES + ".few", "%1$s分",
                DURATION_MINUTES + ".many", "%1$s分",
                DURATION_MINUTES + ".other", "%1$s分",
                DURATION_HOURS + ".zero", "%1$s時間",
                DURATION_HOURS + ".one", "%1$s時間",
                DURATION_HOURS + ".two", "%1$s時間",
                DURATION_HOURS + ".few", "%1$s時間",
                DURATION_HOURS + ".many", "%1$s時間",
                DURATION_HOURS + ".other", "%1$s時間",
                DURATION_DAYS + ".zero", "%1$s日",
                DURATION_DAYS + ".one", "%1$s日",
                DURATION_DAYS + ".two", "%1$s日",
                DURATION_DAYS + ".few", "%1$s日",
                DURATION_DAYS + ".many", "%1$s日",
                DURATION_DAYS + ".other", "%1$s日",
                DISPLAY_NAME_LIMIT + ".zero", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_LIMIT + ".one", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_LIMIT + ".two", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_LIMIT + ".few", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_LIMIT + ".many", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_LIMIT + ".other", "保存できる長さは%1$s文字までです。",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "保存時の文字数は%1$s文字です。上限は%2$s文字です。",
                DISPLAY_NAME_COUNT_A11Y + ".one", "保存時の文字数は%1$s文字です。上限は%2$s文字です。",
                DISPLAY_NAME_COUNT_A11Y + ".two", "保存時の文字数は%1$s文字です。上限は%2$s文字です。",
                DISPLAY_NAME_COUNT_A11Y + ".few", "保存時の文字数は%1$s文字です。上限は%2$s文字です。",
                DISPLAY_NAME_COUNT_A11Y + ".many", "保存時の文字数は%1$s文字です。上限は%2$s文字です。",
                DISPLAY_NAME_COUNT_A11Y + ".other", "保存時の文字数は%1$s文字です。上限は%2$s文字です。"
        );
    }

    // ------------------------------------------------------------------
    // English
    // ------------------------------------------------------------------

    private static Map<String, String> en() {
        return m(
                APP_SUBTITLE, "mainnet · full node",
                STATUS_STOPPED, "Stopped",
                STATUS_STARTING, "Starting",
                STATUS_SYNCING, "Syncing",
                STATUS_SYNCED, "Synced",
                LABEL_BEST_BLOCK, "Best block (this device)",
                LABEL_HIGHEST_BLOCK, "Highest observed block",
                LABEL_PEERS, "Connected peers",
                BUTTON_START_NODE, "Start node",
                BUTTON_STOP_NODE, "Stop node",
                SECTION_TRACK, "Track record",
                LABEL_UPTIME_TOTAL, "Total uptime",
                LABEL_BLOCKS_OBSERVED, "New blocks observed",
                LABEL_MAX_PEERS, "Peak peer count observed",
                SECTION_SETTINGS, "Settings",
                SETTINGS_TELEMETRY, "Appear in telemetry",
                SETTINGS_TELEMETRY_NOTE, "When on, your node name and live status become publicly visible in the official telemetry (telemetry.quantus.com). Off by default.",
                SETTINGS_DISPLAY_NAME, "Display name",
                SETTINGS_LANGUAGE, "Language",
                LANGUAGE_FOLLOW_SYSTEM, "Follow device settings",
                SETTINGS_LOCK, "Lock settings",
                SETTINGS_LOCK_NOTE, "Prevents accidental changes to telemetry, display name, and language. Starting and stopping the node is never locked.",
                NOTE_LAST_EXIT_CODE, "Last exit code: %1$d",
                NOTIF_CHANNEL_NAME, "Node status",
                NOTIF_CHANNEL_DESC, "Quantus node running status",
                NOTIF_PEERS, "%1$d peers",
                NOTIF_ACTION_STOP, "Stop",
                NOTE_FGS_TIMEOUT, "Stopped by the system's time limit",
                NOTE_BINARY_MISSING, "Node binary not found",
                NOTE_START_FAILED, "Failed to start: %1$s",
                ATTEMPTS_TITLE, "Latest start attempts",
                ATTEMPT_METHOD_DIRECT, "direct",
                ATTEMPT_METHOD_LINKER, "via linker64",
                ATTEMPT_SUMMARY, "Attempt %1$d (%2$s): exit code %3$d · %4$d ms",
                ATTEMPT_SUMMARY_NO_EXIT, "Attempt %1$d (%2$s): could not start · %3$d ms",
                ATTEMPT_NO_OUTPUT, "(no output)",
                BUTTON_SHARE_LOG, "Share log",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "No log yet",
                STATUS_START_FAILED, "Couldn't start the node",
                STATUS_UNEXPECTED_STOP, "Node stopped unexpectedly",
                MESSAGE_START_FAILED, "You can review the startup details.",
                MESSAGE_UNEXPECTED_STOP, "You can review the shutdown details.",
                MESSAGE_TIME_LIMIT_STOP, "Stopped because of the system time limit.",
                MESSAGE_BINARY_MISSING, "The file needed to run the node is missing.",
                BUTTON_RETRY, "Try again",
                BUTTON_CANCEL_STARTUP, "Cancel startup",
                BUTTON_STOPPING, "Stopping…",
                ACTION_FAILURE_DETAILS, "Failure details",
                ACTION_STOP_DETAILS, "Stop details",
                LABEL_UPTIME_SESSION, "Current uptime",
                MESSAGE_SYNC_TARGET_UNKNOWN, "Checking the sync target.",
                MESSAGE_NO_PEERS, "No peers are connected.",
                MESSAGE_WAITING_BLOCK, "Waiting to observe a new block.",
                VALUE_UNAVAILABLE, "Not available",
                OBSERVATION_JUST_NOW, "Last observed just now",
                SCREEN_NODE_DETAILS, "Node details",
                SECTION_DIAGNOSTICS, "Diagnostics",
                LABEL_SYNC_GAP, "Sync gap (estimate)",
                SYNC_GAP_NOTE, "The sync gap is the difference between the highest and best block heights. A gap of zero does not by itself mean the node is synced.",
                STOPPED_CUMULATIVE_NOTE, "Showing cumulative records while the node is stopped.",
                TRACK_LOCAL_NOTE, "These records stay on this device and are not sent.",
                RPC_LOCAL_NOTE, "RPC is available only on this device.",
                LABEL_LOG_LOCATION, "Log location",
                DIAGNOSTICS_EMPTY, "No diagnostic records are available.",
                MESSAGE_USER_STOP, "Stopped by the user.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "Changes to telemetry and the display name take effect the next time the node starts.",
                SETTINGS_LANGUAGE_IMMEDIATE, "Selecting a language changes the app language immediately.",
                SETTINGS_PENDING_LINK, "Review pending settings",
                SETTINGS_PENDING_STATUS, "Applies at the next node start",
                SETTINGS_APPLIED_VALUE, "In use: %1$s",
                VALUE_ON, "On",
                VALUE_OFF, "Off",
                SETTINGS_NAME_DEFAULT, "Default name",
                SETTINGS_NAME_EMPTY_HINT, "Leave blank to use the default name.",
                SETTINGS_NAME_SAVED_PREVIEW, "Name to save: %1$s",
                SETTINGS_NAME_LOCKED, "Editing is disabled while settings are locked.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "Save",
                A11Y_BACK, "Back",
                A11Y_EXPANDED, "Expanded",
                A11Y_COLLAPSED, "Collapsed",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "%1$s blocks remaining (estimate)",
                SYNC_REMAINING_BLOCKS + ".one", "%1$s block remaining (estimate)",
                SYNC_REMAINING_BLOCKS + ".two", "%1$s blocks remaining (estimate)",
                SYNC_REMAINING_BLOCKS + ".few", "%1$s blocks remaining (estimate)",
                SYNC_REMAINING_BLOCKS + ".many", "%1$s blocks remaining (estimate)",
                SYNC_REMAINING_BLOCKS + ".other", "%1$s blocks remaining (estimate)",
                OBSERVATION_SECONDS_AGO + ".zero", "Last observed %1$s seconds ago",
                OBSERVATION_SECONDS_AGO + ".one", "Last observed %1$s second ago",
                OBSERVATION_SECONDS_AGO + ".two", "Last observed %1$s seconds ago",
                OBSERVATION_SECONDS_AGO + ".few", "Last observed %1$s seconds ago",
                OBSERVATION_SECONDS_AGO + ".many", "Last observed %1$s seconds ago",
                OBSERVATION_SECONDS_AGO + ".other", "Last observed %1$s seconds ago",
                OBSERVATION_MINUTES_AGO + ".zero", "Last observed %1$s minutes ago",
                OBSERVATION_MINUTES_AGO + ".one", "Last observed %1$s minute ago",
                OBSERVATION_MINUTES_AGO + ".two", "Last observed %1$s minutes ago",
                OBSERVATION_MINUTES_AGO + ".few", "Last observed %1$s minutes ago",
                OBSERVATION_MINUTES_AGO + ".many", "Last observed %1$s minutes ago",
                OBSERVATION_MINUTES_AGO + ".other", "Last observed %1$s minutes ago",
                OBSERVATION_HOURS_AGO + ".zero", "Last observed %1$s hours ago",
                OBSERVATION_HOURS_AGO + ".one", "Last observed %1$s hour ago",
                OBSERVATION_HOURS_AGO + ".two", "Last observed %1$s hours ago",
                OBSERVATION_HOURS_AGO + ".few", "Last observed %1$s hours ago",
                OBSERVATION_HOURS_AGO + ".many", "Last observed %1$s hours ago",
                OBSERVATION_HOURS_AGO + ".other", "Last observed %1$s hours ago",
                OBSERVATION_DAYS_AGO + ".zero", "Last observed %1$s days ago",
                OBSERVATION_DAYS_AGO + ".one", "Last observed %1$s day ago",
                OBSERVATION_DAYS_AGO + ".two", "Last observed %1$s days ago",
                OBSERVATION_DAYS_AGO + ".few", "Last observed %1$s days ago",
                OBSERVATION_DAYS_AGO + ".many", "Last observed %1$s days ago",
                OBSERVATION_DAYS_AGO + ".other", "Last observed %1$s days ago",
                DURATION_LESS_THAN_MINUTES + ".zero", "Less than %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".one", "Less than %1$s minute",
                DURATION_LESS_THAN_MINUTES + ".two", "Less than %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".few", "Less than %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".many", "Less than %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".other", "Less than %1$s minutes",
                DURATION_MINUTES + ".zero", "%1$s minutes",
                DURATION_MINUTES + ".one", "%1$s minute",
                DURATION_MINUTES + ".two", "%1$s minutes",
                DURATION_MINUTES + ".few", "%1$s minutes",
                DURATION_MINUTES + ".many", "%1$s minutes",
                DURATION_MINUTES + ".other", "%1$s minutes",
                DURATION_HOURS + ".zero", "%1$s hours",
                DURATION_HOURS + ".one", "%1$s hour",
                DURATION_HOURS + ".two", "%1$s hours",
                DURATION_HOURS + ".few", "%1$s hours",
                DURATION_HOURS + ".many", "%1$s hours",
                DURATION_HOURS + ".other", "%1$s hours",
                DURATION_DAYS + ".zero", "%1$s days",
                DURATION_DAYS + ".one", "%1$s day",
                DURATION_DAYS + ".two", "%1$s days",
                DURATION_DAYS + ".few", "%1$s days",
                DURATION_DAYS + ".many", "%1$s days",
                DURATION_DAYS + ".other", "%1$s days",
                DISPLAY_NAME_LIMIT + ".zero", "You can save up to %1$s characters.",
                DISPLAY_NAME_LIMIT + ".one", "You can save up to %1$s character.",
                DISPLAY_NAME_LIMIT + ".two", "You can save up to %1$s characters.",
                DISPLAY_NAME_LIMIT + ".few", "You can save up to %1$s characters.",
                DISPLAY_NAME_LIMIT + ".many", "You can save up to %1$s characters.",
                DISPLAY_NAME_LIMIT + ".other", "You can save up to %1$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "Length when saved: %1$s characters. Limit: %2$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "Length when saved: %1$s character. Limit: %2$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "Length when saved: %1$s characters. Limit: %2$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "Length when saved: %1$s characters. Limit: %2$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "Length when saved: %1$s characters. Limit: %2$s characters.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "Length when saved: %1$s characters. Limit: %2$s characters."
        );
    }

    // ------------------------------------------------------------------
    // 简体中文 (Simplified Chinese)
    // ------------------------------------------------------------------

    private static Map<String, String> zh() {
        return m(
                APP_SUBTITLE, "主网 · 全节点",
                STATUS_STOPPED, "已停止",
                STATUS_STARTING, "启动中",
                STATUS_SYNCING, "同步中",
                STATUS_SYNCED, "同步完成",
                LABEL_BEST_BLOCK, "最佳区块（本设备）",
                LABEL_HIGHEST_BLOCK, "观察到的最高区块",
                LABEL_PEERS, "已连接的对等节点数",
                BUTTON_START_NODE, "启动节点",
                BUTTON_STOP_NODE, "停止节点",
                SECTION_TRACK, "航迹",
                LABEL_UPTIME_TOTAL, "累计运行时间",
                LABEL_BLOCKS_OBSERVED, "观察到的新区块数",
                LABEL_MAX_PEERS, "观察到的对等节点数最大值",
                SECTION_SETTINGS, "设置",
                SETTINGS_TELEMETRY, "在遥测中显示",
                SETTINGS_TELEMETRY_NOTE, "开启后，您的节点名称和运行状态将在官方遥测（telemetry.quantus.com）中公开可见。默认为关闭。",
                SETTINGS_DISPLAY_NAME, "显示名称",
                SETTINGS_LANGUAGE, "语言",
                LANGUAGE_FOLLOW_SYSTEM, "跟随设备设置",
                SETTINGS_LOCK, "锁定设置",
                SETTINGS_LOCK_NOTE, "防止意外更改遥测、显示名称和语言。节点的启动和停止不会被锁定。",
                NOTE_LAST_EXIT_CODE, "上次退出代码：%1$d",
                NOTIF_CHANNEL_NAME, "节点状态",
                NOTIF_CHANNEL_DESC, "Quantus 节点运行状态",
                NOTIF_PEERS, "对等节点 %1$d",
                NOTIF_ACTION_STOP, "停止",
                NOTE_FGS_TIMEOUT, "因系统时间限制而停止",
                NOTE_BINARY_MISSING, "未找到节点二进制文件",
                NOTE_START_FAILED, "启动失败：%1$s",
                ATTEMPTS_TITLE, "最近的启动尝试",
                ATTEMPT_METHOD_DIRECT, "直接运行",
                ATTEMPT_METHOD_LINKER, "通过 linker64",
                ATTEMPT_SUMMARY, "尝试 %1$d（%2$s）：退出代码 %3$d · %4$d 毫秒",
                ATTEMPT_SUMMARY_NO_EXIT, "尝试 %1$d（%2$s）：无法启动 · %3$d 毫秒",
                ATTEMPT_NO_OUTPUT, "（无输出）",
                BUTTON_SHARE_LOG, "分享日志",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "尚无日志",
                STATUS_START_FAILED, "无法启动节点",
                STATUS_UNEXPECTED_STOP, "节点意外停止",
                MESSAGE_START_FAILED, "您可以查看启动记录。",
                MESSAGE_UNEXPECTED_STOP, "您可以查看停止时的记录。",
                MESSAGE_TIME_LIMIT_STOP, "因系统时间限制而停止。",
                MESSAGE_BINARY_MISSING, "找不到运行节点所需的文件。",
                BUTTON_RETRY, "重试",
                BUTTON_CANCEL_STARTUP, "取消启动",
                BUTTON_STOPPING, "正在停止…",
                ACTION_FAILURE_DETAILS, "失败详情",
                ACTION_STOP_DETAILS, "停止详情",
                LABEL_UPTIME_SESSION, "本次运行时间",
                MESSAGE_SYNC_TARGET_UNKNOWN, "正在确认同步目标。",
                MESSAGE_NO_PEERS, "没有已连接的对等节点。",
                MESSAGE_WAITING_BLOCK, "正在等待观察新区块。",
                VALUE_UNAVAILABLE, "暂无数据",
                OBSERVATION_JUST_NOW, "最后观察：刚刚",
                SCREEN_NODE_DETAILS, "节点详情",
                SECTION_DIAGNOSTICS, "诊断",
                LABEL_SYNC_GAP, "同步差距（估计）",
                SYNC_GAP_NOTE, "同步差距是最高区块与最佳区块高度之差。即使差距为零，也不代表节点已同步完成，是否同步完成由节点状态决定。",
                STOPPED_CUMULATIVE_NOTE, "节点停止期间显示累计记录。",
                TRACK_LOCAL_NOTE, "这些记录仅保存在本设备中，不会发送。",
                RPC_LOCAL_NOTE, "RPC 仅可在此设备内使用。",
                LABEL_LOG_LOCATION, "日志保存位置",
                DIAGNOSTICS_EMPTY, "没有可显示的诊断记录。",
                MESSAGE_USER_STOP, "已由用户停止。",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "遥测和显示名称的更改将在下次启动节点时生效。",
                SETTINGS_LANGUAGE_IMMEDIATE, "选择后，显示语言会立即更改。",
                SETTINGS_PENDING_LINK, "查看待应用的设置",
                SETTINGS_PENDING_STATUS, "下次启动节点时应用",
                SETTINGS_APPLIED_VALUE, "当前使用：%1$s",
                VALUE_ON, "开",
                VALUE_OFF, "关",
                SETTINGS_NAME_DEFAULT, "默认名称",
                SETTINGS_NAME_EMPTY_HINT, "留空则使用默认名称。",
                SETTINGS_NAME_SAVED_PREVIEW, "将保存的名称：%1$s",
                SETTINGS_NAME_LOCKED, "设置锁定期间无法编辑。",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "保存",
                A11Y_BACK, "返回",
                A11Y_EXPANDED, "已展开",
                A11Y_COLLAPSED, "已折叠",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "还剩 %1$s 个区块（估计）",
                SYNC_REMAINING_BLOCKS + ".one", "还剩 %1$s 个区块（估计）",
                SYNC_REMAINING_BLOCKS + ".two", "还剩 %1$s 个区块（估计）",
                SYNC_REMAINING_BLOCKS + ".few", "还剩 %1$s 个区块（估计）",
                SYNC_REMAINING_BLOCKS + ".many", "还剩 %1$s 个区块（估计）",
                SYNC_REMAINING_BLOCKS + ".other", "还剩 %1$s 个区块（估计）",
                OBSERVATION_SECONDS_AGO + ".zero", "最后观察：%1$s 秒前",
                OBSERVATION_SECONDS_AGO + ".one", "最后观察：%1$s 秒前",
                OBSERVATION_SECONDS_AGO + ".two", "最后观察：%1$s 秒前",
                OBSERVATION_SECONDS_AGO + ".few", "最后观察：%1$s 秒前",
                OBSERVATION_SECONDS_AGO + ".many", "最后观察：%1$s 秒前",
                OBSERVATION_SECONDS_AGO + ".other", "最后观察：%1$s 秒前",
                OBSERVATION_MINUTES_AGO + ".zero", "最后观察：%1$s 分钟前",
                OBSERVATION_MINUTES_AGO + ".one", "最后观察：%1$s 分钟前",
                OBSERVATION_MINUTES_AGO + ".two", "最后观察：%1$s 分钟前",
                OBSERVATION_MINUTES_AGO + ".few", "最后观察：%1$s 分钟前",
                OBSERVATION_MINUTES_AGO + ".many", "最后观察：%1$s 分钟前",
                OBSERVATION_MINUTES_AGO + ".other", "最后观察：%1$s 分钟前",
                OBSERVATION_HOURS_AGO + ".zero", "最后观察：%1$s 小时前",
                OBSERVATION_HOURS_AGO + ".one", "最后观察：%1$s 小时前",
                OBSERVATION_HOURS_AGO + ".two", "最后观察：%1$s 小时前",
                OBSERVATION_HOURS_AGO + ".few", "最后观察：%1$s 小时前",
                OBSERVATION_HOURS_AGO + ".many", "最后观察：%1$s 小时前",
                OBSERVATION_HOURS_AGO + ".other", "最后观察：%1$s 小时前",
                OBSERVATION_DAYS_AGO + ".zero", "最后观察：%1$s 天前",
                OBSERVATION_DAYS_AGO + ".one", "最后观察：%1$s 天前",
                OBSERVATION_DAYS_AGO + ".two", "最后观察：%1$s 天前",
                OBSERVATION_DAYS_AGO + ".few", "最后观察：%1$s 天前",
                OBSERVATION_DAYS_AGO + ".many", "最后观察：%1$s 天前",
                OBSERVATION_DAYS_AGO + ".other", "最后观察：%1$s 天前",
                DURATION_LESS_THAN_MINUTES + ".zero", "不足 %1$s 分钟",
                DURATION_LESS_THAN_MINUTES + ".one", "不足 %1$s 分钟",
                DURATION_LESS_THAN_MINUTES + ".two", "不足 %1$s 分钟",
                DURATION_LESS_THAN_MINUTES + ".few", "不足 %1$s 分钟",
                DURATION_LESS_THAN_MINUTES + ".many", "不足 %1$s 分钟",
                DURATION_LESS_THAN_MINUTES + ".other", "不足 %1$s 分钟",
                DURATION_MINUTES + ".zero", "%1$s 分钟",
                DURATION_MINUTES + ".one", "%1$s 分钟",
                DURATION_MINUTES + ".two", "%1$s 分钟",
                DURATION_MINUTES + ".few", "%1$s 分钟",
                DURATION_MINUTES + ".many", "%1$s 分钟",
                DURATION_MINUTES + ".other", "%1$s 分钟",
                DURATION_HOURS + ".zero", "%1$s 小时",
                DURATION_HOURS + ".one", "%1$s 小时",
                DURATION_HOURS + ".two", "%1$s 小时",
                DURATION_HOURS + ".few", "%1$s 小时",
                DURATION_HOURS + ".many", "%1$s 小时",
                DURATION_HOURS + ".other", "%1$s 小时",
                DURATION_DAYS + ".zero", "%1$s 天",
                DURATION_DAYS + ".one", "%1$s 天",
                DURATION_DAYS + ".two", "%1$s 天",
                DURATION_DAYS + ".few", "%1$s 天",
                DURATION_DAYS + ".many", "%1$s 天",
                DURATION_DAYS + ".other", "%1$s 天",
                DISPLAY_NAME_LIMIT + ".zero", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_LIMIT + ".one", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_LIMIT + ".two", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_LIMIT + ".few", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_LIMIT + ".many", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_LIMIT + ".other", "最多可保存 %1$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".one", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".two", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".few", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".many", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。",
                DISPLAY_NAME_COUNT_A11Y + ".other", "保存时长度为 %1$s 个字符，上限为 %2$s 个字符。"
        );
    }

    // ------------------------------------------------------------------
    // Español (Spanish)
    // ------------------------------------------------------------------

    private static Map<String, String> es() {
        return m(
                APP_SUBTITLE, "red principal · nodo completo",
                STATUS_STOPPED, "Detenido",
                STATUS_STARTING, "Iniciando",
                STATUS_SYNCING, "Sincronizando",
                STATUS_SYNCED, "Sincronizado",
                LABEL_BEST_BLOCK, "Mejor bloque (este dispositivo)",
                LABEL_HIGHEST_BLOCK, "Bloque más alto observado",
                LABEL_PEERS, "Pares conectados",
                BUTTON_START_NODE, "Iniciar nodo",
                BUTTON_STOP_NODE, "Detener nodo",
                SECTION_TRACK, "Trayectoria",
                LABEL_UPTIME_TOTAL, "Tiempo activo total",
                LABEL_BLOCKS_OBSERVED, "Bloques nuevos observados",
                LABEL_MAX_PEERS, "Máximo de pares observados",
                SECTION_SETTINGS, "Ajustes",
                SETTINGS_TELEMETRY, "Aparecer en la telemetría",
                SETTINGS_TELEMETRY_NOTE, "Al activarlo, el nombre de tu nodo y su estado serán visibles públicamente en la telemetría oficial (telemetry.quantus.com). Desactivado por defecto.",
                SETTINGS_DISPLAY_NAME, "Nombre visible",
                SETTINGS_LANGUAGE, "Idioma",
                LANGUAGE_FOLLOW_SYSTEM, "Seguir los ajustes del dispositivo",
                SETTINGS_LOCK, "Bloquear ajustes",
                SETTINGS_LOCK_NOTE, "Evita cambios accidentales en la telemetría, el nombre visible y el idioma. Iniciar y detener el nodo nunca se bloquea.",
                NOTE_LAST_EXIT_CODE, "Último código de salida: %1$d",
                NOTIF_CHANNEL_NAME, "Estado del nodo",
                NOTIF_CHANNEL_DESC, "Estado de funcionamiento del nodo Quantus",
                NOTIF_PEERS, "%1$d pares",
                NOTIF_ACTION_STOP, "Detener",
                NOTE_FGS_TIMEOUT, "Detenido por el límite de tiempo del sistema",
                NOTE_BINARY_MISSING, "No se encontró el binario del nodo",
                NOTE_START_FAILED, "No se pudo iniciar: %1$s",
                ATTEMPTS_TITLE, "Últimos intentos de inicio",
                ATTEMPT_METHOD_DIRECT, "directo",
                ATTEMPT_METHOD_LINKER, "vía linker64",
                ATTEMPT_SUMMARY, "Intento %1$d (%2$s): código de salida %3$d · %4$d ms",
                ATTEMPT_SUMMARY_NO_EXIT, "Intento %1$d (%2$s): no se pudo iniciar · %3$d ms",
                ATTEMPT_NO_OUTPUT, "(sin salida)",
                BUTTON_SHARE_LOG, "Compartir registro",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "Aún no hay registro",
                STATUS_START_FAILED, "No se pudo iniciar el nodo",
                STATUS_UNEXPECTED_STOP, "El nodo se detuvo inesperadamente",
                MESSAGE_START_FAILED, "Puedes revisar los detalles del inicio.",
                MESSAGE_UNEXPECTED_STOP, "Puedes revisar los detalles de la detención.",
                MESSAGE_TIME_LIMIT_STOP, "Se detuvo por el límite de tiempo del sistema.",
                MESSAGE_BINARY_MISSING, "Falta el archivo necesario para ejecutar el nodo.",
                BUTTON_RETRY, "Reintentar",
                BUTTON_CANCEL_STARTUP, "Cancelar el inicio",
                BUTTON_STOPPING, "Deteniendo…",
                ACTION_FAILURE_DETAILS, "Detalles del fallo",
                ACTION_STOP_DETAILS, "Detalles de la detención",
                LABEL_UPTIME_SESSION, "Tiempo activo de esta sesión",
                MESSAGE_SYNC_TARGET_UNKNOWN, "Comprobando el destino de sincronización.",
                MESSAGE_NO_PEERS, "No hay pares conectados.",
                MESSAGE_WAITING_BLOCK, "Esperando observar un bloque nuevo.",
                VALUE_UNAVAILABLE, "No disponible",
                OBSERVATION_JUST_NOW, "Última observación: ahora mismo",
                SCREEN_NODE_DETAILS, "Detalles del nodo",
                SECTION_DIAGNOSTICS, "Diagnóstico",
                LABEL_SYNC_GAP, "Diferencia de sincronización (estimación)",
                SYNC_GAP_NOTE, "La diferencia de sincronización es la diferencia entre la altura del bloque más alto y la del mejor bloque. Una diferencia de cero no significa por sí sola que el nodo esté sincronizado.",
                STOPPED_CUMULATIVE_NOTE, "Mostrando los registros acumulados mientras el nodo está detenido.",
                TRACK_LOCAL_NOTE, "Estos registros permanecen en este dispositivo y no se envían.",
                RPC_LOCAL_NOTE, "RPC solo está disponible en este dispositivo.",
                LABEL_LOG_LOCATION, "Ubicación del registro",
                DIAGNOSTICS_EMPTY, "No hay registros de diagnóstico disponibles.",
                MESSAGE_USER_STOP, "Detenido por el usuario.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "Los cambios en la telemetría y el nombre visible se aplicarán la próxima vez que se inicie el nodo.",
                SETTINGS_LANGUAGE_IMMEDIATE, "Al seleccionar un idioma, el idioma de la aplicación cambia de inmediato.",
                SETTINGS_PENDING_LINK, "Revisar los ajustes pendientes",
                SETTINGS_PENDING_STATUS, "Se aplica al próximo inicio del nodo",
                SETTINGS_APPLIED_VALUE, "En uso: %1$s",
                VALUE_ON, "Activado",
                VALUE_OFF, "Desactivado",
                SETTINGS_NAME_DEFAULT, "Nombre predeterminado",
                SETTINGS_NAME_EMPTY_HINT, "Déjalo vacío para usar el nombre predeterminado.",
                SETTINGS_NAME_SAVED_PREVIEW, "Nombre a guardar: %1$s",
                SETTINGS_NAME_LOCKED, "La edición está desactivada mientras los ajustes están bloqueados.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "Guardar",
                A11Y_BACK, "Atrás",
                A11Y_EXPANDED, "Expandido",
                A11Y_COLLAPSED, "Contraído",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "Quedan %1$s bloques (estimación)",
                SYNC_REMAINING_BLOCKS + ".one", "Queda %1$s bloque (estimación)",
                SYNC_REMAINING_BLOCKS + ".two", "Quedan %1$s bloques (estimación)",
                SYNC_REMAINING_BLOCKS + ".few", "Quedan %1$s bloques (estimación)",
                SYNC_REMAINING_BLOCKS + ".many", "Quedan %1$s bloques (estimación)",
                SYNC_REMAINING_BLOCKS + ".other", "Quedan %1$s bloques (estimación)",
                OBSERVATION_SECONDS_AGO + ".zero", "Última observación: hace %1$s segundos",
                OBSERVATION_SECONDS_AGO + ".one", "Última observación: hace %1$s segundo",
                OBSERVATION_SECONDS_AGO + ".two", "Última observación: hace %1$s segundos",
                OBSERVATION_SECONDS_AGO + ".few", "Última observación: hace %1$s segundos",
                OBSERVATION_SECONDS_AGO + ".many", "Última observación: hace %1$s segundos",
                OBSERVATION_SECONDS_AGO + ".other", "Última observación: hace %1$s segundos",
                OBSERVATION_MINUTES_AGO + ".zero", "Última observación: hace %1$s minutos",
                OBSERVATION_MINUTES_AGO + ".one", "Última observación: hace %1$s minuto",
                OBSERVATION_MINUTES_AGO + ".two", "Última observación: hace %1$s minutos",
                OBSERVATION_MINUTES_AGO + ".few", "Última observación: hace %1$s minutos",
                OBSERVATION_MINUTES_AGO + ".many", "Última observación: hace %1$s minutos",
                OBSERVATION_MINUTES_AGO + ".other", "Última observación: hace %1$s minutos",
                OBSERVATION_HOURS_AGO + ".zero", "Última observación: hace %1$s horas",
                OBSERVATION_HOURS_AGO + ".one", "Última observación: hace %1$s hora",
                OBSERVATION_HOURS_AGO + ".two", "Última observación: hace %1$s horas",
                OBSERVATION_HOURS_AGO + ".few", "Última observación: hace %1$s horas",
                OBSERVATION_HOURS_AGO + ".many", "Última observación: hace %1$s horas",
                OBSERVATION_HOURS_AGO + ".other", "Última observación: hace %1$s horas",
                OBSERVATION_DAYS_AGO + ".zero", "Última observación: hace %1$s días",
                OBSERVATION_DAYS_AGO + ".one", "Última observación: hace %1$s día",
                OBSERVATION_DAYS_AGO + ".two", "Última observación: hace %1$s días",
                OBSERVATION_DAYS_AGO + ".few", "Última observación: hace %1$s días",
                OBSERVATION_DAYS_AGO + ".many", "Última observación: hace %1$s días",
                OBSERVATION_DAYS_AGO + ".other", "Última observación: hace %1$s días",
                DURATION_LESS_THAN_MINUTES + ".zero", "Menos de %1$s minutos",
                DURATION_LESS_THAN_MINUTES + ".one", "Menos de %1$s minuto",
                DURATION_LESS_THAN_MINUTES + ".two", "Menos de %1$s minutos",
                DURATION_LESS_THAN_MINUTES + ".few", "Menos de %1$s minutos",
                DURATION_LESS_THAN_MINUTES + ".many", "Menos de %1$s minutos",
                DURATION_LESS_THAN_MINUTES + ".other", "Menos de %1$s minutos",
                DURATION_MINUTES + ".zero", "%1$s minutos",
                DURATION_MINUTES + ".one", "%1$s minuto",
                DURATION_MINUTES + ".two", "%1$s minutos",
                DURATION_MINUTES + ".few", "%1$s minutos",
                DURATION_MINUTES + ".many", "%1$s minutos",
                DURATION_MINUTES + ".other", "%1$s minutos",
                DURATION_HOURS + ".zero", "%1$s horas",
                DURATION_HOURS + ".one", "%1$s hora",
                DURATION_HOURS + ".two", "%1$s horas",
                DURATION_HOURS + ".few", "%1$s horas",
                DURATION_HOURS + ".many", "%1$s horas",
                DURATION_HOURS + ".other", "%1$s horas",
                DURATION_DAYS + ".zero", "%1$s días",
                DURATION_DAYS + ".one", "%1$s día",
                DURATION_DAYS + ".two", "%1$s días",
                DURATION_DAYS + ".few", "%1$s días",
                DURATION_DAYS + ".many", "%1$s días",
                DURATION_DAYS + ".other", "%1$s días",
                DISPLAY_NAME_LIMIT + ".zero", "Puedes guardar hasta %1$s caracteres.",
                DISPLAY_NAME_LIMIT + ".one", "Puedes guardar hasta %1$s carácter.",
                DISPLAY_NAME_LIMIT + ".two", "Puedes guardar hasta %1$s caracteres.",
                DISPLAY_NAME_LIMIT + ".few", "Puedes guardar hasta %1$s caracteres.",
                DISPLAY_NAME_LIMIT + ".many", "Puedes guardar hasta %1$s caracteres.",
                DISPLAY_NAME_LIMIT + ".other", "Puedes guardar hasta %1$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "Longitud al guardar: %1$s caracteres. Límite: %2$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "Longitud al guardar: %1$s carácter. Límite: %2$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "Longitud al guardar: %1$s caracteres. Límite: %2$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "Longitud al guardar: %1$s caracteres. Límite: %2$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "Longitud al guardar: %1$s caracteres. Límite: %2$s caracteres.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "Longitud al guardar: %1$s caracteres. Límite: %2$s caracteres."
        );
    }

    // ------------------------------------------------------------------
    // हिन्दी (Hindi)
    // ------------------------------------------------------------------

    private static Map<String, String> hi() {
        return m(
                APP_SUBTITLE, "मेननेट · पूर्ण नोड",
                STATUS_STOPPED, "बंद है",
                STATUS_STARTING, "शुरू हो रहा है",
                STATUS_SYNCING, "सिंक हो रहा है",
                STATUS_SYNCED, "सिंक पूर्ण",
                LABEL_BEST_BLOCK, "सर्वश्रेष्ठ ब्लॉक (यह डिवाइस)",
                LABEL_HIGHEST_BLOCK, "देखा गया उच्चतम ब्लॉक",
                LABEL_PEERS, "कनेक्ट हुए पीयर्स",
                BUTTON_START_NODE, "नोड शुरू करें",
                BUTTON_STOP_NODE, "नोड बंद करें",
                SECTION_TRACK, "ट्रैक",
                LABEL_UPTIME_TOTAL, "कुल सक्रिय समय",
                LABEL_BLOCKS_OBSERVED, "देखे गए नए ब्लॉक",
                LABEL_MAX_PEERS, "देखे गए पीयर्स की अधिकतम संख्या",
                SECTION_SETTINGS, "सेटिंग्स",
                SETTINGS_TELEMETRY, "टेलीमेट्री में दिखाएँ",
                SETTINGS_TELEMETRY_NOTE, "चालू करने पर, आपके नोड का नाम और स्थिति आधिकारिक टेलीमेट्री (telemetry.quantus.com) पर सभी को सार्वजनिक रूप से दिखाई देगी। डिफ़ॉल्ट रूप से बंद है।",
                SETTINGS_DISPLAY_NAME, "प्रदर्शन नाम",
                SETTINGS_LANGUAGE, "भाषा",
                LANGUAGE_FOLLOW_SYSTEM, "डिवाइस सेटिंग के अनुसार",
                SETTINGS_LOCK, "सेटिंग्स लॉक करें",
                SETTINGS_LOCK_NOTE, "टेलीमेट्री, प्रदर्शन नाम और भाषा में आकस्मिक बदलावों को रोकता है। नोड को शुरू या बंद करना कभी लॉक नहीं होता।",
                NOTE_LAST_EXIT_CODE, "पिछला निकास कोड: %1$d",
                NOTIF_CHANNEL_NAME, "नोड की स्थिति",
                NOTIF_CHANNEL_DESC, "Quantus नोड की चलने की स्थिति",
                NOTIF_PEERS, "पीयर्स %1$d",
                NOTIF_ACTION_STOP, "बंद करें",
                NOTE_FGS_TIMEOUT, "सिस्टम की समय-सीमा के कारण बंद हुआ",
                NOTE_BINARY_MISSING, "नोड बाइनरी नहीं मिली",
                NOTE_START_FAILED, "शुरू करने में विफल: %1$s",
                ATTEMPTS_TITLE, "हाल के प्रारंभ प्रयास",
                ATTEMPT_METHOD_DIRECT, "सीधा",
                ATTEMPT_METHOD_LINKER, "linker64 के माध्यम से",
                ATTEMPT_SUMMARY, "प्रयास %1$d (%2$s): निकास कोड %3$d · %4$d मिलीसेकंड",
                ATTEMPT_SUMMARY_NO_EXIT, "प्रयास %1$d (%2$s): शुरू नहीं हो सका · %3$d मिलीसेकंड",
                ATTEMPT_NO_OUTPUT, "(कोई आउटपुट नहीं)",
                BUTTON_SHARE_LOG, "लॉग साझा करें",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "अभी कोई लॉग नहीं है",
                STATUS_START_FAILED, "नोड शुरू नहीं हो सका",
                STATUS_UNEXPECTED_STOP, "नोड अप्रत्याशित रूप से बंद हो गया",
                MESSAGE_START_FAILED, "आप शुरुआत का विवरण देख सकते हैं।",
                MESSAGE_UNEXPECTED_STOP, "आप बंद होते समय का विवरण देख सकते हैं।",
                MESSAGE_TIME_LIMIT_STOP, "सिस्टम समय सीमा के कारण बंद हो गया।",
                MESSAGE_BINARY_MISSING, "नोड चलाने के लिए आवश्यक फ़ाइल नहीं मिली।",
                BUTTON_RETRY, "फिर से प्रयास करें",
                BUTTON_CANCEL_STARTUP, "शुरुआत रद्द करें",
                BUTTON_STOPPING, "बंद हो रहा है…",
                ACTION_FAILURE_DETAILS, "विफलता का विवरण",
                ACTION_STOP_DETAILS, "रुकने का विवरण",
                LABEL_UPTIME_SESSION, "इस सत्र का सक्रिय समय",
                MESSAGE_SYNC_TARGET_UNKNOWN, "सिंक लक्ष्य की जाँच हो रही है।",
                MESSAGE_NO_PEERS, "कोई पीयर कनेक्ट नहीं है।",
                MESSAGE_WAITING_BLOCK, "नए ब्लॉक के अवलोकन की प्रतीक्षा है।",
                VALUE_UNAVAILABLE, "उपलब्ध नहीं",
                OBSERVATION_JUST_NOW, "अंतिम अवलोकन: अभी-अभी",
                SCREEN_NODE_DETAILS, "नोड का विवरण",
                SECTION_DIAGNOSTICS, "निदान",
                LABEL_SYNC_GAP, "सिंक अंतर (अनुमान)",
                SYNC_GAP_NOTE, "सिंक अंतर सबसे ऊँचे और सर्वश्रेष्ठ ब्लॉक की ऊँचाई का अंतर है। अंतर शून्य होना अपने आप में यह नहीं दर्शाता कि नोड सिंक हो गया है।",
                STOPPED_CUMULATIVE_NOTE, "नोड बंद होने पर संचयी रिकॉर्ड दिखाए जा रहे हैं।",
                TRACK_LOCAL_NOTE, "ये रिकॉर्ड इस डिवाइस पर रहते हैं और भेजे नहीं जाते।",
                RPC_LOCAL_NOTE, "RPC केवल इस डिवाइस पर उपलब्ध है।",
                LABEL_LOG_LOCATION, "लॉग का स्थान",
                DIAGNOSTICS_EMPTY, "कोई निदान रिकॉर्ड उपलब्ध नहीं है।",
                MESSAGE_USER_STOP, "उपयोगकर्ता द्वारा बंद किया गया।",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "टेलीमेट्री और प्रदर्शन नाम में परिवर्तन अगली बार नोड शुरू होने पर लागू होंगे।",
                SETTINGS_LANGUAGE_IMMEDIATE, "भाषा चुनते ही ऐप की भाषा तुरंत बदल जाती है।",
                SETTINGS_PENDING_LINK, "लंबित सेटिंग देखें",
                SETTINGS_PENDING_STATUS, "अगले नोड प्रारंभ पर लागू होगा",
                SETTINGS_APPLIED_VALUE, "उपयोग में: %1$s",
                VALUE_ON, "चालू",
                VALUE_OFF, "बंद",
                SETTINGS_NAME_DEFAULT, "डिफ़ॉल्ट नाम",
                SETTINGS_NAME_EMPTY_HINT, "डिफ़ॉल्ट नाम के लिए खाली छोड़ें।",
                SETTINGS_NAME_SAVED_PREVIEW, "सहेजा जाने वाला नाम: %1$s",
                SETTINGS_NAME_LOCKED, "सेटिंग लॉक रहने पर संपादन बंद रहता है।",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "सहेजें",
                A11Y_BACK, "वापस",
                A11Y_EXPANDED, "विस्तृत",
                A11Y_COLLAPSED, "संकुचित",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "%1$s ब्लॉक शेष (अनुमान)",
                SYNC_REMAINING_BLOCKS + ".one", "%1$s ब्लॉक शेष (अनुमान)",
                SYNC_REMAINING_BLOCKS + ".two", "%1$s ब्लॉक शेष (अनुमान)",
                SYNC_REMAINING_BLOCKS + ".few", "%1$s ब्लॉक शेष (अनुमान)",
                SYNC_REMAINING_BLOCKS + ".many", "%1$s ब्लॉक शेष (अनुमान)",
                SYNC_REMAINING_BLOCKS + ".other", "%1$s ब्लॉक शेष (अनुमान)",
                OBSERVATION_SECONDS_AGO + ".zero", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_SECONDS_AGO + ".one", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_SECONDS_AGO + ".two", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_SECONDS_AGO + ".few", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_SECONDS_AGO + ".many", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_SECONDS_AGO + ".other", "अंतिम अवलोकन: %1$s सेकंड पहले",
                OBSERVATION_MINUTES_AGO + ".zero", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_MINUTES_AGO + ".one", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_MINUTES_AGO + ".two", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_MINUTES_AGO + ".few", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_MINUTES_AGO + ".many", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_MINUTES_AGO + ".other", "अंतिम अवलोकन: %1$s मिनट पहले",
                OBSERVATION_HOURS_AGO + ".zero", "अंतिम अवलोकन: %1$s घंटे पहले",
                OBSERVATION_HOURS_AGO + ".one", "अंतिम अवलोकन: %1$s घंटा पहले",
                OBSERVATION_HOURS_AGO + ".two", "अंतिम अवलोकन: %1$s घंटे पहले",
                OBSERVATION_HOURS_AGO + ".few", "अंतिम अवलोकन: %1$s घंटे पहले",
                OBSERVATION_HOURS_AGO + ".many", "अंतिम अवलोकन: %1$s घंटे पहले",
                OBSERVATION_HOURS_AGO + ".other", "अंतिम अवलोकन: %1$s घंटे पहले",
                OBSERVATION_DAYS_AGO + ".zero", "अंतिम अवलोकन: %1$s दिन पहले",
                OBSERVATION_DAYS_AGO + ".one", "अंतिम अवलोकन: %1$s दिन पहले",
                OBSERVATION_DAYS_AGO + ".two", "अंतिम अवलोकन: %1$s दिन पहले",
                OBSERVATION_DAYS_AGO + ".few", "अंतिम अवलोकन: %1$s दिन पहले",
                OBSERVATION_DAYS_AGO + ".many", "अंतिम अवलोकन: %1$s दिन पहले",
                OBSERVATION_DAYS_AGO + ".other", "अंतिम अवलोकन: %1$s दिन पहले",
                DURATION_LESS_THAN_MINUTES + ".zero", "%1$s मिनट से कम",
                DURATION_LESS_THAN_MINUTES + ".one", "%1$s मिनट से कम",
                DURATION_LESS_THAN_MINUTES + ".two", "%1$s मिनट से कम",
                DURATION_LESS_THAN_MINUTES + ".few", "%1$s मिनट से कम",
                DURATION_LESS_THAN_MINUTES + ".many", "%1$s मिनट से कम",
                DURATION_LESS_THAN_MINUTES + ".other", "%1$s मिनट से कम",
                DURATION_MINUTES + ".zero", "%1$s मिनट",
                DURATION_MINUTES + ".one", "%1$s मिनट",
                DURATION_MINUTES + ".two", "%1$s मिनट",
                DURATION_MINUTES + ".few", "%1$s मिनट",
                DURATION_MINUTES + ".many", "%1$s मिनट",
                DURATION_MINUTES + ".other", "%1$s मिनट",
                DURATION_HOURS + ".zero", "%1$s घंटे",
                DURATION_HOURS + ".one", "%1$s घंटा",
                DURATION_HOURS + ".two", "%1$s घंटे",
                DURATION_HOURS + ".few", "%1$s घंटे",
                DURATION_HOURS + ".many", "%1$s घंटे",
                DURATION_HOURS + ".other", "%1$s घंटे",
                DURATION_DAYS + ".zero", "%1$s दिन",
                DURATION_DAYS + ".one", "%1$s दिन",
                DURATION_DAYS + ".two", "%1$s दिन",
                DURATION_DAYS + ".few", "%1$s दिन",
                DURATION_DAYS + ".many", "%1$s दिन",
                DURATION_DAYS + ".other", "%1$s दिन",
                DISPLAY_NAME_LIMIT + ".zero", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_LIMIT + ".one", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_LIMIT + ".two", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_LIMIT + ".few", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_LIMIT + ".many", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_LIMIT + ".other", "आप अधिकतम %1$s वर्ण सहेज सकते हैं।",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।",
                DISPLAY_NAME_COUNT_A11Y + ".one", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।",
                DISPLAY_NAME_COUNT_A11Y + ".two", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।",
                DISPLAY_NAME_COUNT_A11Y + ".few", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।",
                DISPLAY_NAME_COUNT_A11Y + ".many", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।",
                DISPLAY_NAME_COUNT_A11Y + ".other", "सहेजते समय लंबाई: %1$s वर्ण। सीमा: %2$s वर्ण।"
        );
    }

    // ------------------------------------------------------------------
    // العربية (Arabic)
    // ------------------------------------------------------------------

    private static Map<String, String> ar() {
        return m(
                APP_SUBTITLE, "الشبكة الرئيسية · عقدة كاملة",
                STATUS_STOPPED, "متوقف",
                STATUS_STARTING, "جارٍ البدء",
                STATUS_SYNCING, "جارٍ المزامنة",
                STATUS_SYNCED, "اكتملت المزامنة",
                LABEL_BEST_BLOCK, "أفضل كتلة (هذا الجهاز)",
                LABEL_HIGHEST_BLOCK, "أعلى كتلة تم رصدها",
                LABEL_PEERS, "الأقران المتصلون",
                BUTTON_START_NODE, "بدء العقدة",
                BUTTON_STOP_NODE, "إيقاف العقدة",
                SECTION_TRACK, "الأثر",
                LABEL_UPTIME_TOTAL, "إجمالي مدة التشغيل",
                LABEL_BLOCKS_OBSERVED, "الكتل الجديدة المرصودة",
                LABEL_MAX_PEERS, "أكبر عدد أقران تم رصده",
                SECTION_SETTINGS, "الإعدادات",
                SETTINGS_TELEMETRY, "الظهور في القياس عن بُعد",
                SETTINGS_TELEMETRY_NOTE, "عند التفعيل، يصبح اسم عقدتك وحالتها ظاهرَين للعامة في القياس الرسمي عن بُعد (telemetry.quantus.com). معطّل افتراضيًا.",
                SETTINGS_DISPLAY_NAME, "اسم العرض",
                SETTINGS_LANGUAGE, "اللغة",
                LANGUAGE_FOLLOW_SYSTEM, "اتباع إعدادات الجهاز",
                SETTINGS_LOCK, "قفل الإعدادات",
                SETTINGS_LOCK_NOTE, "يمنع التغييرات غير المقصودة في القياس عن بُعد واسم العرض واللغة. لا يتم قفل بدء العقدة أو إيقافها أبدًا.",
                NOTE_LAST_EXIT_CODE, "رمز الخروج الأخير: %1$d",
                NOTIF_CHANNEL_NAME, "حالة العقدة",
                NOTIF_CHANNEL_DESC, "حالة تشغيل عقدة Quantus",
                NOTIF_PEERS, "الأقران: %1$d",
                NOTIF_ACTION_STOP, "إيقاف",
                NOTE_FGS_TIMEOUT, "توقف بسبب حد الوقت في النظام",
                NOTE_BINARY_MISSING, "لم يتم العثور على ملف العقدة التنفيذي",
                NOTE_START_FAILED, "فشل البدء: %1$s",
                ATTEMPTS_TITLE, "أحدث محاولات البدء",
                ATTEMPT_METHOD_DIRECT, "مباشر",
                ATTEMPT_METHOD_LINKER, "عبر linker64",
                ATTEMPT_SUMMARY, "المحاولة %1$d (%2$s): رمز الخروج %3$d · %4$d مللي ثانية",
                ATTEMPT_SUMMARY_NO_EXIT, "المحاولة %1$d (%2$s): تعذّر البدء · %3$d مللي ثانية",
                ATTEMPT_NO_OUTPUT, "(لا يوجد إخراج)",
                BUTTON_SHARE_LOG, "مشاركة السجل",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "لا يوجد سجل بعد",
                STATUS_START_FAILED, "تعذّر بدء العقدة",
                STATUS_UNEXPECTED_STOP, "توقفت العقدة بشكل غير متوقع",
                MESSAGE_START_FAILED, "يمكنك مراجعة تفاصيل بدء التشغيل.",
                MESSAGE_UNEXPECTED_STOP, "يمكنك مراجعة تفاصيل الإيقاف.",
                MESSAGE_TIME_LIMIT_STOP, "توقفت بسبب حدّ وقت النظام.",
                MESSAGE_BINARY_MISSING, "الملف اللازم لتشغيل العقدة مفقود.",
                BUTTON_RETRY, "إعادة المحاولة",
                BUTTON_CANCEL_STARTUP, "إلغاء بدء التشغيل",
                BUTTON_STOPPING, "جارٍ الإيقاف…",
                ACTION_FAILURE_DETAILS, "تفاصيل الفشل",
                ACTION_STOP_DETAILS, "تفاصيل الإيقاف",
                LABEL_UPTIME_SESSION, "مدة تشغيل هذه الجلسة",
                MESSAGE_SYNC_TARGET_UNKNOWN, "جارٍ التحقق من هدف المزامنة.",
                MESSAGE_NO_PEERS, "لا يوجد أقران متصلون.",
                MESSAGE_WAITING_BLOCK, "في انتظار رصد كتلة جديدة.",
                VALUE_UNAVAILABLE, "غير متوفر",
                OBSERVATION_JUST_NOW, "آخر رصد: الآن",
                SCREEN_NODE_DETAILS, "تفاصيل العقدة",
                SECTION_DIAGNOSTICS, "التشخيص",
                LABEL_SYNC_GAP, "فجوة المزامنة (تقديرية)",
                SYNC_GAP_NOTE, "فجوة المزامنة هي الفرق بين ارتفاعَي أعلى كتلة وأفضل كتلة. الفجوة الصفرية لا تعني بحد ذاتها أن العقدة قد اكتملت مزامنتها.",
                STOPPED_CUMULATIVE_NOTE, "يتم عرض السجلات التراكمية أثناء توقف العقدة.",
                TRACK_LOCAL_NOTE, "تبقى هذه السجلات على هذا الجهاز ولا تُرسل.",
                RPC_LOCAL_NOTE, "RPC متاح على هذا الجهاز فقط.",
                LABEL_LOG_LOCATION, "موقع السجل",
                DIAGNOSTICS_EMPTY, "لا توجد سجلات تشخيص متاحة.",
                MESSAGE_USER_STOP, "تم الإيقاف بواسطة المستخدم.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "تسري تغييرات القياس عن بُعد واسم العرض عند بدء العقدة في المرة القادمة.",
                SETTINGS_LANGUAGE_IMMEDIATE, "يؤدي اختيار لغة إلى تغيير لغة التطبيق فورًا.",
                SETTINGS_PENDING_LINK, "مراجعة الإعدادات المعلّقة",
                SETTINGS_PENDING_STATUS, "يُطبَّق عند بدء العقدة التالي",
                SETTINGS_APPLIED_VALUE, "قيد الاستخدام: %1$s",
                VALUE_ON, "تشغيل",
                VALUE_OFF, "إيقاف",
                SETTINGS_NAME_DEFAULT, "الاسم الافتراضي",
                SETTINGS_NAME_EMPTY_HINT, "اتركه فارغًا لاستخدام الاسم الافتراضي.",
                SETTINGS_NAME_SAVED_PREVIEW, "الاسم الذي سيُحفظ: %1$s",
                SETTINGS_NAME_LOCKED, "التحرير معطّل أثناء قفل الإعدادات.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "حفظ",
                A11Y_BACK, "رجوع",
                A11Y_EXPANDED, "موسّع",
                A11Y_COLLAPSED, "مطوي",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "تتبقّى %1$s كتل (تقدير)",
                SYNC_REMAINING_BLOCKS + ".one", "تتبقّى %1$s كتلة (تقدير)",
                SYNC_REMAINING_BLOCKS + ".two", "تتبقّى %1$s كتلتان (تقدير)",
                SYNC_REMAINING_BLOCKS + ".few", "تتبقّى %1$s كتل (تقدير)",
                SYNC_REMAINING_BLOCKS + ".many", "تتبقّى %1$s كتلة (تقدير)",
                SYNC_REMAINING_BLOCKS + ".other", "تتبقّى %1$s كتلة (تقدير)",
                OBSERVATION_SECONDS_AGO + ".zero", "آخر رصد: قبل %1$s ثوانٍ",
                OBSERVATION_SECONDS_AGO + ".one", "آخر رصد: قبل %1$s ثانية",
                OBSERVATION_SECONDS_AGO + ".two", "آخر رصد: قبل %1$s ثانيتين",
                OBSERVATION_SECONDS_AGO + ".few", "آخر رصد: قبل %1$s ثوانٍ",
                OBSERVATION_SECONDS_AGO + ".many", "آخر رصد: قبل %1$s ثانية",
                OBSERVATION_SECONDS_AGO + ".other", "آخر رصد: قبل %1$s ثانية",
                OBSERVATION_MINUTES_AGO + ".zero", "آخر رصد: قبل %1$s دقائق",
                OBSERVATION_MINUTES_AGO + ".one", "آخر رصد: قبل %1$s دقيقة",
                OBSERVATION_MINUTES_AGO + ".two", "آخر رصد: قبل %1$s دقيقتين",
                OBSERVATION_MINUTES_AGO + ".few", "آخر رصد: قبل %1$s دقائق",
                OBSERVATION_MINUTES_AGO + ".many", "آخر رصد: قبل %1$s دقيقة",
                OBSERVATION_MINUTES_AGO + ".other", "آخر رصد: قبل %1$s دقيقة",
                OBSERVATION_HOURS_AGO + ".zero", "آخر رصد: قبل %1$s ساعات",
                OBSERVATION_HOURS_AGO + ".one", "آخر رصد: قبل %1$s ساعة",
                OBSERVATION_HOURS_AGO + ".two", "آخر رصد: قبل %1$s ساعتين",
                OBSERVATION_HOURS_AGO + ".few", "آخر رصد: قبل %1$s ساعات",
                OBSERVATION_HOURS_AGO + ".many", "آخر رصد: قبل %1$s ساعة",
                OBSERVATION_HOURS_AGO + ".other", "آخر رصد: قبل %1$s ساعة",
                OBSERVATION_DAYS_AGO + ".zero", "آخر رصد: قبل %1$s أيام",
                OBSERVATION_DAYS_AGO + ".one", "آخر رصد: قبل %1$s يوم",
                OBSERVATION_DAYS_AGO + ".two", "آخر رصد: قبل %1$s يومين",
                OBSERVATION_DAYS_AGO + ".few", "آخر رصد: قبل %1$s أيام",
                OBSERVATION_DAYS_AGO + ".many", "آخر رصد: قبل %1$s يومًا",
                OBSERVATION_DAYS_AGO + ".other", "آخر رصد: قبل %1$s يوم",
                DURATION_LESS_THAN_MINUTES + ".zero", "أقل من %1$s دقائق",
                DURATION_LESS_THAN_MINUTES + ".one", "أقل من %1$s دقيقة",
                DURATION_LESS_THAN_MINUTES + ".two", "أقل من %1$s دقيقتين",
                DURATION_LESS_THAN_MINUTES + ".few", "أقل من %1$s دقائق",
                DURATION_LESS_THAN_MINUTES + ".many", "أقل من %1$s دقيقة",
                DURATION_LESS_THAN_MINUTES + ".other", "أقل من %1$s دقيقة",
                DURATION_MINUTES + ".zero", "%1$s دقائق",
                DURATION_MINUTES + ".one", "%1$s دقيقة",
                DURATION_MINUTES + ".two", "%1$s دقيقتين",
                DURATION_MINUTES + ".few", "%1$s دقائق",
                DURATION_MINUTES + ".many", "%1$s دقيقة",
                DURATION_MINUTES + ".other", "%1$s دقيقة",
                DURATION_HOURS + ".zero", "%1$s ساعات",
                DURATION_HOURS + ".one", "%1$s ساعة",
                DURATION_HOURS + ".two", "%1$s ساعتين",
                DURATION_HOURS + ".few", "%1$s ساعات",
                DURATION_HOURS + ".many", "%1$s ساعة",
                DURATION_HOURS + ".other", "%1$s ساعة",
                DURATION_DAYS + ".zero", "%1$s أيام",
                DURATION_DAYS + ".one", "%1$s يوم",
                DURATION_DAYS + ".two", "%1$s يومين",
                DURATION_DAYS + ".few", "%1$s أيام",
                DURATION_DAYS + ".many", "%1$s يومًا",
                DURATION_DAYS + ".other", "%1$s يوم",
                DISPLAY_NAME_LIMIT + ".zero", "يمكنك حفظ ما يصل إلى %1$s حرفًا.",
                DISPLAY_NAME_LIMIT + ".one", "يمكنك حفظ ما يصل إلى %1$s حرف.",
                DISPLAY_NAME_LIMIT + ".two", "يمكنك حفظ ما يصل إلى %1$s حرفين.",
                DISPLAY_NAME_LIMIT + ".few", "يمكنك حفظ ما يصل إلى %1$s أحرف.",
                DISPLAY_NAME_LIMIT + ".many", "يمكنك حفظ ما يصل إلى %1$s حرفًا.",
                DISPLAY_NAME_LIMIT + ".other", "يمكنك حفظ ما يصل إلى %1$s حرفًا.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "الطول عند الحفظ: %1$s أحرف. الحد: %2$s حرفًا.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "الطول عند الحفظ: %1$s حرف. الحد: %2$s حرف.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "الطول عند الحفظ: %1$s حرفان. الحد: %2$s حرفًا.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "الطول عند الحفظ: %1$s أحرف. الحد: %2$s حرفًا.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "الطول عند الحفظ: %1$s حرفًا. الحد: %2$s حرفًا.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "الطول عند الحفظ: %1$s حرفًا. الحد: %2$s حرفًا."
        );
    }

    // ------------------------------------------------------------------
    // 한국어 (Korean)
    // ------------------------------------------------------------------

    private static Map<String, String> ko() {
        return m(
                APP_SUBTITLE, "메인넷 · 풀 노드",
                STATUS_STOPPED, "정지",
                STATUS_STARTING, "시작 중",
                STATUS_SYNCING, "동기화 중",
                STATUS_SYNCED, "동기화 완료",
                LABEL_BEST_BLOCK, "베스트 블록(이 기기)",
                LABEL_HIGHEST_BLOCK, "관측된 최고 블록",
                LABEL_PEERS, "연결된 피어 수",
                BUTTON_START_NODE, "노드 시작",
                BUTTON_STOP_NODE, "노드 정지",
                SECTION_TRACK, "항적",
                LABEL_UPTIME_TOTAL, "누적 가동 시간",
                LABEL_BLOCKS_OBSERVED, "관측한 새 블록 수",
                LABEL_MAX_PEERS, "관측한 최대 피어 수",
                SECTION_SETTINGS, "설정",
                SETTINGS_TELEMETRY, "텔레메트리에 표시",
                SETTINGS_TELEMETRY_NOTE, "켜면 노드 이름과 가동 상태가 공식 텔레메트리 (telemetry.quantus.com)에서 누구나 볼 수 있는 상태가 됩니다. 기본값은 꺼짐입니다.",
                SETTINGS_DISPLAY_NAME, "표시 이름",
                SETTINGS_LANGUAGE, "언어",
                LANGUAGE_FOLLOW_SYSTEM, "기기 설정 따르기",
                SETTINGS_LOCK, "설정 잠금",
                SETTINGS_LOCK_NOTE, "텔레메트리, 표시 이름, 언어가 실수로 변경되는 것을 방지합니다. 노드 시작과 중지는 잠기지 않습니다.",
                NOTE_LAST_EXIT_CODE, "이전 종료 코드: %1$d",
                NOTIF_CHANNEL_NAME, "노드 상태",
                NOTIF_CHANNEL_DESC, "Quantus 노드 가동 상태",
                NOTIF_PEERS, "피어 %1$d",
                NOTIF_ACTION_STOP, "정지",
                NOTE_FGS_TIMEOUT, "시스템 시간 제한으로 정지했습니다",
                NOTE_BINARY_MISSING, "노드 바이너리를 찾을 수 없습니다",
                NOTE_START_FAILED, "시작에 실패했습니다: %1$s",
                ATTEMPTS_TITLE, "최근 시작 시도",
                ATTEMPT_METHOD_DIRECT, "직접 실행",
                ATTEMPT_METHOD_LINKER, "linker64 경유",
                ATTEMPT_SUMMARY, "시도 %1$d(%2$s): 종료 코드 %3$d · %4$d 밀리초",
                ATTEMPT_SUMMARY_NO_EXIT, "시도 %1$d(%2$s): 시작할 수 없었습니다 · %3$d 밀리초",
                ATTEMPT_NO_OUTPUT, "(출력 없음)",
                BUTTON_SHARE_LOG, "로그 공유",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "아직 로그가 없습니다",
                STATUS_START_FAILED, "노드를 시작할 수 없었습니다",
                STATUS_UNEXPECTED_STOP, "노드가 예기치 않게 중지되었습니다",
                MESSAGE_START_FAILED, "시작 기록을 확인할 수 있습니다.",
                MESSAGE_UNEXPECTED_STOP, "종료 시 기록을 확인할 수 있습니다.",
                MESSAGE_TIME_LIMIT_STOP, "시스템 시간 제한으로 중지되었습니다.",
                MESSAGE_BINARY_MISSING, "노드 실행에 필요한 파일이 없습니다.",
                BUTTON_RETRY, "다시 시도",
                BUTTON_CANCEL_STARTUP, "시작 취소",
                BUTTON_STOPPING, "중지 중…",
                ACTION_FAILURE_DETAILS, "실패 세부 정보",
                ACTION_STOP_DETAILS, "중지 세부 정보",
                LABEL_UPTIME_SESSION, "이번 가동 시간",
                MESSAGE_SYNC_TARGET_UNKNOWN, "동기화 대상을 확인하고 있습니다.",
                MESSAGE_NO_PEERS, "연결된 피어가 없습니다.",
                MESSAGE_WAITING_BLOCK, "새 블록 관측을 기다리고 있습니다.",
                VALUE_UNAVAILABLE, "아직 없음",
                OBSERVATION_JUST_NOW, "최종 관측: 방금",
                SCREEN_NODE_DETAILS, "노드 세부 정보",
                SECTION_DIAGNOSTICS, "진단",
                LABEL_SYNC_GAP, "동기화 차이(추정)",
                SYNC_GAP_NOTE, "동기화 차이는 최고 블록과 베스트 블록 높이의 차입니다. 차이가 0이어도 그 자체로 동기화 완료를 의미하지 않으며, 완료 여부는 노드 상태로 판단합니다.",
                STOPPED_CUMULATIVE_NOTE, "중지 중에는 누적 기록을 표시합니다.",
                TRACK_LOCAL_NOTE, "이 기록은 이 기기 안에만 있으며 전송되지 않습니다.",
                RPC_LOCAL_NOTE, "RPC는 이 기기 내에서만 사용할 수 있습니다.",
                LABEL_LOG_LOCATION, "로그 저장 위치",
                DIAGNOSTICS_EMPTY, "표시할 진단 기록이 없습니다.",
                MESSAGE_USER_STOP, "사용자 조작으로 중지되었습니다.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "텔레메트리와 표시 이름의 변경은 다음 노드 시작 시 반영됩니다.",
                SETTINGS_LANGUAGE_IMMEDIATE, "선택하면 표시 언어가 즉시 변경됩니다.",
                SETTINGS_PENDING_LINK, "적용되지 않은 설정 확인",
                SETTINGS_PENDING_STATUS, "다음 노드 시작 시 반영",
                SETTINGS_APPLIED_VALUE, "적용 중: %1$s",
                VALUE_ON, "켬",
                VALUE_OFF, "끔",
                SETTINGS_NAME_DEFAULT, "기본 이름",
                SETTINGS_NAME_EMPTY_HINT, "비워 두면 기본 이름을 사용합니다.",
                SETTINGS_NAME_SAVED_PREVIEW, "저장될 이름: %1$s",
                SETTINGS_NAME_LOCKED, "설정이 잠긴 동안에는 편집할 수 없습니다.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "저장",
                A11Y_BACK, "뒤로",
                A11Y_EXPANDED, "펼쳐짐",
                A11Y_COLLAPSED, "접힘",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "남은 블록 %1$s개(추정)",
                SYNC_REMAINING_BLOCKS + ".one", "남은 블록 %1$s개(추정)",
                SYNC_REMAINING_BLOCKS + ".two", "남은 블록 %1$s개(추정)",
                SYNC_REMAINING_BLOCKS + ".few", "남은 블록 %1$s개(추정)",
                SYNC_REMAINING_BLOCKS + ".many", "남은 블록 %1$s개(추정)",
                SYNC_REMAINING_BLOCKS + ".other", "남은 블록 %1$s개(추정)",
                OBSERVATION_SECONDS_AGO + ".zero", "최종 관측: %1$s초 전",
                OBSERVATION_SECONDS_AGO + ".one", "최종 관측: %1$s초 전",
                OBSERVATION_SECONDS_AGO + ".two", "최종 관측: %1$s초 전",
                OBSERVATION_SECONDS_AGO + ".few", "최종 관측: %1$s초 전",
                OBSERVATION_SECONDS_AGO + ".many", "최종 관측: %1$s초 전",
                OBSERVATION_SECONDS_AGO + ".other", "최종 관측: %1$s초 전",
                OBSERVATION_MINUTES_AGO + ".zero", "최종 관측: %1$s분 전",
                OBSERVATION_MINUTES_AGO + ".one", "최종 관측: %1$s분 전",
                OBSERVATION_MINUTES_AGO + ".two", "최종 관측: %1$s분 전",
                OBSERVATION_MINUTES_AGO + ".few", "최종 관측: %1$s분 전",
                OBSERVATION_MINUTES_AGO + ".many", "최종 관측: %1$s분 전",
                OBSERVATION_MINUTES_AGO + ".other", "최종 관측: %1$s분 전",
                OBSERVATION_HOURS_AGO + ".zero", "최종 관측: %1$s시간 전",
                OBSERVATION_HOURS_AGO + ".one", "최종 관측: %1$s시간 전",
                OBSERVATION_HOURS_AGO + ".two", "최종 관측: %1$s시간 전",
                OBSERVATION_HOURS_AGO + ".few", "최종 관측: %1$s시간 전",
                OBSERVATION_HOURS_AGO + ".many", "최종 관측: %1$s시간 전",
                OBSERVATION_HOURS_AGO + ".other", "최종 관측: %1$s시간 전",
                OBSERVATION_DAYS_AGO + ".zero", "최종 관측: %1$s일 전",
                OBSERVATION_DAYS_AGO + ".one", "최종 관측: %1$s일 전",
                OBSERVATION_DAYS_AGO + ".two", "최종 관측: %1$s일 전",
                OBSERVATION_DAYS_AGO + ".few", "최종 관측: %1$s일 전",
                OBSERVATION_DAYS_AGO + ".many", "최종 관측: %1$s일 전",
                OBSERVATION_DAYS_AGO + ".other", "최종 관측: %1$s일 전",
                DURATION_LESS_THAN_MINUTES + ".zero", "%1$s분 미만",
                DURATION_LESS_THAN_MINUTES + ".one", "%1$s분 미만",
                DURATION_LESS_THAN_MINUTES + ".two", "%1$s분 미만",
                DURATION_LESS_THAN_MINUTES + ".few", "%1$s분 미만",
                DURATION_LESS_THAN_MINUTES + ".many", "%1$s분 미만",
                DURATION_LESS_THAN_MINUTES + ".other", "%1$s분 미만",
                DURATION_MINUTES + ".zero", "%1$s분",
                DURATION_MINUTES + ".one", "%1$s분",
                DURATION_MINUTES + ".two", "%1$s분",
                DURATION_MINUTES + ".few", "%1$s분",
                DURATION_MINUTES + ".many", "%1$s분",
                DURATION_MINUTES + ".other", "%1$s분",
                DURATION_HOURS + ".zero", "%1$s시간",
                DURATION_HOURS + ".one", "%1$s시간",
                DURATION_HOURS + ".two", "%1$s시간",
                DURATION_HOURS + ".few", "%1$s시간",
                DURATION_HOURS + ".many", "%1$s시간",
                DURATION_HOURS + ".other", "%1$s시간",
                DURATION_DAYS + ".zero", "%1$s일",
                DURATION_DAYS + ".one", "%1$s일",
                DURATION_DAYS + ".two", "%1$s일",
                DURATION_DAYS + ".few", "%1$s일",
                DURATION_DAYS + ".many", "%1$s일",
                DURATION_DAYS + ".other", "%1$s일",
                DISPLAY_NAME_LIMIT + ".zero", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_LIMIT + ".one", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_LIMIT + ".two", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_LIMIT + ".few", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_LIMIT + ".many", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_LIMIT + ".other", "%1$s자까지 저장할 수 있습니다.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "저장 시 길이는 %1$s자입니다. 상한은 %2$s자입니다."
        );
    }

    // ------------------------------------------------------------------
    // Русский (Russian)
    // ------------------------------------------------------------------

    private static Map<String, String> ru() {
        return m(
                APP_SUBTITLE, "основная сеть · полный узел",
                STATUS_STOPPED, "Остановлено",
                STATUS_STARTING, "Запуск",
                STATUS_SYNCING, "Синхронизация",
                STATUS_SYNCED, "Синхронизировано",
                LABEL_BEST_BLOCK, "Лучший блок (это устройство)",
                LABEL_HIGHEST_BLOCK, "Наивысший наблюдаемый блок",
                LABEL_PEERS, "Подключённые пиры",
                BUTTON_START_NODE, "Запустить узел",
                BUTTON_STOP_NODE, "Остановить узел",
                SECTION_TRACK, "След",
                LABEL_UPTIME_TOTAL, "Общее время работы",
                LABEL_BLOCKS_OBSERVED, "Наблюдено новых блоков",
                LABEL_MAX_PEERS, "Максимум наблюдаемых пиров",
                SECTION_SETTINGS, "Настройки",
                SETTINGS_TELEMETRY, "Показывать в телеметрии",
                SETTINGS_TELEMETRY_NOTE, "Если включено, имя вашего узла и его состояние будут публично видны в официальной телеметрии (telemetry.quantus.com). По умолчанию выключено.",
                SETTINGS_DISPLAY_NAME, "Отображаемое имя",
                SETTINGS_LANGUAGE, "Язык",
                LANGUAGE_FOLLOW_SYSTEM, "Как в настройках устройства",
                SETTINGS_LOCK, "Заблокировать настройки",
                SETTINGS_LOCK_NOTE, "Предотвращает случайные изменения телеметрии, отображаемого имени и языка. Запуск и остановка узла не блокируются.",
                NOTE_LAST_EXIT_CODE, "Код прошлого завершения: %1$d",
                NOTIF_CHANNEL_NAME, "Состояние узла",
                NOTIF_CHANNEL_DESC, "Состояние работы узла Quantus",
                NOTIF_PEERS, "пиров: %1$d",
                NOTIF_ACTION_STOP, "Остановить",
                NOTE_FGS_TIMEOUT, "Остановлено по системному ограничению времени",
                NOTE_BINARY_MISSING, "Бинарный файл узла не найден",
                NOTE_START_FAILED, "Не удалось запустить: %1$s",
                ATTEMPTS_TITLE, "Последние попытки запуска",
                ATTEMPT_METHOD_DIRECT, "напрямую",
                ATTEMPT_METHOD_LINKER, "через linker64",
                ATTEMPT_SUMMARY, "Попытка %1$d (%2$s): код завершения %3$d · %4$d мс",
                ATTEMPT_SUMMARY_NO_EXIT, "Попытка %1$d (%2$s): не удалось запустить · %3$d мс",
                ATTEMPT_NO_OUTPUT, "(вывода нет)",
                BUTTON_SHARE_LOG, "Поделиться журналом",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "Журнала пока нет",
                STATUS_START_FAILED, "Не удалось запустить узел",
                STATUS_UNEXPECTED_STOP, "Узел неожиданно остановился",
                MESSAGE_START_FAILED, "Вы можете посмотреть записи запуска.",
                MESSAGE_UNEXPECTED_STOP, "Вы можете посмотреть записи завершения.",
                MESSAGE_TIME_LIMIT_STOP, "Остановлено из-за системного ограничения времени.",
                MESSAGE_BINARY_MISSING, "Отсутствует файл, необходимый для запуска узла.",
                BUTTON_RETRY, "Повторить",
                BUTTON_CANCEL_STARTUP, "Отменить запуск",
                BUTTON_STOPPING, "Остановка…",
                ACTION_FAILURE_DETAILS, "Подробности ошибки",
                ACTION_STOP_DETAILS, "Подробности остановки",
                LABEL_UPTIME_SESSION, "Время работы в этот раз",
                MESSAGE_SYNC_TARGET_UNKNOWN, "Проверка цели синхронизации.",
                MESSAGE_NO_PEERS, "Нет подключённых пиров.",
                MESSAGE_WAITING_BLOCK, "Ожидание наблюдения нового блока.",
                VALUE_UNAVAILABLE, "Нет данных",
                OBSERVATION_JUST_NOW, "Последнее наблюдение: только что",
                SCREEN_NODE_DETAILS, "Сведения об узле",
                SECTION_DIAGNOSTICS, "Диагностика",
                LABEL_SYNC_GAP, "Отставание синхронизации (оценка)",
                SYNC_GAP_NOTE, "Отставание синхронизации — это разница между высотой наивысшего и лучшего блоков. Нулевая разница сама по себе не означает, что узел синхронизирован.",
                STOPPED_CUMULATIVE_NOTE, "Пока узел остановлен, отображаются накопленные записи.",
                TRACK_LOCAL_NOTE, "Эти записи хранятся на этом устройстве и никуда не отправляются.",
                RPC_LOCAL_NOTE, "RPC доступен только на этом устройстве.",
                LABEL_LOG_LOCATION, "Расположение журнала",
                DIAGNOSTICS_EMPTY, "Нет доступных диагностических записей.",
                MESSAGE_USER_STOP, "Остановлено пользователем.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "Изменения телеметрии и отображаемого имени вступят в силу при следующем запуске узла.",
                SETTINGS_LANGUAGE_IMMEDIATE, "При выборе языка язык приложения меняется сразу.",
                SETTINGS_PENDING_LINK, "Проверить неприменённые настройки",
                SETTINGS_PENDING_STATUS, "Применится при следующем запуске узла",
                SETTINGS_APPLIED_VALUE, "Используется: %1$s",
                VALUE_ON, "Вкл.",
                VALUE_OFF, "Выкл.",
                SETTINGS_NAME_DEFAULT, "Имя по умолчанию",
                SETTINGS_NAME_EMPTY_HINT, "Оставьте пустым, чтобы использовать имя по умолчанию.",
                SETTINGS_NAME_SAVED_PREVIEW, "Имя для сохранения: %1$s",
                SETTINGS_NAME_LOCKED, "Редактирование недоступно, пока настройки заблокированы.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "Сохранить",
                A11Y_BACK, "Назад",
                A11Y_EXPANDED, "Развёрнуто",
                A11Y_COLLAPSED, "Свёрнуто",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "Осталось %1$s блоков (оценка)",
                SYNC_REMAINING_BLOCKS + ".one", "Остался %1$s блок (оценка)",
                SYNC_REMAINING_BLOCKS + ".two", "Осталось %1$s блока (оценка)",
                SYNC_REMAINING_BLOCKS + ".few", "Осталось %1$s блока (оценка)",
                SYNC_REMAINING_BLOCKS + ".many", "Осталось %1$s блоков (оценка)",
                SYNC_REMAINING_BLOCKS + ".other", "Осталось %1$s блоков (оценка)",
                OBSERVATION_SECONDS_AGO + ".zero", "Последнее наблюдение: %1$s секунд назад",
                OBSERVATION_SECONDS_AGO + ".one", "Последнее наблюдение: %1$s секунду назад",
                OBSERVATION_SECONDS_AGO + ".two", "Последнее наблюдение: %1$s секунды назад",
                OBSERVATION_SECONDS_AGO + ".few", "Последнее наблюдение: %1$s секунды назад",
                OBSERVATION_SECONDS_AGO + ".many", "Последнее наблюдение: %1$s секунд назад",
                OBSERVATION_SECONDS_AGO + ".other", "Последнее наблюдение: %1$s секунд назад",
                OBSERVATION_MINUTES_AGO + ".zero", "Последнее наблюдение: %1$s минут назад",
                OBSERVATION_MINUTES_AGO + ".one", "Последнее наблюдение: %1$s минуту назад",
                OBSERVATION_MINUTES_AGO + ".two", "Последнее наблюдение: %1$s минуты назад",
                OBSERVATION_MINUTES_AGO + ".few", "Последнее наблюдение: %1$s минуты назад",
                OBSERVATION_MINUTES_AGO + ".many", "Последнее наблюдение: %1$s минут назад",
                OBSERVATION_MINUTES_AGO + ".other", "Последнее наблюдение: %1$s минут назад",
                OBSERVATION_HOURS_AGO + ".zero", "Последнее наблюдение: %1$s часов назад",
                OBSERVATION_HOURS_AGO + ".one", "Последнее наблюдение: %1$s час назад",
                OBSERVATION_HOURS_AGO + ".two", "Последнее наблюдение: %1$s часа назад",
                OBSERVATION_HOURS_AGO + ".few", "Последнее наблюдение: %1$s часа назад",
                OBSERVATION_HOURS_AGO + ".many", "Последнее наблюдение: %1$s часов назад",
                OBSERVATION_HOURS_AGO + ".other", "Последнее наблюдение: %1$s часов назад",
                OBSERVATION_DAYS_AGO + ".zero", "Последнее наблюдение: %1$s дней назад",
                OBSERVATION_DAYS_AGO + ".one", "Последнее наблюдение: %1$s день назад",
                OBSERVATION_DAYS_AGO + ".two", "Последнее наблюдение: %1$s дня назад",
                OBSERVATION_DAYS_AGO + ".few", "Последнее наблюдение: %1$s дня назад",
                OBSERVATION_DAYS_AGO + ".many", "Последнее наблюдение: %1$s дней назад",
                OBSERVATION_DAYS_AGO + ".other", "Последнее наблюдение: %1$s дней назад",
                DURATION_LESS_THAN_MINUTES + ".zero", "Меньше %1$s минут",
                DURATION_LESS_THAN_MINUTES + ".one", "Меньше %1$s минуты",
                DURATION_LESS_THAN_MINUTES + ".two", "Меньше %1$s минут",
                DURATION_LESS_THAN_MINUTES + ".few", "Меньше %1$s минут",
                DURATION_LESS_THAN_MINUTES + ".many", "Меньше %1$s минут",
                DURATION_LESS_THAN_MINUTES + ".other", "Меньше %1$s минут",
                DURATION_MINUTES + ".zero", "%1$s минут",
                DURATION_MINUTES + ".one", "%1$s минута",
                DURATION_MINUTES + ".two", "%1$s минуты",
                DURATION_MINUTES + ".few", "%1$s минуты",
                DURATION_MINUTES + ".many", "%1$s минут",
                DURATION_MINUTES + ".other", "%1$s минут",
                DURATION_HOURS + ".zero", "%1$s часов",
                DURATION_HOURS + ".one", "%1$s час",
                DURATION_HOURS + ".two", "%1$s часа",
                DURATION_HOURS + ".few", "%1$s часа",
                DURATION_HOURS + ".many", "%1$s часов",
                DURATION_HOURS + ".other", "%1$s часов",
                DURATION_DAYS + ".zero", "%1$s дней",
                DURATION_DAYS + ".one", "%1$s день",
                DURATION_DAYS + ".two", "%1$s дня",
                DURATION_DAYS + ".few", "%1$s дня",
                DURATION_DAYS + ".many", "%1$s дней",
                DURATION_DAYS + ".other", "%1$s дней",
                DISPLAY_NAME_LIMIT + ".zero", "Можно сохранить до %1$s символов.",
                DISPLAY_NAME_LIMIT + ".one", "Можно сохранить до %1$s символа.",
                DISPLAY_NAME_LIMIT + ".two", "Можно сохранить до %1$s символа.",
                DISPLAY_NAME_LIMIT + ".few", "Можно сохранить до %1$s символа.",
                DISPLAY_NAME_LIMIT + ".many", "Можно сохранить до %1$s символов.",
                DISPLAY_NAME_LIMIT + ".other", "Можно сохранить до %1$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "Длина при сохранении: %1$s символов. Лимит: %2$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "Длина при сохранении: %1$s символ. Лимит: %2$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "Длина при сохранении: %1$s символа. Лимит: %2$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "Длина при сохранении: %1$s символа. Лимит: %2$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "Длина при сохранении: %1$s символов. Лимит: %2$s символов.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "Длина при сохранении: %1$s символов. Лимит: %2$s символов."
        );
    }

    // ------------------------------------------------------------------
    // Français (French)
    // ------------------------------------------------------------------

    private static Map<String, String> fr() {
        return m(
                APP_SUBTITLE, "réseau principal · nœud complet",
                STATUS_STOPPED, "Arrêté",
                STATUS_STARTING, "Démarrage",
                STATUS_SYNCING, "Synchronisation",
                STATUS_SYNCED, "Synchronisé",
                LABEL_BEST_BLOCK, "Meilleur bloc (cet appareil)",
                LABEL_HIGHEST_BLOCK, "Bloc le plus haut observé",
                LABEL_PEERS, "Pairs connectés",
                BUTTON_START_NODE, "Démarrer le nœud",
                BUTTON_STOP_NODE, "Arrêter le nœud",
                SECTION_TRACK, "Traces",
                LABEL_UPTIME_TOTAL, "Temps de fonctionnement total",
                LABEL_BLOCKS_OBSERVED, "Nouveaux blocs observés",
                LABEL_MAX_PEERS, "Maximum de pairs observés",
                SECTION_SETTINGS, "Réglages",
                SETTINGS_TELEMETRY, "Apparaître dans la télémétrie",
                SETTINGS_TELEMETRY_NOTE, "Si activé, le nom de votre nœud et son état deviennent visibles par tous dans la télémétrie officielle (telemetry.quantus.com). Désactivé par défaut.",
                SETTINGS_DISPLAY_NAME, "Nom d'affichage",
                SETTINGS_LANGUAGE, "Langue",
                LANGUAGE_FOLLOW_SYSTEM, "Suivre les réglages de l'appareil",
                SETTINGS_LOCK, "Verrouiller les réglages",
                SETTINGS_LOCK_NOTE, "Empêche les modifications accidentelles de la télémétrie, du nom d'affichage et de la langue. Le démarrage et l'arrêt du nœud ne sont jamais verrouillés.",
                NOTE_LAST_EXIT_CODE, "Dernier code de sortie : %1$d",
                NOTIF_CHANNEL_NAME, "État du nœud",
                NOTIF_CHANNEL_DESC, "État de fonctionnement du nœud Quantus",
                NOTIF_PEERS, "%1$d pairs",
                NOTIF_ACTION_STOP, "Arrêter",
                NOTE_FGS_TIMEOUT, "Arrêté par la limite de temps du système",
                NOTE_BINARY_MISSING, "Binaire du nœud introuvable",
                NOTE_START_FAILED, "Échec du démarrage : %1$s",
                ATTEMPTS_TITLE, "Dernières tentatives de démarrage",
                ATTEMPT_METHOD_DIRECT, "direct",
                ATTEMPT_METHOD_LINKER, "via linker64",
                ATTEMPT_SUMMARY, "Tentative %1$d (%2$s) : code de sortie %3$d · %4$d ms",
                ATTEMPT_SUMMARY_NO_EXIT, "Tentative %1$d (%2$s) : démarrage impossible · %3$d ms",
                ATTEMPT_NO_OUTPUT, "(aucune sortie)",
                BUTTON_SHARE_LOG, "Partager le journal",
                SHARE_LOG_SUBJECT, "Quantus Node log",
                LOG_MISSING_NOTE, "Aucun journal pour le moment",
                STATUS_START_FAILED, "Impossible de démarrer le nœud",
                STATUS_UNEXPECTED_STOP, "Le nœud s'est arrêté de façon inattendue",
                MESSAGE_START_FAILED, "Vous pouvez consulter les détails du démarrage.",
                MESSAGE_UNEXPECTED_STOP, "Vous pouvez consulter les détails de l'arrêt.",
                MESSAGE_TIME_LIMIT_STOP, "Arrêté en raison de la limite de temps du système.",
                MESSAGE_BINARY_MISSING, "Le fichier nécessaire à l'exécution du nœud est introuvable.",
                BUTTON_RETRY, "Réessayer",
                BUTTON_CANCEL_STARTUP, "Annuler le démarrage",
                BUTTON_STOPPING, "Arrêt en cours…",
                ACTION_FAILURE_DETAILS, "Détails de l'échec",
                ACTION_STOP_DETAILS, "Détails de l'arrêt",
                LABEL_UPTIME_SESSION, "Temps de fonctionnement actuel",
                MESSAGE_SYNC_TARGET_UNKNOWN, "Vérification de la cible de synchronisation.",
                MESSAGE_NO_PEERS, "Aucun pair n'est connecté.",
                MESSAGE_WAITING_BLOCK, "En attente de l'observation d'un nouveau bloc.",
                VALUE_UNAVAILABLE, "Non disponible",
                OBSERVATION_JUST_NOW, "Dernière observation : à l'instant",
                SCREEN_NODE_DETAILS, "Détails du nœud",
                SECTION_DIAGNOSTICS, "Diagnostic",
                LABEL_SYNC_GAP, "Écart de synchronisation (estimation)",
                SYNC_GAP_NOTE, "L'écart de synchronisation est la différence entre les hauteurs du bloc le plus haut et du meilleur bloc. Un écart de zéro ne signifie pas à lui seul que le nœud est synchronisé.",
                STOPPED_CUMULATIVE_NOTE, "Affichage des enregistrements cumulés pendant l'arrêt du nœud.",
                TRACK_LOCAL_NOTE, "Ces enregistrements restent sur cet appareil et ne sont pas envoyés.",
                RPC_LOCAL_NOTE, "RPC est disponible uniquement sur cet appareil.",
                LABEL_LOG_LOCATION, "Emplacement du journal",
                DIAGNOSTICS_EMPTY, "Aucun enregistrement de diagnostic disponible.",
                MESSAGE_USER_STOP, "Arrêté par l'utilisateur.",
                VERSION_FORMAT, "Quantus Node · v%1$s",
                SETTINGS_NODE_RESTART_NOTE, "Les modifications de la télémétrie et du nom d'affichage prendront effet au prochain démarrage du nœud.",
                SETTINGS_LANGUAGE_IMMEDIATE, "Choisir une langue change immédiatement la langue de l'application.",
                SETTINGS_PENDING_LINK, "Vérifier les réglages en attente",
                SETTINGS_PENDING_STATUS, "S'applique au prochain démarrage du nœud",
                SETTINGS_APPLIED_VALUE, "En cours d'utilisation : %1$s",
                VALUE_ON, "Activé",
                VALUE_OFF, "Désactivé",
                SETTINGS_NAME_DEFAULT, "Nom par défaut",
                SETTINGS_NAME_EMPTY_HINT, "Laissez vide pour utiliser le nom par défaut.",
                SETTINGS_NAME_SAVED_PREVIEW, "Nom à enregistrer : %1$s",
                SETTINGS_NAME_LOCKED, "La modification est désactivée tant que les réglages sont verrouillés.",
                CHARACTER_COUNTER_FORMAT, "%1$s / %2$s",
                BUTTON_SAVE, "Enregistrer",
                A11Y_BACK, "Retour",
                A11Y_EXPANDED, "Développé",
                A11Y_COLLAPSED, "Réduit",
                DURATION_JOIN_TWO, "%1$s %2$s",
                DURATION_JOIN_THREE, "%1$s %2$s %3$s",
                SYNC_REMAINING_BLOCKS + ".zero", "%1$s blocs restants (estimation)",
                SYNC_REMAINING_BLOCKS + ".one", "%1$s bloc restant (estimation)",
                SYNC_REMAINING_BLOCKS + ".two", "%1$s blocs restants (estimation)",
                SYNC_REMAINING_BLOCKS + ".few", "%1$s blocs restants (estimation)",
                SYNC_REMAINING_BLOCKS + ".many", "%1$s blocs restants (estimation)",
                SYNC_REMAINING_BLOCKS + ".other", "%1$s blocs restants (estimation)",
                OBSERVATION_SECONDS_AGO + ".zero", "Dernière observation : il y a %1$s secondes",
                OBSERVATION_SECONDS_AGO + ".one", "Dernière observation : il y a %1$s seconde",
                OBSERVATION_SECONDS_AGO + ".two", "Dernière observation : il y a %1$s secondes",
                OBSERVATION_SECONDS_AGO + ".few", "Dernière observation : il y a %1$s secondes",
                OBSERVATION_SECONDS_AGO + ".many", "Dernière observation : il y a %1$s secondes",
                OBSERVATION_SECONDS_AGO + ".other", "Dernière observation : il y a %1$s secondes",
                OBSERVATION_MINUTES_AGO + ".zero", "Dernière observation : il y a %1$s minutes",
                OBSERVATION_MINUTES_AGO + ".one", "Dernière observation : il y a %1$s minute",
                OBSERVATION_MINUTES_AGO + ".two", "Dernière observation : il y a %1$s minutes",
                OBSERVATION_MINUTES_AGO + ".few", "Dernière observation : il y a %1$s minutes",
                OBSERVATION_MINUTES_AGO + ".many", "Dernière observation : il y a %1$s minutes",
                OBSERVATION_MINUTES_AGO + ".other", "Dernière observation : il y a %1$s minutes",
                OBSERVATION_HOURS_AGO + ".zero", "Dernière observation : il y a %1$s heures",
                OBSERVATION_HOURS_AGO + ".one", "Dernière observation : il y a %1$s heure",
                OBSERVATION_HOURS_AGO + ".two", "Dernière observation : il y a %1$s heures",
                OBSERVATION_HOURS_AGO + ".few", "Dernière observation : il y a %1$s heures",
                OBSERVATION_HOURS_AGO + ".many", "Dernière observation : il y a %1$s heures",
                OBSERVATION_HOURS_AGO + ".other", "Dernière observation : il y a %1$s heures",
                OBSERVATION_DAYS_AGO + ".zero", "Dernière observation : il y a %1$s jours",
                OBSERVATION_DAYS_AGO + ".one", "Dernière observation : il y a %1$s jour",
                OBSERVATION_DAYS_AGO + ".two", "Dernière observation : il y a %1$s jours",
                OBSERVATION_DAYS_AGO + ".few", "Dernière observation : il y a %1$s jours",
                OBSERVATION_DAYS_AGO + ".many", "Dernière observation : il y a %1$s jours",
                OBSERVATION_DAYS_AGO + ".other", "Dernière observation : il y a %1$s jours",
                DURATION_LESS_THAN_MINUTES + ".zero", "Moins de %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".one", "Moins de %1$s minute",
                DURATION_LESS_THAN_MINUTES + ".two", "Moins de %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".few", "Moins de %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".many", "Moins de %1$s minutes",
                DURATION_LESS_THAN_MINUTES + ".other", "Moins de %1$s minutes",
                DURATION_MINUTES + ".zero", "%1$s minutes",
                DURATION_MINUTES + ".one", "%1$s minute",
                DURATION_MINUTES + ".two", "%1$s minutes",
                DURATION_MINUTES + ".few", "%1$s minutes",
                DURATION_MINUTES + ".many", "%1$s minutes",
                DURATION_MINUTES + ".other", "%1$s minutes",
                DURATION_HOURS + ".zero", "%1$s heures",
                DURATION_HOURS + ".one", "%1$s heure",
                DURATION_HOURS + ".two", "%1$s heures",
                DURATION_HOURS + ".few", "%1$s heures",
                DURATION_HOURS + ".many", "%1$s heures",
                DURATION_HOURS + ".other", "%1$s heures",
                DURATION_DAYS + ".zero", "%1$s jours",
                DURATION_DAYS + ".one", "%1$s jour",
                DURATION_DAYS + ".two", "%1$s jours",
                DURATION_DAYS + ".few", "%1$s jours",
                DURATION_DAYS + ".many", "%1$s jours",
                DURATION_DAYS + ".other", "%1$s jours",
                DISPLAY_NAME_LIMIT + ".zero", "Vous pouvez enregistrer jusqu'à %1$s caractères.",
                DISPLAY_NAME_LIMIT + ".one", "Vous pouvez enregistrer jusqu'à %1$s caractère.",
                DISPLAY_NAME_LIMIT + ".two", "Vous pouvez enregistrer jusqu'à %1$s caractères.",
                DISPLAY_NAME_LIMIT + ".few", "Vous pouvez enregistrer jusqu'à %1$s caractères.",
                DISPLAY_NAME_LIMIT + ".many", "Vous pouvez enregistrer jusqu'à %1$s caractères.",
                DISPLAY_NAME_LIMIT + ".other", "Vous pouvez enregistrer jusqu'à %1$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".zero", "Longueur à l'enregistrement : %1$s caractères. Limite : %2$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".one", "Longueur à l'enregistrement : %1$s caractère. Limite : %2$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".two", "Longueur à l'enregistrement : %1$s caractères. Limite : %2$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".few", "Longueur à l'enregistrement : %1$s caractères. Limite : %2$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".many", "Longueur à l'enregistrement : %1$s caractères. Limite : %2$s caractères.",
                DISPLAY_NAME_COUNT_A11Y + ".other", "Longueur à l'enregistrement : %1$s caractères. Limite : %2$s caractères."
        );
    }
}
