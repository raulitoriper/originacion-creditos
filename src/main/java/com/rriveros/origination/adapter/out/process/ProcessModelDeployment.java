package com.rriveros.origination.adapter.out.process;

import io.camunda.zeebe.spring.client.annotation.Deployment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Despliega el BPMN y el DMN contra Zeebe al arrancar la aplicacion.
 *
 * <p>Esta anotacion vive aca y no en la clase {@code @SpringBootApplication} por dos razones:
 *
 * <ol>
 *   <li>El despliegue de modelos es infraestructura, no el arranque de la app.
 *   <li>{@code @Profile("!test")} lo apaga en los tests. Camunda Process Test levanta su propio
 *       runtime por metodo de test, y cada test despliega lo que necesita cuando lo necesita. Un
 *       despliegue automatico en el refresh del contexto correria antes de que el cliente apunte al
 *       contenedor y te deja con un fallo dificil de leer.
 * </ol>
 *
 * <p>En produccion esto tambien se apaga: los modelos se despliegan desde el pipeline, versionados
 * y auditados, no como efecto colateral de un arranque.
 */
@Configuration
@Profile("!test")
@Deployment(resources = {
        "classpath:models/credit-origination.bpmn",
        "classpath:models/credit-scoring.dmn"
})
public class ProcessModelDeployment {
}
