package com.saas.omnicanal.contact.service;

/** Teléfonos en E.164 sin "+" (como los chats de WhatsApp). Los celulares peruanos de 9 dígitos se completan con 51. */
public final class Phones {
    private Phones() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.matches("9[0-9]{8}")) digits = "51" + digits;
        return digits.matches("[0-9]{7,15}") ? digits : null;
    }

    public static String chatId(String phone) {
        return phone + "@s.whatsapp.net";
    }
}
