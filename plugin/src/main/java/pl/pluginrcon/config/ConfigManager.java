package pl.pluginrcon.config;

import org.bukkit.configuration.file.FileConfiguration;
import pl.pluginrcon.PluginRcon;

public class ConfigManager {

    private final PluginRcon plugin;
    private String serverName;
    private String apiUrl;
    private String apiToken;
    private int pullInterval;
    private int pullOffset;
    private int connectionTimeout;
    private int readTimeout;
    private int httpPoolSize;
    private boolean debug;

    public ConfigManager(PluginRcon plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        serverName = config.getString("server-name", "default");
        apiUrl = config.getString("api.url", "http://localhost:3000");
        apiToken = config.getString("api.token", "");
        pullInterval = config.getInt("pull.interval", 5);
        pullOffset = config.getInt("pull.offset", 0);
        connectionTimeout = config.getInt("connection.timeout", 10000);
        readTimeout = config.getInt("connection.read-timeout", 10000);
        httpPoolSize = config.getInt("connection.pool-size", 16);
        debug = config.getBoolean("debug", false);

        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
    }

    public String getServerName() { return serverName; }
    public String getApiUrl() { return apiUrl; }
    public String getApiToken() { return apiToken; }
    public int getPullInterval() { return pullInterval; }
    public int getPullOffset() { return pullOffset; }
    public int getConnectionTimeout() { return connectionTimeout; }
    public int getReadTimeout() { return readTimeout; }
    public int getHttpPoolSize() { return httpPoolSize; }
    public boolean isDebug() { return debug; }
}
