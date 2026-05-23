package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.config.DevServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PortAllocationService {

    private final int portRangeStart;
    private final int portRangeEnd;
    private final Map<String, Integer> portMap = new ConcurrentHashMap<>();
    private final Set<Integer> allocatedPorts = ConcurrentHashMap.newKeySet();

    public PortAllocationService(DevServerProperties properties) {
        this.portRangeStart = properties.portRangeStart();
        this.portRangeEnd = properties.portRangeEnd();
    }

    public synchronized int allocatePort(String sessionId, String serverName) {
        String key = compositeKey(sessionId, serverName);
        Integer existing = this.portMap.get(key);
        if (existing != null) {
            return existing;
        }

        int port = this.findFreePort().orElseThrow(() -> new IllegalStateException(
            "No available ports in range " + this.portRangeStart + "-" + this.portRangeEnd));

        this.portMap.put(key, port);
        this.allocatedPorts.add(port);
        log.info("Allocated port {} for sessionId={}, serverName={}", port, sessionId, serverName);
        return port;
    }

    /**
     * Reserve a fixed port that the caller has already chosen (e.g. host-network mode where
     * host port == container port and is not pickable). Idempotent for the same key, but throws
     * if the same port is already held by a different (sessionId, serverName).
     */
    public synchronized void reservePort(String sessionId, String serverName, int port) {
        String key = compositeKey(sessionId, serverName);
        Integer existing = this.portMap.get(key);
        if (existing != null) {
            if (existing == port) return;
            throw new IllegalStateException("Server " + key + " already bound to port " + existing
                + ", cannot rebind to " + port);
        }
        if (this.allocatedPorts.contains(port)) {
            throw new IllegalStateException("Port " + port + " is already in use by another dev server in this JVM");
        }
        this.portMap.put(key, port);
        this.allocatedPorts.add(port);
        log.info("Reserved fixed port {} for sessionId={}, serverName={}", port, sessionId, serverName);
    }

    public synchronized void releasePort(String sessionId, String serverName) {
        String key = compositeKey(sessionId, serverName);
        Integer port = this.portMap.remove(key);
        if (port != null) {
            this.allocatedPorts.remove(port);
            log.info("Released port {} for sessionId={}, serverName={}", port, sessionId, serverName);
        }
    }

    public synchronized void releaseAllPorts(String sessionId) {
        String prefix = sessionId + "|";
        this.portMap.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                this.allocatedPorts.remove(entry.getValue());
                return true;
            }
            return false;
        });
    }

    public Integer getPort(String sessionId, String serverName) {
        return this.portMap.get(compositeKey(sessionId, serverName));
    }

    private OptionalInt findFreePort() {
        for (int port = this.portRangeStart; port <= this.portRangeEnd; port++) {
            if (this.allocatedPorts.contains(port)) {
                continue;
            }
            if (this.isPortAvailable(port)) {
                return OptionalInt.of(port);
            }
        }
        return OptionalInt.empty();
    }

    private static String compositeKey(String sessionId, String serverName) {
        return sessionId + "|" + serverName;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
