package com.rriveros.origination.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests de dominio del agregado {@link CreditApplication}. Sin Spring, sin JPA y sin Camunda: solo
 * JUnit 5 y AssertJ, igual que el agregado que verifican. Los fixtures de estado se arman
 * encadenando transiciones reales, nunca con el constructor de rehidratacion.
 */
class CreditApplicationTest {

    private static final String DOCUMENT = "4501234";
    private static final String NAME = "Solicitante de prueba";
    private static final BureauReport VALID_REPORT = new BureauReport(700, false, new BigDecimal("500000"));

    @Nested
    @DisplayName("submit()")
    class Submit {

        @Test
        void crea_la_solicitud_en_estado_submitted_con_datos_validos() {
            CreditApplication application = submit(new BigDecimal("12000000"), new BigDecimal("30000000"), 24);

            assertThat(application.status()).isEqualTo(ApplicationStatus.SUBMITTED);
            assertThat(application.applicantDocument()).isEqualTo(DOCUMENT);
        }

        @Test
        void rechaza_ingreso_mensual_no_positivo() {
            assertThatThrownBy(() -> submit(new BigDecimal("0"), new BigDecimal("30000000"), 24))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rechaza_monto_solicitado_no_positivo() {
            assertThatThrownBy(() -> submit(new BigDecimal("12000000"), new BigDecimal("-1"), 24))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rechaza_plazo_fuera_de_rango() {
            assertThatThrownBy(() -> submit(new BigDecimal("12000000"), new BigDecimal("30000000"), 121))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("calculos financieros")
    class Calculos {

        @Test
        void monthlyInstallment_redondea_hacia_arriba_con_half_up() {
            // 100 / 7 = 14.285714... -> escala 2 HALF_UP redondea el tercer decimal (5) hacia arriba
            CreditApplication application = submit(new BigDecimal("12000000"), new BigDecimal("100"), 7);

            assertThat(application.monthlyInstallment()).isEqualByComparingTo("14.29");
        }

        @Test
        void installmentToIncomeRatio_redondea_en_el_quinto_decimal() {
            // cuota 6/3 = 2.00; ratio 2.00/3 = 0.6666... -> escala 4 HALF_UP redondea el quinto decimal
            CreditApplication application = submit(new BigDecimal("3"), new BigDecimal("6"), 3);

            assertThat(application.installmentToIncomeRatio()).isEqualByComparingTo("0.6667");
        }
    }

    @Nested
    @DisplayName("recordScreening()")
    class RecordScreening {

        @Test
        void transiciona_a_screened_y_registra_el_reporte_del_bureau() {
            CreditApplication application = submitted();

            application.recordScreening(VALID_REPORT);

            assertThat(application.status()).isEqualTo(ApplicationStatus.SCREENED);
            assertThat(application.bureauScore()).isEqualTo(700);
            assertThat(application.hasActiveDefaults()).isFalse();
        }
    }

    @Nested
    @DisplayName("recordRiskDecision()")
    class RecordRiskDecision {

        @Test
        void registra_la_decision_sin_cambiar_el_estado() {
            CreditApplication application = screened();

            application.recordRiskDecision(RiskDecision.APPROVED);

            assertThat(application.riskDecision()).isEqualTo(RiskDecision.APPROVED);
            assertThat(application.status()).isEqualTo(ApplicationStatus.SCREENED);
        }

        @Test
        void rechaza_una_decision_nula() {
            CreditApplication application = screened();

            assertThatThrownBy(() -> application.recordRiskDecision(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("escalateReview()")
    class EscalateReview {

        @Test
        void transiciona_a_pending_review() {
            CreditApplication application = screened();

            application.escalateReview();

            assertThat(application.status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        }
    }

    @Nested
    @DisplayName("reserveFunds()")
    class ReserveFunds {

        @Test
        void transiciona_a_funds_reserved_y_registra_el_id_de_reserva() {
            CreditApplication application = pendingReview();

            application.reserveFunds("reserva-1");

            assertThat(application.status()).isEqualTo(ApplicationStatus.FUNDS_RESERVED);
            assertThat(application.reservationId()).isEqualTo("reserva-1");
        }
    }

    @Nested
    @DisplayName("markDisbursed()")
    class MarkDisbursed {

        @Test
        void transiciona_a_disbursed() {
            CreditApplication application = fundsReserved();

            application.markDisbursed("desembolso-1");

            assertThat(application.status()).isEqualTo(ApplicationStatus.DISBURSED);
            assertThat(application.disbursementId()).isEqualTo("desembolso-1");
        }
    }

    @Nested
    @DisplayName("releaseFunds()")
    class ReleaseFunds {

        @Test
        void libera_la_reserva_y_anula_los_identificadores() {
            CreditApplication application = fundsReserved();

            application.releaseFunds("compensacion");

            assertThat(application.status()).isEqualTo(ApplicationStatus.REVERTED);
            assertThat(application.reservationId()).isNull();
            assertThat(application.disbursementId()).isNull();
        }

        @Test
        void es_idempotente_cuando_ya_esta_reverted() {
            CreditApplication application = fundsReserved();
            application.releaseFunds("primera liberacion");

            application.releaseFunds("segunda liberacion");

            assertThat(application.status()).isEqualTo(ApplicationStatus.REVERTED);
        }
    }

    @Nested
    @DisplayName("reject()")
    class Reject {

        @Test
        void transiciona_a_rejected_y_registra_el_motivo() {
            CreditApplication application = submitted();

            application.reject("Score insuficiente");

            assertThat(application.status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(application.resolutionReason()).isEqualTo("Score insuficiente");
        }

        @Test
        void es_idempotente_cuando_ya_esta_rejected() {
            CreditApplication application = rejected();

            application.reject("motivo distinto pero no vacio");

            assertThat(application.status()).isEqualTo(ApplicationStatus.REJECTED);
        }

        @Test
        void rechaza_un_motivo_vacio() {
            CreditApplication application = submitted();

            assertThatThrownBy(() -> application.reject(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(application.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        }

        @Test
        void rechaza_un_motivo_en_blanco() {
            CreditApplication application = submitted();

            assertThatThrownBy(() -> application.reject("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(application.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        }

        @Test
        void rechaza_un_motivo_nulo() {
            CreditApplication application = submitted();

            assertThatThrownBy(() -> application.reject(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(application.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        }

        @Test
        void la_guarda_de_motivo_es_total_incluso_en_estado_rejected() {
            // Consecuencia deliberada del diseno: la guarda precede al corto-circuito de idempotencia.
            CreditApplication application = rejected();

            assertThatThrownBy(() -> application.reject(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("transiciones ilegales")
    class IllegalTransitions {

        @ParameterizedTest(name = "{0} no permite {1}")
        @MethodSource("illegalTransitions")
        void lanza_invalid_application_state_exception(
                ApplicationStatus status, String operation, Consumer<CreditApplication> action) {
            CreditApplication application = applicationIn(status);

            assertThatThrownBy(() -> action.accept(application))
                    .isInstanceOf(InvalidApplicationStateException.class);
        }

        private static Stream<Arguments> illegalTransitions() {
            Consumer<CreditApplication> recordScreening = a -> a.recordScreening(VALID_REPORT);
            Consumer<CreditApplication> recordRiskDecision = a -> a.recordRiskDecision(RiskDecision.APPROVED);
            Consumer<CreditApplication> escalateReview = CreditApplication::escalateReview;
            Consumer<CreditApplication> reserveFunds = a -> a.reserveFunds("reserva-x");
            Consumer<CreditApplication> markDisbursed = a -> a.markDisbursed("desembolso-x");
            Consumer<CreditApplication> releaseFunds = a -> a.releaseFunds("motivo-x");
            Consumer<CreditApplication> reject = a -> a.reject("motivo-x");

            return Stream.of(
                    // recordScreening: solo permitido desde SUBMITTED
                    illegalCase(ApplicationStatus.SCREENED, "recordScreening", recordScreening),
                    illegalCase(ApplicationStatus.PENDING_REVIEW, "recordScreening", recordScreening),
                    illegalCase(ApplicationStatus.FUNDS_RESERVED, "recordScreening", recordScreening),
                    illegalCase(ApplicationStatus.DISBURSED, "recordScreening", recordScreening),
                    illegalCase(ApplicationStatus.REJECTED, "recordScreening", recordScreening),
                    illegalCase(ApplicationStatus.REVERTED, "recordScreening", recordScreening),
                    // recordRiskDecision: solo permitido desde SCREENED, PENDING_REVIEW
                    illegalCase(ApplicationStatus.SUBMITTED, "recordRiskDecision", recordRiskDecision),
                    illegalCase(ApplicationStatus.FUNDS_RESERVED, "recordRiskDecision", recordRiskDecision),
                    illegalCase(ApplicationStatus.DISBURSED, "recordRiskDecision", recordRiskDecision),
                    illegalCase(ApplicationStatus.REJECTED, "recordRiskDecision", recordRiskDecision),
                    illegalCase(ApplicationStatus.REVERTED, "recordRiskDecision", recordRiskDecision),
                    // escalateReview: solo permitido desde SCREENED, PENDING_REVIEW
                    illegalCase(ApplicationStatus.SUBMITTED, "escalateReview", escalateReview),
                    illegalCase(ApplicationStatus.FUNDS_RESERVED, "escalateReview", escalateReview),
                    illegalCase(ApplicationStatus.DISBURSED, "escalateReview", escalateReview),
                    illegalCase(ApplicationStatus.REJECTED, "escalateReview", escalateReview),
                    illegalCase(ApplicationStatus.REVERTED, "escalateReview", escalateReview),
                    // reserveFunds: solo permitido desde SCREENED, PENDING_REVIEW
                    illegalCase(ApplicationStatus.SUBMITTED, "reserveFunds", reserveFunds),
                    illegalCase(ApplicationStatus.FUNDS_RESERVED, "reserveFunds", reserveFunds),
                    illegalCase(ApplicationStatus.DISBURSED, "reserveFunds", reserveFunds),
                    illegalCase(ApplicationStatus.REJECTED, "reserveFunds", reserveFunds),
                    illegalCase(ApplicationStatus.REVERTED, "reserveFunds", reserveFunds),
                    // markDisbursed: solo permitido desde FUNDS_RESERVED
                    illegalCase(ApplicationStatus.SUBMITTED, "markDisbursed", markDisbursed),
                    illegalCase(ApplicationStatus.SCREENED, "markDisbursed", markDisbursed),
                    illegalCase(ApplicationStatus.PENDING_REVIEW, "markDisbursed", markDisbursed),
                    illegalCase(ApplicationStatus.DISBURSED, "markDisbursed", markDisbursed),
                    illegalCase(ApplicationStatus.REJECTED, "markDisbursed", markDisbursed),
                    illegalCase(ApplicationStatus.REVERTED, "markDisbursed", markDisbursed),
                    // releaseFunds: permitido desde FUNDS_RESERVED, DISBURSED (REVERTED es no-op, no ilegal)
                    illegalCase(ApplicationStatus.SUBMITTED, "releaseFunds", releaseFunds),
                    illegalCase(ApplicationStatus.SCREENED, "releaseFunds", releaseFunds),
                    illegalCase(ApplicationStatus.PENDING_REVIEW, "releaseFunds", releaseFunds),
                    illegalCase(ApplicationStatus.REJECTED, "releaseFunds", releaseFunds),
                    // reject: permitido desde SUBMITTED, SCREENED, PENDING_REVIEW (REJECTED es no-op, no ilegal)
                    illegalCase(ApplicationStatus.FUNDS_RESERVED, "reject", reject),
                    illegalCase(ApplicationStatus.DISBURSED, "reject", reject),
                    illegalCase(ApplicationStatus.REVERTED, "reject", reject));
        }

        private static Arguments illegalCase(ApplicationStatus status, String operation, Consumer<CreditApplication> action) {
            return Arguments.of(status, operation, action);
        }

        private CreditApplication applicationIn(ApplicationStatus status) {
            return switch (status) {
                case SUBMITTED -> submitted();
                case SCREENED -> screened();
                case PENDING_REVIEW -> pendingReview();
                case FUNDS_RESERVED -> fundsReserved();
                case DISBURSED -> disbursed();
                case REJECTED -> rejected();
                case REVERTED -> reverted();
            };
        }
    }

    // ---------- fixtures: construidos encadenando transiciones reales, sin el constructor de rehidratacion ----------

    private static CreditApplication submit(BigDecimal monthlyIncome, BigDecimal requestedAmount, int termMonths) {
        return CreditApplication.submit(DOCUMENT, NAME, monthlyIncome, requestedAmount, termMonths);
    }

    private static CreditApplication submitted() {
        return submit(new BigDecimal("12000000"), new BigDecimal("30000000"), 24);
    }

    private static CreditApplication screened() {
        CreditApplication application = submitted();
        application.recordScreening(VALID_REPORT);
        return application;
    }

    private static CreditApplication pendingReview() {
        CreditApplication application = screened();
        application.escalateReview();
        return application;
    }

    private static CreditApplication fundsReserved() {
        CreditApplication application = pendingReview();
        application.reserveFunds("reserva-fixture");
        return application;
    }

    private static CreditApplication disbursed() {
        CreditApplication application = fundsReserved();
        application.markDisbursed("desembolso-fixture");
        return application;
    }

    private static CreditApplication rejected() {
        CreditApplication application = submitted();
        application.reject("motivo de fixture");
        return application;
    }

    private static CreditApplication reverted() {
        CreditApplication application = fundsReserved();
        application.releaseFunds("liberacion de fixture");
        return application;
    }
}
