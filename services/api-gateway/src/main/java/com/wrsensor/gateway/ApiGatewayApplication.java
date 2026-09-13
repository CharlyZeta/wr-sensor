package com.wrsensor.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * api-gateway (FEAT-0007): punto de entrada único del stack WR-Sensor. Enruta REST y WebSocket
 * a los servicios internos, aplica rate limiting configurable por ruta y por IP, garantiza
 * {@code X-Correlation-Id} y devuelve errores de dominio ({@code 429}/{@code 502}/{@code 504}/
 * {@code 404}) sin filtrar detalles internos.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
