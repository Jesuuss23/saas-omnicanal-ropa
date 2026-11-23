package com.saas.omnicanal.owner.model;

/** Datos extra del formulario de ingreso: código de verificación y tienda elegida (si el correo tiene varias). */
public record OwnerLoginDetails(String code, String store) {}
