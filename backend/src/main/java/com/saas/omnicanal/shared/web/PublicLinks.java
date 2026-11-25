package com.saas.omnicanal.shared.web;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Enlaces que se pueden mandar a un cliente por WhatsApp. Una URL de desarrollo (localhost, 127.0.0.1, IP privada o
 * *.local) no sirve fuera del servidor: nunca se comparte.
 */
public final class PublicLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"']+", Pattern.CASE_INSENSITIVE);
    private PublicLinks() {}

    public static boolean isPublic(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("http")) return false;
            return !host.isEmpty() && !localHost(host);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean localHost(String host) {
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        return h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.equals("0.0.0.0") || h.equals("::1") || h.startsWith("127.") || h.startsWith("10.")
                || h.startsWith("192.168.") || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*") || h.startsWith("169.254.")
                || h.equals("host.docker.internal") || !h.contains(".") && !h.contains(":");
    }

    /** Quita del texto los enlaces que no son públicos (el resto del mensaje queda igual). */
    public static String stripPrivate(String text) {
        if (text == null) return null;
        var m = URL.matcher(text);
        var out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, isPublic(m.group()) ? java.util.regex.Matcher.quoteReplacement(m.group()) : "");
        m.appendTail(out);
        return out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("[ \\t]{2,}", " ").replaceAll("[:：]\\s*$", ".").strip();
    }
}
