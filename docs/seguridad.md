# Acceso y verificación en dos pasos

## Superadmin

- Credenciales en variables: `SUPERADMIN_EMAIL` y `SUPERADMIN_PASSWORD_HASH` (BCrypt).
- **Primer ingreso:** si no hay segundo factor, la contraseña abre una sesión limitada (`ROLE_PLATFORM_ADMIN_SETUP`), con la que solo se puede llamar `/me` y `/mfa/*`. El frontend lleva a `/superadmin/seguridad/activar` para escanear el QR. Al confirmar el código, la sesión sube a `ROLE_PLATFORM_ADMIN` con un nuevo id de sesión.
- **Compatibilidad:** `SUPERADMIN_TOTP_SECRET` (Base32, 32 caracteres) sigue funcionando mientras no se enrole una app desde el panel. Al enrolar, manda el secreto guardado en la base.
- **Login en dos pasos:** correo y contraseña → `401 {"status":"mfa_required"}` → reenvío con `code` (6 dígitos o código de recuperación).

## Propietarios

- **Ingreso:** en `/ingresar`, con correo y contraseña. El id de la tienda como usuario se acepta por compatibilidad.
- **Varias tiendas:** si el correo administra varias, el login responde `409 {"status":"choose_store","stores":[...]}` y se reenvía con `store`.
- **2FA opcional:** se gestiona desde el panel (tarjeta "Verificación en dos pasos") con `/api/owner/security/mfa/{setup,confirm,recovery-codes,disable}`.
- **Límite de intentos:** 5 fallos cada 5 minutos por correo (`owner_login_attempt`).

## Detalles técnicos

- **TOTP:** RFC 6238 (SHA-1, 6 dígitos, 30 s, tolerancia de ±1 paso) en `auth/service/Totp`. Cada paso usado queda registrado (`last_step`), así un código no sirve dos veces.
- **Secreto:** se guarda cifrado con `SecretCipher`, con contexto `mfa:<tipo>:<sujeto>`. Por eso activar 2FA requiere `SECRETS_ENCRYPTION_KEY`.
- **Códigos de recuperación:** son 10, con el formato `XXXX-XXXX`, y se guarda solo su hash SHA-256. Cada uno es de un solo uso.
- **QR:** el backend lo genera como PNG (zxing) a partir de la URI `otpauth://`. El secreto pendiente vence a los 15 minutos.
