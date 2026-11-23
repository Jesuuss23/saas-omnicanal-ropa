package com.saas.omnicanal.notifications.model;

/** Correo listo para enviar: versión HTML y texto plano. */
public record EmailMessage(String to, String template, String subject, String html, String text) {}
