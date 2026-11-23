# Roadmap de desarrollo por módulos

Lista priorizada para construir el SaaS omnicanal a partir del documento de producto. Cada módulo indica
qué ya existe en `main`, qué entra en el siguiente incremento y de qué depende. El orden sigue el
roadmap de negocio: **Fase 0 (pilotos sin empresa) → Fase 1 (primeras ventas) → Fase 2 (consolidar ropa)**.

Leyenda: ✅ hecho · 🟡 parcial · ⬜ pendiente

## Prioridad 0 — Base técnica (bloquea todo lo demás)

| # | Módulo | Estado | Incluye | Pendiente |
|---|--------|--------|---------|-----------|
| 0.1 | Estructura por módulos | ✅ | Paquetes `modulo/{model,repository,service,controller,config}`; `shared/` para seguridad, cifrado y clientes HTTP | Extraer SQL de los controladores que aún lo tienen (ver [arquitectura.md](arquitectura.md#deuda-conocida)) |
| 0.2 | Migraciones Flyway | ✅ | V1–V34 versionadas, validación estricta, guía en [migraciones.md](migraciones.md); CI las aplica sobre PostgreSQL limpio | — |
| 0.3 | Cifrado de secretos | ✅ | `SecretCipher` AES-256-GCM con contexto por tienda (`SECRETS_ENCRYPTION_KEY`) | Rotación de clave maestra (re-cifrado por lotes) |
| 0.4 | CI | ✅ | GitHub Actions: tests del backend con PostgreSQL, lint/tipos/build del frontend y pruebas de humo con Playwright | — |
| 0.5 | Acceso y 2FA | ✅ | TOTP enrolable con QR para superadmin y propietarios, códigos de recuperación, ingreso de propietarios por correo ([seguridad.md](seguridad.md)) | — |
| 0.6 | Sitio de venta y prueba gratis | ✅ | Landing, precios desde el backend, registro con enlace mágico, recuperación de contraseña, correos con Resend | Dominio propio y analítica de conversión |

## Prioridad 1 — Fase 0: pilotos operando

| # | Módulo | Estado | Incluye | Pendiente |
|---|--------|--------|---------|-----------|
| 1.1 | WhatsApp · Evolution API (QR) | ✅ | Instancia por tienda, QR, webhook HMAC por tienda, inbox y respuestas manuales | Presupuesto de memoria por número, reconexión automática, alerta de desconexión |
| 1.2 | WhatsApp · Cloud API (oficial) | ✅ | Credenciales por tienda cifradas (phone_number_id, WABA, token, app secret), verificación con Meta, webhook con `X-Hub-Signature-256` y enrutamiento por `phone_number_id`, sincronización de plantillas, pantalla de prueba (texto y plantilla) | Embedded Signup (OAuth de Meta como Tech Provider), media (imágenes/PDF), rate limits por número |
| 1.3 | Canal activo e historial unificado | ✅ | Selector Evolution/Cloud API; el chat se guarda como `E.164@s.whatsapp.net` en ambos, así migrar no pierde historial (escenario D) | Migración asistida con checklist |
| 1.4 | Catálogo, variantes, stock | ✅ | Productos/variantes por tienda, reservas atómicas, etiquetas QR, recursos por categoría | Inventario por local (hoy un stock por variante) e historial de movimientos |
| 1.5 | Checkout web de invitado | ✅ | Reserva, contacto, recojo/delivery con tarifa por distancia, enlace de seguimiento | Login con Google y cuentas (ver 3.2) |
| 1.6 | POS y facturación Beta | 🟡 | Venta POS, solicitud fiscal, emisión SUNAT Beta (APISPERU) | Turnos de caja, cierre/conciliación, recuperar carrito por código |

## Prioridad 2 — Fase 1: cobrar

| # | Módulo | Estado | Incluye | Pendiente |
|---|--------|--------|---------|-----------|
| 2.1 | Planes y entitlements | ✅ | `saas_plan`, `saas_plan_entitlement`, overrides por tienda, `EntitlementService` (cuota de productos ya aplicada) | Aplicar cuotas de usuarios, respuestas IA y comprobantes; medidor de consumo mensual |
| 2.2 | Suscripción SaaS con Culqi | 🟡 | Checkout con Culqi.js → cliente, tarjeta y suscripción; webhook idempotente que re-consulta a Culqi y activa el plan; gracia y suspensión automáticas | Validar nombres de eventos y rutas recurrentes en sandbox de Culqi; cambio/cancelación de plan; comprobante de la suscripción |
| 2.3 | Cobros de la tienda con Mercado Pago | 🟡 | OAuth para vincular la cuenta del dueño (tokens cifrados y renovación), Checkout Pro por pedido, webhook con `x-signature` + re-consulta del pago, cierre único (pedido PAID, stock descontado una vez), marcas de revisión | Reembolsos y posventa, conciliación diaria, comisión del marketplace (`marketplace_fee`) |
| 2.4 | Correos transaccionales | ⬜ | — | Bienvenida, fin de trial (día 10 y 13), pago exitoso/fallido, suspensión |
| 2.5 | Trial self-service con magic link | ⬜ | — | Alta pública → tenant TRIAL + subdominio + entitlements de prueba en una transacción |

## Prioridad 3 — Fase 2: consolidar ropa

| # | Módulo | Pendiente |
|---|--------|-----------|
| 3.1 | IA vendedora | Function calling sobre catálogo/stock, prompt por tienda, kill switch por conversación/tienda/plataforma |
| 3.2 | Identidad unificada del contacto | Entidad `Contacto` (teléfono, email, `google_sub`) con reglas de fusión y log reversible |
| 3.3 | Inbox avanzado y escalamiento | Estados de conversación, asignación, SLA, alertas, devolución a la IA con resumen |
| 3.4 | Facturación en producción | 4 pasos de alta fiscal, activación explícita, reintentos de emisión y envío |
| 3.5 | Pedidos y logística | Preparación, recojo con código, reparto con estados y línea de tiempo |
| 3.6 | Marketing | Segmentos, carrito abandonado, colas de trabajo, campañas por email y plantillas de WhatsApp |
| 3.7 | Dominios | Subdominio administrado y dominio propio con verificación DNS/SSL asíncrona |
| 3.8 | Panel comercial del superadmin | Trials por vencer, conversión, tenants en gracia/suspendidos (base: `/api/superadmin/subscriptions/summary`) |

## Siguiente incremento sugerido

1. CI con PostgreSQL (0.4) para que cada PR corra migraciones y pruebas.
2. Validar Culqi y Mercado Pago en sandbox con cuentas reales de prueba y ajustar el mapeo de eventos (2.2, 2.3).
3. Correos transaccionales + trial con magic link (2.4, 2.5): cierran el escenario B de aceptación.
4. Aplicar el resto de cuotas de entitlements (2.1).
