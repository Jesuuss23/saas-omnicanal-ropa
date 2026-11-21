package com.saas.omnicanal.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
class ApisPeruCompanyDirectory {
    private final Environment config;
    private final RestClient api;

    ApisPeruCompanyDirectory(Environment config) {
        this.config = config;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        api = RestClient.builder().requestFactory(factory)
                .baseUrl("https://facturacion.apisperu.com/api/v1").build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Company(Long id, String ruc) {}

    byte[] generateTestCertificate(String ruc, String password) {
        String token = config.getProperty("APISPERU_ACCOUNT_TOKEN", "");
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        try {
            return api.post().uri("/companies/certificate/free")
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("application/x-pkcs12"))
                    .body(java.util.Map.of("ruc", ruc, "password", password))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
                        }
                        byte[] bytes = response.getBody().readNBytes(204801);
                        if (bytes.length == 0 || bytes.length > 204800) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
                        }
                        try {
                            var pfx = java.security.KeyStore.getInstance("PKCS12");
                            pfx.load(new java.io.ByteArrayInputStream(bytes), password.toCharArray());
                            for (var aliases = pfx.aliases(); aliases.hasMoreElements();) {
                                if (pfx.isKeyEntry(aliases.nextElement())) return bytes;
                            }
                        } catch (java.security.GeneralSecurityException | java.io.IOException invalidCertificate) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
                        }
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
                    });
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    void verify(UUID store, String expectedRuc) {
        String key = "APISPERU_COMPANY_ID_" + store.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        String companyId = config.getProperty(key, "");
        String token = config.getProperty("APISPERU_ACCOUNT_TOKEN", "");
        if (!companyId.matches("[1-9][0-9]{0,17}") || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }

        Company company;
        try {
            company = api.get().uri("/companies/{id}", companyId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(Company.class);
        } catch (RestClientResponseException ex) {
            HttpStatus status = ex.getStatusCode().value() == 404
                    ? HttpStatus.FAILED_DEPENDENCY : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        if (company == null || !companyId.equals(String.valueOf(company.id())) || company.ruc() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        if (!expectedRuc.equals(company.ruc())) throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
}
