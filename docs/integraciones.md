# Configuración de integraciones

Todas las credenciales globales llegan por variables de entorno. Las credenciales de cada tienda
(Meta, Mercado Pago) se cargan desde el panel y se guardan cifradas.

## Variables de entorno del backend

Lista completa, con formato, cómo generarlas y valores de local y producción: [configuracion.md](configuracion.md).
Resumen:

| Variable | Uso |
|----------|-----|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `SECRETS_ENCRYPTION_KEY` | Clave maestra AES-256 (32 bytes en Base64: `openssl rand -base64 32`). Sin ella no se guardan tokens ni se puede activar la verificación en dos pasos; el backend lo avisa en el log al arrancar |
| `PUBLIC_API_BASE_URL` | URL pública del backend (para URLs de webhook), p. ej. `https://api.tusaas.com` |
| `STOREFRONT_BASE_URL` | URL pública del frontend (retorno desde Mercado Pago) |
| `EVOLUTION_API_URL`, `EVOLUTION_API_KEY`, `EVOLUTION_WEBHOOK_SECRET` | Evolution API (key global solo para administración) |
| `WHATSAPP_GRAPH_VERSION` | Versión de Graph API (por defecto `v23.0`) |
| `CULQI_PUBLIC_KEY`, `CULQI_SECRET_KEY`, `CULQI_WEBHOOK_TOKEN` | Cuenta Culqi de la empresa (cobro del SaaS) |
| `MP_CLIENT_ID`, `MP_CLIENT_SECRET`, `MP_REDIRECT_URI`, `MP_WEBHOOK_SECRET` | Aplicación de Mercado Pago del SaaS (marketplace) |
| `SUPERADMIN_EMAIL`, `SUPERADMIN_PASSWORD_HASH`, `SUPERADMIN_TOTP_SECRET` | Superadmin |
| `APISPERU_*` | Facturación electrónica |
| `GOOGLE_GENAI_API_KEY`, `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL` | Clave de Gemini (Google AI Studio) para la IA vendedora, modelo (por defecto `gemini-3.5-flash`) y modelo de respaldo ante saturación (por defecto `gemini-flash-latest`). Sin clave la IA no responde y el panel lo indica |
| `GOOGLE_CLIENT_ID` | Client ID web de Google (OAuth) para "Continuar con Google" en la tienda pública. Sin él, el botón no aparece |

## WhatsApp: dos categorías

### Evolution API (conexión rápida por QR)
1. Levantar Evolution (`infra/docker-compose.yml`, con `infra/.env`).
2. En el panel: *WhatsApp de la tienda → Conexión rápida por QR → Conectar* y escanear el QR.
3. Activar el inbox (registra el webhook `/api/webhooks/evolution/{tenantId}` con un header HMAC por tienda).

### WhatsApp Cloud API (oficial de Meta)
Datos que pide el panel (Meta for Developers → tu app → WhatsApp → Configuración de la API):

| Campo | Dónde | Nota |
|-------|-------|------|
| Id del número de teléfono (`phone_number_id`) | Configuración de la API | Enruta los webhooks a la tienda; único en el sistema |
| Id de la cuenta de WhatsApp Business (`waba_id`) | Configuración de la API | Necesario para sincronizar plantillas |
| Token de acceso | Usuario del sistema en Meta Business | Permanente, con `whatsapp_business_messaging` y `whatsapp_business_management` |
| Clave secreta de la app (app secret) | Configuración de la app → Básica | Verifica `X-Hub-Signature-256`; sin ella el webhook rechaza todo |

Después de guardar:
1. El panel verifica las credenciales con Meta y muestra el número y nombre verificado.
2. Copiar la **URL de devolución de llamada** y el **token de verificación** que muestra el panel en
   *WhatsApp → Configuración → Webhook* de la app de Meta y suscribir el campo `messages`.
3. *Sincronizar con Meta* trae las plantillas de la WABA.
4. *Entorno de prueba*: ingresar un número (formato `51987654321`) y enviar una plantilla aprobada o un texto.
   Con el número de prueba de Meta, el destinatario debe estar en la lista de números permitidos y fuera de la
   ventana de 24 h solo se aceptan plantillas.

Endpoints: `GET/PUT/DELETE /api/owner/stores/{id}/whatsapp/cloud`, `POST .../verify`, `GET .../templates`,
`POST .../templates/sync`, `POST .../test-message`, `GET .../test-messages`, y el canal activo en
`GET/PUT /api/owner/stores/{id}/whatsapp/channel`.

## Culqi (suscripción de la tienda al SaaS)
1. Crear en CulqiPanel los planes recurrentes (BASICO, PRO, PREMIUM) y vincular cada id `pln_...` con
   `PUT /api/superadmin/plans/{code}/culqi`.
2. Registrar el webhook: `{PUBLIC_API_BASE_URL}/api/webhooks/culqi/{CULQI_WEBHOOK_TOKEN}` (token largo y aleatorio).
3. El dueño elige plan en *Plan y suscripción*; Culqi Checkout tokeniza la tarjeta en el navegador y el backend
   crea cliente, tarjeta y suscripción. La suscripción queda `PENDING_PAYMENT`.
4. Solo el webhook activa el plan: se registra el evento por id (idempotente), se re-consulta la suscripción a
   Culqi y, si trae `metadata.tenant_id`, se comprueba que sea de la tienda antes de cambiar estado y entitlements.
   Formatos aceptados (`CulqiWebhookService.parse`):
   - evento `{id, type, data}` con `data` en texto JSON u objeto;
   - suscripción suelta del sandbox: `{"message":{"object":{"subsId","planId","email"}}}`;
   - cargo suelto: `{"object":"charge","id","amount","email"}`.

   El id de suscripción se busca en `subsId`, `subscription_id`, `subscriptionId`, `subscription.id` o en el `id` de
   un objeto `subscription`/`sxn_…`. Los cargos (`charge.creation.succeeded`) no lo traen: se ubica la suscripción
   pendiente o activa por el correo con que se pagó (`culqi_email`, guardado en el checkout) y el monto o `planId`
   del plan; si hay varias candidatas o ninguna, el evento queda "Ignorado" con el motivo. Como Culqi avisa la misma
   compra dos veces (suscripción y cargo), un aviso de pago no extiende el plan si el periodo ya está cubierto por
   más de 7 días.
5. **Intentos que no se confirmaron.** Culqi no permite dos suscripciones del mismo cliente al mismo plan
   ("Ya te encuentras registrado a este plan"). Por eso el intento guardado se cancela en Culqi
   (`DELETE culqi.subscriptions.cancel-path`) y se limpia (`culqi_subscription_id`, `pending_plan_code`) cuando:
   - el superadmin ajusta la suscripción (extender prueba, activar a mano sobre algo que no estaba pagado por
     Culqi, o cancelar; cancelar también corta el cobro recurrente);
   - el dueño vuelve a hacer checkout y el intento anterior no está activo. Un `PENDING_PAYMENT` del mismo plan
     es idempotente durante 15 minutos (doble clic); después se descarta y se crea uno nuevo.

   El cliente de Culqi (`culqi_customer_id`) se reutiliza. Los eventos del intento viejo se ignoran porque ya no
   coinciden con ninguna suscripción guardada.

**Pendiente de validar en sandbox**: rutas de la API recurrente (`culqi.subscriptions.*` en `application.yml`)
y nombres exactos de eventos (`CulqiWebhookService.classify`). Sin `type`, un cargo suelto cuenta como
`charge.creation.succeeded` y una suscripción suelta como `subscription.creation.succeeded`.

## Mercado Pago (cobros de cada tienda)
1. Crear una aplicación en Mercado Pago Developers (tipo marketplace/OAuth) y configurar `MP_REDIRECT_URI`
   apuntando al frontend: `https://app.tusaas.com/api/owner/payments/mercadopago/callback`.
2. Copiar la clave secreta de webhooks en `MP_WEBHOOK_SECRET`.
3. El dueño pulsa *Vincular Mercado Pago*, autoriza y vuelve al panel. Los tokens se guardan cifrados y se renuevan
   antes de vencer.
4. En la página del pedido, el comprador pulsa *Pagar con Mercado Pago* (Checkout Pro). El webhook
   `/api/webhooks/mercadopago/{tenantId}` re-consulta el pago y, si el monto coincide, marca el pedido `PAID` y
   descuenta el stock una sola vez. Montos distintos o pagos tardíos quedan con `requires_review`.

## Correos transaccionales (Resend)

- **Configuración:** `RESEND_API_KEY`, `EMAIL_FROM`, `EMAIL_REPLY_TO` opcional y `APP_BASE_URL` (frontend, para armar los enlaces).
- **Remitente:** por defecto `Omnicanal <onboarding@resend.dev>`, que Resend acepta sin dominio verificado pero **solo entrega al correo dueño de la cuenta de Resend**. Para enviar a cualquier persona, verifica tu dominio en Resend y pon `EMAIL_FROM` con ese dominio.
- **Al arrancar** el log dice si Resend está activo (`Correo: Resend activo…`) o si falta la clave (WARN). Cada envío deja `Resend: enviando…` y luego `Resend: aceptado… (id …)` o `Resend: rechazó… → HTTP 4xx: <motivo>` (ERROR).
- **Errores:** si Resend rechaza o no responde, el registro, la recuperación de contraseña y las invitaciones responden 502 y el frontend muestra el error. La bienvenida, las alertas y las campañas usan `sendQuietly`: el fallo queda en el log y en `email_log` sin deshacer la operación.
- **Sin clave (desarrollo y pruebas):** los correos no salen. Se registran en `email_log` con su cuerpo y en el log de la aplicación (WARN), así se pueden abrir los enlaces a mano.
- **Plantillas** (`notifications/service/EmailTemplates`):
  - confirmación de prueba gratis (vence en 48 h);
  - bienvenida;
  - recuperación de contraseña (vence en 1 h, un enlace por tienda);
  - invitación de propietario (vence en 24 h);
  - invitación de miembro del equipo (vence en 72 h).
- **Seguridad de los enlaces:** los tokens viajan en el fragmento de la URL (`#token`), que no llega al servidor ni a los logs. En la base se guarda solo su SHA-256.

## Sitio de venta y prueba gratis

| Endpoint | Uso |
|---|---|
| `GET /api/public/plans` | Planes públicos con sus capacidades (página de precios). |
| `POST /api/public/signup` | Solicitud de prueba gratis. Siempre responde 202, tiene un campo trampa para bots y admite máximo 3 solicitudes por hora por correo. |
| `POST /api/public/signup/preview` | Datos de la solicitud a partir del token. |
| `POST /api/public/signup/confirm` | Crea la tienda, el propietario con contraseña y la suscripción TRIAL. |
| `POST /api/public/password-reset` y `/confirm` | Recuperación de contraseña del propietario. |

## Cuentas de compradores con Google

- **Alta en Google Cloud:** crear un *OAuth client ID* de tipo "Aplicación web" y agregar el dominio del frontend en
  *Authorized JavaScript origins* (por ejemplo `https://tutienda.pe` y `http://localhost:3000` para desarrollo). No hace
  falta *redirect URI*: el botón usa el modo popup de Google Identity Services.
- **Verificación:** el backend valida el ID token con las llaves públicas de Google (firma RS256, emisor, audiencia igual
  a `GOOGLE_CLIENT_ID`, vigencia y `email_verified`). Las llaves se guardan en memoria 6 horas.
- **Sesión:** cookie `omni_customer` HttpOnly, SameSite=Lax, limitada a `/api/public/stores/{tienda}` y válida 30 días.
  En la base solo va el SHA-256 del token (`customer_session`). Con `APP_BASE_URL` en https la cookie es `Secure`.
- **Identidad:** la cuenta de Google o el correo verificado unen al comprador con su contacto. El teléfono escrito en el
  checkout no prueba identidad: si ya es de otro contacto, aparece en "Posibles duplicados" del panel y el dueño decide
  si los une. Cada fusión se puede deshacer (`contact_merge_log`).

## IA vendedora (Gemini)

- **Clave:** `GOOGLE_GENAI_API_KEY` desde Google AI Studio.
- **Modelo:** `GEMINI_MODEL`, por defecto `gemini-3.5-flash`. Los modelos con versión fija se retiran y entonces
  responden 404; la familia `gemini-1.5` ya no existe.
- **Saturación (429/503 "high demand"):** se reintenta el mismo modelo con espera creciente
  (`assistant.gemini-retry-delay-ms`, 1,5 s y luego 3 s) y el último intento usa `GEMINI_FALLBACK_MODEL`
  (por defecto `gemini-flash-latest`). Los errores 4xx distintos de 429 no se reintentan.
  Al arrancar, el backend consulta `GET /v1beta/models/{modelo}`: si no existe deja en ERROR la respuesta de Google y la
  lista de modelos disponibles para la clave.
- **Errores:** cada fallo de Gemini queda en ERROR con código HTTP, estado y mensaje de Google
  (`Gemini HTTP 404 NOT_FOUND: …`), y el mismo texto se guarda en `ai_reply` del mensaje.
- **Interruptores:** global en la consola del superadmin (queda en auditoría), por tienda en Asistente IA y por
  conversación en la bandeja. La cuota mensual sale del plan (`ai.responses.monthly`).
- **Datos que ve el modelo:** los últimos 12 mensajes de la conversación, las indicaciones de la tienda, la nota del
  equipo al devolver la conversación y lo que devuelven las herramientas (catálogo público, recojo y delivery).
  Nunca ve tokens, datos de pago ni otras tiendas.
- **Agrupación de mensajes:** cada mensaje entrante queda `PENDING` en `ai_reply` y reinicia una espera por chat de
  `AI_DEBOUNCE_MS` (`assistant.debounce-ms`, por defecto 8000 ms). Al vencer sin mensajes nuevos, todos los pendientes
  se concatenan en orden de llegada (uno por línea) y van en una sola llamada a Gemini; la respuesta queda en el último
  mensaje y los demás pasan a `BATCHED` con `batch_of`. Los pendientes se toman con un único `UPDATE … RETURNING`
  (`PENDING → WORKING`), así dos hilos o dos instancias nunca responden el mismo mensaje; dentro de una instancia nunca
  corren dos lotes del mismo chat a la vez. Cada minuto se recuperan pendientes huérfanos (reinicio del servidor) y los
  lotes `WORKING` de más de 15 minutos se marcan `FAILED` sin reintentar.
- **Solo temas de la tienda:** el modelo marca las preguntas ajenas (tareas, clima, chistes…) con la herramienta
  `fueraDeTema`, que gana sobre `escalar`: nunca se deriva a una persona. La primera recibe un aviso fijo y suma
  `conversation.off_topic_count`; una consulta válida lo vuelve a cero. La segunda seguida envía un cierre corto y pausa
  la IA en la conversación (`ai_enabled = false`, `ai_paused_reason = 'OFF_TOPIC'`, sigue abierta). Desde ahí los
  mensajes quedan `SKIPPED` con `off_topic_paused` antes de llamar al modelo (0 tokens). Se reinicia cuando el equipo
  activa la IA, toma o devuelve la conversación, o la cierra y el cliente vuelve a escribir. La prueba del panel aplica
  las mismas reglas (el panel envía `offTopicCount`) y agrupa igual los mensajes seguidos.

## Marketing

- El consentimiento vive en `contact` (`email_opt_in`, `whatsapp_opt_in`, `unsubscribed_at`, `unsubscribe_token`). Se da en
  el checkout (casilla de novedades por WhatsApp) o en la cuenta del comprador, y se retira con `/baja/<token>` (el GET
  solo muestra; el POST con JSON da de baja, así un antivirus que abre el enlace no da de baja a nadie).
- Al lanzar una campaña se fija la lista en `campaign_recipient`; `CampaignWorker` envía en lotes de 25 cada
  `marketing.worker-delay-ms` (10 s), vuelve a comprobar el consentimiento antes de cada envío y nunca reintenta un envío
  dudoso. La cuota es `campaigns.email` / `campaigns.whatsapp` por destinatario y mes (hora de Lima).
- WhatsApp solo usa plantillas aprobadas de Cloud API, sin variables o con `{{1}}` = primer nombre del cliente.
- Carrito abandonado: un recordatorio por reserva con datos y aceptación de novedades, pasada la espera configurada y
  dentro de 24 horas.

## Facturación en producción (SUNAT)

- `fiscal_production_setup` guarda usuario SOL, clave SOL, certificado `.pfx` y su clave cifrados con `SecretCipher`
  (contexto por tienda), más sujeto y vencimiento del certificado. Cambiar cualquier paso vuelve el alta a borrador.
- La activación exige los 4 pasos, la capacidad `fiscal.production` y que el propietario escriba su RUC.
- `FiscalReconciler` consulta cada minuto los comprobantes en `BETA_REVIEW` con espera creciente (2, 4, 8… min, tope 6 h,
  máximo 10 intentos). Solo consulta el estado: nunca reenvía.
- **Pendiente:** el envío real a producción en APISPERU no está conectado. La activación deja todo listo, pero hay que
  validarlo con una cuenta de APISPERU de producción y un certificado real antes de emitir comprobantes con validez.
