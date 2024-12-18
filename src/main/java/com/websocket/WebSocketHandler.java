package com.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.services.MqttUtils;
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

    private final MqttUtils mqttUtils;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSocketHandler(MqttUtils mqttUtils) {
        this.mqttUtils = mqttUtils;

        Flux<Object> heartbeatStream = mqttUtils.fetchAllHeartbeats();

        heartbeatStream.subscribe(message -> {
            try {
                String jsonMessage = objectMapper.writeValueAsString(message);
                sendToAllSessions(jsonMessage);
            } catch (Exception e) {
                System.err.println("Error processing MQTT message: " + e.getMessage());
            }
        }, error -> {
            System.err.println("Error in MQTT Flux: " + error.getMessage());
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("WebSocket connected: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        System.out.println("WebSocket disconnected: " + session.getId());
    }

    private void sendToAllSessions(String message) {
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
