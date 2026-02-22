package pl.pluginrcon.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.pluginrcon.PluginRcon;
import pl.pluginrcon.model.RemoteCommand;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class CommandProcessor {

    private static final int MAX_PROCESSED_CACHE_SIZE = 10_000;

    private final PluginRcon plugin;

    private final Set<String> processedIds = Collections.newSetFromMap(
            Collections.synchronizedMap(
                    new LinkedHashMap<String, Boolean>(256, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_PROCESSED_CACHE_SIZE;
                        }
                    }
            )
    );

    public CommandProcessor(PluginRcon plugin) {
        this.plugin = plugin;
    }

    public void processCommand(RemoteCommand cmd) {
        if (cmd == null || cmd.getId() == null) return;

        if (plugin.getExecutionLog() != null && plugin.getExecutionLog().isAlreadyExecuted(cmd.getId())) {
            plugin.logDebug("Skipping command from execution log (backup protection): " + cmd.getId());
            plugin.getApiClient().reportComplete(cmd.getId(), "Already executed (backup protection)");
            return;
        }

        if (!processedIds.add(cmd.getId())) {
            plugin.logDebug("Skipping already processed command: " + cmd.getId());
            return;
        }

        switch (cmd.getExecutionType()) {
            case INSTANT:
                executeImmediate(cmd);
                break;
            case REQUIRE_ONLINE:
                executeRequireOnline(cmd);
                break;
            case BROADCAST_ONLINE:
                executeBroadcast(cmd);
                break;
        }
    }

    private void executeImmediate(RemoteCommand cmd) {
        String resolved = cmd.getResolvedCommand(cmd.getPlayer());
        plugin.logDebug("Executing INSTANT: " + resolved);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                reportResult(cmd, success, success ? "Command dispatched" : "Command dispatch returned false");
            } catch (Exception e) {
                reportResult(cmd, false, "Error: " + e.getMessage());
            }
        });
    }

    private void executeRequireOnline(RemoteCommand cmd) {
        if (cmd.getPlayer() == null) {
            reportResult(cmd, false, "No player specified for REQUIRE_ONLINE");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(cmd.getPlayer());
            if (player != null && player.isOnline()) {
                String resolved = cmd.getResolvedCommand(player.getName());
                plugin.logDebug("Executing REQUIRE_ONLINE (player online): " + resolved);
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                    reportResult(cmd, success, success ? "Command dispatched (player online)" : "Command dispatch returned false");
                } catch (Exception e) {
                    reportResult(cmd, false, "Error: " + e.getMessage());
                }
            } else {
                plugin.logDebug("REQUIRE_ONLINE: player " + cmd.getPlayer() + " not online, waiting: " + cmd.getId());
                processedIds.remove(cmd.getId());
            }
        });
    }

    private void executeBroadcast(RemoteCommand cmd) {
        if (cmd.getPlayer() == null) {
            reportResult(cmd, false, "No player specified for BROADCAST_ONLINE");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(cmd.getPlayer());
            if (player != null && player.isOnline()) {
                String resolved = cmd.getResolvedCommand(player.getName());
                plugin.logDebug("Executing BROADCAST_ONLINE (player found): " + resolved);
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                    reportResult(cmd, success,
                            success ? "Broadcast executed (player on this server)" : "Command dispatch returned false");
                } catch (Exception e) {
                    reportResult(cmd, false, "Error: " + e.getMessage());
                }
            } else {
                plugin.logDebug("BROADCAST_ONLINE: player " + cmd.getPlayer()
                        + " not on this server, waiting: " + cmd.getId());
                processedIds.remove(cmd.getId());
            }
        });
    }

    private void reportResult(RemoteCommand cmd, boolean success, String message) {
        if (success && plugin.getExecutionLog() != null) {
            plugin.getExecutionLog().markExecuted(cmd.getId());
        }

        if (success) {
            plugin.getApiClient().reportComplete(cmd.getId(), message);
        } else {
            plugin.getApiClient().reportFailed(cmd.getId(), message);
        }

        plugin.logDebug("Command " + cmd.getId() + " " + (success ? "completed" : "failed") + ": " + message);
    }

    private void reportSkipped(RemoteCommand cmd, String context) {
        plugin.getApiClient().reportSkipped(cmd.getId(),
                "Player " + cmd.getPlayer() + " not online on " + plugin.getConfigManager().getServerName());
        plugin.logDebug("Command " + cmd.getId() + " skipped (" + context + ")");
    }

    public void clearProcessedCache() {
        processedIds.clear();
    }
}
