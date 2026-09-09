package com.wrsensor.alerting.infrastructure.adapter.out.ws;

import com.wrsensor.alerting.application.port.Notificador;
import com.wrsensor.alerting.domain.AlertaConfirmada;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

/**
 * Notificador broadcast (BR-007): publica cada alerta confirmada en un bus
 * reactivo que el endpoint WS `/ws/alertas` retransmite (JSON manual).
 */
@Component
public class BroadcastNotificador implements Notificador {

    private final Sinks.Many<String> bus = Sinks.many().multicast().directBestEffort();

    public Sinks.Many<String> bus() {
        return bus;
    }

    @Override
    public void notificar(AlertaConfirmada a) {
        String json = "{\"sensorId\":\"" + a.sensorId()
                + "\",\"severidadNueva\":\"" + a.severidadNueva()
                + "\",\"confirmada\":true,\"timestamp\":\"" + a.timestamp() + "\"}";
        bus.tryEmitNext(json);
    }
}
