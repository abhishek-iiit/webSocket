package com.websocket.services;

import com.websocket.configuration.FtpsConnectionConfig;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class MqttUtils {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private FTPSServiceUtils ftpsServiceUtils;
    @Autowired
    FtpsConnectionConfig ftpsConnectionConfig;

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

    private final Map<String, MqttConnectOptions> botOptionsMap = new HashMap<>();
    private final Map<String, Sinks.Many<Object>> topicSinkMap = new ConcurrentHashMap<>();

    public Flux<Object> fetchAllHeartbeats() {
        return Flux.create(sink -> {
            try {
                synchronized (this) {
                    if (botOptionsMap.isEmpty() || topicSinkMap.isEmpty()) {
                        setupMqttConnectionsForAllBots();
                    }
                }

                botOptionsMap.forEach((botId, options) -> {
                    try {
                        MqttClient mqttClient = new MqttClient("ssl://" + mqttHost + ":" + mqttPort, "Elecbits_" + botId, null);
                        mqttClient.connect(options);

                        logger.info("Subscribing to topic for bot {}: heartbeat_{}", botId, botId);

                        mqttClient.subscribe("heartbeat_" + botId, (topic, message) -> {
                            String payload = new String(message.getPayload());
                            logger.info("Message received for bot {} on topic {}: {}", botId, topic, payload);

                            topicSinkMap.computeIfAbsent(botId, t -> Sinks.many().multicast().onBackpressureBuffer())
                                    .tryEmitNext(payload);

                            logger.info("Emitting message to WebSocket: {}", payload);

                            sink.next(Map.of(
                                    "botId", botId,
                                    "topic", topic,
                                    "message", payload
                            ));
                        });
                    } catch (Exception e) {
                        logger.error("Error subscribing to topic for bot {}: {}", botId, e.getMessage(), e);
                    }
                });

            } catch (Exception e) {
                sink.error(new RuntimeException("Error setting up subscriptions for MQTT topics", e));
            }
        });
    }

    private void setupMqttConnectionsForAllBots() throws Exception {
        List<String> botDirectories = ftpsServiceUtils.lsFilesAtLocation("/certificates/", ftpsConnectionConfig);
        if (botDirectories.isEmpty()) {
            throw new RuntimeException("No bot directories found under '/certificates/'");
        }

        Map<String, InputStream> fileStreams = ftpsServiceUtils.fetchMultipleFiles(
                Arrays.asList("/certificates/CA/ca_GW35-h1f1-1024-0101-AA00.crt"), ftpsConnectionConfig);
        InputStream caCertStream = fileStreams.get("/certificates/CA/ca_GW35-h1f1-1024-0101-AA00.crt");
        if (caCertStream == null) {
            throw new RuntimeException("Failed to retrieve CA certificate from SFTP.");
        }
        byte[] caCertBytes = caCertStream.readAllBytes();

        Map<String, MqttConnectOptions> botOptionsMap = new HashMap<>();

        for (String botDir : botDirectories) {
            Map<String, InputStream> botFileStreams = ftpsServiceUtils.fetchMultipleFiles(
                    Arrays.asList("/certificates/" + botDir + "/client_" + botDir + ".crt",
                            "/certificates/" + botDir + "/client_" + botDir + ".key"), ftpsConnectionConfig);

            InputStream clientCertStream = botFileStreams.get("/certificates/" + botDir + "/client_" + botDir + ".crt");
            InputStream clientKeyStream = botFileStreams.get("/certificates/" + botDir + "/client_" + botDir + ".key");

            if (clientCertStream == null || clientKeyStream == null) {
                logger.warn("Failed to retrieve certificates for bot: {}", botDir);
                continue;
            }

            SSLSocketFactory sslSocketFactory = null;
            try {
                sslSocketFactory = SslUtil.getSocketFactory(caCertBytes, clientCertStream, clientKeyStream);
            } catch (Exception e) {
                logger.error("Failed to create SSLSocketFactory for bot: {}", botDir, e);
                continue;
            }

            MqttConnectOptions options = new MqttConnectOptions();
            options.setServerURIs(new String[]{"ssl://" + mqttHost + ":" + mqttPort});
            options.setCleanSession(false);
            options.setSocketFactory(sslSocketFactory);
            options.setUserName(mqttSubscribeUsername);
            options.setPassword(mqttSubscribePassword.toCharArray());
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
            options.setAutomaticReconnect(true);
            botOptionsMap.put(botDir, options);
        }

        List<Mono<Void>> mqttConnectionTasks = botOptionsMap.entrySet().stream()
                .map(entry -> {
                    String botId = entry.getKey();
                    MqttConnectOptions options = entry.getValue();

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

                                    topicSinkMap.put(botId, Sinks.many().multicast().onBackpressureBuffer());

                                    mqttClient.subscribe("heartbeat_" + botId, (topic, message) -> {
                                        String payload = new String(message.getPayload());
                                        logger.info("Message received for bot {} on topic {}: {}", botId, topic, payload);
                                        topicSinkMap.get(botId).tryEmitNext(payload);
                                    });
                                    return null;
                                })
                                .then()
                                .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(5)))
                                .onErrorResume(e -> {
                                    logger.error("Failed to connect to MQTT broker for bot {} after retries: {}", botId, e.getMessage());
                                    return Mono.empty();
                                });
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .collect(Collectors.toList());
        Mono.when(mqttConnectionTasks).subscribe();
    }
}
