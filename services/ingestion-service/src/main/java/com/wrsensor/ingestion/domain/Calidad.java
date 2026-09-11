package com.wrsensor.ingestion.domain;

/** Calidad del dato (FIX-0004): una lectura fuera de rango fisico no participa de severidad. */
public enum Calidad {
    OK,
    ERROR_SENSOR
}
