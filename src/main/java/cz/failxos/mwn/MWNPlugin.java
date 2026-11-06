package cz.failxos.mwn;

import cz.failxos.mwn.api.MwnApi;
import cz.failxos.mwn.command.MwnCommand;
import cz.failxos.mwn.command.ServerCommand;
import cz.failxos.mwn.data.Database;
import cz.failxos.mwn.gui.AdminGui;
import cz.failxos.mwn.gui.SelectorGui;
import cz.failxos.mwn.hook.Hooks;
import cz.failxos.mwn.listener.PlayerListener;
import cz.failxos.mwn.listener.SettingsListener;
import cz.failxos.mwn.manager.BackupManager;
import cz.failxos.mwn.manager.BoardManager;
import cz.failxos.mwn.manager.ChatManager;
import cz.failxos.mwn.manager.FriendManager;
import cz.failxos.mwn.manager.EconomyStore;
import cz.failxos.mwn.manager.GroupManager;
import cz.failxos.mwn.manager.IsolationManager;
import cz.failxos.mwn.manager.PortalManager;
import cz.failxos.mwn.manager.QueueManager;
import cz.failxos.mwn.manager.StatsManager;
import cz.failxos.mwn.manager.SwitchManager;
import cz.failxos.mwn.manager.WorldManager;
import cz.failxos.mwn.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class MWNPlugin extends JavaPlugin {
    private Msg msg;
    private Database db;
    private GroupManager groups;
    private WorldManager worlds;
    private SwitchManager switcher;
    private ChatManager chat;
    private IsolationManager isolation;
    private BoardManager boards;
    private SelectorGui selector;
    private AdminGui adminGui;
    private PortalManager portals;
    private FriendManager friends;
    private StatsManager stats;
    private QueueManager queue;
    private EconomyStore economy;
    private BackupManager backups;
    private PlayerListener playerListener;
    private boolean debug;
    private boolean papi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg = new Msg(this);
        db = new Database(this);
        try {
            db.connect();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database connection failed - disabling FailMultiWorld", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        groups = new GroupManager(this);
        groups.load();
        worlds = new WorldManager(this);
        switcher = new SwitchManager(this);
        chat = new ChatManager(this);
        isolation = new IsolationManager(this);
        boards = new BoardManager(this);
        selector = new SelectorGui(this);
        adminGui = new AdminGui(this);
        portals = new PortalManager(this);
        friends = new FriendManager(this);
        stats = new StatsManager(this);
        queue = new QueueManager(this);
        economy = new EconomyStore(this);
        portals.load();
        backups = new BackupManager(this);
        playerListener = new PlayerListener(this);
        debug = getConfig().getBoolean("debug", false);

        var pm = getServer().getPluginManager();
        pm.registerEvents(playerListener, this);
        pm.registerEvents(chat, this);
        pm.registerEvents(isolation, this);
        pm.registerEvents(new SettingsListener(this), this);
        pm.registerEvents(selector, this);
        pm.registerEvents(adminGui, this);
        pm.registerEvents(portals, this);

        MwnCommand mwn = new MwnCommand(this);
        ServerCommand srv = new ServerCommand(this, mwn);
        getCommand("mwn").setExecutor(mwn);
        getCommand("mwn").setTabCompleter(mwn);
        getCommand("server").setExecutor(srv);
        getCommand("server").setTabCompleter(srv);
        getCommand("servers").setExecutor(srv);

        papi = pm.isPluginEnabled("PlaceholderAPI");
        if (papi) {
            try {
                Hooks.registerPapi(this);
            } catch (Throwable t) {
                papi = false;
                getLogger().log(Level.WARNING, "PlaceholderAPI hook failed", t);
            }
        } else {
            getLogger().warning("PlaceholderAPI not found - %placeholders% from other plugins are disabled (MWN's own %mwn_*% still work in MWN texts).");
        }

        if (getConfig().getBoolean("economy.provider", false)) {
            if (pm.isPluginEnabled("Vault")) {
                try {
                    cz.failxos.mwn.hook.VaultHook.register(this);
                    getLogger().info("Registered MWN as Vault economy provider (balances per group).");
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Vault economy registration failed", t);
                }
            } else {
                getLogger().warning("economy.provider is true but Vault is not installed - economy provider disabled.");
            }
        }

        MwnApi.init(this);
        getServer().getServicesManager().register(MwnApi.class, MwnApi.get(), this, org.bukkit.plugin.ServicePriority.Normal);

        // worlds can only be loaded once the server is fully up
        Bukkit.getScheduler().runTask(this, () -> {
            worlds.loadManagedWorlds();
            groups.applyAllWorldSettings();
            worlds.logSuggestions();
            for (Player p : Bukkit.getOnlinePlayers()) playerListener.attach(p);
            boards.start();
            switcher.startAutosave();
            stats.start();
            queue.start();
        });
        getLogger().info("FailMultiWorld enabled: " + groups.count() + " group(s).");
    }

    @Override
    public void onDisable() {
        if (stats != null) stats.stop();
        if (queue != null) queue.stop();
        if (boards != null) {
            boards.stop();
            boards.showAll();
        }
        if (switcher != null) {
            switcher.stopAutosave();
            switcher.saveAllNow();
        }
        if (db != null) db.close();
    }

    public void reloadAll() {
        reloadConfig();
        msg.load();
        groups.load();
        portals.load();
        groups.applyAllWorldSettings();
        debug = getConfig().getBoolean("debug", false);
        boards.start();
    }

    public void debug(String s) {
        if (debug) getLogger().info("[debug] " + s);
    }

    public boolean isDebug() { return debug; }
    public void setDebug(boolean d) { this.debug = d; }
    public boolean papiPresent() { return papi; }

    public Msg msg() { return msg; }
    public Database db() { return db; }
    public GroupManager groups() { return groups; }
    public WorldManager worlds() { return worlds; }
    public SwitchManager switcher() { return switcher; }
    public ChatManager chat() { return chat; }
    public IsolationManager isolation() { return isolation; }
    public BoardManager boards() { return boards; }
    public SelectorGui selector() { return selector; }
    public AdminGui adminGui() { return adminGui; }
    public PortalManager portals() { return portals; }
    public FriendManager friends() { return friends; }
    public StatsManager stats() { return stats; }
    public QueueManager queue() { return queue; }
    public EconomyStore economy() { return economy; }
    public BackupManager backups() { return backups; }
}
