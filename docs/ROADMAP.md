# Roadmap de desarrollo por módulos

Lista priorizada para construir el SaaS omnicanal a partir del documento de producto. Cada módulo indica
qué ya existe en `main`, qué entra en el siguiente incremento y de qué depende. El orden sigue el
roadmap de negocio: **Fase 0 (pilotos sin empresa) → Fase 1 (primeras ventas) → Fase 2 (consolidar ropa)**.

Leyenda: ✅ hecho · 🟡 parcial · ⬜ pendiente

## Prioridad 0 — Base técnica (bloquea todo lo demás)

| # | Módulo | Estado | Incluye | Pendiente |
|---|--------|--------|---------|-----------|
| 0.1 | Estructura por módulos | ✅ | Paquetes `modulo/{model,repository,service,controller,config}`; `shared/` para seguridad, cifrado y clientes HTTP | Extraer SQL de los controladores que aún lo tienen (ver [arquitectura.md](arquitectura.md#deuda-conocida)) |
| 0.2 | Migraciones Flyway | ✅ | V1–V47 versionadas, validación estricta, guía en [migraciones.md](migraciones.md); CI las aplica sobre PostgreSQL limpio | — |
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
| 1.4 | Catálogo, variantes, stock | ✅ | Productos/variantes por tienda, reservas atómicas, etiquetas QR (una por talla y color, sin copias), alta guiada por tallas × colores con SKU automático, recursos por categoría, fotos de producto (hasta 8, delantera y trasera, en PostgreSQL para el piloto), estados activo/oculto/agotado, precio antes y costo con margen, filtros rápidos, descripción con IA y ficha pública con lupa | Inventario por local (hoy un stock por variante) e historial de movimientos |
| 1.5 | Checkout web y cuentas | ✅ | Reserva, contacto, recojo/delivery con tarifa por distancia, enlace de seguimiento; cuenta del comprador con Google y "Mis pedidos" | Ingreso por código al celular o correo |
| 1.6 | POS y facturación Beta | 🟡 | Venta POS con buscador de productos, QR por variante que agrega la prenda directo, solicitud fiscal, emisión SUNAT Beta (APISPERU) | Turnos de caja, cierre/conciliación, recuperar carrito por código |
| 1.7 | Panel de tienda | ✅ | Navegación lateral por secciones, inicio con métricas y pasos de configuración, apariencia de la tienda (logo, color, contacto) | Mover imágenes a R2/S3 cuando haya dominio y bucket |
| 1.8 | Equipo y roles | ✅ | Miembros por tienda (`store_member`) con rol Encargado o Vendedor, invitación por correo con enlace de 72 h, cuota `team.users`, reglas por rol en `OwnerSecurity` y menú según rol | Registro de acciones por miembro, permisos finos por sección |
| 1.9 | Tienda pública con marca | ✅ | Cabecera con logo, color, frase, WhatsApp e Instagram; prendas agrupadas por producto con selección de talla y color, portada con foto, carrito fijo | Página de detalle por prenda con galería |

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
| 3.1 | IA vendedora | ✅ Gemini (Spring AI) con herramientas sobre catálogo y stock (`buscarPrendas`, `infoTienda`, `escalar`), indicaciones por tienda, interruptores por conversación, tienda y plataforma, cuota `ai.responses.monthly`, idempotencia por mensaje (`ai_reply`), prueba desde el panel, agrupación de mensajes seguidos en una sola respuesta (`assistant.debounce-ms`) y dominio estricto (aviso y pausa sin escalar tras 2 preguntas ajenas a la tienda), respuestas guiadas por categorías y subcategorías (`categorias`) y envío del catálogo de la categoría (`enviarCatalogo`: un solo recurso, PDF como archivo, imagen solo con `whatsapp.media.send`). Notas de voz transcritas (Groq Whisper), fotos con filtro NSFW (Sightengine) y visión del catálogo (Gemini Flash-Lite) con caché estricta en BD, plantilla base de indicaciones y `enviarFotoPrenda`. Pendiente: memoria de largo plazo |
| 3.2 | Identidad unificada del contacto | ✅ `contact` por tienda (teléfono E.164, correo, `google_sub`), pedidos web enlazados, posibles duplicados, fusión manual reversible (`contact_merge_log`) y vista en Clientes. Pendiente: enlazar ventas POS y comprobantes al contacto |
| 3.3 | Inbox avanzado y escalamiento | ✅ Bandeja con estados (abierta, para el equipo, cerrada), tomar conversación, SLA por tienda con marca de vencida, alerta por correo al escalar, devolución a la IA con nota y resumen de la IA para el equipo, notas de voz, fotos, videos y documentos entrantes (Evolution y Cloud API) guardados en `whatsapp_media`, y envío de PDF (todos los planes) e imágenes y videos (`whatsapp.media.send`, Premium). Pendiente: asignación entre miembros y notificaciones push |
| 3.4 | Facturación en producción | 🟡 Alta en 4 pasos (emisor, usuario SOL, certificado .pfx validado y cifrado, series), activación explícita con RUC escrito y capacidad `fiscal.production`, conciliación automática de comprobantes en revisión con espera creciente (nunca reenvía). Pendiente: conectar el envío real a producción en APISPERU y validarlo con una cuenta y certificado reales; notas de crédito |
| 3.5 | Pedidos y logística | Preparación, recojo con código, reparto con estados y línea de tiempo |
| 3.6 | Marketing | ✅ Consentimiento por contacto (checkout, cuenta del comprador, baja por enlace), segmentos (compradores, recencia, gasto), campañas por correo y plantillas de WhatsApp con cola `FOR UPDATE SKIP LOCKED`, cuota mensual `campaigns.*`, recordatorio único de carrito abandonado. Pendiente: plantillas con varias variables, métricas de apertura y clics |
| 3.7 | Dominios | Pantalla Dominio en el panel solo visual (sin funciones). Pendiente: subdominio administrado y dominio propio con verificación DNS/SSL asíncrona |
| 3.8 | Panel comercial del superadmin | ✅ Consola con KPIs (MRR, pruebas por vencer, morosas), tiendas con búsqueda y ficha, suspensión con kill switch (423), ajustes manuales de suscripción, capacidades por tienda, pagos y auditoría (`platform_audit_log`). Pendiente: embudo de conversión y exportación CSV |

## Siguiente incremento sugerido

1. CI con PostgreSQL (0.4) para que cada PR corra migraciones y pruebas.
2. Validar Culqi y Mercado Pago en sandbox con cuentas reales de prueba y ajustar el mapeo de eventos (2.2, 2.3).
3. Correos transaccionales + trial con magic link (2.4, 2.5): cierran el escenario B de aceptación.
4. Aplicar el resto de cuotas de entitlements (2.1).
5. Configurar `GOOGLE_GENAI_API_KEY` y probar la IA vendedora con conversaciones reales antes de activarla en pilotos.
