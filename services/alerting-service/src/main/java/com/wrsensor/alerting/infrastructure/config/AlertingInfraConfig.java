package com.wrsensor.alerting.infrastructure.config;

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

import java.time.Duration;

/** Wiring reactivo + gestor. */
@Configuration
@EnableConfigurationProperties(AlertingProperties.class)
public class AlertingInfraConfig {

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
    com.wrsensor.alerting.application.service.GestorAlertas gestorAlertas(
            com.wrsensor.alerting.application.port.Notificador notificador, AlertingProperties props) {
        return new com.wrsensor.alerting.application.service.GestorAlertas(
                notificador, Duration.ofSeconds(props.histeresisSegundos()));
    }
}
