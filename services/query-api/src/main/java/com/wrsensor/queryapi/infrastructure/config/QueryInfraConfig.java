package com.wrsensor.queryapi.infrastructure.config;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

/** Wiring reactivo de query-api. */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(RegistryProperties.class)
public class QueryInfraConfig {

    private static ConnectionFactory cf(String host, int port, String user, String password) {
        ConnectionFactory c = new ConnectionFactory();
        c.setHost(host);
        c.setPort(port);
        c.setUsername(user);
        c.setPassword(password);
        return c;
    }

    @Bean
    Sender sender(@Value("${spring.rabbitmq.host:localhost}") String host,
                  @Value("${spring.rabbitmq.port:5672}") int port,
                  @Value("${spring.rabbitmq.username:guest}") String user,
                  @Value("${spring.rabbitmq.password:guest}") String password) {
        return RabbitFlux.createSender(new SenderOptions().connectionFactory(cf(host, port, user, password)));
    }

    @Bean
    Receiver receiver(@Value("${spring.rabbitmq.host:localhost}") String host,
                      @Value("${spring.rabbitmq.port:5672}") int port,
                      @Value("${spring.rabbitmq.username:guest}") String user,
                      @Value("${spring.rabbitmq.password:guest}") String password) {
        return RabbitFlux.createReceiver(new ReceiverOptions().connectionFactory(cf(host, port, user, password)));
    }

    @Bean
    com.wrsensor.queryapi.application.service.QueryService queryService(
            com.wrsensor.queryapi.application.port.LecturasPort lecturasPort) {
        return new com.wrsensor.queryapi.application.service.QueryService(lecturasPort);
    }

    /** Reloj del servicio: cache de token del registry (inyectable en tests). */
    @Bean
    java.time.Clock reloj() {
        return java.time.Clock.systemUTC();
    }

    /**
     * Cliente REST de metadata del registry (FEAT-0008 BR-007), con su propio {@code JsonMapper}
     * (misma configuración que el consumidor de Rabbit de este servicio).
     */
    @Bean
    com.wrsensor.queryapi.application.port.SensoresMetadataPort sensoresMetadataPort(
            RegistryProperties props, java.time.Clock reloj) {
        return new com.wrsensor.queryapi.infrastructure.adapter.out.registry.RegistrySensoresAdapter(
                props, reloj);
    }

    /** Caso de uso del resumen del mapa (FEAT-0008 BR-005/BR-006). */
    @Bean
    com.wrsensor.queryapi.application.service.ResumenService resumenService(
            com.wrsensor.queryapi.application.port.SensoresMetadataPort metadata,
            com.wrsensor.queryapi.application.port.UltimasLecturasPort ultimas) {
        return new com.wrsensor.queryapi.application.service.ResumenService(metadata, ultimas);
    }
}
