package com.roften.multichat.db;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import com.roften.multichat.chat.ChatChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous chat logging into MariaDB/MySQL (JDBC).
 *
 * Schema (default table = chat_logs):
 *   ts_epoch_ms, ts_iso, channel, username, uuid, message, dimension, x, y, z
 */
public final class ChatLogDatabase {
    private ChatLogDatabase() {}

    /**
     * A small helper API used by other parts of the mod (e.g. moderation) to reuse the same
     * single-threaded DB executor and connection.
     *
     * <p>This keeps compilation compatible with earlier revisions where ModerationDatabase
     * called {@code ChatLogDatabase.runSql(...)} / {@code querySqlBlocking(...)}.
     */
    @FunctionalInterface
    public interface SqlTask {
        void run(Connection conn) throws Exception;
    }

    @FunctionalInterface
    public interface SqlQuery<T> {
        T run(Connection conn) throws Exception;
    }

    /**
     * Run a DB task asynchronously on the DB thread.
     */
    public static void runSql(MinecraftServer server, SqlTask task) {
        if (task == null) return;
        if (!initialized) init(server);
        ExecutorService ex = executor;
        if (ex == null) return;

        ex.execute(() -> {
            try {
                ensureConnected();
                if (connection == null) return;
                task.run(connection);
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("DB task failed", t);
                safeCloseInsert();
                safeCloseConnection();
            }
        });
    }

    /**
     * Run a DB query on the DB thread and wait for the result.
     *
     * <p>The {@code params} argument is kept for signature compatibility; it is unused here.
     */
    public static <T> T querySqlBlocking(MinecraftServer server, SqlQuery<T> query, java.util.List<Object> params) {
        if (query == null) return null;
        if (!initialized) init(server);
        ExecutorService ex = executor;
        if (ex == null) {
            // Best-effort synchronous fallback.
            try {
                ensureConnected();
                if (connection == null) return null;
                return query.run(connection);
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("DB query failed", t);
                safeCloseInsert();
                safeCloseConnection();
                return null;
            }
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    ensureConnected();
                    if (connection == null) return null;
                    return query.run(connection);
                } catch (Throwable t) {
                    MultiChatMod.LOGGER.warn("DB query failed", t);
                    safeCloseInsert();
                    safeCloseConnection();
                    return null;
                }
            }, ex).get();
        } catch (Exception e) {
            MultiChatMod.LOGGER.warn("DB query interrupted", e);
            return null;
        }
    }

    /**
     * Some code paths (our own chat router) intentionally send SystemMessages to players.
     * We log those via the player chat event handler, so we suppress mixin-based system logging
     * while those messages are being sent to avoid duplicates.
     */
    private static final ThreadLocal<Boolean> SUPPRESS_MIXIN_SYSTEM_LOG = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void runWithoutMixinSystemLogging(Runnable action) {
        boolean prev = SUPPRESS_MIXIN_SYSTEM_LOG.get();
        SUPPRESS_MIXIN_SYSTEM_LOG.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            SUPPRESS_MIXIN_SYSTEM_LOG.set(prev);
        }
    }

    public static boolean isMixinSystemLoggingSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS_MIXIN_SYSTEM_LOG.get());
    }

    private static final String INSERT_SQL_TEMPLATE =
            "INSERT INTO %s (ts_epoch_ms, ts_iso, channel, username, uuid, message, dimension, x, y, z) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_DEATH_SQL_TEMPLATE =
            "INSERT INTO %s (ts_epoch_ms, ts_iso, username, uuid, message, dimension, x, y, z) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String CREATE_TABLE_TEMPLATE =
            "CREATE TABLE IF NOT EXISTS %s (" +
            " id BIGINT NOT NULL AUTO_INCREMENT," +
            " ts_epoch_ms BIGINT NOT NULL," +
            " ts_iso VARCHAR(32) NOT NULL," +
            " channel VARCHAR(8) NOT NULL," +
            " username VARCHAR(64) NOT NULL," +
            " uuid CHAR(36) NOT NULL," +
            " message TEXT NOT NULL," +
            " dimension VARCHAR(128) NOT NULL," +
            " x INT NOT NULL," +
            " y INT NOT NULL," +
            " z INT NOT NULL," +
            " PRIMARY KEY (id)," +
            " INDEX idx_ts (ts_epoch_ms)," +
            " INDEX idx_channel (channel)," +
            " INDEX idx_uuid (uuid)" +
            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

    private static final String CREATE_DEATH_TABLE_TEMPLATE =
            "CREATE TABLE IF NOT EXISTS %s (" +
            " id BIGINT NOT NULL AUTO_INCREMENT," +
            " ts_epoch_ms BIGINT NOT NULL," +
            " ts_iso VARCHAR(32) NOT NULL," +
            " username VARCHAR(64) NOT NULL," +
            " uuid CHAR(36) NOT NULL," +
            " message TEXT NOT NULL," +
            " dimension VARCHAR(128) NOT NULL," +
            " x INT NOT NULL," +
            " y INT NOT NULL," +
            " z INT NOT NULL," +
            " PRIMARY KEY (id)," +
            " INDEX idx_ts (ts_epoch_ms)," +
            " INDEX idx_uuid (uuid)" +
            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

    private static volatile boolean initialized = false;

    private static ExecutorService executor;

    // Connection is used ONLY from the single DB thread (executor), but init/shutdown can touch it too.
    private static Connection connection;
    private static PreparedStatement insertStmt;
    private static PreparedStatement deathInsertStmt;

    private static String jdbcUrl;
    private static String dbUser;
    private static String dbPassword;
    private static String tableName;
    private static boolean autoCreate;

    private static boolean deathLogEnabled;
    private static String deathTableName;
    private static boolean deathAutoCreate;

    public static synchronized void init(MinecraftServer server) {
        if (initialized) return;
        boolean chatEnabled = MultiChatConfig.CHATLOG_ENABLED.getAsBoolean();
        deathLogEnabled = MultiChatConfig.DEATHLOG_ENABLED.getAsBoolean();
        if (!chatEnabled && !deathLogEnabled) {
            initialized = true; // treat as "initialized" so we don't spam init attempts
            return;
        }

        jdbcUrl = Objects.toString(MultiChatConfig.CHATLOG_JDBC_URL.get(), "").trim();
        dbUser = Objects.toString(MultiChatConfig.CHATLOG_DB_USER.get(), "");
        dbPassword = Objects.toString(MultiChatConfig.CHATLOG_DB_PASSWORD.get(), "");
        tableName = sanitizeTableName(Objects.toString(MultiChatConfig.CHATLOG_TABLE.get(), "chat_logs"), "chat_logs");
        autoCreate = MultiChatConfig.CHATLOG_AUTO_CREATE_TABLE.getAsBoolean();

        deathTableName = sanitizeTableName(Objects.toString(MultiChatConfig.DEATHLOG_TABLE.get(), "death_logs"), "death_logs");
        deathAutoCreate = MultiChatConfig.DEATHLOG_AUTO_CREATE_TABLE.getAsBoolean();

        if (jdbcUrl.isEmpty()) {
            MultiChatMod.LOGGER.warn("Database logging is enabled, but chatLogJdbcUrl is empty. Disabling DB logging.");
            initialized = true;
            return;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "multichat-db");
            t.setDaemon(true);
            return t;
        });

        final boolean chatEnabledFinal = chatEnabled;
        final boolean deathEnabledFinal = deathLogEnabled;

        // Warm up the DB connection on the DB thread (and optionally create schema).
        executor.execute(() -> {
            try {
                ensureConnected();
                if (connection == null) {
                    MultiChatMod.LOGGER.warn("DB connection could not be established. DB logging disabled.");
                    return;
                }
                try (Statement st = connection.createStatement()) {
                    if (chatEnabledFinal && autoCreate) {
                        st.execute(String.format(CREATE_TABLE_TEMPLATE, tableName));
                    }
                    if (deathEnabledFinal && deathAutoCreate) {
                        st.execute(String.format(CREATE_DEATH_TABLE_TEMPLATE, deathTableName));
                    }
                }

                if (chatEnabledFinal) {
                    prepareInsert();
                    MultiChatMod.LOGGER.info("Chat logging enabled (MariaDB/MySQL) -> {} table {}", jdbcUrl, tableName);
                }
                if (deathEnabledFinal) {
                    prepareDeathInsert();
                    MultiChatMod.LOGGER.info("Death logging enabled (MariaDB/MySQL) -> {} table {}", jdbcUrl, deathTableName);
                }
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("Failed to initialize DB logging.", t);
            }
        });

        initialized = true;
    }

    public static void log(MinecraftServer server, ChatChannel channel, ServerPlayer sender, String messageText) {
        if (!MultiChatConfig.CHATLOG_ENABLED.getAsBoolean()) return;
        if (!initialized) init(server);
        ExecutorService ex = executor;
        if (ex == null) return;

        final long now = System.currentTimeMillis();
        final String iso = Instant.ofEpochMilli(now).toString();
        final BlockPos pos = sender.blockPosition();
        final String dimension = sender.level().dimension().location().toString();
        final String username = sender.getGameProfile().getName();
        final String uuid = sender.getUUID().toString();
        final String chan = channel.name();
        final String msg = messageText;
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        ex.execute(() -> {
            try {
                ensureConnected();
                if (connection == null) return;

                if (insertStmt == null || insertStmt.isClosed()) {
                    prepareInsert();
                }
                if (insertStmt == null) return;

                insertStmt.setLong(1, now);
                insertStmt.setString(2, iso);
                insertStmt.setString(3, chan);
                insertStmt.setString(4, username);
                insertStmt.setString(5, uuid);
                insertStmt.setString(6, msg);
                insertStmt.setString(7, dimension);
                insertStmt.setInt(8, x);
                insertStmt.setInt(9, y);
                insertStmt.setInt(10, z);
                insertStmt.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.warn("Failed to write chat log row", e);
                // If connection died, next write will reconnect
                safeCloseInsert();
                safeCloseConnection();
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("Failed to write chat log row (unexpected)", t);
            }
        });
    }

    /**
     * Logs a system/mod message that appears in chat but does not have a real player sender.
     *
     * Notes:
     * - Username is typically "SERVER".
     * - UUID uses the all-zeros UUID.
     * - Coordinates may be 0/0/0 if there is no meaningful origin.
     */
    public static void logSystem(MinecraftServer server, String messageText, String dimension, int x, int y, int z) {
        logCustom(server, "SYSTEM", "SERVER", "00000000-0000-0000-0000-000000000000", messageText, dimension, x, y, z);
    }

    public static void logCustom(MinecraftServer server,
                                 String channel,
                                 String username,
                                 String uuid,
                                 String messageText,
                                 String dimension,
                                 int x,
                                 int y,
                                 int z) {
        if (!MultiChatConfig.CHATLOG_ENABLED.getAsBoolean()) return;
        if (!initialized) init(server);
        ExecutorService ex = executor;
        if (ex == null) return;

        final long now = System.currentTimeMillis();
        final String iso = Instant.ofEpochMilli(now).toString();
        final String chan = channel == null ? "SYSTEM" : channel;
        final String user = username == null ? "SERVER" : username;
        final String id = uuid == null ? "00000000-0000-0000-0000-000000000000" : uuid;
        final String msg = messageText == null ? "" : messageText;
        final String dim = dimension == null ? "server" : dimension;
        final int px = x;
        final int py = y;
        final int pz = z;

        ex.execute(() -> {
            try {
                ensureConnected();
                if (connection == null) return;
                if (insertStmt == null || insertStmt.isClosed()) {
                    prepareInsert();
                }
                if (insertStmt == null) return;

                insertStmt.setLong(1, now);
                insertStmt.setString(2, iso);
                insertStmt.setString(3, chan);
                insertStmt.setString(4, user);
                insertStmt.setString(5, id);
                insertStmt.setString(6, msg);
                insertStmt.setString(7, dim);
                insertStmt.setInt(8, px);
                insertStmt.setInt(9, py);
                insertStmt.setInt(10, pz);
                insertStmt.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.warn("Failed to write chat log row", e);
                safeCloseInsert();
                safeCloseConnection();
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("Failed to write chat log row (unexpected)", t);
            }
        });
    }

    /**
     * Logs a player death message into a dedicated DB table (deathLogTable).
     */
    public static void logDeath(MinecraftServer server, ServerPlayer player, String deathMessageText) {
        if (!MultiChatConfig.DEATHLOG_ENABLED.getAsBoolean()) return;
        if (!initialized) init(server);
        ExecutorService ex = executor;
        if (ex == null) return;

        final long now = System.currentTimeMillis();
        final String iso = Instant.ofEpochMilli(now).toString();
        final BlockPos pos = player.blockPosition();
        final String dimension = player.level().dimension().location().toString();
        final String username = player.getGameProfile().getName();
        final String uuid = player.getUUID().toString();
        final String msg = deathMessageText == null ? "" : deathMessageText;
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        ex.execute(() -> {
            try {
                ensureConnected();
                if (connection == null) return;

                if (deathInsertStmt == null || deathInsertStmt.isClosed()) {
                    prepareDeathInsert();
                }
                if (deathInsertStmt == null) return;

                deathInsertStmt.setLong(1, now);
                deathInsertStmt.setString(2, iso);
                deathInsertStmt.setString(3, username);
                deathInsertStmt.setString(4, uuid);
                deathInsertStmt.setString(5, msg);
                deathInsertStmt.setString(6, dimension);
                deathInsertStmt.setInt(7, x);
                deathInsertStmt.setInt(8, y);
                deathInsertStmt.setInt(9, z);
                deathInsertStmt.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.warn("Failed to write death log row", e);
                safeCloseDeathInsert();
                safeCloseConnection();
            } catch (Throwable t) {
                MultiChatMod.LOGGER.warn("Failed to write death log row (unexpected)", t);
            }
        });
    }

    /**
     * Safe shutdown (called on server stop).
     */
    public static synchronized void shutdown() {
        ExecutorService ex = executor;
        executor = null;

        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        safeCloseInsert();
        safeCloseDeathInsert();
        safeCloseConnection();

        jdbcUrl = null;
        dbUser = null;
        dbPassword = null;
        tableName = null;
        autoCreate = false;
        deathTableName = null;
        deathAutoCreate = false;
        initialized = false;
    }

    private static void prepareInsert() throws SQLException {
        safeCloseInsert();
        String insertSql = String.format(INSERT_SQL_TEMPLATE, tableName);
        insertStmt = connection.prepareStatement(insertSql);
    }

    private static void prepareDeathInsert() throws SQLException {
        safeCloseDeathInsert();
        String insertSql = String.format(INSERT_DEATH_SQL_TEMPLATE, deathTableName);
        deathInsertStmt = connection.prepareStatement(insertSql);
    }

    private static void ensureConnected() throws SQLException {
        // Called from DB thread; but can be invoked during init warmup too.
        if (connection != null) {
            try {
                if (connection.isValid(2)) return;
            } catch (SQLException ignored) {
                // fallthrough to reconnect
            }
            safeCloseInsert();
            safeCloseDeathInsert();
            safeCloseConnection();
        }

        // Ensure driver class is loaded in some classloader setups.
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ignored) {}

        if (dbUser == null) {
            // init not yet captured config; this should not happen in normal flow
            dbUser = "";
        }
        if (dbPassword == null) dbPassword = "";

        connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
        // MariaDB/MySQL: keep connection alive and reduce latency
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {}
    }

    private static String sanitizeTableName(String raw, String fallback) {
        String fb = (fallback == null || fallback.isBlank()) ? "chat_logs" : fallback;
        String s = raw == null ? fb : raw.trim();
        if (s.isEmpty()) return fb;
        // Only allow [a-zA-Z0-9_], else fallback to default to avoid SQL injection.
        if (!s.matches("[A-Za-z0-9_]+")) return fb;
        return s;
    }

    private static void safeCloseInsert() {
        try {
            if (insertStmt != null) insertStmt.close();
        } catch (SQLException ignored) {}
        insertStmt = null;
    }

    private static void safeCloseDeathInsert() {
        try {
            if (deathInsertStmt != null) deathInsertStmt.close();
        } catch (SQLException ignored) {}
        deathInsertStmt = null;
    }

    private static void safeCloseConnection() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {}
        connection = null;
    }
}
