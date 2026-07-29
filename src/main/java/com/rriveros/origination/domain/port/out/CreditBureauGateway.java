package com.rriveros.origination.domain.port.out;

import com.rriveros.origination.domain.model.BureauReport;

/** Puerto de salida: consulta al bureau de credito externo. */
public interface CreditBureauGateway {

    BureauReport fetchReport(String applicantDocument);
}
