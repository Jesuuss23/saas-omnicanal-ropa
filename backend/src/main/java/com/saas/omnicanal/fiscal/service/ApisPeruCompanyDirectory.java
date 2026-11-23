package com.saas.omnicanal.fiscal.service;

import com.saas.omnicanal.fiscal.model.FiscalProfile;

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
public class ApisPeruCompanyDirectory {
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
    public record Company(Long id, String ruc, String token) {
        @Override public String toString() { return "Company[id=" + id + "]"; }
    }

    public byte[] generateTestCertificate(String ruc, String password) {
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

    public String accountToken() {
        String token = config.getProperty("APISPERU_ACCOUNT_TOKEN", "");
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return token;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Converted(String pem) {}

    public String convert(String pfx, String password, String token) {
        try {
            Converted converted = api.post().uri("/companies/certificate")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("cert", pfx, "cert_pass", password, "base64", true))
                    .retrieve().body(Converted.class);
            if (converted == null || converted.pem() == null || converted.pem().isBlank()
                    || converted.pem().length() > 273068) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            return converted.pem();
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(ex.getStatusCode().value() == 400
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    public Company registerBeta(FiscalProfile profile, String pem, String logo, String token) {
        var payload = java.util.Map.of("plan", "free", "environment", "beta",
                "sol_user", "MODDATOS", "sol_pass", "moddatos", "ruc", profile.ruc(),
                "razon_social", profile.legalName(), "direccion", profile.address(),
                "certificado", pem, "logo", logo);
        Company company = api.post().uri("/companies").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Company.class);
        if (company == null || company.id() == null || company.id() <= 0
                || (company.ruc() != null && !profile.ruc().equals(company.ruc()))) return null;
        verify(company.id().toString(), profile.ruc());
        return company;
    }

    public void requireBetaCompany(long companyId, String ruc, String token) {
        var remote = api.get().uri("/companies/{id}", companyId)
                .headers(h -> h.setBearerAuth(accountToken())).retrieve().body(tools.jackson.databind.JsonNode.class);
        if (remote == null || !String.valueOf(companyId).equals(remote.path("id").asText())
                || !ruc.equals(remote.path("ruc").asText()))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        var environment = remote.path("environment");
        String mode = environment.isObject() ? environment.path("code").asText(environment.path("name").asText("")) : environment.asText("");
        if (!token.equals(remote.path("token").asText(""))) {
            // Some account responses omit the token. Compare against the operator-pinned company token.
            String pinned = config.getProperty("APISPERU_BETA_COMPANY_TOKEN_" + companyId, "");
            if (!remote.path("token").asText("").isEmpty() || !token.equals(pinned))
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        }
        if (!"beta".equalsIgnoreCase(mode))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
    }

    public tools.jackson.databind.JsonNode invoiceStatus(String ruc, String type, String series, int number, String token) {
        return api.get().uri(uri -> uri.path("/invoice/status")
                        .queryParam("ruc",ruc).queryParam("tipo",type)
                        .queryParam("serie",series).queryParam("numero",number).build())
                .headers(h -> h.setBearerAuth(token)).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(tools.jackson.databind.JsonNode.class);
    }

    public tools.jackson.databind.JsonNode sendInvoice(Object invoice, String token) {
        return api.post().uri("/invoice/send").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(invoice).retrieve().body(tools.jackson.databind.JsonNode.class);
    }

    public String configuredCompanyId(UUID store) {
        String key = "APISPERU_COMPANY_ID_" + store.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return config.getProperty(key, "");
    }

    public void verify(String companyId, String expectedRuc) {
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
