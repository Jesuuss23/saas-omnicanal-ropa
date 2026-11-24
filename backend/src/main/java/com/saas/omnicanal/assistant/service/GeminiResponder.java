package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** IA vendedora con Gemini (Spring AI) y llamadas a herramientas sobre el catálogo de la tienda. */
@Service
public class GeminiResponder implements AssistantResponder {
    private final ObjectProvider<ChatClient.Builder> builders;
    private final PublicCatalogRepository catalog;
    private final String apiKey;

    public GeminiResponder(ObjectProvider<ChatClient.Builder> builders, PublicCatalogRepository catalog,
                           @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        this.builders = builders;
        this.catalog = catalog;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty() && !"sin-configurar".equals(apiKey) && builders.getIfAvailable() != null;
    }

    @Override
    public Answer answer(UUID tenant, StoreFacts facts, String instructions, List<Turn> history, String note, String message) {
        var tools = new StoreTools(tenant, catalog, facts);
        List<Message> messages = new ArrayList<>();
        for (Turn t : history) messages.add("CUSTOMER".equals(t.author()) ? new UserMessage(t.text()) : new AssistantMessage(t.text()));
        String text = builders.getObject().build().prompt()
                .system(system(facts, instructions, note))
                .messages(messages)
                .user(message)
                .tools(tools)
                .call().content();
        boolean escalate = tools.escalationReason != null;
        if (text == null || text.isBlank()) text = escalate ? "Gracias por escribirnos. Una persona del equipo te responderá pronto."
                : "Gracias por escribirnos. ¿Me cuentas qué prenda buscas y en qué talla?";
        return new Answer(text.strip(), escalate, tools.escalationReason, tools.escalationSummary, List.copyOf(tools.used));
    }

    static String system(StoreFacts facts, String instructions, String note) {
        var s = new StringBuilder();
        s.append("Eres la persona que atiende el WhatsApp de ").append(facts.storeName())
                .append(", una tienda de ropa en Perú. Respondes a clientes que quieren comprar.\n")
                .append("Reglas:\n")
                .append("- Escribe en español de Perú, cordial y breve: máximo 4 oraciones, sin listas largas.\n")
                .append("- Nunca inventes precios, tallas, colores ni stock: consulta buscarPrendas antes de mencionarlos.\n")
                .append("- Los precios están en soles (S/). Si no hay stock de algo, ofrece alternativas reales del catálogo.\n")
                .append("- Para comprar, comparte el enlace de la prenda o el del catálogo: ").append(facts.catalogUrl()).append("\n")
                .append("- Para recojo o delivery usa infoTienda.\n")
                .append("- No pidas datos de tarjetas ni contraseñas. No prometas descuentos, cambios ni devoluciones.\n")
                .append("- Llama a escalar si el cliente pide hablar con una persona, tiene un reclamo, pide descuento, cambio o ")
                .append("devolución, se molesta o no puedes resolver su consulta con las herramientas.\n");
        if (instructions != null && !instructions.isBlank())
            s.append("Indicaciones del dueño de la tienda (respétalas si no contradicen las reglas):\n").append(instructions.strip()).append("\n");
        if (note != null && !note.isBlank())
            s.append("Nota del equipo, que atendió antes esta conversación:\n").append(note.strip()).append("\n");
        return s.toString();
    }
}
