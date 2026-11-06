package cz.failxos.mwn.data;

import cz.failxos.mwn.MWNPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** SQLite / MySQL / MariaDB storage. One connection guarded by a lock, all heavy work on a dedicated thread. */
public final class Database {
    @FunctionalInterface
    public interface SqlFn<T> {
        T run(Connection c) throws Exception;
    }

    public record JoinInfo(String groupId, String pendingFrom, Set<UUID> ignores) {}

    private final MWNPlugin plugin;
    private final Object lock = new Object();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "MWN-Database"));
    private Connection conn;
    private String url;
    private String user;
    private String pass;
    private String type = "sqlite";
    public volatile long lastLatencyMs;
    public final AtomicInteger failures = new AtomicInteger();

    public Database(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public String type() { return type; }

    public void connect() throws Exception {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("database");
        type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        if (s != null && (type.equals("mysql") || type.equals("mariadb"))) {
            Class.forName(type.equals("mysql") ? "com.mysql.cj.jdbc.Driver" : "org.mariadb.jdbc.Driver");
            url = "jdbc:" + type + "://" + s.getString("host", "localhost") + ":" + s.getInt("port", 3306)
                    + "/" + s.getString("name", "mwn") + "?useSSL=" + s.getBoolean("ssl", false);
            user = s.getString("user", "root");
            pass = s.getString("password", "");
        } else {
            type = "sqlite";
            Class.forName("org.sqlite.JDBC");
            plugin.getDataFolder().mkdirs();
            File f = new File(plugin.getDataFolder(), s == null ? "data.db" : s.getString("file", "data.db"));
            url = "jdbc:sqlite:" + f.getAbsolutePath();
        }
        sync(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_data (uuid VARCHAR(36) NOT NULL, category VARCHAR(16) NOT NULL, "
                        + "profile VARCHAR(64) NOT NULL, data LONGTEXT, PRIMARY KEY (uuid, category, profile))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_player_group (uuid VARCHAR(36) NOT NULL PRIMARY KEY, group_id VARCHAR(64) NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_pending (uuid VARCHAR(36) NOT NULL PRIMARY KEY, from_group VARCHAR(64) NOT NULL, to_group VARCHAR(64) NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_friends (uuid VARCHAR(36) NOT NULL, friend VARCHAR(36) NOT NULL, PRIMARY KEY (uuid, friend))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_playtime (uuid VARCHAR(36) NOT NULL, group_id VARCHAR(64) NOT NULL, seconds BIGINT NOT NULL, PRIMARY KEY (uuid, group_id))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_balance (uuid VARCHAR(36) NOT NULL, scope VARCHAR(64) NOT NULL, balance DOUBLE NOT NULL, PRIMARY KEY (uuid, scope))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mwn_ignores (uuid VARCHAR(36) NOT NULL, ignored VARCHAR(36) NOT NULL, PRIMARY KEY (uuid, ignored))");
            }
            return null;
        });
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(2)) {
            conn = user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, pass);
        }
        return conn;
    }

    public <T> T sync(SqlFn<T> fn) throws Exception {
        synchronized (lock) {
            long t = System.nanoTime();
            try {
                return fn.run(conn());
            } catch (Exception e) {
                failures.incrementAndGet();
                throw e;
            } finally {
                lastLatencyMs = (System.nanoTime() - t) / 1_000_000L;
            }
        }
    }

    public <T> CompletableFuture<T> async(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    f.complete(sync(fn));
                } catch (Exception e) {
                    f.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    public void close() {
        exec.shutdown();
        try {
            exec.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            try {
                if (conn != null) conn.close();
            } catch (SQLException ignored) { }
        }
    }

    /** Runs fn inside a transaction (commit / rollback). */
    public static <T> T inTx(Connection c, SqlFn<T> fn) throws Exception {
        boolean old = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T r = fn.run(c);
            c.commit();
            return r;
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(old);
        }
    }

    public static void saveData(Connection c, UUID u, String cat, String profile, String data) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("REPLACE INTO mwn_data (uuid, category, profile, data) VALUES (?,?,?,?)")) {
            ps.setString(1, u.toString());
            ps.setString(2, cat);
            ps.setString(3, profile);
            ps.setString(4, data);
            ps.executeUpdate();
        }
    }

    public static String loadData(Connection c, UUID u, String cat, String profile) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT data FROM mwn_data WHERE uuid=? AND category=? AND profile=?")) {
            ps.setString(1, u.toString());
            ps.setString(2, cat);
            ps.setString(3, profile);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void setPending(Connection c, UUID u, String from, String to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("REPLACE INTO mwn_pending (uuid, from_group, to_group) VALUES (?,?,?)")) {
            ps.setString(1, u.toString());
            ps.setString(2, from);
            ps.setString(3, to);
            ps.executeUpdate();
        }
    }

    public static void clearPending(Connection c, UUID u) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM mwn_pending WHERE uuid=?")) {
            ps.setString(1, u.toString());
            ps.executeUpdate();
        }
    }

    public static void setPlayerGroup(Connection c, UUID u, String groupId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("REPLACE INTO mwn_player_group (uuid, group_id) VALUES (?,?)")) {
            ps.setString(1, u.toString());
            ps.setString(2, groupId);
            ps.executeUpdate();
        }
    }

    public JoinInfo readJoinInfo(UUID u) throws Exception {
        return sync(c -> {
            String group = null;
            String pending = null;
            Set<UUID> ignores = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT group_id FROM mwn_player_group WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) group = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT from_group FROM mwn_pending WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) pending = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ignored FROM mwn_ignores WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            ignores.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
            return new JoinInfo(group, pending, ignores);
        });
    }

    public CompletableFuture<Void> setIgnore(UUID u, UUID target, boolean ignore) {
        return async(c -> {
            String sql = ignore ? "REPLACE INTO mwn_ignores (uuid, ignored) VALUES (?,?)" : "DELETE FROM mwn_ignores WHERE uuid=? AND ignored=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, u.toString());
                ps.setString(2, target.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteGroupData(String groupId) {
        return async(c -> inTx(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM mwn_data WHERE profile=?")) {
                ps.setString(1, groupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM mwn_player_group WHERE group_id=?")) {
                ps.setString(1, groupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM mwn_balance WHERE scope=?")) {
                ps.setString(1, groupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM mwn_playtime WHERE group_id=?")) {
                ps.setString(1, groupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM mwn_pending WHERE from_group=? OR to_group=?")) {
                ps.setString(1, groupId);
                ps.setString(2, groupId);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    public CompletableFuture<Integer> countPending() {
        return async(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mwn_pending")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
