package com.websocket.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.configuration.FtpsConnectionConfig;
import org.apache.commons.net.ftp.FTPSClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MqttUtils {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_CONCURRENT_BOTS = 10;

    @Autowired
    private FTPSServiceUtils ftpsServiceUtils;
    @Autowired
    FtpsConnectionConfig ftpsConnectionConfig;
    @Autowired
    private EventPublisher eventPublisher;

    @Value("${mqtt.config-map.host}")
    private String mqttHost;
    @Value("${mqtt.config-map.port}")
    private String mqttPort;
    @Value("${mqtt.config-map.publishUsername}")
    private String mqttPublishUsername;
    @Value("${mqtt.config-map.publishPassword}")
    private String mqttPublishPassword;
    @Value("${mqtt.config-map.subscribeUsername}")
    private String mqttSubscribeUsername;
    @Value("${mqtt.config-map.subscribePassword}")
    private String mqttSubscribePassword;

    public Flux<Void> fetchAllHeartbeats() {
        return Flux.defer(() -> {
            try {
                return setupMqttConnectionsForAllBots();
            } catch (Exception e) {
                return Flux.error(new RuntimeException("Error setting up MQTT connections", e));
            }
        });
    }

    private Flux<Void> setupMqttConnectionsForAllBots() throws Exception {
        FTPSClient ftpsClient = ftpsServiceUtils.createFtpsClient(ftpsConnectionConfig);

        List<String> botDirectories = ftpsServiceUtils.lsFilesAtLocation("/certificates/", ftpsClient);
        if (botDirectories.isEmpty()) {
            throw new RuntimeException("No bot directories found under '/certificates/'");
        }

        Map<String, InputStream> fileStreams = ftpsServiceUtils.fetchMultipleFiles(
                Arrays.asList("/certificates/CA/ca_GW35-h1f1-1024-0101-AA00.crt"), ftpsClient);
        InputStream caCertStream = fileStreams.get("/certificates/CA/ca_GW35-h1f1-1024-0101-AA00.crt");
        if (caCertStream == null) {
            throw new RuntimeException("Failed to retrieve CA certificate from SFTP.");
        }
        byte[] caCertBytes = caCertStream.readAllBytes();

        return Flux.fromIterable(botDirectories)
                .flatMap(botDir -> setupConnectionForBot(botDir, caCertBytes, ftpsClient), MAX_CONCURRENT_BOTS)
                .doFinally(signalType -> ftpsServiceUtils.disconnectClientSafely(ftpsClient));
    }

    private Mono<Void> setupConnectionForBot(String botDir, byte[] caCertBytes, FTPSClient ftpsClient) {
        return Mono.defer(() -> {
            try {
                Map<String, InputStream> botFileStreams = ftpsServiceUtils.fetchMultipleFiles(
                        Arrays.asList("/certificates/" + botDir + "/client_" + botDir + ".crt",
                                "/certificates/" + botDir + "/client_" + botDir + ".key"), ftpsClient);

                InputStream clientCertStream = botFileStreams.get("/certificates/" + botDir + "/client_" + botDir + ".crt");
                InputStream clientKeyStream = botFileStreams.get("/certificates/" + botDir + "/client_" + botDir + ".key");

                if (clientCertStream == null || clientKeyStream == null) {
                    logger.warn("Failed to retrieve certificates for bot: {}", botDir);
                    return Mono.empty();
                }

                SSLSocketFactory sslSocketFactory = SslUtil.getSocketFactory(caCertBytes, clientCertStream, clientKeyStream);

                MqttConnectOptions options = new MqttConnectOptions();
                options.setServerURIs(new String[]{"ssl://" + mqttHost + ":" + mqttPort});
                options.setCleanSession(false);
                options.setSocketFactory(sslSocketFactory);
                options.setUserName(mqttSubscribeUsername);
                options.setPassword(mqttSubscribePassword.toCharArray());
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(20);
                options.setAutomaticReconnect(true);
                return connectBot(botDir, options);
            } catch (Exception e) {
                logger.error("Failed to set up connection for bot: {}", botDir, e);
                return Mono.empty();
            }
        });
    }

    private Mono<Void> connectBot(String botId, MqttConnectOptions options) {
        return Mono.defer(() -> {
            MqttClient mqttClient;
            try {
                mqttClient = new MqttClient("ssl://" + mqttHost + ":" + mqttPort, "Elecbits_" + botId, null);
            } catch (MqttException e) {
                logger.error("Error creating MqttClient for bot {}: {}", botId, e.getMessage());
                return Mono.empty();
            }
            return Mono.fromCallable(() -> {
                        mqttClient.connect(options);
                        logger.info("Connected to MQTT broker successfully for bot: {}", botId);
                        return null;
                    }).retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(5)))
                    .then(Mono.fromRunnable(() -> {
                        Mono.fromCallable(() -> {
                                    mqttClient.subscribe("heartbeat_" + botId, (topic, message) -> {
                                        String payload = new String(message.getPayload());
                                        try {
                                            String jsonMessage = objectMapper.writeValueAsString(Map.of(
                                                    "botId", botId,
                                                    "topic", topic,
                                                    "message", payload
                                            ));
                                            eventPublisher.publish(jsonMessage);
                                        } catch (Exception e) {
                                            logger.error("Error processing MQTT message for WebSocket: {}", e.getMessage());
                                        }
                                    });
                                    return null;
                                })
                                .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(5)))
                                .doOnTerminate(() -> logger.info("Subscribed to topic for bot: {}", botId))
                                .subscribe();
                    }))
                    .onErrorResume(e -> {
                        logger.error("Failed to connect to MQTT broker for bot {} after retries: {}", botId, e.getMessage());
                        return Mono.empty();
                    });
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
