package com.wrsensor.sensorregistry.infrastructure.adapter.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

/** Configuracion R2DBC (reactiva). Sin JPA, sin JDBC. */
@Configuration
@EnableR2dbcAuditing
public class R2dbcConfig {
}
