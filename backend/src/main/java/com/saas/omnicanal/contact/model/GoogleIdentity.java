package com.saas.omnicanal.contact.model;

/** Datos verificados de un ID token de Google (el correo solo se acepta si Google lo marcó como verificado). */
public record GoogleIdentity(String subject, String email, String name) {}
