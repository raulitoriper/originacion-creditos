package com.rriveros.origination.domain.port.out;

import com.rriveros.origination.domain.model.CreditApplication;
import java.util.Optional;

/** Puerto de salida: persistencia del agregado. Lo implementa el adaptador JPA. */
public interface CreditApplicationRepository {

    CreditApplication save(CreditApplication application);

    Optional<CreditApplication> findById(String id);

    /** Igual a {@link #findById} pero falla en vez de devolver vacio: los workers necesitan el agregado. */
    default CreditApplication getById(String id) {
        return findById(id).orElseThrow(
                () -> new IllegalArgumentException("No existe la solicitud " + id));
    }
}
