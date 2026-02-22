package pl.pluginrcon;

import org.bukkit.plugin.java.JavaPlugin;
import pl.pluginrcon.api.ApiClient;
import pl.pluginrcon.command.AdminCommand;
import pl.pluginrcon.command.CommandProcessor;
import pl.pluginrcon.config.ConfigManager;
import pl.pluginrcon.persistence.ExecutionLog;
import pl.pluginrcon.task.PullTask;

public class PluginRcon extends JavaPlugin {

    private ConfigManager configManager;
    private ApiClient apiClient;
    private CommandProcessor commandProcessor;
    private ExecutionLog executionLog;
    private PullTask pullTask;
    private int flushTaskId = -1;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        executionLog = new ExecutionLog(this);
        apiClient = new ApiClient(this);
        commandProcessor = new CommandProcessor(this);

        startPullTask();
        startFlushTask();

        var cmd = getCommand("pluginrcon");
        if (cmd != null) {
            AdminCommand adminCommand = new AdminCommand(this);
            cmd.setExecutor(adminCommand);
            cmd.setTabCompleter(adminCommand);
        }

        getLogger().info("PluginRCON enabled - Server: " + configManager.getServerName());
    }

    @Override
    public void onDisable() {
        if (flushTaskId != -1) {
            getServer().getScheduler().cancelTask(flushTaskId);
        }

        if (pullTask != null) {
            pullTask.cancel();
        }

        if (executionLog != null) {
            executionLog.forceSave();
        }

        if (apiClient != null) {
            apiClient.shutdown();
        }

        commandProcessor.clearProcessedCache();

        getLogger().info("PluginRCON disabled");
    }

    public void reload() {
        if (flushTaskId != -1) {
            getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }

        if (pullTask != null) {
            pullTask.cancel();
            pullTask = null;
        }

        configManager.reload();
        commandProcessor.clearProcessedCache();
        apiClient.shutdown();
        apiClient = new ApiClient(this);

        startPullTask();
        startFlushTask();

        getLogger().info("PluginRCON reloaded - Server: " + configManager.getServerName());
    }

    private void startPullTask() {
        pullTask = new PullTask(this);
        long intervalTicks = configManager.getPullInterval() * 20L;
        long offsetTicks = configManager.getPullOffset() * 20L;
        long initialDelay = 40L + offsetTicks;
        pullTask.runTaskTimerAsynchronously(this, initialDelay, intervalTicks);
    }

    private void startFlushTask() {
        flushTaskId = getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> executionLog.flushIfDirty(), 200L, 200L).getTaskId();
    }

    public void logDebug(String message) {
        if (configManager.isDebug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public void logWarning(String message) {
        getLogger().warning(message);
    }

    public ConfigManager getConfigManager() { return configManager; }
    public ApiClient getApiClient() { return apiClient; }
    public CommandProcessor getCommandProcessor() { return commandProcessor; }
    public ExecutionLog getExecutionLog() { return executionLog; }
}
