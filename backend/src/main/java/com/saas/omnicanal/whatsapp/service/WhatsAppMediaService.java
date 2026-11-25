package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Descarga los archivos entrantes (voz, imagen, video, documento) del proveedor después de confirmar el mensaje y
 * los guarda en {@code whatsapp_media}. El webhook responde 200 sin esperar la descarga.
 */
@Service
public class WhatsAppMediaService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppMediaService.class);
    private final WhatsAppMediaRepository media;
    private final EvolutionApiClient evolution;
    private final CloudApiClient cloud;
    private final CloudApiService cloudAccounts;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public WhatsAppMediaService(WhatsAppMediaRepository media, EvolutionApiClient evolution, CloudApiClient cloud,
                                CloudApiService cloudAccounts) {
        this.media = media;
        this.evolution = evolution;
        this.cloud = cloud;
        this.cloudAccounts = cloudAccounts;
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("wa-media-");
        executor.initialize();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInboundMedia(InboundMediaEvent event) {
        executor.execute(() -> download(event));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** Descarga y guarda; deja STORED, TOO_LARGE o FAILED en el mensaje. Público para las pruebas. */
    public void download(InboundMediaEvent e) {
        try {
            byte[] content;
            String mime;
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
                return;
            }
            var id = media.store(e.tenant(), e.kind(), mime, null, content);
            media.finishInbound(e.tenant(), e.chat(), e.messageId(), "STORED", WhatsAppMediaRepository.url(id), mime);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp: no se pudo descargar el archivo {} de {}: {}", e.messageId(), e.tenant(), ex.getMessage());
            media.finishInbound(e.tenant(), e.chat(), e.messageId(), "FAILED", null, null);
        }
    }
}
