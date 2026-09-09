package com.wrsensor.alerting.infrastructure.adapter.in.ws;

import com.wrsensor.alerting.infrastructure.adapter.out.ws.BroadcastNotificador;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/** Endpoint WS `/ws/alertas` (FEAT-0012 AC-007): retransmite el bus de alertas. */
@Component
public class WsAlertasHandler implements WebSocketHandler {

    private final BroadcastNotificador broadcaster;

    public WsAlertasHandler(BroadcastNotificador broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.send(broadcaster.bus().asFlux()
                .map(session::textMessage))
                .and(session.receive().then());
    }
}
