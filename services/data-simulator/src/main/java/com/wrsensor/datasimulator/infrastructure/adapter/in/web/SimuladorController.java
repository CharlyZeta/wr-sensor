package com.wrsensor.datasimulator.infrastructure.adapter.in.web;

import com.wrsensor.datasimulator.application.service.SimuladorService;
import com.wrsensor.datasimulator.application.service.SimuladorService.SimuladorEstado;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Adapter in (web): control de data-simulator (FEAT-0010). Sin auth en v1
 * (decision HO-Gate: herramienta dev de red interna).
 */
@RestController
@RequestMapping("/api/simulador")
public class SimuladorController {

    private final SimuladorService simulador;

    public SimuladorController(SimuladorService simulador) {
        this.simulador = simulador;
    }

    @PostMapping("/iniciar")
    public EstadoResponse iniciar() {
        return toResponse(simulador.iniciar());
    }

    @PostMapping("/detener")
    public EstadoResponse detener() {
        return toResponse(simulador.detener());
    }

    @PostMapping("/{sensorId}/anomalia")
    public EstadoResponse anomalia(@PathVariable String sensorId) {
        return toResponse(simulador.inyectarAnomalia(sensorId));
    }

    @GetMapping("/estado")
    public EstadoResponse estado() {
        return toResponse(simulador.estado());
    }

    private static EstadoResponse toResponse(SimuladorEstado e) {
        return new EstadoResponse(e.running() ? "RUNNING" : "STOPPED", e.sensores(), e.lecturasPublicadas());
    }

    /** Body de los endpoints de control: {estado, sensores, lecturasPublicadas}. */
    public record EstadoResponse(String estado, List<String> sensores, long lecturasPublicadas) {}
}
