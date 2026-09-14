package com.wrsensor.ingestion.infrastructure.config;

import com.wrsensor.ingestion.application.port.LecturaStore;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

/** Wiring reactivo (Sender/Receiver Reactor RabbitMQ + beans de aplicacion). */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionInfraConfig {

    private static ConnectionFactory connectionFactory(String host, int port, String user, String password) {
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(host);
        cf.setPort(port);
        cf.setUsername(user);
        cf.setPassword(password);
        return cf;
    }

    @Bean
    org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder() {
        return org.springframework.web.reactive.function.client.WebClient.builder();
    }

    @Bean
    Sender sender(@Value("${spring.rabbitmq.host:localhost}") String host,
                  @Value("${spring.rabbitmq.port:5672}") int port,
                  @Value("${spring.rabbitmq.username:guest}") String user,
                  @Value("${spring.rabbitmq.password:guest}") String password) {
        return RabbitFlux.createSender(new SenderOptions().connectionFactory(
                connectionFactory(host, port, user, password)));
    }

    @Bean
    Receiver receiver(@Value("${spring.rabbitmq.host:localhost}") String host,
                      @Value("${spring.rabbitmq.port:5672}") int port,
                      @Value("${spring.rabbitmq.username:guest}") String user,
                      @Value("${spring.rabbitmq.password:guest}") String password) {
        return RabbitFlux.createReceiver(new ReceiverOptions().connectionFactory(
                connectionFactory(host, port, user, password)));
    }

    @Bean
    org.springframework.transaction.reactive.TransactionalOperator transactionalOperator(
            io.r2dbc.spi.ConnectionFactory connectionFactory) {
        return org.springframework.transaction.reactive.TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(connectionFactory));
    }

    @Bean
    IngestorLecturas ingestorLecturas(SensorConfigPort sensores,
                                      com.wrsensor.ingestion.application.port.IngestaTransaccionalPort store,
                                      IngestionProperties props) {
        return new IngestorLecturas(sensores, store, props);
    }

    /** FIX-0006: política de versiones del schema (valida y registra evidencia). */
    @Bean
    com.wrsensor.ingestion.application.service.RegistroEsquema registroEsquema(IngestionProperties props) {
        return new com.wrsensor.ingestion.application.service.RegistroEsquema(props.schema());
    }

    // ===== FIX-0007: resiliencia del lookup de config de sensores =====

    /** Reloj inyectable para el circuit breaker y la cache (testeable sin esperar ventanas). */
    @Bean
    public java.time.Clock reloj() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public com.wrsensor.ingestion.domain.CircuitoResiliencia circuitoResiliencia(IngestionProperties props) {
        var c = props.registry().circuito() == null
                ? new IngestionProperties.Registry.Circuito(null, null, null)
                : props.registry().circuito();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
                com.wrsensor.ingestion.domain.CircuitoResiliencia.class);
        return new com.wrsensor.ingestion.domain.CircuitoResiliencia(
                c.fallosParaAbrirOrDefault(),
                java.time.Duration.ofSeconds(c.segundosAbiertoOrDefault()),
                c.exitosParaCerrarOrDefault(),
                transicion -> {
                    if ("ABIERTO".equals(transicion)) {
                        log.warn("[ingestion] circuit breaker del registry ABIERTO");
                    } else {
                        log.info("[ingestion] circuit breaker del registry {}", transicion);
                    }
                });
    }

    @Bean
    public com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores cacheConfigSensores() {
        return new com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores();
    }

    /** FIX-0007 BR-007/BR-010: endpoint interno de estado (sin actuator). */
    @Bean
    public org.springframework.web.reactive.function.server.RouterFunction<
            org.springframework.web.reactive.function.server.ServerResponse> resilienciaRoutes(
            com.wrsensor.ingestion.domain.CircuitoResiliencia circuito,
            com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores cache) {
        return org.springframework.web.reactive.function.server.RouterFunctions.route(
                org.springframework.web.reactive.function.server.RequestPredicates.GET(
                        "/api/ingestion/resiliencia"),
                request -> {
                    String body = "{\"circuito\":\"" + circuito.estado().name() + "\""
                            + ",\"fallosConsecutivos\":" + circuito.fallosConsecutivos()
                            + ",\"llamadas\":" + circuito.llamadas()
                            + ",\"cacheTamano\":" + cache.tamano()
                            + ",\"cacheAciertos\":" + cache.aciertos()
                            + ",\"cacheRefrescos\":" + cache.refrescos()
                            + ",\"cacheVencidasUsadas\":" + cache.vencidasUsadas()
                            + "}";
                    return org.springframework.web.reactive.function.server.ServerResponse.ok()
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .bodyValue(body);
                });
    }
}

