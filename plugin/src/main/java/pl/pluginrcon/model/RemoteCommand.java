package pl.pluginrcon.model;

public class RemoteCommand {

    public enum ExecutionType {
        INSTANT,
        REQUIRE_ONLINE,
        BROADCAST_ONLINE;

        public static ExecutionType fromString(String value) {
            if (value == null) return INSTANT;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return INSTANT;
            }
        }
    }

    private String id;
    private String serverId;
    private String gameMode;
    private String command;
    private String player;
    private ExecutionType executionType;
    private String status;
    private String response;
    private String groupId;
    private String createdAt;
    private String expiresAt;

    public RemoteCommand() {}

    public RemoteCommand(String id, String serverId, String gameMode, String command,
                         String player, ExecutionType executionType, String status,
                         String groupId) {
        this.id = id;
        this.serverId = serverId;
        this.gameMode = gameMode;
        this.command = command;
        this.player = player;
        this.executionType = executionType;
        this.status = status;
        this.groupId = groupId;
    }

    public String getResolvedCommand(String playerName) {
        if (command == null) return "";
        String resolved = command;
        if (playerName != null) {
            resolved = resolved.replace("%player%", playerName);
            resolved = resolved.replace("%PLAYER%", playerName);
            resolved = resolved.replace("{player}", playerName);
        }
        return resolved;
    }

    public boolean isBroadcast() {
        return executionType == ExecutionType.BROADCAST_ONLINE;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getGameMode() { return gameMode; }
    public void setGameMode(String gameMode) { this.gameMode = gameMode; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getPlayer() { return player; }
    public void setPlayer(String player) { this.player = player; }

    public ExecutionType getExecutionType() { return executionType; }
    public void setExecutionType(ExecutionType executionType) { this.executionType = executionType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
}
