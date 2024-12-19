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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketHandler extends TextWebSocketHandler {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    public WebSocketHandler(MqttUtils mqttUtils){
        mqttUtils.fetchAllHeartbeats().subscribe();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        logger.info("WebSocket connected: " + session.getId());
        Flux<String> messageFlux = eventPublisher.getSink().asFlux();
        messageFlux.subscribe(this::sendToAllSessions);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        logger.info("WebSocket disconnected: " + session.getId());
    }

    public void sendToAllSessions(String message) {
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                logger.info("Error sending message to WebSocket session: " + e.getMessage());
            }
        });
    }
}
