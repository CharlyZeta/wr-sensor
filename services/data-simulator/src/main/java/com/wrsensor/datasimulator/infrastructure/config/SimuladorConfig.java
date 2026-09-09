package com.wrsensor.datasimulator.infrastructure.config;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;

/**
 * Wiring: Sender de Reactor RabbitMQ (reactivo, sin RabbitTemplate) +
 * SimuladorService. Configuracion de mensajeria desde application.yml/env.
 */
@Configuration
@EnableConfigurationProperties(SimuladorProperties.class)
public class SimuladorConfig {

    @Bean
    Sender rabbitSender(@Value("${spring.rabbitmq.host:localhost}") String host,
                        @Value("${spring.rabbitmq.port:5672}") int port,
                        @Value("${spring.rabbitmq.username:guest}") String user,
                        @Value("${spring.rabbitmq.password:guest}") String password) {
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(host);
        cf.setPort(port);
        cf.setUsername(user);
        cf.setPassword(password);
        return RabbitFlux.createSender(new reactor.rabbitmq.SenderOptions().connectionFactory(cf));
    }

    @Bean
    com.wrsensor.datasimulator.application.service.SimuladorService simuladorService(
            com.wrsensor.datasimulator.application.port.out.LecturaPublisher publisher,
            SimuladorProperties props) {
        return new com.wrsensor.datasimulator.application.service.SimuladorService(props, publisher);
    }
}
