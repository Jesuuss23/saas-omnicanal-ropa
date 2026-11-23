package com.saas.omnicanal.fiscal.controller;

import com.saas.omnicanal.fiscal.model.FiscalProfile;
import com.saas.omnicanal.fiscal.service.ApisPeruCompanyDirectory;
import com.saas.omnicanal.fiscal.service.EmissionTokenCipher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/issuer")
class FiscalIssuerController {
    private final JdbcTemplate db;

    private final ApisPeruCompanyDirectory companies;

    private final EmissionTokenCipher tokens;

    FiscalIssuerController(JdbcTemplate db, ApisPeruCompanyDirectory companies, EmissionTokenCipher tokens) {
        this.tokens = tokens;
        this.companies = companies;
        this.db = db;
    }


    @GetMapping
    FiscalProfile get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return db.query("""
                SELECT ruc, legal_name, address, ubigeo, revision
                FROM tenant_fiscal_issuer WHERE tenant_id = ?
                """, (rs, row) -> new FiscalProfile(rs.getString("ruc"), rs.getString("legal_name"),
                rs.getString("address"), rs.getString("ubigeo"), rs.getInt("revision")), store)
                .stream().findFirst().orElse(new FiscalProfile("", "", "", "", 0));
    }

    record Verification(String ruc) {}

    @GetMapping("/verification")
    org.springframework.http.ResponseEntity<Verification> verify(@PathVariable UUID store, Principal owner) {
        FiscalProfile profile = get(store, owner);
        if (profile.revision() == 0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        String companyId = companies.configuredCompanyId(store);
        if (companyId.isBlank()) {
            companyId = db.query("SELECT company_id FROM beta_company_registration WHERE tenant_id=? AND registered_ruc=? AND company_id IS NOT NULL",
                    (rs, row) -> rs.getString(1), store, profile.ruc()).stream().findFirst().orElse("");
        }
        companies.verify(companyId, profile.ruc());
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new Verification(profile.ruc()));
    }

    record CertificateRequest(@NotNull @Positive Integer revision,
                              @NotBlank @Size(min = 8, max = 128) String password) {}
    record TestCertificate(String ruc, byte[] pfx) {}

    @PostMapping("/test-certificate")
    org.springframework.http.ResponseEntity<TestCertificate> testCertificate(
            @PathVariable UUID store, Principal owner, @Valid @RequestBody CertificateRequest input) {
        FiscalProfile profile = get(store, owner);
        if (!input.revision().equals(profile.revision())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        byte[] pfx = companies.generateTestCertificate(profile.ruc(), input.password());
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new TestCertificate(profile.ruc(), pfx));
    }

    record BetaRequest(@NotNull @Positive Integer revision,
                       @NotBlank @Size(max=128) String password,
                       @NotBlank @Size(max=273068) String pfx,
                       @NotBlank @Size(max=273068) String logo) {}
    record Registration(String status) {}

    @GetMapping("/beta-company")
    Registration registration(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        if (!companies.configuredCompanyId(store).isBlank()) return new Registration("CONFIGURED");
        return db.query("SELECT company_id FROM beta_company_registration WHERE tenant_id=?",
                (rs, row) -> new Registration(rs.getObject(1) == null ? "REVIEW" : "REGISTERED"), store)
                .stream().findFirst().orElse(new Registration("AVAILABLE"));
    }

    @PostMapping("/beta-company")
    Registration register(@PathVariable UUID store, Principal owner, @Valid @RequestBody BetaRequest input) {
        FiscalProfile profile = get(store, owner);
        Registration current = registration(store, owner);
        if (!current.status().equals("AVAILABLE")) return current;
        if (!input.revision().equals(profile.revision())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        byte[] logo = decodeUpload(input.logo());
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (logo.length < signature.length || !java.util.Arrays.equals(signature,
                java.util.Arrays.copyOf(logo, signature.length))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        decodeUpload(input.pfx());
        String token = companies.accountToken();
        var encryptionKey = tokens.keyIfConfigured();
        String pem = companies.convert(input.pfx(), input.password(), token);
        // Commit the claim before contacting the non-idempotent creation endpoint.
        int claimed = db.update("""
                INSERT INTO beta_company_registration(tenant_id,registered_ruc)
                SELECT tenant_id,ruc FROM tenant_fiscal_issuer WHERE tenant_id=? AND revision=?
                ON CONFLICT (tenant_id) DO NOTHING
                """, store, profile.revision());
        if (claimed == 0) {
            current = registration(store, owner);
            if (current.status().equals("AVAILABLE")) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return current;
        }
        try {
            var company = companies.registerBeta(profile, pem, input.logo(), token);
            if (company != null) {
                String tokenValue = company.token();
                byte[] encrypted = encryptionKey != null && tokenValue != null && !tokenValue.isBlank()
                        ? tokens.encrypt(encryptionKey, store, company.id(), profile.ruc(), tokenValue) : null;
                db.update("UPDATE beta_company_registration SET company_id=?, emission_token_ciphertext=? WHERE tenant_id=?",
                        company.id(), encrypted, store);
                return new Registration("REGISTERED");
            }
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 400 || code == 401 || code == 403 || code == 422) {
                db.update("DELETE FROM beta_company_registration WHERE tenant_id=? AND company_id IS NULL", store);
                throw new ResponseStatusException(code == 400 || code == 422
                        ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY);
            }
        } catch (org.springframework.web.client.RestClientException | ResponseStatusException uncertain) {
            // A remote company may already exist. Keep the claim for reconciliation.
        }
        return new Registration("REVIEW");
    }

    record TokenStatus(boolean stored) {}
    record TokenInput(@NotBlank @Size(max=16384) String token, @NotNull @Positive Integer revision) {
        @Override public String toString() { return "TokenInput[redacted]"; }
    }

    @GetMapping("/beta-company/token")
    TokenStatus tokenStatus(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return new TokenStatus(Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM beta_company_registration b
                    JOIN tenant_fiscal_issuer i USING (tenant_id)
                    WHERE b.tenant_id=? AND b.registered_ruc=i.ruc AND b.emission_token_ciphertext IS NOT NULL)
                """, Boolean.class, store)));
    }

    @PutMapping("/beta-company/token")
    TokenStatus saveToken(@PathVariable UUID store, Principal owner, @Valid @RequestBody TokenInput input) {
        FiscalProfile profile = get(store, owner);
        if (!profile.revision().equals(input.revision()) || !companies.configuredCompanyId(store).isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        Long companyId = db.query("""
                SELECT company_id FROM beta_company_registration
                WHERE tenant_id=? AND registered_ruc=? AND company_id IS NOT NULL
                """, (rs, row) -> rs.getLong(1), store, profile.ruc()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        var key = tokens.keyIfConfigured();
        if (key == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        byte[] encrypted = tokens.encrypt(key, store, companyId, profile.ruc(), input.token().strip());
        int changed = db.update("""
                UPDATE beta_company_registration b SET emission_token_ciphertext=?
                FROM tenant_fiscal_issuer i WHERE b.tenant_id=i.tenant_id AND b.tenant_id=?
                AND b.company_id=? AND b.registered_ruc=i.ruc AND i.revision=?
                """, encrypted, store, companyId, input.revision());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new TokenStatus(true);
    }

    private static byte[] decodeUpload(String value) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > 204800) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    @PutMapping
    FiscalProfile save(@PathVariable UUID store, Principal owner, @Valid @RequestBody FiscalProfile input) {
        requireOwner(store, owner);
        String name = input.legalName().strip();
        String address = input.address().strip();
        int changed;
        if (input.revision() == 0) {
            changed = db.update("""
                    INSERT INTO tenant_fiscal_issuer(tenant_id,ruc,legal_name,address,ubigeo,revision)
                    VALUES (?,?,?,?,?,1) ON CONFLICT (tenant_id) DO NOTHING
                    """, store, input.ruc(), name, address, input.ubigeo());
        } else {
            changed = db.update("""
                    UPDATE tenant_fiscal_issuer SET ruc=?,legal_name=?,address=?,ubigeo=?,revision=revision+1
                    WHERE tenant_id=? AND revision=?
                    """, input.ruc(), name, address, input.ubigeo(), store, input.revision());
        }
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new FiscalProfile(input.ruc(), name, address, input.ubigeo(), input.revision()+1);
    }

    private static void requireOwner(UUID store, Principal owner) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
