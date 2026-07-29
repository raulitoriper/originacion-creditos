package com.rriveros.origination.domain.service;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.in.SubmitCreditApplicationUseCase;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import com.rriveros.origination.domain.port.out.OriginationProcessGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitCreditApplicationService implements SubmitCreditApplicationUseCase {

    private final CreditApplicationRepository repository;
    private final OriginationProcessGateway processGateway;

    public SubmitCreditApplicationService(CreditApplicationRepository repository,
                                          OriginationProcessGateway processGateway) {
        this.repository = repository;
        this.processGateway = processGateway;
    }

    /**
     * Guarda la solicitud y arranca la instancia de proceso.
     *
     * <p>Ojo con esto: hay una doble escritura (base + Zeebe) dentro de la misma transaccion. Si el
     * commit falla despues de crear la instancia, queda un proceso huerfano. La respuesta correcta
     * en produccion es un outbox transaccional que publique el arranque despues del commit. Se deja
     * asi a proposito, documentado, para no esconder el problema detras de codigo que parece
     * atomico y no lo es.
     */
    @Override
    @Transactional
    public String submit(SubmitCommand command) {
        CreditApplication application = CreditApplication.submit(
                command.applicantDocument(),
                command.applicantName(),
                command.monthlyIncome(),
                command.requestedAmount(),
                command.termMonths());

        repository.save(application);

        long processInstanceKey = processGateway.startOrigination(application);
        application.attachProcessInstance(processInstanceKey);
        repository.save(application);

        return application.id();
    }
}
