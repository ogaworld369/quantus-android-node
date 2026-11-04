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
 * Number policy (decided, do not change casually): numbers are always
 * rendered with ASCII digits 0-9 and no grouping separators, in every
 * language. {@link I18n} formats with Locale.US to pin that down, so
 * e.g. Arabic and Hindi do not switch to local digit shapes.
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
    public static final String SECTION_QUANTUM_WATCH = "section_quantum_watch";
    public static final String LABEL_WATCH_OWN_SYNC = "label_watch_own_sync";
    public static final String LABEL_WATCH_NETWORK = "label_watch_network";
    public static final String LABEL_WATCH_BLOCKS = "label_watch_blocks";
    public static final String WATCH_THREAT_STUB = "watch_threat_stub";
    public static final String SECTION_SETTINGS = "section_settings";
    public static final String SETTINGS_TELEMETRY = "settings_telemetry";
    public static final String SETTINGS_TELEMETRY_NOTE = "settings_telemetry_note";
    public static final String SETTINGS_DISPLAY_NAME = "settings_display_name";
    public static final String SETTINGS_LANGUAGE = "settings_language";
    public static final String LANGUAGE_FOLLOW_SYSTEM = "language_follow_system";
    public static final String SETTINGS_LOCK = "settings_lock";
    public static final String SETTINGS_UNLOCK = "settings_unlock";
    public static final String SETTINGS_LOCKED_NOTE = "settings_locked_note";
    /** Always-visible one-liner under the settings-lock switch. */
    public static final String SETTINGS_LOCK_NOTE = "settings_lock_note";
    public static final String SETTINGS_RESTART_NOTE = "settings_restart_note";
    public static final String FOOTER_INFO = "footer_info";
    /** Format: %1$d = exit code. */
    public static final String NOTE_LAST_EXIT_CODE = "note_last_exit_code";
    /** Format: %1$d = peer count. */
    public static final String VALUE_PEERS_CONNECTED = "value_peers_connected";
    /** Format: %1$d = hours, %2$d = minutes. */
    public static final String DURATION_HOURS_MINUTES = "duration_hours_minutes";
    /** Format: %1$d = minutes. */
    public static final String DURATION_MINUTES = "duration_minutes";
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

    /** Supported language tags, in picker order. "zh" is Simplified Chinese. */
    public static final String[] TAGS = {
            "ja", "en", "zh", "es", "hi", "ar", "ko", "ru", "fr"
    };

    /** The source language every key is written in first. */
    public static final String SOURCE_TAG = "ja";

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
                APP_SUBTITLE, "mainnet · full node",
                STATUS_STOPPED, "停止",
                STATUS_STARTING, "起動中",
                STATUS_SYNCING, "同期中",
                STATUS_SYNCED, "同期完了",
                LABEL_BEST_BLOCK, "ベストブロック",
                LABEL_HIGHEST_BLOCK, "最高ブロック",
                LABEL_PEERS, "ピア数",
                BUTTON_START_NODE, "ノードを開始",
                BUTTON_STOP_NODE, "ノードを停止",
                SECTION_TRACK, "航跡",
                LABEL_UPTIME_TOTAL, "稼働時間（累計）",
                LABEL_BLOCKS_OBSERVED, "観測した新ブロック数",
                LABEL_MAX_PEERS, "観測したピア数の最大値",
                SECTION_QUANTUM_WATCH, "量子ウォッチ",
                LABEL_WATCH_OWN_SYNC, "自ノードの同期状態",
                LABEL_WATCH_NETWORK, "ネットワーク接続（ピア）",
                LABEL_WATCH_BLOCKS, "ベスト / 最高ブロック",
                WATCH_THREAT_STUB, "外部の脅威情報: 未実装（設計メモ参照）",
                SECTION_SETTINGS, "設定",
                SETTINGS_TELEMETRY, "テレメトリに表示する",
                SETTINGS_TELEMETRY_NOTE, "オンにすると、ノード名と稼働状態が公式テレメトリ（telemetry.quantus.com）で誰でも見える状態になります。既定はオフです。",
                SETTINGS_DISPLAY_NAME, "表示名（空欄なら既定の名前になります）",
                SETTINGS_LANGUAGE, "言語",
                LANGUAGE_FOLLOW_SYSTEM, "端末の設定に従う",
                SETTINGS_LOCK, "設定をロック",
                SETTINGS_UNLOCK, "ロックを解除",
                SETTINGS_LOCKED_NOTE, "設定はロックされています。変更するにはロックを解除してください。",
                SETTINGS_LOCK_NOTE, "テレメトリ・表示名・言語の誤変更を防ぎます。開始・停止はロックされません。",
                SETTINGS_RESTART_NOTE, "設定を変更しました。反映するにはノードの再起動（停止 → 開始）が必要です。自動では再起動しません。",
                FOOTER_INFO, "RPC は端末内 (127.0.0.1:9944) のみ · ログ: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "前回の終了コード: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d 接続中",
                DURATION_HOURS_MINUTES, "%1$d時間 %2$d分",
                DURATION_MINUTES, "%1$d分",
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
                LOG_MISSING_NOTE, "ログはまだありません"
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
                LABEL_BEST_BLOCK, "Best block",
                LABEL_HIGHEST_BLOCK, "Highest block",
                LABEL_PEERS, "Peers",
                BUTTON_START_NODE, "Start node",
                BUTTON_STOP_NODE, "Stop node",
                SECTION_TRACK, "Track",
                LABEL_UPTIME_TOTAL, "Uptime (total)",
                LABEL_BLOCKS_OBSERVED, "New blocks observed",
                LABEL_MAX_PEERS, "Most peers observed",
                SECTION_QUANTUM_WATCH, "Quantum Watch",
                LABEL_WATCH_OWN_SYNC, "This node's sync status",
                LABEL_WATCH_NETWORK, "Network connections (peers)",
                LABEL_WATCH_BLOCKS, "Best / highest block",
                WATCH_THREAT_STUB, "External threat intel: not implemented yet (see design notes)",
                SECTION_SETTINGS, "Settings",
                SETTINGS_TELEMETRY, "Appear in telemetry",
                SETTINGS_TELEMETRY_NOTE, "When on, your node name and live status become publicly visible in the official telemetry (telemetry.quantus.com). Off by default.",
                SETTINGS_DISPLAY_NAME, "Display name (leave blank for the default name)",
                SETTINGS_LANGUAGE, "Language",
                LANGUAGE_FOLLOW_SYSTEM, "Follow device settings",
                SETTINGS_LOCK, "Lock settings",
                SETTINGS_UNLOCK, "Unlock settings",
                SETTINGS_LOCKED_NOTE, "Settings are locked. Unlock to make changes.",
                SETTINGS_LOCK_NOTE, "Prevents accidental changes to telemetry, display name, and language. Starting and stopping the node is never locked.",
                SETTINGS_RESTART_NOTE, "Settings changed. To apply them, restart the node (Stop → Start). It never restarts automatically.",
                FOOTER_INFO, "RPC stays on this device (127.0.0.1:9944) · Log: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "Last exit code: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d connected",
                DURATION_HOURS_MINUTES, "%1$dh %2$dm",
                DURATION_MINUTES, "%1$dm",
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
                LOG_MISSING_NOTE, "No log yet"
        );
    }

    // ------------------------------------------------------------------
    // 简体中文
    // ------------------------------------------------------------------

    private static Map<String, String> zh() {
        return m(
                APP_SUBTITLE, "主网 · 全节点",
                STATUS_STOPPED, "已停止",
                STATUS_STARTING, "启动中",
                STATUS_SYNCING, "同步中",
                STATUS_SYNCED, "同步完成",
                LABEL_BEST_BLOCK, "最佳区块",
                LABEL_HIGHEST_BLOCK, "最高区块",
                LABEL_PEERS, "对等节点数",
                BUTTON_START_NODE, "启动节点",
                BUTTON_STOP_NODE, "停止节点",
                SECTION_TRACK, "航迹",
                LABEL_UPTIME_TOTAL, "运行时间（累计）",
                LABEL_BLOCKS_OBSERVED, "观察到的新区块数",
                LABEL_MAX_PEERS, "观察到的对等节点数最大值",
                SECTION_QUANTUM_WATCH, "量子观察",
                LABEL_WATCH_OWN_SYNC, "本节点同步状态",
                LABEL_WATCH_NETWORK, "网络连接（对等节点）",
                LABEL_WATCH_BLOCKS, "最佳 / 最高区块",
                WATCH_THREAT_STUB, "外部威胁信息：尚未实现（见设计说明）",
                SECTION_SETTINGS, "设置",
                SETTINGS_TELEMETRY, "在遥测中显示",
                SETTINGS_TELEMETRY_NOTE, "开启后，您的节点名称和运行状态将在官方遥测（telemetry.quantus.com）中公开可见。默认为关闭。",
                SETTINGS_DISPLAY_NAME, "显示名称（留空则使用默认名称）",
                SETTINGS_LANGUAGE, "语言",
                LANGUAGE_FOLLOW_SYSTEM, "跟随设备设置",
                SETTINGS_LOCK, "锁定设置",
                SETTINGS_UNLOCK, "解锁设置",
                SETTINGS_LOCKED_NOTE, "设置已锁定。解锁后可更改。",
                SETTINGS_LOCK_NOTE, "防止意外更改遥测、显示名称和语言。节点的启动和停止不会被锁定。",
                SETTINGS_RESTART_NOTE, "设置已更改。要生效，请重启节点（停止 → 启动）。不会自动重启。",
                FOOTER_INFO, "RPC 仅在本设备内 (127.0.0.1:9944) · 日志：files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "上次退出代码：%1$d",
                VALUE_PEERS_CONNECTED, "已连接 %1$d 个",
                DURATION_HOURS_MINUTES, "%1$d 小时 %2$d 分钟",
                DURATION_MINUTES, "%1$d 分钟",
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
                LOG_MISSING_NOTE, "尚无日志"
        );
    }

    // ------------------------------------------------------------------
    // Español
    // ------------------------------------------------------------------

    private static Map<String, String> es() {
        return m(
                APP_SUBTITLE, "red principal · nodo completo",
                STATUS_STOPPED, "Detenido",
                STATUS_STARTING, "Iniciando",
                STATUS_SYNCING, "Sincronizando",
                STATUS_SYNCED, "Sincronizado",
                LABEL_BEST_BLOCK, "Mejor bloque",
                LABEL_HIGHEST_BLOCK, "Bloque más alto",
                LABEL_PEERS, "Pares",
                BUTTON_START_NODE, "Iniciar nodo",
                BUTTON_STOP_NODE, "Detener nodo",
                SECTION_TRACK, "Trayectoria",
                LABEL_UPTIME_TOTAL, "Tiempo activo (total)",
                LABEL_BLOCKS_OBSERVED, "Bloques nuevos observados",
                LABEL_MAX_PEERS, "Máximo de pares observados",
                SECTION_QUANTUM_WATCH, "Vigilancia cuántica",
                LABEL_WATCH_OWN_SYNC, "Sincronización de este nodo",
                LABEL_WATCH_NETWORK, "Conexiones de red (pares)",
                LABEL_WATCH_BLOCKS, "Mejor bloque / más alto",
                WATCH_THREAT_STUB, "Información de amenazas externas: aún no implementada (ver notas de diseño)",
                SECTION_SETTINGS, "Ajustes",
                SETTINGS_TELEMETRY, "Aparecer en la telemetría",
                SETTINGS_TELEMETRY_NOTE, "Al activarlo, el nombre de tu nodo y su estado serán visibles públicamente en la telemetría oficial (telemetry.quantus.com). Desactivado por defecto.",
                SETTINGS_DISPLAY_NAME, "Nombre visible (déjalo vacío para usar el nombre predeterminado)",
                SETTINGS_LANGUAGE, "Idioma",
                LANGUAGE_FOLLOW_SYSTEM, "Seguir los ajustes del dispositivo",
                SETTINGS_LOCK, "Bloquear ajustes",
                SETTINGS_UNLOCK, "Desbloquear ajustes",
                SETTINGS_LOCKED_NOTE, "Los ajustes están bloqueados. Desbloquéalos para hacer cambios.",
                SETTINGS_LOCK_NOTE, "Evita cambios accidentales en la telemetría, el nombre visible y el idioma. Iniciar y detener el nodo nunca se bloquea.",
                SETTINGS_RESTART_NOTE, "Ajustes modificados. Para aplicarlos, reinicia el nodo (Detener → Iniciar). Nunca se reinicia automáticamente.",
                FOOTER_INFO, "RPC solo en este dispositivo (127.0.0.1:9944) · Registro: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "Último código de salida: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d conectados",
                DURATION_HOURS_MINUTES, "%1$d h %2$d min",
                DURATION_MINUTES, "%1$d min",
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
                LOG_MISSING_NOTE, "Aún no hay registro"
        );
    }

    // ------------------------------------------------------------------
    // हिन्दी
    // ------------------------------------------------------------------

    private static Map<String, String> hi() {
        return m(
                APP_SUBTITLE, "मेननेट · पूर्ण नोड",
                STATUS_STOPPED, "बंद है",
                STATUS_STARTING, "शुरू हो रहा है",
                STATUS_SYNCING, "सिंक हो रहा है",
                STATUS_SYNCED, "सिंक पूर्ण",
                LABEL_BEST_BLOCK, "सर्वश्रेष्ठ ब्लॉक",
                LABEL_HIGHEST_BLOCK, "उच्चतम ब्लॉक",
                LABEL_PEERS, "पीयर्स की संख्या",
                BUTTON_START_NODE, "नोड शुरू करें",
                BUTTON_STOP_NODE, "नोड बंद करें",
                SECTION_TRACK, "ट्रैक",
                LABEL_UPTIME_TOTAL, "सक्रिय समय (कुल)",
                LABEL_BLOCKS_OBSERVED, "देखे गए नए ब्लॉक",
                LABEL_MAX_PEERS, "देखे गए पीयर्स की अधिकतम संख्या",
                SECTION_QUANTUM_WATCH, "क्वांटम वॉच",
                LABEL_WATCH_OWN_SYNC, "इस नोड की सिंक स्थिति",
                LABEL_WATCH_NETWORK, "नेटवर्क कनेक्शन (पीयर्स)",
                LABEL_WATCH_BLOCKS, "सर्वश्रेष्ठ / उच्चतम ब्लॉक",
                WATCH_THREAT_STUB, "बाहरी ख़तरे की जानकारी: अभी लागू नहीं (डिज़ाइन नोट देखें)",
                SECTION_SETTINGS, "सेटिंग्स",
                SETTINGS_TELEMETRY, "टेलीमेट्री में दिखाएँ",
                SETTINGS_TELEMETRY_NOTE, "चालू करने पर, आपके नोड का नाम और स्थिति आधिकारिक टेलीमेट्री (telemetry.quantus.com) पर सभी को सार्वजनिक रूप से दिखाई देगी। डिफ़ॉल्ट रूप से बंद है।",
                SETTINGS_DISPLAY_NAME, "प्रदर्शन नाम (खाली छोड़ने पर डिफ़ॉल्ट नाम रहेगा)",
                SETTINGS_LANGUAGE, "भाषा",
                LANGUAGE_FOLLOW_SYSTEM, "डिवाइस सेटिंग के अनुसार",
                SETTINGS_LOCK, "सेटिंग्स लॉक करें",
                SETTINGS_UNLOCK, "सेटिंग्स अनलॉक करें",
                SETTINGS_LOCKED_NOTE, "सेटिंग्स लॉक हैं। बदलाव के लिए अनलॉक करें।",
                SETTINGS_LOCK_NOTE, "टेलीमेट्री, प्रदर्शन नाम और भाषा में आकस्मिक बदलावों को रोकता है। नोड को शुरू या बंद करना कभी लॉक नहीं होता।",
                SETTINGS_RESTART_NOTE, "सेटिंग बदल गई है। लागू करने के लिए नोड को फिर से शुरू करें (बंद करें → शुरू करें)। यह अपने आप पुनः शुरू नहीं होता।",
                FOOTER_INFO, "RPC केवल इस डिवाइस पर (127.0.0.1:9944) · लॉग: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "पिछला निकास कोड: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d कनेक्टेड",
                DURATION_HOURS_MINUTES, "%1$d घंटे %2$d मिनट",
                DURATION_MINUTES, "%1$d मिनट",
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
                LOG_MISSING_NOTE, "अभी कोई लॉग नहीं है"
        );
    }

    // ------------------------------------------------------------------
    // العربية
    // ------------------------------------------------------------------

    private static Map<String, String> ar() {
        return m(
                APP_SUBTITLE, "الشبكة الرئيسية · عقدة كاملة",
                STATUS_STOPPED, "متوقف",
                STATUS_STARTING, "جارٍ البدء",
                STATUS_SYNCING, "جارٍ المزامنة",
                STATUS_SYNCED, "اكتملت المزامنة",
                LABEL_BEST_BLOCK, "أفضل كتلة",
                LABEL_HIGHEST_BLOCK, "أعلى كتلة",
                LABEL_PEERS, "عدد الأقران",
                BUTTON_START_NODE, "بدء العقدة",
                BUTTON_STOP_NODE, "إيقاف العقدة",
                SECTION_TRACK, "الأثر",
                LABEL_UPTIME_TOTAL, "مدة التشغيل (الإجمالي)",
                LABEL_BLOCKS_OBSERVED, "الكتل الجديدة المرصودة",
                LABEL_MAX_PEERS, "أكبر عدد أقران تم رصده",
                SECTION_QUANTUM_WATCH, "المراقبة الكمّية",
                LABEL_WATCH_OWN_SYNC, "حالة مزامنة هذه العقدة",
                LABEL_WATCH_NETWORK, "اتصالات الشبكة (الأقران)",
                LABEL_WATCH_BLOCKS, "أفضل / أعلى كتلة",
                WATCH_THREAT_STUB, "معلومات التهديدات الخارجية: غير مُنفَّذة بعد (راجع ملاحظات التصميم)",
                SECTION_SETTINGS, "الإعدادات",
                SETTINGS_TELEMETRY, "الظهور في القياس عن بُعد",
                SETTINGS_TELEMETRY_NOTE, "عند التفعيل، يصبح اسم عقدتك وحالتها ظاهرَين للعامة في القياس الرسمي عن بُعد (telemetry.quantus.com). معطّل افتراضيًا.",
                SETTINGS_DISPLAY_NAME, "اسم العرض (اتركه فارغًا لاستخدام الاسم الافتراضي)",
                SETTINGS_LANGUAGE, "اللغة",
                LANGUAGE_FOLLOW_SYSTEM, "اتباع إعدادات الجهاز",
                SETTINGS_LOCK, "قفل الإعدادات",
                SETTINGS_UNLOCK, "إلغاء قفل الإعدادات",
                SETTINGS_LOCKED_NOTE, "الإعدادات مقفلة. ألغِ القفل لإجراء تغييرات.",
                SETTINGS_LOCK_NOTE, "يمنع التغييرات غير المقصودة في القياس عن بُعد واسم العرض واللغة. لا يتم قفل بدء العقدة أو إيقافها أبدًا.",
                SETTINGS_RESTART_NOTE, "تم تغيير الإعدادات. لتطبيقها، أعد تشغيل العقدة (إيقاف ← بدء). لا تُعاد العقدة تلقائيًا أبدًا.",
                FOOTER_INFO, "RPC على هذا الجهاز فقط (127.0.0.1:9944) · السجل: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "رمز الخروج الأخير: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d متصل",
                DURATION_HOURS_MINUTES, "%1$d ساعة %2$d دقيقة",
                DURATION_MINUTES, "%1$d دقيقة",
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
                LOG_MISSING_NOTE, "لا يوجد سجل بعد"
        );
    }

    // ------------------------------------------------------------------
    // 한국어
    // ------------------------------------------------------------------

    private static Map<String, String> ko() {
        return m(
                APP_SUBTITLE, "메인넷 · 풀 노드",
                STATUS_STOPPED, "정지",
                STATUS_STARTING, "시작 중",
                STATUS_SYNCING, "동기화 중",
                STATUS_SYNCED, "동기화 완료",
                LABEL_BEST_BLOCK, "베스트 블록",
                LABEL_HIGHEST_BLOCK, "최고 블록",
                LABEL_PEERS, "피어 수",
                BUTTON_START_NODE, "노드 시작",
                BUTTON_STOP_NODE, "노드 정지",
                SECTION_TRACK, "항적",
                LABEL_UPTIME_TOTAL, "가동 시간 (누적)",
                LABEL_BLOCKS_OBSERVED, "관측한 새 블록 수",
                LABEL_MAX_PEERS, "관측한 최대 피어 수",
                SECTION_QUANTUM_WATCH, "양자 워치",
                LABEL_WATCH_OWN_SYNC, "내 노드의 동기화 상태",
                LABEL_WATCH_NETWORK, "네트워크 연결 (피어)",
                LABEL_WATCH_BLOCKS, "베스트 / 최고 블록",
                WATCH_THREAT_STUB, "외부 위협 정보: 미구현 (설계 메모 참조)",
                SECTION_SETTINGS, "설정",
                SETTINGS_TELEMETRY, "텔레메트리에 표시",
                SETTINGS_TELEMETRY_NOTE, "켜면 노드 이름과 가동 상태가 공식 텔레메트리 (telemetry.quantus.com)에서 누구나 볼 수 있는 상태가 됩니다. 기본값은 꺼짐입니다.",
                SETTINGS_DISPLAY_NAME, "표시 이름 (비워 두면 기본 이름이 됩니다)",
                SETTINGS_LANGUAGE, "언어",
                LANGUAGE_FOLLOW_SYSTEM, "기기 설정 따르기",
                SETTINGS_LOCK, "설정 잠금",
                SETTINGS_UNLOCK, "잠금 해제",
                SETTINGS_LOCKED_NOTE, "설정이 잠겨 있습니다. 변경하려면 잠금을 해제하세요.",
                SETTINGS_LOCK_NOTE, "텔레메트리, 표시 이름, 언어가 실수로 변경되는 것을 방지합니다. 노드 시작과 중지는 잠기지 않습니다.",
                SETTINGS_RESTART_NOTE, "설정을 변경했습니다. 반영하려면 노드를 재시작(정지 → 시작)해야 합니다. 자동으로 재시작하지 않습니다.",
                FOOTER_INFO, "RPC는 이 기기 내부만 (127.0.0.1:9944) · 로그: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "이전 종료 코드: %1$d",
                VALUE_PEERS_CONNECTED, "%1$d개 연결 중",
                DURATION_HOURS_MINUTES, "%1$d시간 %2$d분",
                DURATION_MINUTES, "%1$d분",
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
                LOG_MISSING_NOTE, "아직 로그가 없습니다"
        );
    }

    // ------------------------------------------------------------------
    // Русский
    // ------------------------------------------------------------------

    private static Map<String, String> ru() {
        return m(
                APP_SUBTITLE, "основная сеть · полный узел",
                STATUS_STOPPED, "Остановлено",
                STATUS_STARTING, "Запуск",
                STATUS_SYNCING, "Синхронизация",
                STATUS_SYNCED, "Синхронизировано",
                LABEL_BEST_BLOCK, "Лучший блок",
                LABEL_HIGHEST_BLOCK, "Наивысший блок",
                LABEL_PEERS, "Число пиров",
                BUTTON_START_NODE, "Запустить узел",
                BUTTON_STOP_NODE, "Остановить узел",
                SECTION_TRACK, "След",
                LABEL_UPTIME_TOTAL, "Время работы (всего)",
                LABEL_BLOCKS_OBSERVED, "Наблюдено новых блоков",
                LABEL_MAX_PEERS, "Максимум наблюдаемых пиров",
                SECTION_QUANTUM_WATCH, "Квантовый дозор",
                LABEL_WATCH_OWN_SYNC, "Синхронизация этого узла",
                LABEL_WATCH_NETWORK, "Сетевые подключения (пиры)",
                LABEL_WATCH_BLOCKS, "Лучший / наивысший блок",
                WATCH_THREAT_STUB, "Внешние данные об угрозах: пока не реализовано (см. заметки к дизайну)",
                SECTION_SETTINGS, "Настройки",
                SETTINGS_TELEMETRY, "Показывать в телеметрии",
                SETTINGS_TELEMETRY_NOTE, "Если включено, имя вашего узла и его состояние будут публично видны в официальной телеметрии (telemetry.quantus.com). По умолчанию выключено.",
                SETTINGS_DISPLAY_NAME, "Отображаемое имя (пусто — имя по умолчанию)",
                SETTINGS_LANGUAGE, "Язык",
                LANGUAGE_FOLLOW_SYSTEM, "Как в настройках устройства",
                SETTINGS_LOCK, "Заблокировать настройки",
                SETTINGS_UNLOCK, "Разблокировать настройки",
                SETTINGS_LOCKED_NOTE, "Настройки заблокированы. Разблокируйте, чтобы внести изменения.",
                SETTINGS_LOCK_NOTE, "Предотвращает случайные изменения телеметрии, отображаемого имени и языка. Запуск и остановка узла не блокируются.",
                SETTINGS_RESTART_NOTE, "Настройки изменены. Чтобы применить их, перезапустите узел (Остановить → Запустить). Автоматического перезапуска не происходит.",
                FOOTER_INFO, "RPC только на этом устройстве (127.0.0.1:9944) · Журнал: files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "Код прошлого завершения: %1$d",
                VALUE_PEERS_CONNECTED, "подключено: %1$d",
                DURATION_HOURS_MINUTES, "%1$d ч %2$d мин",
                DURATION_MINUTES, "%1$d мин",
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
                LOG_MISSING_NOTE, "Журнала пока нет"
        );
    }

    // ------------------------------------------------------------------
    // Français
    // ------------------------------------------------------------------

    private static Map<String, String> fr() {
        return m(
                APP_SUBTITLE, "réseau principal · nœud complet",
                STATUS_STOPPED, "Arrêté",
                STATUS_STARTING, "Démarrage",
                STATUS_SYNCING, "Synchronisation",
                STATUS_SYNCED, "Synchronisé",
                LABEL_BEST_BLOCK, "Meilleur bloc",
                LABEL_HIGHEST_BLOCK, "Bloc le plus haut",
                LABEL_PEERS, "Nombre de pairs",
                BUTTON_START_NODE, "Démarrer le nœud",
                BUTTON_STOP_NODE, "Arrêter le nœud",
                SECTION_TRACK, "Traces",
                LABEL_UPTIME_TOTAL, "Temps de fonctionnement (total)",
                LABEL_BLOCKS_OBSERVED, "Nouveaux blocs observés",
                LABEL_MAX_PEERS, "Maximum de pairs observés",
                SECTION_QUANTUM_WATCH, "Veille quantique",
                LABEL_WATCH_OWN_SYNC, "Synchronisation de ce nœud",
                LABEL_WATCH_NETWORK, "Connexions réseau (pairs)",
                LABEL_WATCH_BLOCKS, "Meilleur bloc / le plus haut",
                WATCH_THREAT_STUB, "Informations sur les menaces externes : pas encore implémentées (voir les notes de conception)",
                SECTION_SETTINGS, "Réglages",
                SETTINGS_TELEMETRY, "Apparaître dans la télémétrie",
                SETTINGS_TELEMETRY_NOTE, "Si activé, le nom de votre nœud et son état deviennent visibles par tous dans la télémétrie officielle (telemetry.quantus.com). Désactivé par défaut.",
                SETTINGS_DISPLAY_NAME, "Nom d'affichage (vide = nom par défaut)",
                SETTINGS_LANGUAGE, "Langue",
                LANGUAGE_FOLLOW_SYSTEM, "Suivre les réglages de l'appareil",
                SETTINGS_LOCK, "Verrouiller les réglages",
                SETTINGS_UNLOCK, "Déverrouiller les réglages",
                SETTINGS_LOCKED_NOTE, "Les réglages sont verrouillés. Déverrouillez-les pour les modifier.",
                SETTINGS_LOCK_NOTE, "Empêche les modifications accidentelles de la télémétrie, du nom d'affichage et de la langue. Le démarrage et l'arrêt du nœud ne sont jamais verrouillés.",
                SETTINGS_RESTART_NOTE, "Réglages modifiés. Pour les appliquer, redémarrez le nœud (Arrêter → Démarrer). Il ne redémarre jamais automatiquement.",
                FOOTER_INFO, "RPC sur cet appareil uniquement (127.0.0.1:9944) · Journal : files/logs/node.log · v0.1-mvp",
                NOTE_LAST_EXIT_CODE, "Dernier code de sortie : %1$d",
                VALUE_PEERS_CONNECTED, "%1$d connectés",
                DURATION_HOURS_MINUTES, "%1$d h %2$d min",
                DURATION_MINUTES, "%1$d min",
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
                LOG_MISSING_NOTE, "Aucun journal pour le moment"
        );
    }
}
