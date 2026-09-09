package com.wrsensor.queryapi.infrastructure.adapter.in.ws;

import com.wrsensor.queryapi.domain.QueryException;
import com.wrsensor.queryapi.infrastructure.realtime.LecturaRealtimeBus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** WS /ws/sensores/{id} (FEAT-0013 AC-007): lecturas en tiempo real por sensor. */
@Component
public class WsLecturasSensorHandler implements WebSocketHandler {

    private final LecturaRealtimeBus bus;

    public WsLecturasSensorHandler(LecturaRealtimeBus bus) {
        this.bus = bus;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        UUID sensorId;
        try {
            sensorId = UUID.fromString(last);
        } catch (IllegalArgumentException e) {
            return session.close(new org.springframework.web.reactive.socket.CloseStatus(1008, "sensor invalido"));
        }
        return session.send(bus.canal(sensorId).asFlux().map(session::textMessage))
                .and(session.receive().then());
    }
}
