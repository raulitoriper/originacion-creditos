package com.rriveros.origination.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Sondeo REST de solo lectura para un unico flow node instance, usado por {@code
 * CreditOriginationProcessTest} para observar los elementos de cola de las 2 instancias que
 * {@code CamundaAssert.hasCompletedElements} no puede ver, {@code fix-process-test-failures}.
 *
 * <p><b>Causa raiz, probada a nivel bytecode con {@code javap} contra
 * camunda-process-test-java-8.7.6.jar (no una hipotesis mas de este cambio):</b>
 *
 * <ol>
 *   <li>{@code CamundaDataSource.getFlowNodeInstancesByProcessInstanceKey(long)} delega en {@code
 *       CamundaApiClient.findFlowNodeInstancesByProcessInstanceKey(long)} y devuelve solo {@code
 *       .getItems()}, sin paginar.
 *   <li>Ese metodo de {@code CamundaApiClient} arma el cuerpo del POST a {@code
 *       /v1/flownode-instances/search} desde una constante de compilacion: {@code
 *       {"filter":{"processInstanceKey":%d}}}. No envia {@code size} ni {@code page}, asi que el
 *       broker responde con su tamano de pagina por defecto: 10 filas. No es configurable: no hay
 *       overload ni setter.
 *   <li>{@code FlowNodeInstancesResponseDto} tiene {@code items} y tambien {@code total} (con
 *       {@code getTotal()}), pero {@code CamundaDataSource} descarta {@code total} y devuelve solo
 *       {@code items}. El truncamiento es silencioso.
 *   <li>Correlacion confirmada en el dump de CI (run 30470024764): Zona gris 8 elementos, pasa;
 *       Mora vigente 6 elementos, pasa; Compensacion 10 elementos, falla (elementos 11, 12 y 13
 *       ausentes); Score alto 10 elementos, falla (elemento 11 ausente). El proceso es CORRECTO: en
 *       el dump de compensacion, {@code Activity_DisburseLoan} queda {@code TERMINATED} y {@code
 *       Boundary_DisbursementFailed} queda {@code COMPLETED} -- la firma exacta de un error BPMN
 *       atrapado por su boundary.
 * </ol>
 *
 * <p>El limite de paginacion desaparece si la consulta devuelve pocas filas: por eso esta clase
 * filtra por {@code flowNodeId} ademas de {@code processInstanceKey}, acotando la respuesta a lo
 * sumo 1 fila, muy por debajo de cualquier tamano de pagina. {@code CamundaApiClient} no sirve para
 * esto porque {@code sendPostRequest} y {@code ensureAuthenticated} son privados: esta clase hace su
 * propia llamada autenticada.
 *
 * <p>Login: {@code POST {restAddress}/api/login?username=demo&password=demo}, credenciales literales
 * segun las constantes del propio {@code CamundaApiClient}. La sesion se sostiene por el cookie
 * store del cliente HTTP durante la vida de esta instancia (un login por llamada; ver {@link
 * #findElementState(long, String)}).
 *
 * <p>No verificable sin ejecutar: si {@code flowNodeId} es un filtro aceptado por el broker, y si el
 * login se comporta como esta documentado, no se pudo confirmar offline ({@code
 * CreditOriginationProcessTest} no corre en esta maquina: Docker 29.3.1 vs Testcontainers 1.20.6 ->
 * HTTP 400). Por eso {@link Result} nunca lanza y siempre expone HTTP status, {@code total}, cantidad
 * de items y el cuerpo crudo (truncado): si esta sonda no funciona, el mensaje de falla debe alcanzar
 * para decidir sin otra corrida de CI.
 *
 * <p>Invariante: nunca lanza. Cualquier fallo (login, red, parseo) se devuelve como {@link Result}
 * con {@code failureDetail} no nulo; el llamador decide como fallar la asercion.
 */
public final class FlowNodeElementProbe {

    private static final String LOGIN_USER = "demo";
    private static final String LOGIN_PASSWORD = "demo";
    private static final int BODY_EXCERPT_LIMIT = 500;

    private final String camundaRestAddress;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FlowNodeElementProbe(String camundaRestAddress) {
        this.camundaRestAddress = camundaRestAddress;
    }

    /**
     * Devuelve el estado observado del flow node instance identificado por {@code flowNodeId}
     * dentro de la instancia {@code processInstanceKey}, o un detalle de falla si no se pudo
     * resolver. Nunca lanza.
     *
     * @param processInstanceKey key de la instancia de proceso a consultar
     * @param flowNodeId id BPMN del elemento a consultar (acota la respuesta a lo sumo 1 fila)
     * @return resultado con el estado observado, o con el detalle de por que no se pudo obtener
     */
    public Result findElementState(long processInstanceKey, String flowNodeId) {
        if (camundaRestAddress == null) {
            return Result.failure(-1, null, 0, "camundaRestAddress es null: no se pudo resolver la"
                    + " direccion REST del broker (CamundaProcessTestContext.getCamundaRestAddress())");
        }
        // Timeouts acotados a proposito. Sin RequestConfig, HttpClient 5 deja responseTimeout sin
        // limite: si el broker acepta el TCP y no responde nunca, esta sonda colgaria en vez de
        // fallar, y eso derrota su unico proposito -- que el mensaje de falla alcance para decidir
        // sin gastar otra corrida de CI. Una falla rapida informa; un cuelgue no informa nada.
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultCookieStore(new BasicCookieStore())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .build())
                .build()) {
            HttpClientContext context = HttpClientContext.create();
            int loginStatus = login(httpClient, context);
            if (loginStatus < 200 || loginStatus >= 300) {
                return Result.failure(loginStatus, null, 0, "login fallo con HTTP " + loginStatus);
            }
            return search(httpClient, context, processInstanceKey, flowNodeId);
        } catch (IOException e) {
            return Result.failure(-1, null, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private int login(CloseableHttpClient httpClient, HttpClientContext context) throws IOException {
        HttpPost login = new HttpPost(
                camundaRestAddress + "/api/login?username=" + LOGIN_USER + "&password=" + LOGIN_PASSWORD);
        return httpClient.execute(login, context, (ClassicHttpResponse response) -> {
            EntityUtils.consumeQuietly(response.getEntity());
            return response.getCode();
        });
    }

    private Result search(
            CloseableHttpClient httpClient, HttpClientContext context, long processInstanceKey, String flowNodeId)
            throws IOException {
        String requestBody = "{\"filter\":{\"processInstanceKey\":" + processInstanceKey
                + ",\"flowNodeId\":\"" + flowNodeId + "\"}}";
        HttpPost search = new HttpPost(camundaRestAddress + "/v1/flownode-instances/search");
        search.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
        return httpClient.execute(search, context, (ClassicHttpResponse response) -> {
            int status = response.getCode();
            String rawBody = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
            if (status < 200 || status >= 300) {
                return Result.failure(status, null, 0, excerpt(rawBody));
            }
            return parseSearchResponse(status, rawBody);
        });
    }

    private Result parseSearchResponse(int status, String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            Integer total = root.has("total") ? Integer.valueOf(root.path("total").asInt()) : null;
            JsonNode items = root.path("items");
            int itemCount = items.isArray() ? items.size() : 0;
            String state = itemCount > 0 ? items.get(0).path("state").asText(null) : null;
            if (state == null) {
                return Result.failure(status, total, itemCount, excerpt(rawBody));
            }
            return Result.success(state, status, total, itemCount);
        } catch (Exception parseError) {
            return Result.failure(
                    status,
                    null,
                    0,
                    "no se pudo parsear la respuesta: " + parseError.getMessage() + " body=" + excerpt(rawBody));
        }
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > BODY_EXCERPT_LIMIT ? body.substring(0, BODY_EXCERPT_LIMIT) + "...(truncado)" : body;
    }

    /**
     * Resultado de {@link #findElementState(long, String)}. O {@code state} no es nulo y la
     * consulta se resolvio, o {@code failureDetail} no es nulo y describe exactamente que paso:
     * HTTP status, {@code total} devuelto por el broker, cantidad de items y el cuerpo crudo
     * (truncado) o el mensaje de la excepcion.
     */
    public record Result(String state, int httpStatus, Integer total, int itemCount, String failureDetail) {

        static Result success(String state, int httpStatus, Integer total, int itemCount) {
            return new Result(state, httpStatus, total, itemCount, null);
        }

        static Result failure(int httpStatus, Integer total, int itemCount, String failureDetail) {
            return new Result(null, httpStatus, total, itemCount, failureDetail);
        }

        /** Texto legible para adjuntar a un mensaje de asercion o a una linea DIAG. */
        public String diagnosticDetail() {
            if (failureDetail == null) {
                return "state=" + state + " httpStatus=" + httpStatus + " total=" + total
                        + " itemCount=" + itemCount;
            }
            return "httpStatus=" + httpStatus + " total=" + total + " itemCount=" + itemCount
                    + " detail=" + failureDetail;
        }
    }
}
