package de.braste.SPfB;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.kitteh.vanish.VanishPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerMarkers extends JavaPlugin implements Runnable, Listener {
    private static final String MappingSectionName = "Mapping";

    private JSONDataWriter mDataWriter = null;
    private File mOfflineLocationsFile = null;
    private Map<String, String> mMapNameMapping = new HashMap<>();
    private ConcurrentHashMap<String, SimpleLocation> mOfflineLocations = new ConcurrentHashMap<>();;
    private boolean mSaveOfflinePlayers = true;
    private boolean mHideVanishedPlayers = true;
    private boolean mHideSneakingPlayers = true;
    private boolean mHideInvisiblePlayers = true;
    private boolean mHideSpectators = true;
    private boolean mSendJSONOnVanishedPlayers = false;
    private boolean mSendJSONOnSneakingPlayers = false;
    private boolean mSendJSONOnInvisiblePlayers = false;
    private boolean mSendJSONOnSpectators = false;
    private boolean mSendHealthInfo = true;
    private boolean mSendFoodLevel = true;
    private boolean mSendLevel = true;
    private String mMapUrlBase = "";
    private String mMapUrlFormat = "#/{x}/{y}/{z}/-1/{title}/{world}";

    private VanishPlugin vnp = null;

    public void onEnable() {
        loadConfig();

        getCommand("playermarkers").setExecutor(new PlayerMarkersCommand(this));

        if (getServer().getPluginManager().isPluginEnabled("VanishNoPacket")) {
            vnp = (VanishPlugin) getServer().getPluginManager().getPlugin("VanishNoPacket");
        }

        if (mSaveOfflinePlayers) {
            initializeOfflinePlayersMap();
        }

        int updateInterval = getConfig().getInt("updateInterval");
        // Convert interval from 1000 ms to game ticks (20 per second)
        updateInterval /= 50;

        // Register update task
        getServer().getScheduler().scheduleSyncRepeatingTask(this, this, updateInterval, updateInterval);


        // Done initializing, tell the world
        getLogger().info(String.format("%s version %s enabled", getName(), getDescription().getVersion()));
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();

        String targetFile = getConfig().getString("targetFile");
        mDataWriter = new JSONDataWriter(targetFile);

        boolean oldSaveOffline = mSaveOfflinePlayers;
        mSaveOfflinePlayers = getConfig().getBoolean("saveOfflinePlayers");
        if (oldSaveOffline != mSaveOfflinePlayers) {
            if (mSaveOfflinePlayers) {
                // Register our event handlers
                getServer().getPluginManager().registerEvents(this, this);
            } else {
                PlayerJoinEvent.getHandlerList().unregister((Listener) this);
                PlayerQuitEvent.getHandlerList().unregister((Listener) this);
            }
        }
        mHideVanishedPlayers = getConfig().getBoolean("hideVanishedPlayers");
        mHideSneakingPlayers = getConfig().getBoolean("hideSneakingPlayers");
        mHideInvisiblePlayers = getConfig().getBoolean("hideInvisiblePlayers");
        mHideSpectators = getConfig().getBoolean("hideSpectators");
        mSendJSONOnVanishedPlayers = getConfig().getBoolean("sendJSONOnVanishedPlayers");
        mSendJSONOnSneakingPlayers = getConfig().getBoolean("sendJSONOnSneakingPlayers");
        mSendJSONOnInvisiblePlayers = getConfig().getBoolean("sendJSONOnInvisiblePlayers");
        mSendJSONOnSpectators = getConfig().getBoolean("sendJSONOnSpectators");
        mSendHealthInfo = getConfig().getBoolean("sendHealthInfo");
        mSendFoodLevel = getConfig().getBoolean("sendFoodLevel");
        mSendLevel = getConfig().getBoolean("sendLevel");
        mMapUrlBase = getConfig().getString("mapUrl.base");
        mMapUrlFormat = getConfig().getString("mapUrl.format");

        // Initialize the mapping bukkit to overviewer map names
        initMapNameMapping();

        // Save the config
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public void onDisable() {
        // Disable updates
        getServer().getScheduler().cancelTasks(this);

        if (mSaveOfflinePlayers) {
            // Save the offline players map
            saveOfflinePlayersMap();
        }
        getLogger().info(String.format("%s version %s disabled", getName(), getDescription().getVersion()));
    }

    @EventHandler
    public void playerJoin(PlayerJoinEvent event) {
        mOfflineLocations.remove(event.getPlayer().getName());
    }

    @EventHandler
    public void playerQuit(PlayerQuitEvent event) {
        mOfflineLocations.put(event.getPlayer().getName(), new SimpleLocation(event.getPlayer().getLocation()));
    }

    private void initMapNameMapping() {
        // Clear out the mapping
        mMapNameMapping.clear();

        // Load the name mapping from the config
        ConfigurationSection mappingSection = getConfig().getConfigurationSection(MappingSectionName);
        if (mappingSection != null) {
            // Load and check the mapping found in the config
            Map<String, Object> configMap = mappingSection.getValues(false);
            for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                mMapNameMapping.put(entry.getKey(), (String) entry.getValue());
            }
        } else {
            getLogger().warning(String.format("[%s] found no configured mapping, creating a default one.", getName()));
        }

        // If there are new worlds in the server add them to the mapping
        List<World> serverWorlds = getServer().getWorlds();
        for (World w : serverWorlds) {
            if (!mMapNameMapping.containsKey(w.getName())) {
                mMapNameMapping.put(w.getName(), w.getName());
            }
        }

        // Set the new mapping in the config
        getConfig().createSection(MappingSectionName, mMapNameMapping);
    }

    @SuppressWarnings("unchecked")
    private void initializeOfflinePlayersMap() {
        File configOfflineLocationPath = new File(getConfig().getString("offlineFile"));
        if (configOfflineLocationPath.isAbsolute()) {
            mOfflineLocationsFile = configOfflineLocationPath;
        } else {
            mOfflineLocationsFile = new File(getDataFolder(), configOfflineLocationPath.getPath());
        }

        if (mOfflineLocationsFile.exists() && mOfflineLocationsFile.isFile()) {
            // Data is stored, load it
            try {
                ObjectInputStream in = new ObjectInputStream(new FileInputStream(mOfflineLocationsFile));
                mOfflineLocations = (ConcurrentHashMap<String, SimpleLocation>) in.readObject();
                in.close();
            } catch (IOException e) {
                getLogger().warning(String.format("%s: Couldn't open Locations file from %s!", getName(), mOfflineLocationsFile.toString()));
            } catch (ClassNotFoundException e) {
                getLogger().warning(String.format("%s: Couldn't load Locations file from %s!", getName(), mOfflineLocationsFile.toString()));
            }
        }
    }

    private void saveOfflinePlayersMap() {
        if (mOfflineLocationsFile != null && mSaveOfflinePlayers) {
            try {
                ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(mOfflineLocationsFile));
                out.writeObject(mOfflineLocations);
                out.close();
            } catch (IOException e) {
                getLogger().warning(String.format("%s: Couldn't write Locations file from %s! \n%s", getName(), mOfflineLocationsFile.toString(), e.getMessage()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void run() {
        JsonArray jsonList = new JsonArray();
        JsonObject out;

        // Write Online players
        Collection<? extends Player> players = getServer().getOnlinePlayers();
        for (Player p : players) {
            boolean sendDataVanished = true;
            boolean sendDataSneaking = true;
            boolean sendDataInvisible = true;
            boolean sendDataSpectator = true;

            out = new JsonObject();
            out.addProperty("msg", p.getName());
            out.addProperty("id", 4);
            out.addProperty("world", getMappedWorld(p.getLocation().getWorld().getName()));
            out.addProperty("x", p.getLocation().getBlockX());
            out.addProperty("y", p.getLocation().getBlockY());
            out.addProperty("z", p.getLocation().getBlockZ());
            if (mSendHealthInfo) {
                out.addProperty("health", p.getHealth());
            }
            if (mSendFoodLevel) {
                out.addProperty("foodlevel", p.getFoodLevel());
            }
            if (mSendLevel) {
                out.addProperty("level", p.getLevel());
            }

            // Handles sneaking player
            if (mHideSneakingPlayers) {
                boolean isSneaking = p.isSneaking();

                if (isSneaking) {
                    if (mSendJSONOnSneakingPlayers) {
                        out.addProperty("id", 6);
                    }

                    sendDataSneaking = mSendJSONOnSneakingPlayers;
                }
            }

            // Handles invisible potion effect on player
            if (mHideInvisiblePlayers) {
                boolean isInvisible = p.hasPotionEffect(PotionEffectType.INVISIBILITY);

                if (isInvisible) {
                    if (mSendJSONOnInvisiblePlayers) {
                        out.addProperty("id", 7); // will replace sneaking player ID
                    }

                    sendDataInvisible = mSendJSONOnInvisiblePlayers;
                }
            }

            // Handles vanished player
            if (mHideVanishedPlayers) {
                sendDataVanished = vnp == null || !vnp.getManager().isVanished(p);
                if (sendDataVanished) {
                    List<MetadataValue> list = p.getMetadata("vanished");
                    for (MetadataValue value : list) {
                        if (value.asBoolean()) {
                            sendDataVanished = false;

                            break;
                        }
                    }
                }
                if (!sendDataVanished && mSendJSONOnVanishedPlayers) {
                    sendDataVanished = true;
                    out.addProperty("id", 5); // will replace invisible player ID
                }
            }

            // Handles players in spectator mode
            if (mHideSpectators && p.getGameMode() == GameMode.SPECTATOR) {
                if (mSendJSONOnSpectators) {
                    out.addProperty("id", 8); // will replace spectator ID
                }

                sendDataSpectator = mSendJSONOnSpectators;
            }

            if (sendDataSneaking && sendDataInvisible && sendDataVanished && sendDataSpectator) {
                jsonList.add(out);
            }
        }

        if (mSaveOfflinePlayers) {
            // Write Offline players
            for (ConcurrentHashMap.Entry<String, SimpleLocation> p : mOfflineLocations.entrySet()) {
                out = new JsonObject();
                out.addProperty("msg", p.getKey());
                out.addProperty("id", 5);
                out.addProperty("world", getMappedWorld(p.getValue().worldName));
                out.addProperty("x", p.getValue().x);
                out.addProperty("y", p.getValue().y);
                out.addProperty("z", p.getValue().z);

                jsonList.add(out);
            }
        }

        mDataWriter.setData(jsonList);
        getServer().getScheduler().runTaskAsynchronously(this, mDataWriter);
    }

    public String getMappedWorld(String worldName) {
        return mMapNameMapping.get(worldName);
    }

    public String getMapUrlBase() {
        return mMapUrlBase;
    }

    public String getMapUrlFormat() {
        return mMapUrlFormat;
    }

    static String replace(String string, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            string = string.replace("{" + replacements[i] + "}", replacements[i+1]);
        }
        return string;
    }

    private class JSONDataWriter implements Runnable {
        private final String targetPath;
        private JsonArray jsonData;

        public JSONDataWriter(String path) {
            targetPath = path;
        }

        public void setData(JsonArray data) {
            if (jsonData == null) {
                jsonData = new JsonArray();
                jsonData.addAll(data);
            }
        }

        public void run() {
            if (jsonData != null && targetPath != null) {
                try {
                    FileWriter fileWriter = new FileWriter(targetPath);
                    BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
                    PrintWriter writer = new PrintWriter(bufferedWriter);
                    writer.print(jsonData);
                    writer.close();
                } catch (java.io.IOException e) {
                    getLogger().severe(String.format("Unable to write to %s: %s", targetPath, e.getMessage()));
                } finally {
                    jsonData = null;
                }
            }
        }

    }
}