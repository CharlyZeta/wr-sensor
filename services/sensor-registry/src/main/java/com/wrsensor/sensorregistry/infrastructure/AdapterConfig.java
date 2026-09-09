package com.wrsensor.sensorregistry.infrastructure;

import com.wrsensor.sensorregistry.application.port.in.CreateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.in.DeactivateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.in.GetSensorDetailUseCase;
import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase;
import com.wrsensor.sensorregistry.application.port.in.LoginUseCase;
import com.wrsensor.sensorregistry.application.port.in.UpdateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.ContainsSensorWithCodePort;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.FindUserByEmailPort;
import com.wrsensor.sensorregistry.application.port.out.ListSensorsPort;
import com.wrsensor.sensorregistry.application.port.out.PasswordVerifier;
import com.wrsensor.sensorregistry.application.port.out.PublishSensorCreatedPort;
import com.wrsensor.sensorregistry.application.port.out.SaveSensorPort;
import com.wrsensor.sensorregistry.application.port.out.TokenIssuer;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.application.service.CreateSensorService;
import com.wrsensor.sensorregistry.application.service.DeactivateSensorService;
import com.wrsensor.sensorregistry.application.service.GetSensorDetailService;
import com.wrsensor.sensorregistry.application.service.ListSensorsService;
import com.wrsensor.sensorregistry.application.service.LoginService;
import com.wrsensor.sensorregistry.application.service.UpdateSensorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring de beans: instancia el caso de uso con sus ports. Mantiene la capa de
 * application libre de anotaciones Spring (la unica dependencia Spring aqui es
 * de infraestructura).
 */
@Configuration
public class AdapterConfig {

    @Bean
    CreateSensorUseCase createSensorUseCase(ContainsSensorWithCodePort containsPort,
                                             SaveSensorPort savePort,
                                             PublishSensorCreatedPort publishPort) {
        return new CreateSensorService(containsPort, savePort, publishPort);
    }

    @Bean
    ListSensorsUseCase listSensorsUseCase(ListSensorsPort listPort) {
        return new ListSensorsService(listPort);
    }

    @Bean
    GetSensorDetailUseCase getSensorDetailUseCase(FindSensorByIdPort findPort) {
        return new GetSensorDetailService(findPort);
    }

    @Bean
    LoginUseCase loginUseCase(FindUserByEmailPort findUserPort,
                              PasswordVerifier passwordVerifier,
                              TokenIssuer tokenIssuer) {
        return new LoginService(findUserPort, passwordVerifier, tokenIssuer);
    }

    @Bean
    UpdateSensorUseCase updateSensorUseCase(FindSensorByIdPort findPort, UpdateSensorPort updatePort) {
        return new UpdateSensorService(findPort, updatePort);
    }

    @Bean
    DeactivateSensorUseCase deactivateSensorUseCase(FindSensorByIdPort findPort, UpdateSensorPort updatePort) {
        return new DeactivateSensorService(findPort, updatePort);
    }
}
