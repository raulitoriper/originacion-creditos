package com.rriveros.origination.domain.port.in;

import java.math.BigDecimal;

/** Puerto de entrada: alta de una solicitud y arranque de la originacion. */
public interface SubmitCreditApplicationUseCase {

    /** @return el id de la solicitud creada */
    String submit(SubmitCommand command);

    record SubmitCommand(String applicantDocument,
                         String applicantName,
                         BigDecimal monthlyIncome,
                         BigDecimal requestedAmount,
                         int termMonths) {
    }
}
