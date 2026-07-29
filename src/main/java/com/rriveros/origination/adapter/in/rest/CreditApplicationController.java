package com.rriveros.origination.adapter.in.rest;

import com.rriveros.origination.domain.model.ApplicationStatus;
import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.model.RiskDecision;
import com.rriveros.origination.domain.port.in.FindCreditApplicationUseCase;
import com.rriveros.origination.domain.port.in.SignApplicationUseCase;
import com.rriveros.origination.domain.port.in.SubmitCreditApplicationUseCase;
import com.rriveros.origination.domain.port.in.SubmitCreditApplicationUseCase.SubmitCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit-applications")
public class CreditApplicationController {

    private final SubmitCreditApplicationUseCase submitApplication;
    private final SignApplicationUseCase signApplication;
    private final FindCreditApplicationUseCase findApplication;

    public CreditApplicationController(SubmitCreditApplicationUseCase submitApplication,
                                       SignApplicationUseCase signApplication,
                                       FindCreditApplicationUseCase findApplication) {
        this.submitApplication = submitApplication;
        this.signApplication = signApplication;
        this.findApplication = findApplication;
    }

    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody SubmitRequest request) {
        String applicationId = submitApplication.submit(new SubmitCommand(
                request.applicantDocument(),
                request.applicantName(),
                request.monthlyIncome(),
                request.requestedAmount(),
                request.termMonths()));

        return ResponseEntity.created(URI.create("/api/credit-applications/" + applicationId)).build();
    }

    /** Simula el callback del proveedor de firma digital. */
    @PostMapping("/{applicationId}/signature")
    public ResponseEntity<Void> sign(@PathVariable String applicationId) {
        signApplication.sign(applicationId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationView> get(@PathVariable String applicationId) {
        return findApplication.findById(applicationId)
                .map(ApplicationView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SubmitRequest(
            @NotBlank String applicantDocument,
            @NotBlank String applicantName,
            @NotNull @DecimalMin("1") BigDecimal monthlyIncome,
            @NotNull @DecimalMin("1") BigDecimal requestedAmount,
            @Min(3) @Max(120) int termMonths) {
    }

    public record ApplicationView(
            String id,
            String applicantName,
            BigDecimal requestedAmount,
            BigDecimal monthlyInstallment,
            ApplicationStatus status,
            Integer bureauScore,
            RiskDecision riskDecision,
            String reservationId,
            String disbursementId,
            String resolutionReason,
            Long processInstanceKey) {

        static ApplicationView from(CreditApplication application) {
            return new ApplicationView(
                    application.id(),
                    application.applicantName(),
                    application.requestedAmount(),
                    application.monthlyInstallment(),
                    application.status(),
                    application.bureauScore(),
                    application.riskDecision(),
                    application.reservationId(),
                    application.disbursementId(),
                    application.resolutionReason(),
                    application.processInstanceKey());
        }
    }
}
