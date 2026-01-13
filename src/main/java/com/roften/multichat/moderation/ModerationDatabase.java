package com.roften.multichat.moderation;

import com.roften.multichat.MultiChatConfig;
import com.roften.multichat.MultiChatMod;
import net.minecraft.server.MinecraftServer;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stores mutes and moderation actions in MySQL/MariaDB using JDBC.
 * Uses the same JDBC settings as chat logging.
 */
public final class ModerationDatabase {
    private ModerationDatabase() {}

    private static volatile boolean initialized = false;
    private static Connection connection;
    private static ExecutorService executor;

    public static synchronized void init(MinecraftServer server) {
        if (initialized) return;
        if (!MultiChatConfig.MUTES_ENABLED.get()) {
            MultiChatMod.LOGGER.info("[MultiChat] Mutes disabled; moderation DB not initialized.");
            initialized = true;
            return;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "multichat-moderation-db");
            t.setDaemon(true);
            return t;
        });

        try {
            connection = DriverManager.getConnection(
                    MultiChatConfig.CHATLOG_JDBC_URL.get(),
                    MultiChatConfig.CHATLOG_DB_USER.get(),
                    MultiChatConfig.CHATLOG_DB_PASSWORD.get()
            );
            MultiChatMod.LOGGER.info("[MultiChat] Connected moderation DB via JDBC.");
            if (MultiChatConfig.MUTES_AUTO_CREATE_TABLE.get()) {
                ensureTables();
            }
        } catch (SQLException e) {
            MultiChatMod.LOGGER.error("[MultiChat] Failed to connect moderation DB", e);
        }
        initialized = true;
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
        initialized = false;
    }

    private static void ensureTables() throws SQLException {
        String mutesTable = sanitizeName(MultiChatConfig.MUTES_TABLE.get());
        String modlogTable = sanitizeName(MultiChatConfig.MODLOG_TABLE.get());

        try (Statement st = connection.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + mutesTable + "` (" +
                            "uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                            "name VARCHAR(64) NULL," +
                            "actor_uuid VARCHAR(36) NULL," +
                            "actor_name VARCHAR(64) NULL," +
                            "created_at BIGINT NOT NULL," +
                            "expires_at BIGINT NOT NULL," +
                            "reason TEXT NULL" +
                            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            );
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + modlogTable + "` (" +
                            "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                            "ts_epoch_ms BIGINT NOT NULL," +
                            "ts_iso VARCHAR(64) NOT NULL," +
                            "action VARCHAR(32) NOT NULL," +
                            "actor_uuid VARCHAR(36) NULL," +
                            "actor_name VARCHAR(64) NULL," +
                            "target_uuid VARCHAR(36) NULL," +
                            "target_name VARCHAR(64) NULL," +
                            "duration_ms BIGINT NULL," +
                            "expires_at BIGINT NULL," +
                            "reason TEXT NULL," +
                            "dimension VARCHAR(128) NULL," +
                            "x INT NULL, y INT NULL, z INT NULL" +
                            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            );
        }
        MultiChatMod.LOGGER.info("[MultiChat] Ensured tables {} and {}", mutesTable, modlogTable);
    }

    public static Map<UUID, MuteEntry> loadAllMutes() {
        if (!MultiChatConfig.MUTES_ENABLED.get() || connection == null) return Collections.emptyMap();
        String mutesTable = sanitizeName(MultiChatConfig.MUTES_TABLE.get());
        Map<UUID, MuteEntry> out = new HashMap<>();
        String sql = "SELECT uuid,name,actor_uuid,actor_name,created_at,expires_at,reason FROM `" + mutesTable + "`";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                String name = rs.getString(2);
                String actorUuidS = rs.getString(3);
                UUID actorUuid = actorUuidS == null ? null : UUID.fromString(actorUuidS);
                String actorName = rs.getString(4);
                long created = rs.getLong(5);
                long expires = rs.getLong(6);
                String reason = rs.getString(7);
                out.put(uuid, new MuteEntry(uuid, name, actorUuid, actorName, created, expires, reason));
            }
        } catch (Exception e) {
            MultiChatMod.LOGGER.error("[MultiChat] Failed to load mutes", e);
        }
        return out;
    }

    public static void upsertMuteAsync(MuteEntry entry) {
        if (!MultiChatConfig.MUTES_ENABLED.get() || connection == null || executor == null) return;
        Objects.requireNonNull(entry);
        executor.execute(() -> {
            String table = sanitizeName(MultiChatConfig.MUTES_TABLE.get());
            String sql = "INSERT INTO `" + table + "` (uuid,name,actor_uuid,actor_name,created_at,expires_at,reason) " +
                    "VALUES (?,?,?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), actor_uuid=VALUES(actor_uuid), actor_name=VALUES(actor_name), " +
                    "created_at=VALUES(created_at), expires_at=VALUES(expires_at), reason=VALUES(reason)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.targetUuid().toString());
                ps.setString(2, entry.targetName());
                ps.setString(3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
                ps.setString(4, entry.actorName());
                ps.setLong(5, entry.createdAtEpochMs());
                ps.setLong(6, entry.expiresAtEpochMs());
                ps.setString(7, entry.reason());
                ps.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.error("[MultiChat] Failed to upsert mute", e);
            }
        });
    }

    public static void deleteMuteAsync(UUID targetUuid) {
        if (!MultiChatConfig.MUTES_ENABLED.get() || connection == null || executor == null) return;
        executor.execute(() -> {
            String table = sanitizeName(MultiChatConfig.MUTES_TABLE.get());
            String sql = "DELETE FROM `" + table + "` WHERE uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.error("[MultiChat] Failed to delete mute", e);
            }
        });
    }

    public static void logActionAsync(String action, UUID actorUuid, String actorName, UUID targetUuid, String targetName,
                                      Long durationMs, Long expiresAt, String reason,
                                      String dimension, Integer x, Integer y, Integer z) {
        if (!MultiChatConfig.MUTES_ENABLED.get() || connection == null || executor == null) return;
        long now = System.currentTimeMillis();
        String iso = Instant.ofEpochMilli(now).toString();
        executor.execute(() -> {
            String table = sanitizeName(MultiChatConfig.MODLOG_TABLE.get());
            String sql = "INSERT INTO `" + table + "` (ts_epoch_ms,ts_iso,action,actor_uuid,actor_name,target_uuid,target_name," +
                    "duration_ms,expires_at,reason,dimension,x,y,z) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, now);
                ps.setString(2, iso);
                ps.setString(3, action);
                ps.setString(4, actorUuid == null ? null : actorUuid.toString());
                ps.setString(5, actorName);
                ps.setString(6, targetUuid == null ? null : targetUuid.toString());
                ps.setString(7, targetName);
                if (durationMs == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, durationMs);
                if (expiresAt == null) ps.setNull(9, Types.BIGINT); else ps.setLong(9, expiresAt);
                ps.setString(10, reason);
                ps.setString(11, dimension);
                if (x == null) ps.setNull(12, Types.INTEGER); else ps.setInt(12, x);
                if (y == null) ps.setNull(13, Types.INTEGER); else ps.setInt(13, y);
                if (z == null) ps.setNull(14, Types.INTEGER); else ps.setInt(14, z);
                ps.executeUpdate();
            } catch (SQLException e) {
                MultiChatMod.LOGGER.error("[MultiChat] Failed to log moderation action", e);
            }
        });
    }

    private static String sanitizeName(String name) {
        if (name == null) return "chat_mutes";
        String s = name.trim();
        if (s.isEmpty()) return "chat_mutes";
        // allow only alnum and underscore
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) return "chat_mutes";
        return s;
    }
}
