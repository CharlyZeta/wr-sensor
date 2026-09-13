package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.domain.EsquemaLectura;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registro de versiones de schema vistas por el consumer (FIX-0006 BR-006/BR-007/BR-011):
 * contabiliza eventos legados, avisa **una sola vez por versión mayor desconocida** y aplica la
 * política de tolerancia configurada. No conoce RabbitMQ ni la DLQ: sólo decide y registra; el
 * rechazo viaja como {@link RechazoLecturaException} y el consumer lo rutea a la DLQ.
 */
public class RegistroEsquema {

    private static final Logger log = LoggerFactory.getLogger(RegistroEsquema.class);

    private final IngestionProperties.Schema cfg;
    private final Set<Integer> mayoresAvisadas = ConcurrentHashMap.newKeySet();
    private final AtomicLong eventosLegado = new AtomicLong();
    private final AtomicLong eventosVersionDesconocida = new AtomicLong();

    public RegistroEsquema(IngestionProperties.Schema cfg) {
        this.cfg = cfg == null ? new IngestionProperties.Schema(null, null) : cfg;
    }

    /**
     * Valida la versión recibida: registra evidencia y lanza el rechazo cuando corresponde.
     *
     * @throws RechazoLecturaException {@code INVALIDA} siempre; {@code MAYOR_DESCONOCIDA} sólo
     *         con {@code tolerar-versiones-mayores: false} (motivo {@code SCHEMA_UNSUPPORTED})
     */
    public EsquemaLectura.Resolucion validar(String schemaVersion) {
        EsquemaLectura.Resolucion r = EsquemaLectura.resolver(schemaVersion, cfg.versionSoportada());
        switch (r.estado()) {
            case LEGADO -> {
                long total = eventosLegado.incrementAndGet();
                log.info("[ingestion] evento legado (sin schemaVersion) procesado como {}: total={}",
                        EsquemaLectura.VERSION_LEGADO, total);
            }
            case MAYOR_DESCONOCIDA -> {
                eventosVersionDesconocida.incrementAndGet();
                if (!cfg.tolerarMayores()) {
                    throw new RechazoLecturaException(RechazoLecturaException.SCHEMA_UNSUPPORTED,
                            "schemaVersion " + r.version() + " no soportada (tolerancia desactivada; "
                                    + "soportada=" + cfg.versionSoportada() + ")");
                }
                if (mayoresAvisadas.add(r.mayor())) {
                    log.warn("[ingestion] schemaVersion mayor desconocida {} (soportada={}): se procesa "
                                    + "igual por tolerancia hacia adelante; este aviso se emite una sola vez "
                                    + "por versión", r.version(), cfg.versionSoportada());
                }
            }
            case INVALIDA -> throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "schemaVersion invalida: '" + r.version() + "'");
            case SOPORTADA -> { /* nada que registrar */ }
        }
        return r;
    }

    /** Eventos legados vistos (telemetría para decidir el cierre de la ventana de compatibilidad). */
    public long eventosLegado() {
        return eventosLegado.get();
    }

    public long eventosVersionDesconocida() {
        return eventosVersionDesconocida.get();
    }
}
