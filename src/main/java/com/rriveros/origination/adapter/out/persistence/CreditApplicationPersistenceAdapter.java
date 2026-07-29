package com.rriveros.origination.adapter.out.persistence;

import com.rriveros.origination.domain.model.CreditApplication;
import com.rriveros.origination.domain.port.out.CreditApplicationRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Implementa el puerto de dominio traduciendo entre agregado y entidad JPA. */
@Component
public class CreditApplicationPersistenceAdapter implements CreditApplicationRepository {

    private final CreditApplicationJpaRepository jpaRepository;

    CreditApplicationPersistenceAdapter(CreditApplicationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public CreditApplication save(CreditApplication application) {
        return jpaRepository.save(CreditApplicationJpaEntity.fromDomain(application)).toDomain();
    }

    @Override
    public Optional<CreditApplication> findById(String id) {
        return jpaRepository.findById(id).map(CreditApplicationJpaEntity::toDomain);
    }
}
