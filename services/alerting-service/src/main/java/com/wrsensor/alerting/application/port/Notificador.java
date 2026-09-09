package com.wrsensor.alerting.application.port;

import com.wrsensor.alerting.domain.AlertaConfirmada;

/** Port out: entrega de alerta confirmada (broadcast WS). */
public interface Notificador {

    void notificar(AlertaConfirmada alerta);
}
