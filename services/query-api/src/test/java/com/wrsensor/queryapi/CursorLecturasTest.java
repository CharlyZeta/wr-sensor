package com.wrsensor.queryapi;

import com.wrsensor.queryapi.domain.CursorLecturas;
import com.wrsensor.queryapi.domain.QueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit — FEAT-0013 BR-001: cursor opaco de keyset. */
class CursorLecturasTest {

    @Test
    @DisplayName("BR-001: roundtrip encode/decode del ts")
    void roundtrip() {
        Instant ts = Instant.parse("2026-09-09T12:00:00Z");
        String cursor = CursorLecturas.encode(ts);
        assertThat(CursorLecturas.decode(cursor)).isEqualTo(ts);
        assertThat(cursor).doesNotContain("2026");
    }

    @Test
    @DisplayName("BR-001 / AF-04: cursor malformado → SENSOR_INVALID_CURSOR")
    void malformado() {
        assertThatThrownBy(() -> CursorLecturas.decode("no-es-un-cursor"))
                .isInstanceOf(QueryException.class)
                .extracting(e -> ((QueryException) e).code)
                .isEqualTo(QueryException.SENSOR_INVALID_CURSOR);
        assertThatThrownBy(() -> CursorLecturas.decode("a.b"))
                .isInstanceOf(QueryException.class);
    }
}
