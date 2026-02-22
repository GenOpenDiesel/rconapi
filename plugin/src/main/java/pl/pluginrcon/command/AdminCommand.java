package pl.pluginrcon.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import pl.pluginrcon.PluginRcon;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final Component PREFIX = Component.text("[PluginRCON] ", NamedTextColor.GOLD);

    private final PluginRcon plugin;

    public AdminCommand(PluginRcon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "pull":
                handlePull(sender);
                break;
            default:
                sendHelp(sender);
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(PREFIX.append(Component.text("Configuration reloaded!", NamedTextColor.GREEN)));
    }

    private void handleStatus(CommandSender sender) {
        var config = plugin.getConfigManager();

        sender.sendMessage(PREFIX.append(Component.text("=== PluginRCON Status ===", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("  Server: ", NamedTextColor.GRAY)
                .append(Component.text(config.getServerName(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  API: ", NamedTextColor.GRAY)
                .append(Component.text(config.getApiUrl(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  Poll interval: ", NamedTextColor.GRAY)
                .append(Component.text(config.getPullInterval() + "s", NamedTextColor.GREEN)));
    }

    private void handlePull(CommandSender sender) {
        sender.sendMessage(PREFIX.append(Component.text("Forcing pull...", NamedTextColor.YELLOW)));
        plugin.getApiClient().fetchPendingCommands()
                .thenAccept(commands -> {
                    sender.sendMessage(PREFIX.append(Component.text("Pulled " + commands.size() + " commands", NamedTextColor.GREEN)));
                    for (var cmd : commands) {
                        plugin.getCommandProcessor().processCommand(cmd);
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(PREFIX.append(Component.text("Pull failed: " + ex.getMessage(), NamedTextColor.RED)));
                    return null;
                });
    }


    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX.append(Component.text("=== Commands ===", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("  /pluginrcon reload", NamedTextColor.GOLD).append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /pluginrcon status", NamedTextColor.GOLD).append(Component.text(" - Show connection status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /pluginrcon pull", NamedTextColor.GOLD).append(Component.text(" - Force pull commands", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "status", "pull").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
