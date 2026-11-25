package com.saas.omnicanal.fiscal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.fiscal.service.BillingAccountService;
import com.saas.omnicanal.fiscal.service.FiscalReconciler;
import com.saas.omnicanal.fiscal.service.InvoiceProvider;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** Facturación con la cuenta Nubefact de cada tienda: conexión, emisión desde el POS, errores legibles y consulta. */
@SpringBootTest
@AutoConfigureMockMvc
class NubefactBillingTests {
    static final String URL = "https://api.nubefact.com/api/v1/abc-123", TOKEN = "tok_0123456789abcdefghijXYZ";

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired BillingAccountService accounts;
    @Autowired FiscalReconciler reconciler;
    @Autowired MockMvc mvc;
    @MockitoBean InvoiceProvider provider;

    @Test
    void connectValidatesWithNubefactAndStoresTheTokenEncrypted() {
        UUID store = store();
        assertEquals(400, code(() -> accounts.connect(store, input("http://evil.test/x", TOKEN))), "solo RUTAs https de Nubefact");
        doThrow(new InvoiceProvider.ProviderException(401, "10", "Nubefact rechazó el TOKEN.", true, true))
                .when(provider).test(argThat(c -> c != null && c.token().equals("tok_malo_0123456789abcdef")), any());
        var rejected = assertThrows(ResponseStatusException.class, () -> accounts.connect(store, input(URL, "tok_malo_0123456789abcdef")));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("Nubefact rechazó el TOKEN.", rejected.getReason(), "el mensaje del proveedor llega al dueño");

        var account = accounts.connect(store, input(URL, TOKEN));
        assertTrue(account.linked());
        assertEquals("••••jXYZ", account.tokenHint());
        assertEquals("FFF1", account.invoiceSeries());
        byte[] stored = db.queryForObject("SELECT api_token_ciphertext FROM store_billing_account WHERE tenant_id=?", byte[].class, store);
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains(TOKEN), "el token no se guarda en claro");
        // Sin token se conserva el guardado.
        assertEquals("BBB2", accounts.connect(store, new BillingAccountService.Input(URL, "", "BETA", "FFF1", "bbb2")).receiptSeries());
        assertEquals(402, code(() -> accounts.connect(store, new BillingAccountService.Input(URL, "", "PRODUCTION", "FFF1", "BBB1"))),
                "producción requiere el plan");
        accounts.disconnect(store);
        assertFalse(accounts.account(store).linked());
    }

    @Test
    void posBoletaIsNumberedSentAcceptedAndItsXmlCanBeDownloaded() throws Exception {
        UUID store = connected();
        UUID sale = sale(store);
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/number", store, sale).with(owner(store)).with(csrf()))
                .andExpect(MockMvcResultMatchers.status().isOk()).andExpect(jsonPath("$.series").value("BBB1"));
        String xml = zip("20123456789-03-BBB1-1.xml", "<Invoice/>");
        when(provider.send(any(), anyMap())).thenReturn(json.readTree("""
                {"aceptada_por_sunat":true,"sunat_description":"La Boleta numero BBB1-1, ha sido aceptada","sunat_responsecode":"0",
                 "xml_zip_base64":"%s","cdr_zip_base64":"%s","enlace_del_pdf":"https://www.nubefact.com/cpe/abc.pdf"}
                """.formatted(xml, zip("R-x.xml", "<cdr/>"))));
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/send", store, sale).with(owner(store)).with(csrf()))
                .andExpect(MockMvcResultMatchers.status().isOk()).andExpect(jsonPath("$.status").value("BETA_ACCEPTED"));

        @SuppressWarnings("unchecked")
        var body = (org.mockito.ArgumentCaptor<Map<String, Object>>) (Object) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(provider).send(argThat(c -> c.apiUrl().equals(URL) && c.token().equals(TOKEN)), body.capture());
        assertEquals(2, body.getValue().get("tipo_de_comprobante"));
        assertEquals("BBB1", body.getValue().get("serie"));
        assertEquals(new java.math.BigDecimal("59.00"), body.getValue().get("total"));

        mvc.perform(get("/api/owner/stores/{s}/billing/pos-documents", store).with(owner(store)))
                .andExpect(jsonPath("$.documents[0].hasXml").value(true))
                .andExpect(jsonPath("$.documents[0].pdfUrl").value("https://www.nubefact.com/cpe/abc.pdf"))
                .andExpect(jsonPath("$.documents[0].submission.description").value("La Boleta numero BBB1-1, ha sido aceptada"));
        String file = mvc.perform(get("/api/owner/stores/{s}/billing/pos-documents/{sale}/files/xml", store, sale).with(owner(store)))
                .andExpect(MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("<Invoice/>", new String(Base64.getDecoder().decode(json.readTree(file).path("content").asText())));
        // Ya aceptado: no se vuelve a enviar.
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/send", store, sale).with(owner(store)).with(csrf()))
                .andExpect(jsonPath("$.status").value("BETA_ACCEPTED"));
        verify(provider, times(1)).send(any(), anyMap());
    }

    @Test
    void validationErrorIsReadableAndAllowsResendWhileLostResponseIsOnlyReconciled() throws Exception {
        UUID store = connected();
        UUID sale = sale(store);
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/number", store, sale).with(owner(store)).with(csrf()));
        when(provider.send(any(), anyMap())).thenThrow(new InvoiceProvider.ProviderException(400, "20", "Nubefact: El campo serie no es válido", true, false));
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/send", store, sale).with(owner(store)).with(csrf()))
                .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity()).andExpect(r -> assertEquals("Nubefact: El campo serie no es válido", ((ResponseStatusException) r.getResolvedException()).getReason()));
        assertEquals("PENDING", fiscalStatus(store, sale), "no se generó: puede corregirse y reenviarse");

        reset(provider);
        when(provider.send(any(), anyMap())).thenThrow(new InvoiceProvider.ProviderException(504, null, "Sin respuesta", false, false));
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/send", store, sale).with(owner(store)).with(csrf()))
                .andExpect(jsonPath("$.status").value("BETA_REVIEW"));
        when(provider.status(any(), eq(2), eq("BBB1"), eq(1))).thenReturn(json.readTree("""
                {"aceptada_por_sunat":false,"sunat_responsecode":"2017","sunat_description":"El número de documento no es válido"}
                """));
        db.update("UPDATE pos_fiscal_request SET next_reconcile_at=now() - interval '1 minute' WHERE tenant_id=? AND sale_id=?", store, sale);
        mvc.perform(post("/api/owner/stores/{s}/billing/pos-documents/{sale}/reconcile", store, sale).with(owner(store)).with(csrf()))
                .andExpect(jsonPath("$.status").value("BETA_REJECTED"));
        verify(provider, times(1)).send(any(), anyMap());
    }

    private UUID store() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Nubefact')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        return store;
    }

    private UUID connected() {
        UUID store = store();
        String ruc = "20" + String.format("%09d", Math.abs(store.getMostSignificantBits() % 1_000_000_000L));
        db.update("INSERT INTO tenant_fiscal_issuer(tenant_id,ruc,legal_name,address,ubigeo,revision) VALUES (?,?,'Tienda SAC','Av. Lima 1','150101',1)", store, ruc);
        accounts.connect(store, input(URL, TOKEN));
        return store;
    }

    private UUID sale(UUID store) {
        UUID sale = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        db.update("INSERT INTO product(id,tenant_id,name) VALUES (?,?,?)", product, store, "Polo");
        db.update("""
                INSERT INTO product_variant(id,product_id,tenant_id,sku,size,color,price,stock_physical,stock_reserved)
                VALUES (?,?,?,?,'M','Negro',59.00,5,0)
                """, variant, product, store, "POLO-" + variant.toString().substring(0, 8));
        db.update("INSERT INTO pos_sale(tenant_id,id,method,total,request_signature) VALUES (?,?,'CASH',59.00,'x')", store, sale);
        db.update("INSERT INTO pos_sale_line(tenant_id,sale_id,variant_id,name,sku,size,unit_price,quantity) VALUES (?,?,?,'Polo','POLO-1','M',59.00,1)", store, sale, variant);
        db.update("INSERT INTO pos_fiscal_request(tenant_id,sale_id,kind) VALUES (?,?,'BOLETA')", store, sale);
        return sale;
    }

    private static BillingAccountService.Input input(String url, String token) {
        return new BillingAccountService.Input(url, token, "beta", "fff1", "bbb1");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner(UUID store) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(store.toString()).roles("OWNER");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }

    private String fiscalStatus(UUID store, UUID sale) {
        return db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?", String.class, store, sale);
    }

    private static String zip(String name, String content) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes());
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static int code(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
    }
}
