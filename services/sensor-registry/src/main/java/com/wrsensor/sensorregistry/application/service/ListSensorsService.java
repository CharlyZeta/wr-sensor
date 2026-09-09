package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase;
import com.wrsensor.sensorregistry.application.port.out.ListSensorsPort;
import com.wrsensor.sensorregistry.domain.model.InvalidListLimitException;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Implementacion del caso de uso "List Sensors" (Main Flow FEAT-0002).
 *
 * <p>Orquesta: valida {@code limit} en [1,1000] (default 100, fuera →
 * {@link InvalidListLimitException}); decodifica {@code cursor} (null → desde el
 * inicio; malformado → {@link com.wrsensor.sensorregistry.domain.model.InvalidListCursorException});
 * pide {@code limit+1} filas al port out para detectar mas paginas (BR-005); si vienen
 * mas de {@code limit}, arma {@code nextCursor} desde la ultima fila devuelta
 * (codificacion de {@code (fechaInstalacion, id)}) y devuelve las primeras {@code limit}.
 *
 * <p>Las validaciones de limit/cursor son lógica de aplicacion (sin Spring, sin Reactor
 * en la validacion pura); el flujo reactivo solo orquesta ports — patron de
 * {@code CreateSensorService}.
 */
public class ListSensorsService implements ListSensorsUseCase {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final ListSensorsPort listPort;

    public ListSensorsService(ListSensorsPort listPort) {
        this.listPort = listPort;
    }

    @Override
    public Mono<SensorPage> list(ListSensorsQuery query) {
        return Mono.defer(() -> {
            int effectiveLimit = validateLimit(query.limit());
            CursorCodec.Cursor cursor = (query.cursor() == null || query.cursor().isBlank())
                    ? new CursorCodec.Cursor(null, null)
                    : CursorCodec.decode(query.cursor());
            int fetchSize = effectiveLimit + 1; // BR-005: limit+1 detecta mas paginas
            return listPort.listAfter(cursor.fecha(), cursor.id(), fetchSize)
                    .collectList()
                    .map(rows -> buildPage(rows, effectiveLimit));
        });
    }

    private static SensorPage buildPage(List<Sensor> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<Sensor> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Sensor last = items.get(items.size() - 1);
            nextCursor = CursorCodec.encode(last.fechaInstalacion(), last.id());
        }
        return new SensorPage(items, nextCursor);
    }

    private static int validateLimit(Integer limit) {
        int l = limit == null ? DEFAULT_LIMIT : limit;
        if (l < 1 || l > MAX_LIMIT) throw new InvalidListLimitException();
        return l;
    }
}
