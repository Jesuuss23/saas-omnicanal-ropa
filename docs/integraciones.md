# Configuración de integraciones

Todas las credenciales globales llegan por variables de entorno. Las credenciales de cada tienda
(Meta, Mercado Pago) se cargan desde el panel y se guardan cifradas.

## Variables de entorno del backend

| Variable | Uso |
|----------|-----|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `SECRETS_ENCRYPTION_KEY` | Clave maestra AES-256 (32 bytes en Base64: `openssl rand -base64 32`). Sin ella no se guardan tokens |
| `PUBLIC_API_BASE_URL` | URL pública del backend (para URLs de webhook), p. ej. `https://api.tusaas.com` |
| `STOREFRONT_BASE_URL` | URL pública del frontend (retorno desde Mercado Pago) |
| `EVOLUTION_API_URL`, `EVOLUTION_API_KEY`, `EVOLUTION_WEBHOOK_SECRET` | Evolution API (key global solo para administración) |
| `WHATSAPP_GRAPH_VERSION` | Versión de Graph API (por defecto `v23.0`) |
| `CULQI_PUBLIC_KEY`, `CULQI_SECRET_KEY`, `CULQI_WEBHOOK_TOKEN` | Cuenta Culqi de la empresa (cobro del SaaS) |
| `MP_CLIENT_ID`, `MP_CLIENT_SECRET`, `MP_REDIRECT_URI`, `MP_WEBHOOK_SECRET` | Aplicación de Mercado Pago del SaaS (marketplace) |
| `SUPERADMIN_EMAIL`, `SUPERADMIN_PASSWORD_HASH`, `SUPERADMIN_TOTP_SECRET` | Superadmin |
| `APISPERU_*` | Facturación electrónica |

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
   Culqi y se comprueba `metadata.tenant_id` antes de cambiar estado y entitlements.

**Pendiente de validar en sandbox**: rutas de la API recurrente (`culqi.subscriptions.*` en `application.yml`)
y nombres exactos de eventos (`CulqiWebhookService.classify`).

## Mercado Pago (cobros de cada tienda)
1. Crear una aplicación en Mercado Pago Developers (tipo marketplace/OAuth) y configurar `MP_REDIRECT_URI`
   apuntando al frontend: `https://app.tusaas.com/api/owner/payments/mercadopago/callback`.
2. Copiar la clave secreta de webhooks en `MP_WEBHOOK_SECRET`.
3. El dueño pulsa *Vincular Mercado Pago*, autoriza y vuelve al panel. Los tokens se guardan cifrados y se renuevan
   antes de vencer.
4. En la página del pedido, el comprador pulsa *Pagar con Mercado Pago* (Checkout Pro). El webhook
   `/api/webhooks/mercadopago/{tenantId}` re-consulta el pago y, si el monto coincide, marca el pedido `PAID` y
   descuenta el stock una sola vez. Montos distintos o pagos tardíos quedan con `requires_review`.
