package pl.pluginrcon.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.pluginrcon.PluginRcon;
import pl.pluginrcon.model.RemoteCommand;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public class ApiClient {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 500;

    private final PluginRcon plugin;
    private final Gson gson = new Gson();
    private final HttpClient httpClient;
    private final ScheduledExecutorService executor;

    public ApiClient(PluginRcon plugin) {
        this.plugin = plugin;
        int poolSize = plugin.getConfigManager().getHttpPoolSize();
        this.executor = new ScheduledThreadPoolExecutor(poolSize, r -> {
            Thread t = new Thread(r, "PluginRCON-HTTP");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(plugin.getConfigManager().getConnectionTimeout()))
                .executor(executor)
                .build();
    }

    public CompletableFuture<List<RemoteCommand>> fetchPendingCommands() {
        String serverName = plugin.getConfigManager().getServerName();
        String url = plugin.getConfigManager().getApiUrl() + "/api/commands/pending/" + serverName;

        return sendGetAsync(url).thenApply(response -> {
            if (response == null || !response.has("commands")) {
                return Collections.<RemoteCommand>emptyList();
            }
            JsonArray arr = response.getAsJsonArray("commands");
            List<RemoteCommand> commands = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                commands.add(parseCommand(el.getAsJsonObject()));
            }
            return commands;
        }).exceptionally(e -> {
            plugin.logWarning("Failed to fetch pending commands: " + e.getMessage());
            return Collections.emptyList();
        });
    }

    public CompletableFuture<List<RemoteCommand>> fetchQueuedForPlayer(String playerName) {
        String serverName = plugin.getConfigManager().getServerName();
        String url = plugin.getConfigManager().getApiUrl()
                + "/api/commands/queued/" + serverName + "/" + playerName;

        return sendGetAsync(url).thenApply(response -> {
            if (response == null || !response.has("commands")) {
                return Collections.<RemoteCommand>emptyList();
            }
            JsonArray arr = response.getAsJsonArray("commands");
            List<RemoteCommand> commands = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                commands.add(parseCommand(el.getAsJsonObject()));
            }
            return commands;
        }).exceptionally(e -> {
            plugin.logWarning("Failed to fetch queued commands for " + playerName + ": " + e.getMessage());
            return Collections.emptyList();
        });
    }

    public void reportComplete(String commandId, String response) {
        String url = plugin.getConfigManager().getApiUrl() + "/api/commands/" + commandId + "/complete";
        JsonObject body = new JsonObject();
        body.addProperty("response", response);
        sendPostAsync(url, body, "complete", commandId);
    }

    public void reportFailed(String commandId, String error) {
        String url = plugin.getConfigManager().getApiUrl() + "/api/commands/" + commandId + "/fail";
        JsonObject body = new JsonObject();
        body.addProperty("error", error);
        sendPostAsync(url, body, "failed", commandId);
    }

    public void reportQueued(String commandId) {
        String url = plugin.getConfigManager().getApiUrl() + "/api/commands/" + commandId + "/queue";
        sendPostAsync(url, new JsonObject(), "queued", commandId);
    }

    public void reportSkipped(String commandId, String reason) {
        String url = plugin.getConfigManager().getApiUrl() + "/api/commands/" + commandId + "/skip";
        JsonObject body = new JsonObject();
        body.addProperty("response", reason);
        sendPostAsync(url, body, "skipped", commandId);
    }

    public CompletableFuture<List<String>> fetchNetworkServers() {
        String serverName = plugin.getConfigManager().getServerName();
        String url = plugin.getConfigManager().getApiUrl() + "/api/servers/network/" + serverName;

        return sendGetAsync(url).thenApply(response -> {
            if (response == null || !response.has("servers")) {
                return Collections.<String>emptyList();
            }
            JsonArray arr = response.getAsJsonArray("servers");
            List<String> servers = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                servers.add(el.getAsString());
            }
            return servers;
        }).exceptionally(e -> {
            plugin.logWarning("Failed to fetch network servers: " + e.getMessage());
            return Collections.emptyList();
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<JsonObject> sendGetAsync(String url) {
        HttpRequest request = buildRequest(url, "GET", null);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        plugin.logDebug("GET " + url + " returned " + resp.statusCode());
                        return null;
                    }
                    return gson.fromJson(resp.body(), JsonObject.class);
                });
    }

    private void sendPostAsync(String url, JsonObject body, String action, String commandId) {
        sendPostAttempt(url, gson.toJson(body), action, commandId, 0);
    }

    private void sendPostAttempt(String url, String bodyJson, String action, String commandId, int attempt) {
        HttpRequest request = buildRequest(url, "POST", bodyJson);

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        if (attempt < MAX_RETRIES) {
                            plugin.logDebug("Error for " + commandId + ", retry " + (attempt + 1)
                                    + ": " + ex.getMessage());
                            scheduleRetry(url, bodyJson, action, commandId, attempt);
                        } else {
                            plugin.logWarning("Failed to report " + action + " for " + commandId
                                    + " after " + MAX_RETRIES + " retries: " + ex.getMessage());
                        }
                        return;
                    }

                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        plugin.logDebug("Reported " + action + ": " + commandId);
                        return;
                    }

                    if (resp.statusCode() >= 500 && attempt < MAX_RETRIES) {
                        plugin.logDebug("Server error " + resp.statusCode() + " for " + commandId
                                + ", retry " + (attempt + 1));
                        scheduleRetry(url, bodyJson, action, commandId, attempt);
                        return;
                    }

                    plugin.logWarning("Failed to report " + action + " for " + commandId
                            + ": HTTP " + resp.statusCode());
                });
    }

    private void scheduleRetry(String url, String bodyJson, String action, String commandId, int attempt) {
        long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
        executor.schedule(
                () -> sendPostAttempt(url, bodyJson, action, commandId, attempt + 1),
                delay, TimeUnit.MILLISECONDS
        );
    }

    private HttpRequest buildRequest(String url, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(plugin.getConfigManager().getReadTimeout()))
                .header("Authorization", "Bearer " + plugin.getConfigManager().getApiToken())
                .header("X-Server-Name", plugin.getConfigManager().getServerName());

        if ("POST".equals(method) && body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
        } else {
            builder.GET();
        }

        return builder.build();
    }

    private RemoteCommand parseCommand(JsonObject obj) {
        RemoteCommand cmd = new RemoteCommand();
        cmd.setId(getStr(obj, "id"));
        cmd.setServerId(getStr(obj, "server_id"));
        cmd.setGameMode(getStr(obj, "game_mode"));
        cmd.setCommand(getStr(obj, "command"));
        cmd.setPlayer(getStr(obj, "player"));
        cmd.setStatus(getStr(obj, "status"));
        cmd.setResponse(getStr(obj, "response"));
        cmd.setGroupId(getStr(obj, "group_id"));
        cmd.setCreatedAt(getStr(obj, "created_at"));
        cmd.setExpiresAt(getStr(obj, "expires_at"));

        String execType = getStr(obj, "execution_type");
        cmd.setExecutionType(RemoteCommand.ExecutionType.fromString(execType));

        return cmd;
    }

    private String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
}
