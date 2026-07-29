package com.rriveros.origination.domain.port.in;

import com.rriveros.origination.domain.model.CreditApplication;
import java.util.Optional;

/** Puerto de entrada de lectura. */
public interface FindCreditApplicationUseCase {

    Optional<CreditApplication> findById(String applicationId);
}
