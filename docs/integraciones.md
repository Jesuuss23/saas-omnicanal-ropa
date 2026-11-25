# Configuración de integraciones

Todas las credenciales globales llegan por variables de entorno. Las credenciales de cada tienda
(Meta, llaves de Culqi de cada tienda) se cargan desde el panel y se guardan cifradas.

## Variables de entorno del backend

Lista completa, con formato, cómo generarlas y valores de local y producción: [configuracion.md](configuracion.md).
Resumen:

| Variable | Uso |
|----------|-----|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `SECRETS_ENCRYPTION_KEY` | Clave maestra AES-256 (32 bytes en Base64: `openssl rand -base64 32`). Sin ella no se guardan tokens ni se puede activar la verificación en dos pasos; el backend lo avisa en el log al arrancar |
| `PUBLIC_API_BASE_URL` | URL pública del backend (para URLs de webhook), p. ej. `https://api.tusaas.com` |
| `STOREFRONT_BASE_URL` | URL pública del frontend (enlaces de seguimiento) |
| `EVOLUTION_API_URL`, `EVOLUTION_API_KEY`, `EVOLUTION_WEBHOOK_SECRET` | Evolution API (key global solo para administración) |
| `WHATSAPP_GRAPH_VERSION` | Versión de Graph API (por defecto `v23.0`) |
| `CULQI_PUBLIC_KEY`, `CULQI_SECRET_KEY`, `CULQI_WEBHOOK_TOKEN` | Cuenta Culqi de la empresa (cobro del SaaS) |
| `SUPERADMIN_EMAIL`, `SUPERADMIN_PASSWORD_HASH`, `SUPERADMIN_TOTP_SECRET` | Superadmin |
| `APISPERU_*` | Facturación electrónica |
| `GOOGLE_GENAI_API_KEY`, `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL` | Clave de Gemini (Google AI Studio) para la IA vendedora, modelo (por defecto `gemini-3.5-flash`) y modelo de respaldo ante saturación (por defecto `gemini-flash-latest`). Sin clave la IA no responde y el panel lo indica |
| `GOOGLE_CLIENT_ID` | Client ID web de Google (OAuth) para "Continuar con Google" en la tienda pública. Sin él, el botón no aparece |

## WhatsApp: dos categorías

### Evolution API (conexión rápida por QR)
1. Levantar Evolution (`infra/docker-compose.yml`, con `infra/.env`).
2. En el panel: *WhatsApp de la tienda → Conexión rápida por QR → Conectar* y escanear el QR.
3. Activar el inbox (registra el webhook `/api/webhooks/evolution/{tenantId}` con un header HMAC por tienda).

Compatibilidad con Evolution 2.3.x (Baileys 7):
- **Chats `@lid`:** el chat se identifica por `key.remoteJid` tal cual (`…@lid` o `…@s.whatsapp.net`, sin el sufijo
  de dispositivo `:NN`). Si es `@lid`, el número visible se toma de `key.remoteJidAlt` (o `key.senderPn`) y queda en
  `conversation.phone`. Así la bandeja muestra el teléfono y lo asocia al contacto. Grupos (`@g.us`) y estados se ignoran.
- **Envíos:** las respuestas del panel y de la IA van a `POST /message/sendText/{instance}` con el `remoteJid` completo en
  `number`, sin limpiarlo. Cloud API solo responde chats de número: un chat `@lid` responde 409 allí.
- **`fromMe`:** los mensajes que la tienda envía también llegan en `MESSAGES_UPSERT`. Se guardan como autor `PHONE`
  ("Desde el celular" en la bandeja) y nunca disparan la IA. No se guarda el eco de un envío del panel o de la IA: se
  reconoce por el mismo `key.id`, o por el mismo texto aún en curso si el webhook llega antes que la respuesta del envío.
- **Acuses (`MESSAGES_UPDATE`):** se aceptan en chats `@lid` y `@s.whatsapp.net`.
- **Estado de conexión:** el último estado (`CONNECTED`, `PAIRING`, `DISCONNECTED`, `UNLINKED`) queda en
  `whatsapp_channel_setting.evolution_state` con su hora. Se actualiza con "Actualizar estado" del panel
  (`POST /api/owner/stores/{id}/whatsapp/refresh`, que consulta en vivo `GET /instance/connectionState/{instance}`), con
  `GET .../whatsapp` y con el webhook `CONNECTION_UPDATE` (`open`, `connecting`, `close`). Si la instancia está `open`
  pero la tienda no la tenía asignada (por ejemplo, se vinculó o reconectó el QR desde Evolution Manager), se asigna sola.
- **Antes de un 409 al responder:** `PUT .../whatsapp/replies/{requestId}` y `.../media` consultan en vivo el estado si
  la instancia no figura asignada; si está `open`, guardan el estado y envían normalmente. Solo responden 409 "Conecta el
  número de WhatsApp antes de responder." cuando Evolution confirma que no está conectada.
- **Webhook:** "Activar inbox" registra `MESSAGES_UPSERT`, `MESSAGES_UPDATE` y `CONNECTION_UPDATE` con el header de la
  tienda. Las tiendas que lo activaron antes deben pulsarlo otra vez para recibir `CONNECTION_UPDATE`. Un webhook creado a
  mano en Evolution Manager no lleva el header y se rechaza.

### Archivos (voz, imagen, video y documentos)

- **Entrantes:** Evolution (`audioMessage`, `imageMessage`, `videoMessage`, `documentMessage`, también dentro de
  `ephemeralMessage` o `viewOnceMessage`) y Cloud API (`type` `audio`, `image`, `video`, `document`). El mensaje se guarda
  al instante con `media_type`, `mime_type`, `caption` y `media_status = 'PENDING'`; el cuerpo es la leyenda o un marcador
  (`[Nota de voz]`, `[Imagen]`, `[Video]`, `[Documento]`) que también recibe la IA. Después de confirmar la transacción,
  `WhatsAppMediaService` descarga el archivo en segundo plano: Evolution con `message.base64` si viene en el webhook o
  `POST /chat/getBase64FromMediaMessage/{instance}`; Cloud API con `GET /{media_id}` y la URL que devuelve, con el token
  de la tienda. Queda en `whatsapp_media` (PostgreSQL, hasta 16 MB) con `media_url = /media/{id}` y estado `STORED`;
  si falla, `FAILED`, y si es más grande, `TOO_LARGE`.
- **Lectura:** `GET /api/owner/stores/{id}/whatsapp/media/{mediaId}`, solo para el equipo de la tienda.
- **Envío:** `POST /api/owner/stores/{id}/whatsapp/replies/{requestId}/media` (multipart `chat`, `caption`, `file`). El tipo
  se valida por los primeros bytes: JPG, PNG, WebP, MP4/3GP o PDF. Evolution usa `POST /message/sendMedia/{instance}` en
  base64; Cloud API sube el archivo a `POST /{phone_number_id}/media` y lo envía por id. Imágenes y videos (de la bandeja o
  de la IA) requieren la capacidad `whatsapp.media.send` (planes Premium y Piloto); sin ella el backend responde 402 con
  "Tu plan no incluye enviar imágenes ni videos por WhatsApp. Está disponible en el plan Premium.". Los PDF se envían con
  cualquier plan. `GET .../whatsapp/media/capabilities` devuelve `{send}` para la bandeja.

### Pipeline multimedia entrante (voz, NSFW y visión)

Las notas de voz y las imágenes se publican primero solo para la bandeja (`InboundMessageEvent.Delivery.INBOX`); la IA
espera a que `WhatsAppMediaService.process` termine y recibe un evento `ASSISTANT` con el texto en
`whatsapp_inbound_message.ai_context`. Cada paso guarda su resultado en `whatsapp_media` (columna nula = pendiente) y
hereda lo ya calculado para el mismo `content_sha256` de la tienda, así que **ninguna API externa se llama dos veces**.

1. **Audio** → Groq Whisper (`TranscriptionClient`). `transcript` se muestra bajo el reproductor y la IA lo recibe como
   `[Nota de voz transcrita] …`.
2. **Imagen** → Sightengine (`ModerationClient`, `nudity-2.1`). Si el puntaje supera el umbral: `moderation_status =
   BLOCKED_NSFW`, `media_status = BLOCKED_NSFW`, la vista previa se oculta (`GET media/{id}` responde 451 salvo
   `?reveal=true`), se registra `whatsapp_media_incident` (NSFW), se envía el aviso automático por el canal del chat
   (Evolution o Cloud API) y, si `store_ai_settings.nsfw_pause_ai`, la IA se pausa con motivo `NSFW`.
3. **Imagen segura** → `VisionCatalogService` (Gemini Flash-Lite con `responseSchema`) devuelve `esConsultaValidaTienda`,
   `tipoPrenda`, `subcategoria`, `color`, `estilo` y `resumen`, más `coincidencias` de `PublicCatalogRepository.similar`
   (tipo 3 + subcategoría 2 + color 2, sin tildes ni plural, solo activos con stock). Fuera de contexto aplica la regla
   de tema ajeno de la tienda (aviso o pausa, incidente `OFF_CONTEXT`). Válida: la IA principal recibe solo el resumen y
   las coincidencias en texto, nunca la imagen, y puede responder con `enviarFotoPrenda` si el plan permite fotos.

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

## Culqi (cobros de cada tienda)
El dinero de cada venta web va directo a la cuenta Culqi del comercio: el SaaS cobra con la llave secreta de la tienda.

1. **Conexión** (`PUT /api/owner/stores/{id}/payments/culqi`, solo el dueño, capacidad `payments.online`): el dueño
   pega `public_key` (`pk_test_`/`pk_live_`) y `secret_key` (`sk_test_`/`sk_live_`) del mismo modo. El backend las
   valida con `GET https://api.culqi.com/v2/charges?limit=1` (`Authorization: Bearer sk_…`; 200 = válidas) y guarda
   en `store_payment_account` `provider='CULQI'`, la pública, la secreta cifrada en `access_token_ciphertext`
   (`SecretCipher`, contexto `culqi/secret-key/{tienda}`), `live_mode` según `sk_live_` y `status='ACTIVE'`.
   `GET` muestra solo `sk_…••••1234`; `DELETE` desconecta.
2. **Configuración pública**: `GET /api/public/stores/{id}/payment-config` → `{enabled, provider, publicKey, liveMode}`.
3. **Pago** en la página del pedido (`tienda/[store]/pedido/[order]`): Culqi Checkout v4 tokeniza la tarjeta con la llave
   pública de la tienda (`tkn_…` + email del comprador) y el navegador llama
   `POST /api/public/stores/{id}/orders/{orderId}/culqi/pay` con `X-Order-Token`. El backend exige `PENDING_PAYMENT`,
   reserva activa, monto conocido y token del mismo modo que la tienda; cobra con `POST /v2/charges` (`amount` en
   céntimos, `currency_code: PEN`, `email`, `source_id`, `metadata.tenant_id/order_id`). Con 201 y
   `outcome.type = venta_exitosa` registra el pago y marca `PAID` (reserva consumida y stock descontado una vez) en la
   misma transacción. Un pedido ya pagado no se vuelve a cobrar.
4. **3‑D Secure**: si Culqi responde `action_code: REVIEW`, el endpoint devuelve `REQUIRES_3DS`; el navegador abre
   Culqi 3DS (`https://3ds.culqi.com`) y reintenta con `authentication3DS` y el mismo token. Rechazos (4xx) devuelven
   `DECLINED` con el `user_message` de Culqi.
5. **Webhook de respaldo**: `POST /api/webhooks/culqi/stores/{storeId}` (el dueño lo registra en Culqi para
   `charge.creation.succeeded` y `refund.creation.succeeded`). Es idempotente (`payment_webhook_event`, id
   `store:{tienda}:{evt}`) y nunca confía en el cuerpo: vuelve a pedir `GET /v2/charges/{id}` con la llave de la tienda.
   Un cargo aprobado cierra el pedido si el navegador no alcanzó a hacerlo; un reembolso marca el pago `REFUNDED`
   con `requires_review`. (`/api/webhooks/culqi/{token}` sigue siendo el de la suscripción del SaaS.)

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
- **Categorías y subcategorías:** `product.subcategory` (por ejemplo Polos → Retro, Verano, Invierno). La herramienta
  `categorias` devuelve el árbol con prendas en stock; ante una pregunta general, la IA cuenta la variedad, pregunta cuál
  se adecua mejor y ofrece más información o el catálogo de la categoría.
- **Catálogo por categoría (un solo recurso):** cada categoría puede tener PDF, imagen y enlace, pero se usa uno. El
  dueño elige cuál (`active`) o deja Automático, que toma el primero cargado en orden **PDF → imagen → enlace**. La IA lo
  manda con `enviarCatalogo`: el PDF subido o generado se adjunta como documento después del texto, la imagen solo si el
  plan tiene `whatsapp.media.send` (si no, pasa al siguiente) y el enlace solo si es público. El PDF se genera desde el
  catálogo (`GET /api/owner/stores/{id}/catalog/pdf?category=` para descargarlo, `POST .../catalog/resources/generated-pdf`
  para guardarlo como el PDF de la categoría): A4, 6 prendas por página con foto, subcategoría, precios, tallas y colores,
  hasta 10 MB.
- **Sin enlaces locales:** si `STOREFRONT_BASE_URL` es localhost, una IP privada o un host sin dominio, la IA no recibe
  enlace del catálogo y el texto final pasa por `PublicLinks.stripPrivate`, que borra cualquier URL no pública.

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
