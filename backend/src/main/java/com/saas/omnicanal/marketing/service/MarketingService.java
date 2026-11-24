package com.saas.omnicanal.marketing.service;

import com.saas.omnicanal.marketing.model.MarketingModels.AbandonedSettings;
import com.saas.omnicanal.marketing.model.MarketingModels.Campaign;
import com.saas.omnicanal.marketing.model.MarketingModels.Channel;
import com.saas.omnicanal.marketing.model.MarketingModels.Preferences;
import com.saas.omnicanal.marketing.model.MarketingModels.Segment;
import com.saas.omnicanal.marketing.repository.MarketingRepository;
import com.saas.omnicanal.marketing.repository.MarketingRepository.Audience;
import com.saas.omnicanal.marketing.repository.MarketingRepository.ReminderStats;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.whatsapp.model.WhatsAppTemplate;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Campañas por correo y WhatsApp a contactos con consentimiento, y recordatorio de carrito abandonado.
 * La cuota del plan se cuenta por destinatario y por mes; la cola la envía {@link CampaignWorker}.
 */
@Service
public class MarketingService {
    private static final Set<String> BUYERS = Set.of("ANY", "BUYERS", "NON_BUYERS");
    private final MarketingRepository repo;
    private final EntitlementService entitlements;
    private final CloudApiService cloud;

    public MarketingService(MarketingRepository repo, EntitlementService entitlements, CloudApiService cloud) {
        this.repo = repo;
        this.entitlements = entitlements;
        this.cloud = cloud;
    }

    public static String entitlementKey(Channel channel) {
        return channel == Channel.EMAIL ? "campaigns.email" : "campaigns.whatsapp";
    }

    public record ChannelState(boolean included, Long monthlyLimit, long usedThisMonth) {}
    public record Overview(Audience audience, ChannelState email, ChannelState whatsapp, boolean cloudApiReady,
                           List<WhatsAppTemplate> templates, AbandonedSettings abandoned, ReminderStats reminders,
                           List<Campaign> campaigns) {}

    @Transactional(readOnly = true)
    public Overview overview(UUID tenant) {
        boolean cloudReady = cloud.credentials(tenant).isPresent();
        List<WhatsAppTemplate> approved = cloudReady
                ? cloud.templates(tenant).stream().filter(t -> "APPROVED".equalsIgnoreCase(t.status())).toList() : List.of();
        return new Overview(repo.audience(tenant), state(tenant, Channel.EMAIL), state(tenant, Channel.WHATSAPP), cloudReady,
                approved, repo.abandonedSettings(tenant), repo.reminderStats(tenant), repo.campaigns(tenant));
    }

    private ChannelState state(UUID tenant, Channel channel) {
        var e = entitlements.get(tenant, entitlementKey(channel));
        return new ChannelState(e.enabled(), e.limit(), repo.sentThisMonth(tenant, channel));
    }

    public record Preview(long recipients) {}

    public Preview preview(UUID tenant, Channel channel, Segment segment) {
        return new Preview(repo.countSegment(tenant, channel, clean(segment)));
    }

    public record Draft(String name, Channel channel, Segment segment, String subject, String body,
                        String templateName, String templateLang) {}

    @Transactional
    public Campaign create(UUID tenant, Draft in, String by) {
        var d = validate(tenant, in);
        UUID id = repo.insert(tenant, d.name(), d.channel(), d.segment(), d.subject(), d.body(), d.templateName(), d.templateLang(), by);
        return repo.find(tenant, id).orElseThrow();
    }

    @Transactional
    public Campaign update(UUID tenant, UUID id, long revision, Draft in) {
        var current = require(tenant, id);
        if (current.channel() != in.channel()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El canal no se puede cambiar");
        var d = validate(tenant, in);
        if (!repo.update(tenant, id, revision, d.name(), d.segment(), d.subject(), d.body(), d.templateName(), d.templateLang()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La campaña cambió o ya se envió; recarga la página");
        return repo.find(tenant, id).orElseThrow();
    }

    /** Congela el segmento de este momento en la cola y marca la campaña en envío. Respeta la cuota mensual del plan. */
    @Transactional
    public Campaign launch(UUID tenant, UUID id, long revision) {
        var c = require(tenant, id);
        if (!"DRAFT".equals(c.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "La campaña ya se lanzó");
        String key = entitlementKey(c.channel());
        entitlements.requireEnabled(tenant, key);
        if (c.channel() == Channel.WHATSAPP) requireTemplate(tenant, c.templateName(), c.templateLang());
        long recipients = repo.countSegment(tenant, c.channel(), c.segment());
        if (recipients == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "El segmento no tiene contactos con consentimiento");
        entitlements.requireQuota(tenant, key, repo.sentThisMonth(tenant, c.channel()), recipients);
        if (!repo.start(tenant, id, revision))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La campaña cambió; recarga la página");
        repo.enqueue(tenant, id, c.channel(), c.segment(), recipients);
        return repo.find(tenant, id).orElseThrow();
    }

    @Transactional
    public Campaign cancel(UUID tenant, UUID id) {
        require(tenant, id);
        if (!repo.cancel(tenant, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "La campaña ya terminó");
        return repo.find(tenant, id).orElseThrow();
    }

    @Transactional
    public AbandonedSettings saveAbandoned(UUID tenant, long revision, boolean enabled, int delay, String template, String lang) {
        template = blank(template);
        lang = blank(lang);
        if (delay < 15 || delay > 1440) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La espera va de 15 a 1440 minutos");
        if (enabled) {
            entitlements.requireEnabled(tenant, entitlementKey(Channel.WHATSAPP));
            requireTemplate(tenant, template, lang);
        }
        if (!repo.saveAbandoned(tenant, revision, enabled, delay, template, lang))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración cambió; recarga la página");
        return repo.abandonedSettings(tenant);
    }

    // --- Consentimiento ---

    /** Al confirmar un pedido: si el comprador aceptó novedades, su contacto queda con consentimiento de WhatsApp. */
    @Transactional
    public void optInFromCheckout(UUID tenant, UUID order, UUID contact) {
        if (contact == null) return;
        UUID reservation = repo.orderReservation(tenant, order);
        if (reservation != null && repo.reservationOptIn(tenant, reservation)) repo.setConsent(tenant, contact, null, true);
    }

    public Preferences preferences(UUID tenant, UUID contact) {
        return repo.preferences(tenant, contact).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Preferences savePreferences(UUID tenant, UUID contact, boolean email, boolean whatsapp) {
        repo.setConsent(tenant, contact, email, whatsapp);
        return preferences(tenant, contact);
    }

    public Preferences preferencesByToken(String token) {
        return repo.preferencesByToken(validToken(token)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Preferences unsubscribe(String token) {
        if (!repo.unsubscribe(validToken(token))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return preferencesByToken(token);
    }

    // --- Validación ---

    private Campaign require(UUID tenant, UUID id) {
        return repo.find(tenant, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Draft validate(UUID tenant, Draft in) {
        if (in.channel() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige un canal");
        String name = blank(in.name());
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ponle un nombre a la campaña");
        if (in.channel() == Channel.EMAIL) {
            String subject = blank(in.subject());
            String body = blank(in.body());
            if (subject == null || body == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el asunto y el mensaje del correo");
            return new Draft(name, Channel.EMAIL, clean(in.segment()), subject, body, null, null);
        }
        String template = blank(in.templateName());
        String lang = blank(in.templateLang());
        if (template == null || lang == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige una plantilla aprobada");
        return new Draft(name, Channel.WHATSAPP, clean(in.segment()), null, null, template, lang);
    }

    private void requireTemplate(UUID tenant, String name, String lang) {
        if (cloud.credentials(tenant).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conecta WhatsApp Cloud API para enviar plantillas");
        var template = cloud.templates(tenant).stream()
                .filter(t -> t.name().equals(name) && t.language().equals(lang) && "APPROVED".equalsIgnoreCase(t.status()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La plantilla no está aprobada o no está sincronizada"));
        if (TemplateParams.count(template.componentsJson()) > 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usa una plantilla sin variables o con una sola ({{1}} = nombre del cliente)");
    }

    static Segment clean(Segment s) {
        if (s == null) return Segment.all();
        String buyers = s.buyers() == null ? "ANY" : s.buyers().toUpperCase();
        if (!BUYERS.contains(buyers)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segmento inválido");
        return new Segment(buyers, days(s.lastOrderWithinDays()), days(s.noOrderForDays()),
                s.minSpent() == null || s.minSpent().signum() <= 0 ? null : s.minSpent().min(new BigDecimal("1000000")));
    }

    private static Integer days(Integer d) {
        if (d == null || d <= 0) return null;
        if (d > 3650) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usa como máximo 3650 días");
        return d;
    }

    private static String validToken(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return token;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
