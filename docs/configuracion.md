# Configuración: variables de entorno

Todas las variables que lee el backend, en un solo lugar. La plantilla con todas las variables está en
[`.env.example`](../.env.example).

## Cómo se cargan

1. **Variables del sistema.** Por ejemplo `$env:X = "..."` en PowerShell, `export X=...`, el `environment:` de Docker o
   el panel del hosting. Tienen prioridad.
2. **Archivo `.env`.** El backend lo busca en la carpeta donde arranca (`./.env`) y en la carpeta superior (`../.env`).
   Así sirve tanto la raíz del repo como `backend/`. Tiene formato `.properties`: `CLAVE=valor`, sin comillas y sin
   `export`. Está en `.gitignore`: nunca lo subas.
3. **Valores por defecto** de `application.yml`, que se usan para lo que falte.

No hay JWT: las sesiones del panel usan cookie de sesión de Spring Security y no necesitan una clave propia.

## Obligatorias

| Variable | Formato | Local / test | Producción |
|---|---|---|---|
| `DB_URL` | URL JDBC: `jdbc:postgresql://host:puerto/base` | `jdbc:postgresql://localhost:5432/omni` | La de tu PostgreSQL gestionado, con `?sslmode=require` si el proveedor lo pide |
| `DB_USERNAME` | Texto | `omni` | Usuario propio de la app, sin permisos de superusuario |
| `DB_PASSWORD` | Texto | `omni` | Contraseña larga generada |

Sin estas tres variables el backend no arranca.

## Muy recomendada: clave de cifrado

| Variable | Formato | Local / test | Producción |
|---|---|---|---|
| `SECRETS_ENCRYPTION_KEY` | 32 bytes aleatorios en **Base64** (44 caracteres, termina en `=`). No acepta hex ni texto libre | Una generada para tu máquina. CI usa `MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=` (solo para pruebas) | Una generada solo para producción, guardada en el gestor de secretos del hosting |

Es la clave AES-256-GCM que cifra en la base:

- tokens y app secret de WhatsApp Cloud API;
- tokens de Mercado Pago de cada tienda;
- datos de facturación;
- secretos de la verificación en dos pasos.

Si falta, el backend arranca igual pero avisa en el log (`Cifrado: falta SECRETS_ENCRYPTION_KEY`). Esas pantallas
responden "El servidor no tiene configurada la clave de cifrado de secretos". Si está cargada, el log dice
`Cifrado: SECRETS_ENCRYPTION_KEY cargada`.

Para generarla:

```bash
openssl rand -base64 32
```

```powershell
$b = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b)
```

> **No la cambies** una vez que guardaste credenciales con ella. Con otra clave, los tokens guardados y la verificación
> en dos pasos ya no se pueden leer, y hay que cargarlos de nuevo. Usa una clave distinta por entorno: local,
> staging y producción.

## URLs públicas

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `APP_BASE_URL` | Opcional (por defecto `STOREFRONT_BASE_URL` o `http://localhost:3000`) | URL sin `/` final | `http://localhost:3000` | `https://app.tudominio.com` |
| `STOREFRONT_BASE_URL` | Para Mercado Pago | URL sin `/` final | `http://localhost:3000` | `https://app.tudominio.com` |
| `PUBLIC_API_BASE_URL` | Para webhooks (Meta, Mercado Pago, Culqi) | URL pública del backend | Vacía, o la de ngrok si pruebas webhooks | `https://api.tudominio.com` |
| `EVOLUTION_CALLBACK_BASE_URL` | Opcional (por defecto `PUBLIC_API_BASE_URL`) | URL que Evolution puede alcanzar | `http://host.docker.internal:8080` si Evolution corre en Docker | Igual que `PUBLIC_API_BASE_URL` |

## Superadmin

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `SUPERADMIN_EMAIL` | Para usar `/superadmin` | Correo | El tuyo | El del operador |
| `SUPERADMIN_PASSWORD_HASH` | Para usar `/superadmin` | Hash **BCrypt** (`$2a$10$…`) | Hash de una contraseña de prueba | Hash de una contraseña larga |
| `SUPERADMIN_TOTP_SECRET` | Opcional (heredado) | Base32 de 32 caracteres `[A-Z2-7]` | Vacía: en el primer ingreso se enrola la app con QR | Vacía (se usa el QR) |

Sin email o sin hash, la consola rechaza todos los ingresos.

Para generar el hash:

```bash
htpasswd -bnBC 10 "" 'TuContraseña' | tr -d ':\n'
```

```bash
python -c "import bcrypt; print(bcrypt.hashpw(b'TuContraseña', bcrypt.gensalt(10)).decode())"
```

En PowerShell pon el hash entre **comillas simples**, porque con comillas dobles `$2a$10$…` se interpreta como
variables:

```powershell
$env:SUPERADMIN_PASSWORD_HASH = '$2a$10$...'
```

## IA vendedora (Gemini)

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `GOOGLE_GENAI_API_KEY` | Para la IA | Clave de Google AI Studio (`AIza…`) | Tu clave de pruebas, o **sin definir** | Clave del proyecto de producción |
| `GEMINI_MODEL` | Opcional (`gemini-3.5-flash`) | Id de modelo | Por defecto | Por defecto |
| `GEMINI_FALLBACK_MODEL` | Opcional (`gemini-flash-latest`) | Id de modelo | Por defecto | Por defecto |
| `AI_DEBOUNCE_MS` | Opcional (`8000`) | Milisegundos | `8000` (los tests usan 300) | Entre `8000` y `15000` |

> No definas `GOOGLE_GENAI_API_KEY` vacía, porque Spring AI falla al arrancar. Déjala sin definir y la IA queda
> apagada, con aviso en el panel.

## Multimedia entrante (moderación, voz y visión)

Tres clientes independientes. Cada resultado se guarda en `whatsapp_media` y nunca se vuelve a pedir: un reintento,
un reinicio o la misma foto reenviada (mismo SHA-256 en la tienda) usan lo guardado.

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `SIGHTENGINE_ENABLED` | Opcional (`false`) | `true`/`false` | `false` | `true` |
| `SIGHTENGINE_API_USER` / `SIGHTENGINE_API_SECRET` | Si está activo | Credenciales de sightengine.com | — | Las de la cuenta |
| `SIGHTENGINE_NSFW_THRESHOLD` | Opcional (`0.7`) | 0 a 1 (máximo de `sexual_activity`, `sexual_display`, `erotica`) | `0.7` | `0.6`–`0.8` |
| `SIGHTENGINE_TIMEOUT_MS` | Opcional (`8000`) | Milisegundos | Por defecto | Por defecto |
| `GROQ_TRANSCRIPTION_ENABLED` | Opcional (`false`) | `true`/`false` | `false` | `true` |
| `GROQ_API_KEY` | Si está activo | Clave `gsk_…` | — | Clave de producción |
| `GROQ_WHISPER_MODEL` / `GROQ_WHISPER_LANGUAGE` | Opcionales | `whisper-large-v3-turbo` / `es` | Por defecto | Por defecto |
| `GROQ_TIMEOUT_MS` | Opcional (`20000`) | Milisegundos | Por defecto | Por defecto |
| `VISION_ENABLED` | Opcional (`false`) | `true`/`false` | `false` | `true` |
| `VISION_GEMINI_API_KEY` | Opcional | Clave de AI Studio; vacía usa `GOOGLE_GENAI_API_KEY` | — | Puede ser otra clave |
| `VISION_MODEL` | Opcional (`gemini-flash-lite-latest`) | Id de modelo con visión | Por defecto | Por defecto |
| `VISION_TIMEOUT_MS` | Opcional (`15000`) | Milisegundos | Por defecto | Por defecto |
| `VISION_REQUIRE_MODERATION` | Opcional (`true`) | `true`: sin "seguro" de Sightengine la foto no se analiza | `true` | `true` |

La descripción de productos con IA (`POST catalog/description/generate`) usa `GOOGLE_GENAI_API_KEY` y `GEMINI_MODEL`
(o `CATALOG_DESCRIPTION_MODEL` en `catalog.description-model`); sin clave devuelve un borrador con los datos de la ficha.

## Correo (Resend)

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `RESEND_API_KEY` | Para enviar correos de verdad | `re_…` | Vacía: los correos quedan en `email_log` y en el log | Clave de Resend |
| `EMAIL_FROM` | Opcional | `Nombre <correo@dominio>` | `Omnicanal <onboarding@resend.dev>` | Un remitente de tu dominio verificado en Resend |
| `EMAIL_REPLY_TO` | Opcional | Correo | Vacía | Correo de soporte |

## Suscripción del SaaS (Culqi)

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `CULQI_PUBLIC_KEY` | Para cobrar planes | `pk_test_…` / `pk_live_…` | `pk_test_…` | `pk_live_…` |
| `CULQI_SECRET_KEY` | Para cobrar planes | `sk_test_…` / `sk_live_…` | `sk_test_…` | `sk_live_…` |
| `CULQI_WEBHOOK_TOKEN` | Para el webhook | Texto aleatorio, solo letras y números (va en la URL) | `openssl rand -hex 24` | Otro distinto, generado igual |

La URL del webhook en CulqiPanel es `{PUBLIC_API_BASE_URL}/api/webhooks/culqi/{CULQI_WEBHOOK_TOKEN}`.

## Cobros de las tiendas (Mercado Pago)

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `MP_CLIENT_ID`, `MP_CLIENT_SECRET` | Para conectar tiendas | Credenciales de la aplicación marketplace del SaaS | Aplicación de prueba | Aplicación de producción |
| `MP_REDIRECT_URI` | Para conectar tiendas | URL exacta registrada en la aplicación | `http://localhost:3000/api/owner/payments/mercadopago/callback` | `https://app.tudominio.com/api/owner/payments/mercadopago/callback` |
| `MP_WEBHOOK_SECRET` | Recomendada | Clave secreta de webhooks de Mercado Pago | Vacía (los pagos se vuelven a consultar igual) | La del panel de Mercado Pago |

## WhatsApp

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `WHATSAPP_GRAPH_VERSION` | Opcional (`v23.0`) | `vNN.N` | Por defecto | Por defecto |
| `EVOLUTION_API_URL` | Para la conexión por QR | URL de Evolution API | `http://localhost:8081` | URL interna de tu Evolution |
| `EVOLUTION_API_KEY` | Para la conexión por QR | La `AUTHENTICATION_API_KEY` de Evolution | La de `infra/.env` | Clave larga generada |
| `EVOLUTION_WEBHOOK_SECRET` | Para la conexión por QR | Texto aleatorio (firma HMAC de los webhooks) | `openssl rand -hex 32` | Otro distinto |

WhatsApp Cloud API no usa variables por tienda: cada tienda carga sus datos en el panel y se guardan cifrados con
`SECRETS_ENCRYPTION_KEY`. Para Evolution en Docker, `infra/.env` define además `EVOLUTION_DATABASE_URI`.

## Otras integraciones

| Variable | Obligatoria | Formato | Local / test | Producción |
|---|---|---|---|---|
| `GOOGLE_CLIENT_ID` | Opcional | Client ID web de OAuth (`…apps.googleusercontent.com`) | Vacía (sin botón de Google) o un cliente con origen `http://localhost:3000` | Cliente con el dominio real como origen |
| `APISPERU_ACCOUNT_TOKEN` | Para facturación | Token de la cuenta APISPERU | Token de la cuenta de pruebas | Token de producción |
| `APISPERU_EMISSION_KEY` | Para facturación | 32 bytes en Base64, igual que la clave de cifrado | `openssl rand -base64 32` | Una propia, distinta de `SECRETS_ENCRYPTION_KEY` |
| `APISPERU_BETA_COMPANY_TOKEN_<companyId>` | Opcional | Token de empresa beta fijado por el operador | Solo si APISPERU no devuelve el token | Sin usar |
| `FLYWAY_BASELINE_ON_MIGRATE` | Opcional (`false`) | `true` / `false` | `false` | `true` solo una vez, para adoptar una base existente sin historial de Flyway |

## Frontend (Next.js)

Van en `frontend/.env.local` o en el hosting del frontend, no en el `.env` del backend.

| Variable | Local | Producción |
|---|---|---|
| `BACKEND_API_URL` | `http://localhost:8080/api` (por defecto) | URL interna del backend, con `/api` |
| `NEXT_PUBLIC_API_URL` | Igual que arriba | URL pública del backend, con `/api` |
| `STOREFRONT_PUBLIC_URL` | `http://localhost:3000` | `https://app.tudominio.com` |

## Arranque local con PowerShell

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/omni"
$env:DB_USERNAME = "omni"
$env:DB_PASSWORD = "omni"
$env:SECRETS_ENCRYPTION_KEY = "PEGA_AQUI_LA_CLAVE_BASE64"   # generada una vez, ver arriba
cd backend; .\mvnw.cmd spring-boot:run
```

En lugar de las líneas `$env:` puedes copiar `.env.example` como `.env` en la raíz y completarlo.
