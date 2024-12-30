package com.websocket;

import com.websocket.services.EventPublisher;
import com.websocket.services.MqttUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketHandler extends TextWebSocketHandler {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Set<WebSocketSession> allSessions = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketSession> individualBotSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    public WebSocketHandler(MqttUtils mqttUtils){
        mqttUtils.fetchAllHeartbeats().subscribe();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String path = Objects.requireNonNull(session.getUri()).getPath();

        if (path.startsWith("/ws/heartbeat/all")) {
            allSessions.add(session);
            logger.info("WebSocket connected for all bots: {}", session.getId());
        } else if (path.startsWith("/ws/heartbeat/")) {
            individualBotSessions.add(session);
            logger.info("WebSocket connected for individual bot: {}", session.getId());
        }

        Flux<String> messageFlux = eventPublisher.getSink().asFlux();
        messageFlux.subscribe(message -> sendMessageBasedOnSessionPath(session, message));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        allSessions.remove(session);
        individualBotSessions.remove(session);
        logger.info("WebSocket disconnected: {}", session.getId());
    }

    private void sendMessageBasedOnSessionPath(WebSocketSession session, String message) {
        try {
            String path = Objects.requireNonNull(session.getUri()).getPath();

            if (path.startsWith("/ws/heartbeat/all")) {
                sendToAllSessions(allSessions, message);
            } else if (path.startsWith("/ws/heartbeat/")) {
                String botId = path.split("/")[3];
                if (message.contains("\"botId\":\"" + botId + "\"")) {
                    sendToAllSessions(Set.of(session), message);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing WebSocket message: {}", e.getMessage());
        }
    }

    public void sendToAllSessions(Set<WebSocketSession> sessions, String message) {
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                logger.info("Error sending message to WebSocket session: {}", e.getMessage());
            }
        });
    }
}
