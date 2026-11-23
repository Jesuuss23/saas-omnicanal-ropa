package com.saas.omnicanal.whatsapp.model;

/**
 * Las dos categorías de conexión de WhatsApp de una tienda.
 * EVOLUTION: conexión rápida por QR (Baileys), para onboarding y bajo volumen.
 * CLOUD_API: API oficial de Meta, para producción, plantillas y campañas.
 */
public enum WhatsAppProvider {
    EVOLUTION,
    CLOUD_API
}
