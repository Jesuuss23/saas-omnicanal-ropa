package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import java.util.List;
import java.util.UUID;

/** Modelo de lenguaje detrás de la IA vendedora (Gemini en producción; reemplazable en pruebas). */
public interface AssistantResponder {
    boolean configured();

    /**
     * @param history mensajes anteriores en orden (autor CUSTOMER, AI o HUMAN), sin el mensaje actual
     * @param note    nota que dejó el equipo al devolver la conversación a la IA, o null
     */
    Answer answer(UUID tenant, StoreFacts facts, String instructions, List<Turn> history, String note, String message);
}
