package com.rriveros.origination.domain.port.out;

import com.rriveros.origination.domain.model.CreditApplication;

/**
 * Puerto de salida hacia el orquestador.
 *
 * <p>Existe para que el dominio pueda pedir "arranca la originacion" sin importar un solo tipo de
 * Camunda. Cambiar Camunda 8 por otro motor toca una unica clase: el adaptador.
 */
public interface OriginationProcessGateway {

    /** @return la clave de la instancia de proceso creada */
    long startOrigination(CreditApplication application);

    /** Correlaciona la firma digital del solicitante con la instancia en curso. */
    void signalSignatureCompleted(String applicationId);
}
