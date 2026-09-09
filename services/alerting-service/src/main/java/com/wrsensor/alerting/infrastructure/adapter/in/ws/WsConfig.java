package com.wrsensor.alerting.infrastructure.adapter.in.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.Map;

/** Mapea WS /ws/alertas al handler. */
@Configuration
public class WsConfig {

    @Bean
    HandlerMapping wsHandlerMapping(WsAlertasHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/alertas", handler));
        mapping.setOrder(-1);
        return mapping;
    }
}
