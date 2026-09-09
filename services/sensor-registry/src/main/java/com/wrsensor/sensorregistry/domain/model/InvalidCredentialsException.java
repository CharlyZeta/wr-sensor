package com.wrsensor.sensorregistry.domain.model;

/** FEAT-0006 BR-002: credenciales invalidas (email inexistente o password incorrecta)
 *  → 401 INVALID_CREDENTIALS, respuesta identica para ambos casos (AF-03, AC-003, AC-004). */
public class InvalidCredentialsException extends SensorException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "credenciales invalidas");
    }
}
