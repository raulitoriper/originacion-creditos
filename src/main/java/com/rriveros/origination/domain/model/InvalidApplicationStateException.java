package com.rriveros.origination.domain.model;

/** Se intento una transicion que las reglas del dominio no permiten. */
public class InvalidApplicationStateException extends RuntimeException {

    public InvalidApplicationStateException(String applicationId, String action, ApplicationStatus current) {
        super("No se puede '%s' la solicitud %s porque esta en estado %s".formatted(action, applicationId, current));
    }
}
