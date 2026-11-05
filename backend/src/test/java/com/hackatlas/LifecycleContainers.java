package com.hackatlas;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Closed-test services must never publish the inbox or database on the LAN. */
final class LifecycleContainers {
    private LifecycleContainers() { }

    static PostgreSQLContainer postgres(String owner) {
        return new PostgreSQLContainer(DockerImageName.parse(
                "postgres@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5"))
                .withDatabaseName("hackatlas_lifecycle_test").withUsername(owner)
                .withPassword(UUID.randomUUID().toString()).withReuse(false)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withPortBindings(loopback(5432)));
    }

    static GenericContainer<?> mailpit() {
        return new GenericContainer<>(DockerImageName.parse(
                "axllent/mailpit@sha256:9b3a463be15354c0f0eba91697d80e3e0d8a5623ef0e627cacc836bc17fd5231"))
                .withExposedPorts(1025, 8025).withEnv("MP_DATABASE", "/tmp/mailpit.db")
                .withEnv("MP_SMTP_DISABLE_RDNS", "true")
                .withTmpFs(Map.of("/tmp", "rw,nosuid,noexec,size=32m,mode=1777"))
                .withCreateContainerCmdModifier(command -> command.withUser("1000:1000").getHostConfig()
                        .withReadonlyRootfs(true).withCapDrop(Capability.ALL)
                        .withSecurityOpts(List.of("no-new-privileges:true"))
                        .withPortBindings(loopback(1025), loopback(8025)))
                .waitingFor(Wait.forHttp("/livez").forPort(8025)).withReuse(false);
    }

    private static PortBinding loopback(int port) {
        return new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(port));
    }
}
