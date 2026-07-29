package com.rriveros.origination.domain.service;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.in.FindCreditApplicationUseCase;
import com.rriveros.origination.domain.port.in.SignApplicationUseCase;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import com.rriveros.origination.domain.port.out.OriginationProcessGateway;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditApplicationQueryService implements FindCreditApplicationUseCase, SignApplicationUseCase {

    private final CreditApplicationRepository repository;
    private final OriginationProcessGateway processGateway;

    public CreditApplicationQueryService(CreditApplicationRepository repository,
                                         OriginationProcessGateway processGateway) {
        this.repository = repository;
        this.processGateway = processGateway;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditApplication> findById(String applicationId) {
        return repository.findById(applicationId);
    }

    /**
     * Valida que la solicitud exista antes de publicar el mensaje. Sin esta guarda, un id
     * inexistente publica un mensaje que nunca correlaciona y no falla nada: el error aparece
     * recien cuando alguien nota que el proceso no avanzo.
     */
    @Override
    @Transactional(readOnly = true)
    public void sign(String applicationId) {
        CreditApplication application = repository.getById(applicationId);
        processGateway.signalSignatureCompleted(application.id());
    }
}
