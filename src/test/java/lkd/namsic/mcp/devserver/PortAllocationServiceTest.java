package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.config.DevServerProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortAllocationServiceTest {

    private static DevServerProperties props() {
        return new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(30));
    }

    @Test
    void allocatesDifferentPortsForDifferentServers() {
        PortAllocationService svc = new PortAllocationService(props());
        int a = svc.allocatePort("sid", "backend");
        int b = svc.allocatePort("sid", "frontend");
        assertNotEquals(a, b);
        assertTrue(a >= 10000 && a <= 10999);
        assertTrue(b >= 10000 && b <= 10999);
    }

    @Test
    void allocatePortIsIdempotentForSameKey() {
        PortAllocationService svc = new PortAllocationService(props());
        int a = svc.allocatePort("sid", "backend");
        int b = svc.allocatePort("sid", "backend");
        assertEquals(a, b);
    }

    @Test
    void releasePortMakesItReusable() {
        PortAllocationService svc = new PortAllocationService(props());
        svc.allocatePort("sid-1", "srv");
        svc.releasePort("sid-1", "srv");
        assertNull(svc.getPort("sid-1", "srv"));
    }

    @Test
    void releaseAllPortsClearsOnlyThatSession() {
        PortAllocationService svc = new PortAllocationService(props());
        svc.allocatePort("sid-a", "srv");
        int b = svc.allocatePort("sid-b", "srv");
        svc.releaseAllPorts("sid-a");
        assertNull(svc.getPort("sid-a", "srv"));
        assertEquals(b, svc.getPort("sid-b", "srv"));
    }

    @Test
    void concurrentAllocationDoesNotDuplicatePorts() throws InterruptedException {
        PortAllocationService svc = new PortAllocationService(props());
        int n = 16;
        Set<Integer> ports = new HashSet<>();
        Thread[] threads = new Thread[n];
        int[] results = new int[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> results[idx] = svc.allocatePort("sid", "srv-" + idx));
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        for (int r : results) ports.add(r);
        assertEquals(n, ports.size(), "All allocated ports must be distinct: " + java.util.Arrays.toString(results));
    }
}
