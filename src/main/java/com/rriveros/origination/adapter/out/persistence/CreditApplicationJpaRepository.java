package com.rriveros.origination.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de Spring Data. Detalle de infraestructura: es {@code package-private} a proposito. */
interface CreditApplicationJpaRepository extends JpaRepository<CreditApplicationJpaEntity, String> {
}
