package com.wrsensor.queryapi.infrastructure.realtime;

import com.wrsensor.queryapi.domain.LecturaConsulta;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bus por sensor para el WS tiempo real (FEAT-0013 BR-005). */
@Component
public class LecturaRealtimeBus {

    private final Map<UUID, Sinks.Many<String>> canales = new ConcurrentHashMap<>();

    public Sinks.Many<String> canal(UUID sensorId) {
        return canales.computeIfAbsent(sensorId, k -> Sinks.many().multicast().directBestEffort());
    }

    public void publicar(LecturaConsulta l) {
        Sinks.Many<String> canal = canales.get(l.sensorId());
        if (canal != null) {
            // FIX-0006 BR-009: se agrega `calidad` cuando el evento la trae; el payload de un
            // evento legado queda exactamente igual que antes.
            String json = "{\"sensorId\":\"" + l.sensorId()
                    + "\",\"timestamp\":\"" + l.timestamp()
                    + "\",\"valor\":" + l.valor().toPlainString()
                    + ",\"unidadMedida\":\"" + l.unidadMedida() + "\""
                    + (l.calidad() == null ? "" : ",\"calidad\":\"" + l.calidad() + "\"")
                    + "}";
            canal.tryEmitNext(json);
        }
    }
}
