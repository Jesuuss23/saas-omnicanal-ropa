package com.saas.omnicanal.notifications.service;

import com.saas.omnicanal.notifications.model.EmailMessage;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Plantillas de los correos transaccionales, con un diseño común compatible con clientes de correo. */
@Component
public class EmailTemplates {
    public static final String BRAND = "Omnicanal";
    private final String baseUrl;

    public EmailTemplates(@Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public String url(String path) {
        return baseUrl + path;
    }

    public record StoreLink(String store, String link) {}

    public EmailMessage signupConfirmation(String to, String name, String storeName, String link) {
        String subject = "Confirma tu prueba gratis de " + BRAND;
        String intro = "Hola " + esc(name) + ", recibimos tu solicitud para crear <strong>" + esc(storeName)
                + "</strong>. Confirma tu correo y crea tu contraseña para empezar tus 14 días gratis.";
        return message(to, "signup_confirmation", subject, "Activa tu tienda", intro, "Confirmar y crear contraseña", link,
                "El enlace vence en 48 horas. Si no pediste esta cuenta, ignora este correo.",
                "Hola " + name + ", confirma tu prueba gratis de " + storeName + " en este enlace (vence en 48 horas):\n" + link);
    }

    public EmailMessage passwordReset(String to, List<StoreLink> stores) {
        String subject = "Restablece tu contraseña de " + BRAND;
        if (stores.size() == 1) {
            var store = stores.get(0);
            return message(to, "password_reset", subject, "Restablece tu contraseña",
                    "Pediste restablecer la contraseña de <strong>" + esc(store.store()) + "</strong>.", "Crear nueva contraseña",
                    store.link(), "El enlace vence en 1 hora. Si no fuiste tú, ignora este correo: tu contraseña no cambia.",
                    "Restablece la contraseña de " + store.store() + " (vence en 1 hora):\n" + store.link());
        }
        StringBuilder list = new StringBuilder("<p style=\"margin:0 0 12px\">Tu correo administra varias tiendas. Elige cuál:</p>");
        StringBuilder text = new StringBuilder("Restablece la contraseña de tus tiendas (los enlaces vencen en 1 hora):\n");
        for (var store : stores) {
            list.append("<p style=\"margin:0 0 8px\"><a href=\"").append(esc(store.link()))
                    .append("\" style=\"color:#0f766e;font-weight:600\">").append(esc(store.store())).append("</a></p>");
            text.append("- ").append(store.store()).append(": ").append(store.link()).append('\n');
        }
        return new EmailMessage(to, "password_reset", subject, layout("Restablece tu contraseña", list.toString(), null, null,
                "Los enlaces vencen en 1 hora. Si no fuiste tú, ignora este correo."), text.toString());
    }

    public EmailMessage ownerInvitation(String to, String storeName, String link) {
        return message(to, "owner_invitation", "Te invitaron a administrar " + storeName, "Activa tu acceso",
                "Ya puedes administrar <strong>" + esc(storeName) + "</strong> en " + BRAND
                        + ". Crea tu contraseña para entrar a tu panel.", "Activar mi acceso", link,
                "El enlace vence en 24 horas.", "Activa tu acceso a " + storeName + " (vence en 24 horas):\n" + link);
    }

    public EmailMessage welcome(String to, String name, String storeName, String panelLink, String trialEnds) {
        return message(to, "welcome", "¡Bienvenido a " + BRAND + "!", "Tu tienda está lista",
                "Hola " + esc(name) + ", <strong>" + esc(storeName) + "</strong> ya está creada. Tu prueba gratis dura hasta el "
                        + esc(trialEnds) + ". Empieza cargando tus productos y conectando tu WhatsApp.",
                "Ir a mi panel", panelLink, "Te recomendamos activar la verificación en dos pasos desde tu panel.",
                "Tu tienda " + storeName + " está lista. Entra a tu panel: " + panelLink);
    }

    private EmailMessage message(String to, String template, String subject, String title, String introHtml,
                                 String action, String link, String footnote, String text) {
        return new EmailMessage(to, template, subject, layout(title, "<p style=\"margin:0 0 20px\">" + introHtml + "</p>",
                action, link, footnote), text);
    }

    private static String layout(String title, String bodyHtml, String action, String link, String footnote) {
        String button = action == null ? "" : """
                <p style="margin:0 0 24px"><a href="%s" style="display:inline-block;background:#0f766e;color:#ffffff;\
                text-decoration:none;font-weight:600;padding:12px 22px;border-radius:10px">%s</a></p>
                <p style="margin:0 0 20px;font-size:13px;color:#57534e">Si el botón no funciona, copia este enlace:<br>\
                <span style="word-break:break-all;color:#0f766e">%s</span></p>""".formatted(esc(link), esc(action), esc(link));
        return """
                <!doctype html><html lang="es"><body style="margin:0;background:#f5f5f4;font-family:Inter,Segoe UI,Arial,sans-serif;color:#1c1917">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:32px 12px"><tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:16px;overflow:hidden">
                <tr><td style="background:#134e4a;padding:20px 28px;color:#ffffff;font-weight:700;font-size:18px">%s</td></tr>
                <tr><td style="padding:28px;font-size:15px;line-height:1.6">
                <h1 style="margin:0 0 16px;font-size:22px">%s</h1>%s%s
                <p style="margin:0;font-size:13px;color:#78716c">%s</p></td></tr></table>
                <p style="font-size:12px;color:#a8a29e;margin-top:16px">%s · Plataforma omnicanal para tiendas de ropa</p>
                </td></tr></table></body></html>""".formatted(BRAND, esc(title), bodyHtml, button, footnote == null ? "" : esc(footnote), BRAND);
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
