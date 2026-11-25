package com.saas.omnicanal.subscription.model;

/** Claves estables de entitlements. El código consulta estas claves, nunca el nombre del plan. */
public final class Entitlements {
    public static final String CATALOG_PRODUCTS = "catalog.products";
    public static final String TEAM_USERS = "team.users";
    public static final String AI_RESPONSES_MONTHLY = "ai.responses.monthly";
    public static final String FISCAL_DOCUMENTS_MONTHLY = "fiscal.documents.monthly";
    public static final String FISCAL_PRODUCTION = "fiscal.production";
    public static final String WHATSAPP_EVOLUTION = "whatsapp.evolution";
    public static final String WHATSAPP_CLOUD_API = "whatsapp.cloud_api";
    public static final String DOMAIN_CUSTOM = "domain.custom";
    public static final String CAMPAIGNS_EMAIL = "campaigns.email";
    public static final String CAMPAIGNS_WHATSAPP = "campaigns.whatsapp";
    public static final String PAYMENTS_MERCADOPAGO = "payments.mercadopago";
    /** Enviar imágenes y videos por WhatsApp (bandeja e IA). */
    public static final String WHATSAPP_MEDIA_SEND = "whatsapp.media.send";

    private Entitlements() {}
}
