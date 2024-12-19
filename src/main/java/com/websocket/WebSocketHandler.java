package com.websocket;

import com.websocket.services.EventPublisher;
import com.websocket.services.MqttUtils;
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

    @Autowired
    private MqttUtils mqttUtils;

    @Autowired
    private EventPublisher eventPublisher;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("WebSocket connected: " + session.getId());
        mqttUtils.fetchAllHeartbeats().subscribe();
        Flux<String> messageFlux = eventPublisher.getSink().asFlux();
        messageFlux.subscribe(this::sendToAllSessions);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        System.out.println("WebSocket disconnected: " + session.getId());
    }

    public void sendToAllSessions(String message) {
        System.out.println("Broadcasting message to all WebSocket sessions: " + message);
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                    System.out.println("Message sent to session: " + session.getId());
                }
            } catch (IOException e) {
                System.err.println("Error sending message to WebSocket session: " + e.getMessage());
            }
        });
    }
}
