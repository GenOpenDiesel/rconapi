package pl.pluginrcon.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import pl.pluginrcon.PluginRcon;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists executed command IDs to disk so that backup restorations
 * of the API database don't cause duplicate command execution.
 * Entries are cleared at the start of the next business day (Mon-Fri).
 */
public class ExecutionLog {

    private final PluginRcon plugin;
    private final File logFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private String storedDate;
    private final Set<String> executedIds = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;

    public ExecutionLog(PluginRcon plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "executed_commands.json");
        load();
    }

    public boolean isAlreadyExecuted(String commandId) {
        return executedIds.contains(commandId);
    }

    public void markExecuted(String commandId) {
        executedIds.add(commandId);
        storedDate = LocalDate.now().toString();
        dirty = true;
    }

    public void flushIfDirty() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    public void forceSave() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    public int size() {
        return executedIds.size();
    }

    public void cleanup() {
        LocalDate today = LocalDate.now();
        LocalDate currentBusinessDay = getCurrentBusinessDay(today);

        if (storedDate != null) {
            try {
                LocalDate stored = LocalDate.parse(storedDate);
                if (stored.isBefore(currentBusinessDay)) {
                    int count = executedIds.size();
                    executedIds.clear();
                    storedDate = today.toString();
                    save();
                    plugin.getLogger().info("Execution log cleared (" + count + " entries) - new business day");
                }
            } catch (Exception e) {
                plugin.logWarning("Failed to parse execution log date, resetting: " + e.getMessage());
                executedIds.clear();
                storedDate = today.toString();
                save();
            }
        }
    }

    private LocalDate getCurrentBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return date.minusDays(1);
        if (dow == DayOfWeek.SUNDAY) return date.minusDays(2);
        return date;
    }

    private void load() {
        if (!logFile.exists()) {
            storedDate = LocalDate.now().toString();
            return;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> data = gson.fromJson(reader, type);

            if (data != null) {
                storedDate = data.containsKey("date") ? String.valueOf(data.get("date")) : null;

                if (data.containsKey("commands")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> commands = (java.util.List<String>) data.get("commands");
                    if (commands != null) {
                        executedIds.addAll(commands);
                    }
                }
            }

            plugin.getLogger().info("Loaded execution log: " + executedIds.size() + " entries from " + storedDate);
        } catch (Exception e) {
            plugin.logWarning("Failed to load execution log, starting fresh: " + e.getMessage());
            storedDate = LocalDate.now().toString();
        }

        cleanup();
    }

    private synchronized void save() {
        try {
            if (!logFile.getParentFile().exists()) {
                logFile.getParentFile().mkdirs();
            }

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("date", storedDate);
            data.put("commands", new java.util.ArrayList<>(executedIds));

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            plugin.logWarning("Failed to save execution log: " + e.getMessage());
        }
    }
}
