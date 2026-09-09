package com.wrsensor.queryapi.infrastructure.adapter.in.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.Map;

/** Mapea WS /ws/sensores/* al handler de tiempo real. */
@Configuration
public class QueryWsConfig {

    @Bean
    HandlerMapping wsHandlerMapping(WsLecturasSensorHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/sensores/*", handler));
        mapping.setOrder(-1);
        return mapping;
    }
}
