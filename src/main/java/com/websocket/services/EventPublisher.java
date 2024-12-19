package com.websocket.services;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

@Component
public class EventPublisher {

    private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

    public void publish(String message) {
        sink.tryEmitNext(message);
    }

    public Sinks.Many<String> getSink() {
        return sink;
    }
}
