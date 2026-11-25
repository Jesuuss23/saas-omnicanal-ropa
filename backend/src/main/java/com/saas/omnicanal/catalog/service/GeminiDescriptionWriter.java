package com.saas.omnicanal.catalog.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Usa el mismo Gemini del asistente (GOOGLE_GENAI_API_KEY), sin herramientas: solo texto. */
@Component
class GeminiDescriptionWriter implements DescriptionWriter {
    private final ObjectProvider<ChatClient.Builder> builders;
    private final String apiKey;
    private final String model;

    GeminiDescriptionWriter(ObjectProvider<ChatClient.Builder> builders,
                            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
                            @Value("${catalog.description-model:${spring.ai.google.genai.chat.model:gemini-3.5-flash}}") String model) {
        this.builders = builders;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty() && !"sin-configurar".equals(apiKey) && builders.getIfAvailable() != null;
    }

    @Override
    public String write(String system, String prompt) {
        return builders.getObject().build().prompt()
                .options(GoogleGenAiChatOptions.builder().model(model))
                .system(system).user(prompt).call().content();
    }
}
