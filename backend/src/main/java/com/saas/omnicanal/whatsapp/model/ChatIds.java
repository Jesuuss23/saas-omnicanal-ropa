package com.saas.omnicanal.whatsapp.model;

/** Normaliza identificadores de chat para que ambos proveedores compartan historial. */
public final class ChatIds {
    public static final String SUFFIX = "@s.whatsapp.net";
    private ChatIds() {}

    /** "51987654321" (wa_id de Cloud API) -> "51987654321@s.whatsapp.net" (formato de Evolution). */
    public static String fromPhone(String digits) {
        return digits + SUFFIX;
    }

    public static boolean isPhoneChat(String chat) {
        return chat != null && chat.matches("[0-9]{5,15}@s[.]whatsapp[.]net");
    }

    public static String phoneOf(String chat) {
        return chat.substring(0, chat.indexOf('@'));
    }

    /** Acepta "+51 987 654 321", "51-987654321", etc. Devuelve solo dígitos E.164 o null. */
    public static String normalizePhone(String input) {
        if (input == null) return null;
        String digits = input.replaceAll("[\\s()+.-]", "");
        return digits.matches("[0-9]{8,15}") ? digits : null;
    }
}
