package com.saas.omnicanal.whatsapp.model;

/** Tipo de archivo de un mensaje de WhatsApp. */
public enum MediaKind {
    IMAGE, VIDEO, AUDIO, DOCUMENT;

    /** Texto que queda como cuerpo del mensaje cuando el archivo llega sin leyenda. */
    public String placeholder() {
        return switch (this) {
            case IMAGE -> "[Imagen]";
            case VIDEO -> "[Video]";
            case AUDIO -> "[Nota de voz]";
            case DOCUMENT -> "[Documento]";
        };
    }

    /** Imágenes y videos salientes dependen del plan (whatsapp.media.send); los PDF de catálogo no. */
    public boolean needsPlan() {
        return this == IMAGE || this == VIDEO;
    }

    public static MediaKind fromMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.startsWith("image/")) return IMAGE;
        if (m.startsWith("video/")) return VIDEO;
        if (m.startsWith("audio/")) return AUDIO;
        return DOCUMENT;
    }
}
