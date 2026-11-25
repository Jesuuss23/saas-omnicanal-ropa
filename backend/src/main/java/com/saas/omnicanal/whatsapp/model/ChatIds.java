package com.saas.omnicanal.whatsapp.model;

/**
 * Identificadores de chat. Cloud API y Evolution comparten el formato {@code 51987654321@s.whatsapp.net}; Evolution
 * 2.3.x (Baileys 7) además usa {@code 123456789012345@lid}, un identificador que no es el número de teléfono.
 */
public final class ChatIds {
    public static final String SUFFIX = "@s.whatsapp.net";
    public static final String LID = "@lid";
    private ChatIds() {}

    /** "51987654321" (wa_id de Cloud API) -> "51987654321@s.whatsapp.net" (formato de Evolution). */
    public static String fromPhone(String digits) {
        return digits + SUFFIX;
    }

    /** Chat cuyo identificador es el número: {@code 51987654321@s.whatsapp.net}. */
    public static boolean isPhoneChat(String chat) {
        return chat != null && chat.matches("[0-9]{5,15}@s[.]whatsapp[.]net");
    }

    /** Chat con identificador anónimo de WhatsApp: {@code 123456789012345@lid}. */
    public static boolean isLidChat(String chat) {
        return chat != null && chat.matches("[0-9]{5,25}@lid");
    }

    /** Chat individual al que se puede responder (no grupos, estados ni canales). */
    public static boolean isDirectChat(String chat) {
        return isPhoneChat(chat) || isLidChat(chat);
    }

    /** Quita el sufijo de dispositivo que a veces trae Baileys: "51987654321:12@s.whatsapp.net" -> "51987654321@s.whatsapp.net". */
    public static String withoutDevice(String jid) {
        return jid == null ? "" : jid.strip().replaceFirst(":[0-9]+@", "@");
    }

    /** Número de un chat de teléfono; null en chats @lid. */
    public static String phoneOf(String chat) {
        return isPhoneChat(chat) ? chat.substring(0, chat.indexOf('@')) : null;
    }

    /** Acepta "+51 987 654 321", "51-987654321", etc. Devuelve solo dígitos E.164 o null. */
    public static String normalizePhone(String input) {
        if (input == null) return null;
        String digits = input.replaceAll("[\\s()+.-]", "");
        return digits.matches("[0-9]{8,15}") ? digits : null;
    }
}
