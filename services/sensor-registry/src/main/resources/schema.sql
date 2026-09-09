CREATE TABLE IF NOT EXISTS sensor (
    id                              UUID            PRIMARY KEY,
    codigo                          VARCHAR(64)     NOT NULL UNIQUE,
    nombre                          VARCHAR(255)    NOT NULL,
    tipo                            VARCHAR(32)     NOT NULL,
    latitud                         DOUBLE PRECISION NOT NULL,
    longitud                        DOUBLE PRECISION NOT NULL,
    unidad_medida                   VARCHAR(32)     NOT NULL,
    estado                          VARCHAR(32)     NOT NULL,
    histeresis                      NUMERIC(12,4)   NOT NULL,
    frecuencia_reporte_segundos     INTEGER         NOT NULL,
    fecha_instalacion               TIMESTAMP       NOT NULL,
    rango_normal_min                NUMERIC(12,4)   NOT NULL,
    rango_normal_max                NUMERIC(12,4)   NOT NULL,
    rango_warning_min               NUMERIC(12,4)   NOT NULL,
    rango_warning_max               NUMERIC(12,4)   NOT NULL,
    rango_critical_min              NUMERIC(12,4)   NOT NULL,
    rango_critical_max              NUMERIC(12,4)   NOT NULL
);

CREATE TABLE IF NOT EXISTS usuario (
    id              UUID            PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(100)    NOT NULL,
    rol             VARCHAR(32)     NOT NULL
);
