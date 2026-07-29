package com.rriveros.origination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Punto de entrada. El despliegue de modelos vive en {@code ProcessModelDeployment}. */
@SpringBootApplication
public class CreditOriginationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditOriginationApplication.class, args);
    }
}
