package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.assistant.service.AssistantService;
import com.saas.omnicanal.media.ModerationClient;
import com.saas.omnicanal.media.TranscriptionClient;
import com.saas.omnicanal.media.VisionCatalogService;
import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline de archivos entrantes, después de confirmar el mensaje (el webhook responde 200 sin esperar):
 * <ol>
 *   <li>Descarga del proveedor (Evolution o Cloud API) y guardado en {@code whatsapp_media}.</li>
 *   <li>Nota de voz: transcripción con Groq Whisper.</li>
 *   <li>Imagen: moderación con Sightengine; si es explícita se bloquea, se avisa al cliente y no se analiza. Si es limpia,
 *       el modelo de visión extrae atributos y se buscan prendas parecidas.</li>
 *   <li>La IA conversacional recibe solo texto: la transcripción o el contexto visual ({@code ai_context}).</li>
 * </ol>
 * Cada resultado se guarda una vez; un mensaje ya procesado (o un archivo idéntico de la tienda) nunca vuelve a llamar a
 * las APIs externas.
 */
@Service
public class WhatsAppMediaService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppMediaService.class);
    private final WhatsAppMediaRepository media;
    private final EvolutionApiClient evolution;
    private final CloudApiClient cloud;
    private final CloudApiService cloudAccounts;
    private final ModerationClient moderation;
    private final TranscriptionClient transcription;
    private final VisionCatalogService vision;
    private final AssistantService assistant;
    private final ApplicationEventPublisher events;
    private final boolean visionRequiresModeration;
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public WhatsAppMediaService(WhatsAppMediaRepository media, EvolutionApiClient evolution, CloudApiClient cloud,
                                CloudApiService cloudAccounts, ModerationClient moderation, TranscriptionClient transcription,
                                VisionCatalogService vision, @Lazy AssistantService assistant, ApplicationEventPublisher events,
                                @Value("${media.vision.require-moderation:true}") boolean visionRequiresModeration) {
        this.media = media;
        this.evolution = evolution;
        this.cloud = cloud;
        this.cloudAccounts = cloudAccounts;
        this.moderation = moderation;
        this.transcription = transcription;
        this.vision = vision;
        this.assistant = assistant;
        this.events = events;
        this.visionRequiresModeration = visionRequiresModeration;
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("wa-media-");
        executor.initialize();
    }

    /** Notas de voz e imágenes esperan el análisis antes de llegar a la IA; video y documentos llegan de inmediato. */
    public static boolean waitsForAnalysis(MediaKind kind) {
        return kind == MediaKind.AUDIO || kind == MediaKind.IMAGE;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInboundMedia(InboundMediaEvent event) {
        try {
            executor.execute(() -> download(event));
        } catch (TaskRejectedException full) {
            log.warn("WhatsApp: cola de archivos llena; {} se responde sin análisis", event.messageId());
            media.finishInbound(event.tenant(), event.chat(), event.messageId(), "FAILED", null, null);
            if (waitsForAnalysis(event.kind())) toAssistant(event, null);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** Descarga, guarda y procesa. Público para las pruebas (corre en el hilo que lo llama). */
    public void download(InboundMediaEvent e) {
        UUID id;
        byte[] content;
        String mime;
        try {
            switch (e.provider()) {
                case EVOLUTION -> {
                    var file = evolution.media(EvolutionApiClient.instanceName(e.tenant()), e.evolutionItem());
                    content = file.content();
                    mime = file.mimeType();
                }
                case CLOUD_API -> {
                    var creds = cloudAccounts.credentials(e.tenant()).orElseThrow(() -> new IllegalStateException("Cloud API sin configurar"));
                    var file = cloud.download(creds, e.cloudMediaId());
                    content = file.content();
                    mime = file.mimeType();
                }
                default -> throw new IllegalStateException();
            }
            if (mime == null || mime.isBlank() || mime.startsWith("application/octet")) mime = e.mimeType();
            mime = mime == null ? "application/octet-stream" : mime.split(";")[0].strip();
            if (content.length > WhatsAppMediaRepository.MAX_BYTES) {
                media.finishInbound(e.tenant(), e.chat(), e.messageId(), "TOO_LARGE", null, mime);
                if (waitsForAnalysis(e.kind())) toAssistant(e, null);
                return;
            }
            id = media.store(e.tenant(), e.kind(), mime, null, content);
            media.finishInbound(e.tenant(), e.chat(), e.messageId(), "STORED", WhatsAppMediaRepository.url(id), mime);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp: no se pudo descargar el archivo {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
            media.finishInbound(e.tenant(), e.chat(), e.messageId(), "FAILED", null, null);
            if (waitsForAnalysis(e.kind())) toAssistant(e, null);
            return;
        }
        process(e, id, content, mime);
    }

    /** Procesa un archivo ya guardado. Público para las pruebas; reutiliza todo lo que ya esté en la base. */
    public void process(InboundMediaEvent e, UUID id, byte[] content, String mime) {
        try {
            media.inheritFromSameContent(e.tenant(), id);
            switch (e.kind()) {
                case AUDIO -> audio(e, id, content, mime);
                case IMAGE -> image(e, id, content, mime);
                default -> { }
            }
        } catch (RuntimeException ex) {
            // Nunca dejar al cliente sin respuesta por un fallo del análisis.
            log.warn("WhatsApp: falló el análisis del archivo {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
            // Una imagen bloqueada nunca llega a la IA, aunque haya fallado un paso posterior.
            if (!media.blocked(e.tenant(), id)) toAssistant(e, null);
        }
    }

    private void audio(InboundMediaEvent e, UUID id, byte[] content, String mime) {
        var cached = media.analysis(e.tenant(), id);
        String transcript = cached.transcript();
        if (cached.transcriptStatus() == null) {
            if (!transcription.enabled()) {
                media.saveTranscript(e.tenant(), id, "DISABLED", null);
            } else {
                try {
                    transcript = transcription.transcribe(content, mime);
                    media.saveTranscript(e.tenant(), id, transcript.isBlank() ? "EMPTY" : "DONE", transcript);
                } catch (RuntimeException ex) {
                    log.warn("Transcripción: falló Groq para {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
                    media.saveTranscript(e.tenant(), id, "FAILED", null);
                    transcript = null;
                }
            }
        }
        toAssistant(e, transcript == null || transcript.isBlank() ? null : "[Nota de voz transcrita] " + transcript);
    }

    private void image(InboundMediaEvent e, UUID id, byte[] content, String mime) {
        var cached = media.analysis(e.tenant(), id);
        String status = cached.moderationStatus();
        Double score = cached.moderationScore() == null ? null : cached.moderationScore().doubleValue();
        if (status == null) {
            if (!moderation.enabled()) {
                status = "DISABLED";
            } else {
                try {
                    var r = moderation.check(content, mime);
                    status = r.blocked() ? "BLOCKED_NSFW" : "SAFE";
                    score = r.score();
                } catch (RuntimeException ex) {
                    log.warn("Moderación: falló Sightengine para {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
                    status = "FAILED";
                }
            }
            media.saveModeration(e.tenant(), id, status, score);
        }
        if ("BLOCKED_NSFW".equals(status)) {
            media.markBlocked(e.tenant(), e.chat(), e.messageId());
            media.incident(e.tenant(), e.chat(), e.messageId(), id, "NSFW", score);
            assistant.blockedImage(e.tenant(), e.chat(), e.messageId());
            return;
        }
        // Sin moderación confirmada no se analiza (salvo que la configuración lo permita).
        boolean clean = "SAFE".equals(status) || !visionRequiresModeration;
        JsonNode analysis = cached.visionJson() == null ? null : json.readTree(cached.visionJson());
        if (analysis == null && cached.visionStatus() == null) {
            if (!clean || !vision.enabled()) {
                media.saveVision(e.tenant(), id, clean ? "DISABLED" : "SKIPPED", null);
            } else {
                String caption = media.inbound(e.tenant(), e.chat(), e.messageId()).map(WhatsAppMediaRepository.Inbound::caption).orElse(null);
                try {
                    analysis = vision.analyze(e.tenant(), content, mime, caption);
                    media.saveVision(e.tenant(), id, "DONE", json.writeValueAsString(analysis));
                } catch (RuntimeException ex) {
                    log.warn("Visión: falló el análisis de {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
                    media.saveVision(e.tenant(), id, "FAILED", null);
                }
            }
        }
        if (analysis == null) {
            toAssistant(e, null);
            return;
        }
        if (!analysis.path("esConsultaValidaTienda").asBoolean(false)) {
            media.incident(e.tenant(), e.chat(), e.messageId(), id, "OFF_CONTEXT", null);
            assistant.offContextImage(e.tenant(), e.chat(), e.messageId());
            return;
        }
        String caption = media.inbound(e.tenant(), e.chat(), e.messageId()).map(WhatsAppMediaRepository.Inbound::caption).orElse(null);
        toAssistant(e, VisionCatalogService.context(analysis, caption));
    }

    /** Entrega el mensaje a la IA con el texto que debe leer (o el marcador/leyenda si no hubo análisis). */
    private void toAssistant(InboundMediaEvent e, String context) {
        if (context != null) media.setAiContext(e.tenant(), e.chat(), e.messageId(), context);
        String body = context != null ? context
                : media.inbound(e.tenant(), e.chat(), e.messageId()).map(WhatsAppMediaRepository.Inbound::body).orElse(e.kind().placeholder());
        events.publishEvent(new InboundMessageEvent(e.tenant(), e.chat(), e.messageId(), body, null, InboundMessageEvent.Delivery.ASSISTANT));
    }
}
