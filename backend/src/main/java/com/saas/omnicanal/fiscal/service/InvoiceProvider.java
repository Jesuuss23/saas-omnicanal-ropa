package com.saas.omnicanal.fiscal.service;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Proveedor de facturación electrónica (OSE/PSE) con las credenciales propias de cada tienda. */
public interface InvoiceProvider {
    /** RUTA y TOKEN de la cuenta del proveedor. */
    record Credentials(String apiUrl, String token) {
        @Override public String toString() { return "Credentials[" + apiUrl + "]"; }
    }

    /**
     * Error devuelto por el proveedor. {@code notCreated} indica que el comprobante con seguridad no se generó
     * (credenciales o datos inválidos), así que puede corregirse y volver a enviarse sin riesgo de duplicarlo.
     */
    class ProviderException extends RuntimeException {
        private final int httpStatus;
        private final String code;
        private final boolean notCreated;
        private final boolean credentials;

        public ProviderException(int httpStatus, String code, String message, boolean notCreated, boolean credentials) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
            this.notCreated = notCreated;
            this.credentials = credentials;
        }
        public int httpStatus() { return httpStatus; }
        public String code() { return code; }
        public boolean notCreated() { return notCreated; }
        public boolean credentials() { return credentials; }
    }

    /** Comprueba que la RUTA y el TOKEN sean válidos. Lanza {@link ProviderException} con un mensaje legible si no. */
    void test(Credentials credentials, String receiptSeries);

    /** Genera el comprobante y lo envía a SUNAT. Sin respuesta (timeout) lanza {@link ProviderException} con notCreated=false. */
    JsonNode send(Credentials credentials, Map<String, Object> document);

    /** Consulta un comprobante ya generado; nunca lo reenvía. {@code type}: 1 factura, 2 boleta. */
    JsonNode status(Credentials credentials, int type, String series, int number);
}
