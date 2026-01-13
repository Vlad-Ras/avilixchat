package com.roften.multichat;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration.
 *
 * <p>We keep legacy constant aliases (multiple constant names referencing the same
 * underlying config entry) so older/experimental code paths still compile.
 */
public final class MultiChatConfig {
    private MultiChatConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // -------------------- Chat / UI --------------------

    public static final ModConfigSpec.IntValue LOCAL_RADIUS_BLOCKS = BUILDER
            .comment("Radius (in blocks) for local chat.")
            .defineInRange("localRadiusBlocks", 100, 1, 1024);

    public static final ModConfigSpec.BooleanValue SHOW_SYSTEM_IN_ALL_TABS = BUILDER
            .comment("If true, system messages (advancements, game info, etc.) are shown in every tab.")
            .define("showSystemInAllTabs", true);

    public static final ModConfigSpec.ConfigValue<String> LUCKPERMS_PREFIX_FORMAT = BUILDER
            .comment("How to parse LuckPerms prefixes: AUTO, LEGACY, MINIMESSAGE, PLAIN")
            .define("luckPermsPrefixFormat", "AUTO");

    // -------------------- Death messages --------------------

    public static final ModConfigSpec.BooleanValue DEATH_MESSAGES_LOCAL_ONLY = BUILDER
            .comment("If true, player death messages are only shown in LOCAL chat within deathRadiusBlocks.")
            .define("deathMessagesLocalOnly", true);

    public static final ModConfigSpec.IntValue DEATH_RADIUS_BLOCKS = BUILDER
            .comment("Radius (in blocks) for death messages when deathMessagesLocalOnly=true.")
            .defineInRange("deathRadiusBlocks", 100, 1, 1024);

    public static final ModConfigSpec.BooleanValue DEATHLOG_ENABLED = BUILDER
            .comment("If true, log player death messages into a dedicated MariaDB/MySQL table.")
            .define("deathLogEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> DEATHLOG_TABLE = BUILDER
            .comment("Table name for death logs.")
            .define("deathLogTable", "death_logs");

    public static final ModConfigSpec.BooleanValue DEATHLOG_AUTO_CREATE_TABLE = BUILDER
            .comment("If true, the mod will CREATE TABLE IF NOT EXISTS for death logs on server start.")
            .define("deathLogAutoCreateTable", true);

    // -------------------- Chat logging (MySQL/MariaDB) --------------------

    public static final ModConfigSpec.BooleanValue CHATLOG_ENABLED = BUILDER
            .comment("If true, log all player chat messages routed by MultiChat into a MariaDB/MySQL database.")
            .define("chatLogEnabled", true);

    public static final ModConfigSpec.BooleanValue CHATLOG_INCLUDE_SYSTEM_MESSAGES = BUILDER
            .comment("If true, also log system/mod messages that are broadcast/sent via server system chat.")
            .define("chatLogIncludeSystemMessages", true);

    public static final ModConfigSpec.ConfigValue<String> CHATLOG_JDBC_URL = BUILDER
            .comment("JDBC URL for MariaDB/MySQL. Example:",
                    "  jdbc:mysql://127.0.0.1:3306/avilix?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC",
                    "For MySQL driver you can use jdbc:mysql://... as well.")
            .define("chatLogJdbcUrl", "jdbc:mysql://127.0.0.1:3306/avilix?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC");

    public static final ModConfigSpec.ConfigValue<String> CHATLOG_DB_USER = BUILDER
            .comment("Database username for chat logging.")
            .define("chatLogDbUser", "economy");

    public static final ModConfigSpec.ConfigValue<String> CHATLOG_DB_PASSWORD = BUILDER
            .comment("Database password for chat logging.")
            .define("chatLogDbPassword", "");

    public static final ModConfigSpec.ConfigValue<String> CHATLOG_TABLE = BUILDER
            .comment("Table name for chat logs.")
            .define("chatLogTable", "chat_logs");

    public static final ModConfigSpec.BooleanValue CHATLOG_AUTO_CREATE_TABLE = BUILDER
            .comment("If true, the mod will CREATE TABLE IF NOT EXISTS on server start.")
            .define("chatLogAutoCreateTable", true);

    // -------------------- Moderation / mutes --------------------

    /** Enable /mute /tempmute and mute enforcement. */
    public static final ModConfigSpec.BooleanValue MUTES_ENABLED = BUILDER
            .comment("If true, enable mutes and persist them in the database.")
            .define("mutesEnabled", true);

    /** Vanilla permission level fallback when LuckPerms is not present. */
    public static final ModConfigSpec.IntValue MUTE_REQUIRED_PERMISSION_LEVEL = BUILDER
            .comment("Fallback vanilla permission level required for moderation commands when LuckPerms is not present.")
            .defineInRange("muteRequiredPermissionLevel", 2, 0, 4);

    /** Table name for mutes storage. */
    public static final ModConfigSpec.ConfigValue<String> MUTES_TABLE = BUILDER
            .comment("Table name for stored mutes.")
            .define("mutesTable", "chat_mutes");

    /** Table name for moderation actions log (mute/unmute/etc). */
    public static final ModConfigSpec.ConfigValue<String> MODLOG_TABLE = BUILDER
            .comment("Table name for moderation actions log (mute/unmute/etc).")
            .define("modlogTable", "chat_moderation_log");

    /** Auto-create moderation tables on server start. */
    public static final ModConfigSpec.BooleanValue MUTES_AUTO_CREATE_TABLE = BUILDER
            .comment("If true, CREATE TABLE IF NOT EXISTS for mutes/modlog on server start.")
            .define("mutesAutoCreateTable", true);

    // ---- Legacy aliases (do NOT define new keys, just point to the same values) ----
    public static final ModConfigSpec.BooleanValue MODERATION_AUTO_CREATE_TABLES = MUTES_AUTO_CREATE_TABLE;
    public static final ModConfigSpec.ConfigValue<String> MODERATION_LOG_TABLE = MODLOG_TABLE;

    
// -------------------- UI (client tabs) --------------------
static {
    BUILDER.push("ui");

    UI_CHAT_SWITCH_KEY = BUILDER
            .comment("Key used to choose chat at the beginning of message. Example: '$l hello', '$t sell'.",
                     "Default is '$' to avoid conflicts with HEX colors (#RRGGBB).")
            .define("chatSwitchKey", "$");

    BUILDER.push("tabs");
    UI_TAB_GLOBAL = BUILDER.comment("Tab label for GLOBAL chat (1-3 chars).").define("global", "G");
    UI_TAB_LOCAL  = BUILDER.comment("Tab label for LOCAL chat (1-3 chars).").define("local", "L");
    UI_TAB_TRADE  = BUILDER.comment("Tab label for TRADE chat (1-3 chars).").define("trade", "T");
    UI_TAB_CLAN   = BUILDER.comment("Tab label for CLAN chat (1-3 chars).").define("clan", "C");
    UI_TAB_ADMIN  = BUILDER.comment("Tab label for ADMIN chat (1-3 chars).").define("admin", "A");
    BUILDER.pop();

    BUILDER.push("colors");
    UI_COLOR_GLOBAL = BUILDER.comment("Tab accent color for GLOBAL in '#RRGGBB'.").define("global", "#AAAAAA");
    UI_COLOR_LOCAL  = BUILDER.comment("Tab accent color for LOCAL in '#RRGGBB'.").define("local", "#55FF55");
    UI_COLOR_TRADE  = BUILDER.comment("Tab accent color for TRADE in '#RRGGBB'.").define("trade", "#FFAA00");
    UI_COLOR_CLAN   = BUILDER.comment("Tab accent color for CLAN in '#RRGGBB'.").define("clan", "#55FFFF");
    UI_COLOR_ADMIN  = BUILDER.comment("Tab accent color for ADMIN in '#RRGGBB'.").define("admin", "#FF5555");
    BUILDER.pop();

    BUILDER.push("textColors");
    TEXT_COLOR_GLOBAL = BUILDER.comment("Default message text color for GLOBAL when player text has no color, in '#RRGGBB'.").define("global", "#FFFFFF");
    TEXT_COLOR_LOCAL  = BUILDER.comment("Default message text color for LOCAL when player text has no color, in '#RRGGBB'.").define("local", "#FFFFFF");
    TEXT_COLOR_TRADE  = BUILDER.comment("Default message text color for TRADE when player text has no color, in '#RRGGBB'.").define("trade", "#FFFFFF");
    TEXT_COLOR_CLAN   = BUILDER.comment("Default message text color for CLAN when player text has no color, in '#RRGGBB'.").define("clan", "#FFFFFF");
    TEXT_COLOR_ADMIN  = BUILDER.comment("Default message text color for ADMIN when player text has no color, in '#RRGGBB'.").define("admin", "#FFFFFF");
    BUILDER.pop();

    BUILDER.pop();
}

public static final ModConfigSpec.ConfigValue<String> UI_CHAT_SWITCH_KEY;
public static final ModConfigSpec.ConfigValue<String> UI_TAB_GLOBAL;
public static final ModConfigSpec.ConfigValue<String> UI_TAB_LOCAL;
public static final ModConfigSpec.ConfigValue<String> UI_TAB_TRADE;
public static final ModConfigSpec.ConfigValue<String> UI_TAB_CLAN;
public static final ModConfigSpec.ConfigValue<String> UI_TAB_ADMIN;

public static final ModConfigSpec.ConfigValue<String> UI_COLOR_GLOBAL;
public static final ModConfigSpec.ConfigValue<String> UI_COLOR_LOCAL;
public static final ModConfigSpec.ConfigValue<String> UI_COLOR_TRADE;
public static final ModConfigSpec.ConfigValue<String> UI_COLOR_CLAN;
public static final ModConfigSpec.ConfigValue<String> UI_COLOR_ADMIN;

public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR_GLOBAL;
public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR_LOCAL;
public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR_TRADE;
public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR_CLAN;
public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR_ADMIN;

public static String getTabLabel(com.roften.multichat.chat.ChatChannel ch) {
    return switch (ch) {
        case GLOBAL -> UI_TAB_GLOBAL.get();
        case LOCAL -> UI_TAB_LOCAL.get();
        case TRADE -> UI_TAB_TRADE.get();
        case CLAN -> UI_TAB_CLAN.get();
        case ADMIN -> UI_TAB_ADMIN.get();
    };
}

public static int getTabRgb(com.roften.multichat.chat.ChatChannel ch) {
    final String hex = switch (ch) {
        case GLOBAL -> UI_COLOR_GLOBAL.get();
        case LOCAL -> UI_COLOR_LOCAL.get();
        case TRADE -> UI_COLOR_TRADE.get();
        case CLAN -> UI_COLOR_CLAN.get();
        case ADMIN -> UI_COLOR_ADMIN.get();
    };
    return parseHexRgb(hex, 0xAAAAAA);
}

public static int getTextRgb(com.roften.multichat.chat.ChatChannel ch) {
    final String hex = switch (ch) {
        case GLOBAL -> TEXT_COLOR_GLOBAL.get();
        case LOCAL -> TEXT_COLOR_LOCAL.get();
        case TRADE -> TEXT_COLOR_TRADE.get();
        case CLAN -> TEXT_COLOR_CLAN.get();
        case ADMIN -> TEXT_COLOR_ADMIN.get();
    };
    return parseHexRgb(hex, 0xFFFFFF);
}

private static int parseHexRgb(String s, int fallback) {
    if (s == null) return fallback;
    String t = s.trim();
    if (t.startsWith("#")) t = t.substring(1);
    if (t.length() != 6) return fallback;
    try {
        return Integer.parseInt(t, 16);
    } catch (NumberFormatException e) {
        return fallback;
    }
}

    
    // -------------------- Admin: area history (server) --------------------
    /** Max messages to keep in memory for /areahistory and /spy area history. */
    public static final ModConfigSpec.IntValue AREA_HISTORY_MAX_MESSAGES;
    /** Default minutes for /areahistory when not specified. */
    public static final ModConfigSpec.IntValue AREA_HISTORY_DEFAULT_MINUTES;

    // Legacy/alternate names (aliases) kept for older code paths.
    public static final ModConfigSpec.IntValue ADMIN_AREA_HISTORY_MAX_MESSAGES;
    public static final ModConfigSpec.IntValue ADMIN_AREA_HISTORY_DEFAULT_MINUTES;

    static {
        BUILDER.push("admin");
        BUILDER.push("areaHistory");

        AREA_HISTORY_MAX_MESSAGES = BUILDER
                .comment("Сколько сообщений хранить в памяти для истории по радиусу (используется /areahistory и /spy area history).",
                         "Range: 100 ~ 20000")
                .defineInRange("maxMessages", 2000, 100, 20000);

        AREA_HISTORY_DEFAULT_MINUTES = BUILDER
                .comment("Сколько минут истории показывать по умолчанию, если не указано в команде.",
                         "Range: 1 ~ 120")
                .defineInRange("defaultMinutes", 10, 1, 120);

        BUILDER.pop(2);

        ADMIN_AREA_HISTORY_MAX_MESSAGES = AREA_HISTORY_MAX_MESSAGES;
        ADMIN_AREA_HISTORY_DEFAULT_MINUTES = AREA_HISTORY_DEFAULT_MINUTES;
    }

public static final ModConfigSpec SPEC = BUILDER.build();
}
