package com.rriveros.origination.domain.port.in;

/**
 * Puerto de entrada: el solicitante firmo digitalmente la oferta.
 *
 * <p>Es un evento externo asincronico. El proceso ya esta detenido en un event-based gateway
 * esperandolo; esta operacion solo lo correlaciona.
 */
public interface SignApplicationUseCase {

    void sign(String applicationId);
}
