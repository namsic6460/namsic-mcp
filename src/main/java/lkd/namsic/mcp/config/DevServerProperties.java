package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public record DevServerProperties(
    Map<String, ServerConfig> devServers,
    Integer portRangeStart,
    Integer portRangeEnd,
    Duration startupTimeout
) {

    public DevServerProperties {
        if (devServers == null) {
            devServers = Map.of();
        }
        if (portRangeStart == null || portRangeStart <= 0) {
            portRangeStart = 10000;
        }
        if (portRangeEnd == null || portRangeEnd <= 0 || portRangeEnd < portRangeStart) {
            portRangeEnd = 10999;
        }
        if (startupTimeout == null) {
            startupTimeout = Duration.ofMinutes(5);
        }
    }

    public record ServerConfig(
        Path path,
        String dockerImage,
        String startCommand,
        Integer containerPort,
        List<DependsOnRef> dependsOn,
        List<String> preCommands,
        String readinessPath,
        Map<String, String> environment,
        @DefaultValue("false") boolean useHostNetwork
    ) {
        public ServerConfig {
            if (dependsOn == null) {
                dependsOn = List.of();
            }
            if (preCommands == null) {
                preCommands = List.of();
            }
            if (environment == null) {
                environment = Map.of();
            }
            if (readinessPath == null || readinessPath.isBlank()) {
                readinessPath = "/";
            } else if (!readinessPath.startsWith("/")) {
                readinessPath = "/" + readinessPath;
            }
        }

        /**
         * A dependency entry. {@code waitForReady=true} (default) means this server's start blocks
         * until the dependency is HTTP-ready (separate wave). {@code waitForReady=false} keeps the
         * dependency in the transitive start set but lets both servers boot in the same wave.
         */
        public record DependsOnRef(String name, @DefaultValue("true") boolean waitForReady) {
            public DependsOnRef {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("dependsOn name required");
                }
            }
        }
    }
}
