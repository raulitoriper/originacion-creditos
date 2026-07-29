package com.rriveros.origination.domain.port.in;

import java.math.BigDecimal;

/** Puerto de entrada: consulta al bureau y calculo de los insumos de la politica de riesgo. */
public interface ScreenApplicantUseCase {

    ScreeningResult screen(String applicationId);

    /**
     * Insumos que consume la tabla DMN.
     *
     * <p>Los nombres de los componentes son exactamente los nombres de las variables de proceso y
     * de las {@code inputExpression} del DMN. Ese acoplamiento es real: dejarlo explicito en un
     * record es mejor que esconderlo en un {@code Map} anonimo.
     */
    record ScreeningResult(int bureauScore,
                           boolean hasActiveDefaults,
                           BigDecimal installmentToIncomeRatio) {
    }
}
