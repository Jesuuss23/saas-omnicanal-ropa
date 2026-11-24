package com.saas.omnicanal.marketing.service;

import com.saas.omnicanal.marketing.model.MarketingModels.AbandonedCart;
import com.saas.omnicanal.marketing.model.MarketingModels.Campaign;
import com.saas.omnicanal.marketing.model.MarketingModels.Channel;
import com.saas.omnicanal.marketing.model.MarketingModels.Recipient;
import com.saas.omnicanal.marketing.repository.MarketingRepository;
import com.saas.omnicanal.notifications.service.EmailService;
import com.saas.omnicanal.notifications.service.EmailTemplates;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppTemplate;
import com.saas.omnicanal.whatsapp.service.CloudApiClient;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trabajador de la cola de marketing. Cada pasada toma un lote con FOR UPDATE SKIP LOCKED y marca cada destinatario
 * en la misma transacción, así dos instancias nunca envían lo mismo. Un envío dudoso se marca FAILED y no se reintenta:
 * es preferible perder un mensaje que mandarlo dos veces.
 */
@Component
public class CampaignWorker {
    private static final Logger log = LoggerFactory.getLogger(CampaignWorker.class);
    private static final int BATCH = 25;
    private final MarketingRepository repo;
    private final EmailService email;
    private final EmailTemplates templates;
    private final CloudApiService cloud;
    private final CloudApiClient client;
    private final EntitlementService entitlements;
    private final TransactionTemplate tx;

    public CampaignWorker(MarketingRepository repo, EmailService email, EmailTemplates templates, CloudApiService cloud,
                          CloudApiClient client, EntitlementService entitlements, TransactionTemplate tx) {
        this.repo = repo;
        this.email = email;
        this.templates = templates;
        this.cloud = cloud;
        this.client = client;
        this.entitlements = entitlements;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${marketing.worker-delay-ms:10000}", initialDelayString = "${marketing.worker-initial-delay-ms:20000}")
    public void tick() {
        try {
            drainOnce();
            remindAbandoned();
        } catch (RuntimeException e) {
            log.warn("Fallo en la cola de marketing", e);
        }
    }

    /** Procesa un lote de campañas; devuelve cuántos destinatarios tocó. */
    public int drainOnce() {
        Integer done = tx.execute(status -> {
            var batch = repo.claimBatch(BATCH);
            var campaigns = new HashMap<String, Optional<Campaign>>();
            var creds = new HashMap<UUID, Optional<CloudApiCredentials>>();
            var stores = new HashMap<UUID, String>();
            for (var r : batch) {
                var campaign = campaigns.computeIfAbsent(r.tenant() + "/" + r.campaign(), k -> repo.find(r.tenant(), r.campaign())).orElse(null);
                if (campaign == null) continue;
                if (!repo.stillConsents(r, campaign.channel())) {
                    repo.mark(r, "SKIPPED", "Sin consentimiento al momento del envío");
                    continue;
                }
                if (!entitlements.enabled(r.tenant(), MarketingService.entitlementKey(campaign.channel()))) {
                    repo.mark(r, "SKIPPED", "El plan ya no incluye este canal");
                    continue;
                }
                if (campaign.channel() == Channel.EMAIL) sendEmail(r, campaign, stores.computeIfAbsent(r.tenant(), repo::storeName));
                else sendWhatsApp(r, campaign, creds.computeIfAbsent(r.tenant(), cloud::credentials));
            }
            repo.finishDrained();
            return batch.size();
        });
        return done == null ? 0 : done;
    }

    private void sendEmail(Recipient r, Campaign c, String store) {
        String link = templates.url("/baja/" + r.unsubscribeToken());
        boolean sent = email.sendQuietly(templates.campaign(r.destination(), store, c.subject(), c.body(), link));
        repo.mark(r, sent ? "SENT" : "FAILED", sent ? null : "Resend no aceptó el correo");
    }

    private void sendWhatsApp(Recipient r, Campaign c, Optional<CloudApiCredentials> creds) {
        if (creds.isEmpty()) {
            repo.mark(r, "FAILED", "WhatsApp Cloud API no está conectado");
            return;
        }
        String components = template(r.tenant(), c.templateName(), c.templateLang());
        SendResult result = client.sendTemplate(creds.get(), r.destination(), c.templateName(), c.templateLang(),
                TemplateParams.forName(components, r.name()));
        repo.mark(r, "ACCEPTED".equals(result.status()) ? "SENT" : "FAILED", result.error());
    }

    private String template(UUID tenant, String name, String lang) {
        return cloud.templates(tenant).stream().filter(t -> t.name().equals(name) && t.language().equals(lang))
                .map(WhatsAppTemplate::componentsJson).findFirst().orElse(null);
    }

    /** Un recordatorio por reserva abandonada, solo si el comprador aceptó novedades y la tienda lo activó. */
    public int remindAbandoned() {
        int sent = 0;
        Map<UUID, Optional<CloudApiCredentials>> creds = new HashMap<>();
        for (AbandonedCart cart : repo.abandonedCarts(25)) {
            if (!repo.claimReminder(cart)) continue;
            var settings = repo.abandonedSettings(cart.tenant());
            var c = creds.computeIfAbsent(cart.tenant(), cloud::credentials);
            if (cart.phone() == null || c.isEmpty() || !entitlements.enabled(cart.tenant(), "campaigns.whatsapp")) {
                repo.finishReminder(cart, "SKIPPED", cart.phone() == null ? "Teléfono inválido" : "Canal no disponible");
                continue;
            }
            String components = template(cart.tenant(), settings.templateName(), settings.templateLang());
            var result = client.sendTemplate(c.get(), cart.phone(), settings.templateName(), settings.templateLang(),
                    TemplateParams.forName(components, cart.name()));
            boolean ok = "ACCEPTED".equals(result.status());
            repo.finishReminder(cart, ok ? "SENT" : "FAILED", result.error());
            if (ok) sent++;
        }
        return sent;
    }
}
