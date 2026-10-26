package com.wrsensor.sensorregistry.infrastructure.seed;

import com.wrsensor.sensorregistry.domain.model.Rol;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Seed de usuarios dev (decision HO-Gate FEAT-0006): no hay alta de usuarios en
 * v1, asi que al arrancar se aseguran 2 cuentas si no existen (upsert por email,
 * BR-002 email unico). FEAT-0019: la password dev es el placeholder {@code localdev}
 * (mismo valor que docker-compose y los application.yml de los servicios); NO usar
 * en entornos reales. El hash BCrypt se genera en runtime (BR-001: nunca password
 * en claro en la base).
 */
@Component
public class DevUserSeeder implements ApplicationRunner {

    public static final String EMAIL_ADMIN = "admin@wrsensor.local";
    public static final String DEV_PASSWORD_ADMIN = "localdev";
    public static final String EMAIL_VIEWER = "viewer@wrsensor.local";
    public static final String DEV_PASSWORD_VIEWER = "localdev";

    private static final String UPSERT = """
            INSERT INTO usuario (id, email, password_hash, rol)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (email) DO NOTHING
            """;

    private final DatabaseClient db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DevUserSeeder(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Arranque del contexto (hilo main, no reactor): block() aceptable para seed.
        db.sql(UPSERT)
                .bind(0, UUID.randomUUID())
                .bind(1, EMAIL_ADMIN)
                .bind(2, encoder.encode(DEV_PASSWORD_ADMIN))
                .bind(3, Rol.ADMIN.name())
                .fetch().rowsUpdated()
                .then(db.sql(UPSERT)
                        .bind(0, UUID.randomUUID())
                        .bind(1, EMAIL_VIEWER)
                        .bind(2, encoder.encode(DEV_PASSWORD_VIEWER))
                        .bind(3, Rol.VIEWER.name())
                        .fetch().rowsUpdated())
                .then()
                .block();
    }
}
