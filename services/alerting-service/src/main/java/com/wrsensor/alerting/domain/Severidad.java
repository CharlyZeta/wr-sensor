package com.wrsensor.alerting.domain;

/** Severidad (misma semantica que FEAT-0011; orden NORMAL < WARNING < CRITICAL). */
public enum Severidad {
    NORMAL,
    WARNING,
    CRITICAL;

    public boolean esMasSeveraQue(Severidad otra) {
        return this.ordinal() > otra.ordinal();
    }
}
