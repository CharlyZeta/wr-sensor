package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase.SensorPage;

import java.util.List;

/**
 * DTO de response de {@code GET /api/sensores} (Main Flow FEAT-0002).
 * Reusa {@link SensorResponse#from} para mapear cada {@code Sensor} del dominio.
 */
public record SensorListResponse(List<SensorResponse> items, String nextCursor) {

    public static SensorListResponse from(SensorPage page) {
        List<SensorResponse> items = page.items().stream().map(SensorResponse::from).toList();
        return new SensorListResponse(items, page.nextCursor());
    }
}
