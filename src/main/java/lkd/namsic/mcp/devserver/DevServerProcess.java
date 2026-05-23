package lkd.namsic.mcp.devserver;

public record DevServerProcess(
    String containerName,
    String serverName,
    int hostPort,
    int containerPort,
    String volumeName
) {
}
