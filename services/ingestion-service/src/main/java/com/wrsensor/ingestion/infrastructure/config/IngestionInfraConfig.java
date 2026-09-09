package com.wrsensor.ingestion.infrastructure.config;

import com.wrsensor.ingestion.application.port.AlertaEventoPublisher;
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
    IngestorLecturas ingestorLecturas(SensorConfigPort sensores, LecturaStore store,
                                      AlertaEventoPublisher alertas, IngestionProperties props) {
        return new IngestorLecturas(sensores, store, alertas, props);
    }
}
