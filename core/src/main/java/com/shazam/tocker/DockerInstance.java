/*
 * Copyright 2015 Shazam Entertainment Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.shazam.tocker;

import com.spotify.docker.client.*;
import com.spotify.docker.client.messages.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.shazam.tocker.AliveStrategies.alwaysAlive;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public class DockerInstance {
    private final String imageName;
    private final String containerName;
    private final ImageStrategy imageStrategy;
    private HostConfig hostConfig;
    private String[] env;
    private DefaultDockerClient dockerClient;

    private DockerInstance(String imageName, String containerName, HostConfig hostConfig, String[] env, ImageStrategy imageStrategy) {
        this.imageName = imageName;
        this.containerName = containerName;
        this.hostConfig = hostConfig;
        this.env = env;

        try {
            dockerClient = DefaultDockerClient.fromEnv().build();
        } catch (DockerCertificateException e) {
            throw new RuntimeException(e);
        }

        this.imageStrategy = imageStrategy;
    }

    public static DockerInstanceBuilder fromImage(String imageName) {
        return new DockerInstanceBuilder(imageName);
    }

    public static DockerInstanceBuilder fromFile(Path dir, String imageName) {
        return new DockerInstanceBuilder(dir, imageName);
    }

    public String host() {
        return dockerClient.getHost();
    }

    public void run() {
        run(alwaysAlive());
    }

    public void run(AliveStrategy aliveStrategyCheck) {
        withClient((client) -> {
            try {
                startContainerIfNecessary(client, client.inspectContainer(containerName), aliveStrategyCheck);
            } catch (DockerException de) {
                ensureImageExists(client);
                ContainerCreation container = createContainer(client);
                startContainer(client, container.id(), aliveStrategyCheck);
            }
        });
    }

    private void startContainerIfNecessary(DockerClient client, ContainerInfo containerInfo, AliveStrategy upCheck) throws DockerException, InterruptedException {
        if (!containerInfo.state().running()) {
            startContainer(client, containerInfo.id(), upCheck);
        }
    }

    private void startContainer(DockerClient client, String containerId, AliveStrategy upCheck) throws DockerException, InterruptedException {
        client.startContainer(containerId);
        upCheck.waitUntilAlive();
    }

    private ContainerCreation createContainer(DockerClient client) throws DockerException, InterruptedException {
        ContainerConfig containerConfig = ContainerConfig.builder()
                .image(imageName)
                .hostConfig(hostConfig)
                .env(env)
                .build();
        return client.createContainer(containerConfig, containerName);
    }

    private void ensureImageExists(DockerClient client) throws Exception {
        try {
            client.inspectImage(imageName);
        } catch (ImageNotFoundException infe) {
            imageStrategy.loadImage(client);
        }
    }

    public void stop() {
        withClient((client) -> client.stopContainer(containerName, 10));
    }

    private void withClient(DockerCommand consumer) {
        try {
            consumer.runCommand(dockerClient);
        } catch (Exception de) {
            throw new RuntimeException(de);
        }
    }

    public static class DockerInstanceBuilder {
        private final ImageStrategy imageStrategy;
        private String imageName;
        private String containerName;
        private HostConfig.Builder hostConfig = HostConfig.builder();
        private String[] env;

        public DockerInstanceBuilder(String imageName) {
            this.imageName = imageName;
            this.imageStrategy = (client) -> client.pull(imageName);
        }

        public DockerInstanceBuilder(Path dirToCustomDockerFile, String imageName) {
            this.imageName = imageName;
            this.imageStrategy = (client) -> client.build(dirToCustomDockerFile, imageName);
        }

        public DockerInstance build() {
            return new DockerInstance(imageName, containerName, hostConfig.build(), env, imageStrategy);
        }

        public DockerInstanceBuilder withContainerName(String containerName) {
            this.containerName = containerName;
            return this;
        }

        public DockerInstanceBuilder mappingPorts(PortMap ... portMaps) {
            Map<String, List<PortBinding>> portBindings = stream(portMaps).collect(toMap(
                    pm -> String.format("%d/tcp", pm.containerPort()),
                    pm -> asList(PortBinding.of("0.0.0.0", pm.localhostPort()))));
            hostConfig.portBindings(portBindings).build();
            return this;
        }

        public DockerInstanceBuilder privileged() {
            this.hostConfig.privileged(true);
            return this;
        }

        public DockerInstanceBuilder withEnv(String ... env) {
            this.env = env;
            return this;
        }
    }

    private static interface DockerCommand {
        public void runCommand(DockerClient dc) throws Exception;
    }

    private static interface ImageStrategy {
        public void loadImage(DockerClient dc) throws Exception;
    }
}
