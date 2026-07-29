package com.rriveros.origination.domain.port.out;

import com.rriveros.origination.domain.model.CreditApplication;

/** Puerto de salida: avisos al solicitante y al equipo de riesgo. */
public interface NotificationGateway {

    void notifyApproval(CreditApplication application);

    void notifyRejection(CreditApplication application);

    void notifySupervisor(CreditApplication application);
}
