package com.wrsensor.sensorregistry.infrastructure.adapter.out.persistence;

import com.wrsensor.sensorregistry.application.port.out.FindUserByEmailPort;
import com.wrsensor.sensorregistry.domain.model.Rol;
import com.wrsensor.sensorregistry.domain.model.Usuario;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Adapter out (persistence R2DBC) para {@code usuario} (FEAT-0006). Misma tabla
 * Postgres de metadata (schema.sql). El email se busca ya normalizado a minusculas
 * (BR-002: el write seed tambien normaliza).
 */
@Component
public class UsuarioPersistenceAdapter implements FindUserByEmailPort {

    private static final String FIND_BY_EMAIL = """
            SELECT id, email, password_hash, rol
            FROM usuario
            WHERE email = $1
            LIMIT 1
            """;

    private final DatabaseClient db;

    public UsuarioPersistenceAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Usuario> findByEmail(String email) {
        return db.sql(FIND_BY_EMAIL)
                .bind(0, email)
                .map((row, meta) -> mapRow(row))
                .one();
    }

    private static Usuario mapRow(Row row) {
        Object id = row.get("id");
        UUID uuid = id instanceof UUID u ? u : UUID.fromString(String.valueOf(id));
        return new Usuario(
                uuid,
                row.get("email", String.class),
                row.get("password_hash", String.class),
                Rol.valueOf(row.get("rol", String.class)));
    }
}
