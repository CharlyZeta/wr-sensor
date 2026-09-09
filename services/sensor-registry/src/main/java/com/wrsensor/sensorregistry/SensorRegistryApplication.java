package com.wrsensor.sensorregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap de sensor-registry (Spring Boot 4.1 / WebFlux reactivo).
 * Escanea el paquete base `com.wrsensor.sensorregistry` para wired beans.
 */
@SpringBootApplication(scanBasePackages = "com.wrsensor.sensorregistry")
public class SensorRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SensorRegistryApplication.class, args);
    }
}
