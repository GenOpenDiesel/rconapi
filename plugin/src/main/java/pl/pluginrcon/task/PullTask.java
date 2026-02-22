package pl.pluginrcon.task;

import org.bukkit.scheduler.BukkitRunnable;
import pl.pluginrcon.PluginRcon;
import pl.pluginrcon.model.RemoteCommand;

import java.util.List;

public class PullTask extends BukkitRunnable {

    private final PluginRcon plugin;

    public PullTask(PluginRcon plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        plugin.getApiClient().fetchPendingCommands()
                .thenAccept(this::processCommands);
    }

    private void processCommands(List<RemoteCommand> commands) {
        if (commands.isEmpty()) return;

        plugin.logDebug("[Pull] Received " + commands.size() + " commands");

        for (RemoteCommand cmd : commands) {
            plugin.getCommandProcessor().processCommand(cmd);
        }
    }
}
