package com.rriveros.origination.adapter.out.bureau;

import com.rriveros.origination.domain.model.BureauReport;
import com.rriveros.origination.domain.port.out.CreditBureauGateway;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Bureau simulado. Reemplazable por un {@code RestClient} contra el proveedor real sin tocar una
 * linea de dominio: es justo para eso que existe el puerto.
 *
 * <p>El score se deriva del documento de forma determinista, asi la demo es reproducible. Los
 * ultimos tres digitos mandan:
 *
 * <ul>
 *   <li>{@code 000-199} -> score bajo, con mora vigente -> rechazo
 *   <li>{@code 200-599} -> zona gris -> revision manual
 *   <li>{@code 600-999} -> score alto -> aprobacion automatica
 * </ul>
 */
@Component
public class SimulatedCreditBureauAdapter implements CreditBureauGateway {

    @Override
    public BureauReport fetchReport(String applicantDocument) {
        int seed = lastThreeDigits(applicantDocument);

        if (seed < 200) {
            return new BureauReport(320 + seed / 4, true, new BigDecimal("18500000"));
        }
        if (seed < 600) {
            return new BureauReport(560 + (seed - 200) / 5, false, new BigDecimal("7200000"));
        }
        int score = Math.min(1000, 760 + (seed - 600) / 5);
        return new BureauReport(score, false, new BigDecimal("2500000"));
    }

    private int lastThreeDigits(String document) {
        StringBuilder digits = new StringBuilder();
        for (int i = document.length() - 1; i >= 0 && digits.length() < 3; i--) {
            char c = document.charAt(i);
            if (Character.isDigit(c)) {
                digits.insert(0, c);
            }
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }
}
