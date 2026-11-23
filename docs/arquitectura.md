# Arquitectura del backend

Spring Boot modular (monolito), PostgreSQL con aislamiento por `tenant_id`.

## Paquetes

```
com.saas.omnicanal
├── shared/            # transversal: config de seguridad, OwnerAccess, SecretCipher, Hmac, HttpClients
├── tenant/            # tiendas (tenant)
├── owner/             # acceso del dueño (invitación, login)
├── platformadmin/     # superadmin con 2FA
├── catalog/           # productos, variantes, recursos, etiquetas
├── checkout/          # reservas, pedidos web, seguimiento
├── pos/               # punto de venta
├── fiscal/            # facturación electrónica (SUNAT vía APISPERU)
├── crm/
├── fulfillment/       # recojo y delivery
├── whatsapp/          # Evolution API (QR) y WhatsApp Cloud API
├── subscription/      # planes, entitlements y suscripción SaaS (Culqi)
└── payments/          # cobros de la tienda con Mercado Pago
```

Cada módulo se divide en:

| Capa | Responsabilidad | Reglas |
|------|-----------------|--------|
| `model` | Records, enums y entidades del dominio | Sin dependencias de Spring web |
| `repository` | Acceso a datos (`JdbcTemplate` o Spring Data) | Toda consulta filtra por `tenant_id`; nada de lógica de negocio |
| `service` | Reglas de negocio, transacciones, clientes de APIs externas | `@Transactional` aquí, no en controladores |
| `controller` | HTTP: validación de entrada, `OwnerAccess.requireOwner`, mapeo a respuesta | Sin SQL ni llamadas HTTP salientes |
| `config` | Beans de seguridad o configuración propios del módulo | — |

## Seguridad por superficie

| Ruta | Cadena | Autenticación |
|------|--------|---------------|
| `/api/webhooks/**` | `PublicApiSecurityConfig.webhooks` | Sin sesión; cada servicio valida firma/credencial (Evolution: HMAC por tienda; Meta: `X-Hub-Signature-256`; Culqi: token en URL + re-consulta; Mercado Pago: `x-signature` + re-consulta) |
| `/api/public/**` | `PublicApiSecurityConfig.publicStoreApi` | Pública, con tokens de reserva o de seguimiento |
| `/api/superadmin/**` | `AdminSecurity` | Contraseña + TOTP |
| `/api/owner/**` | `OwnerSecurity` | Sesión del dueño + CSRF; `OwnerAccess.requireOwner` en cada endpoint |

## Secretos

Tokens de Meta, Mercado Pago y similares se guardan con `SecretCipher` (AES-256-GCM). El contexto AAD
incluye propósito y tenant (`whatsapp-cloud/access-token/{tenant}`), así un ciphertext copiado a otra fila
no descifra. Las APIs nunca devuelven secretos: solo `accessTokenConfigured: true`.

## Frontend

Ver `docs/frontend.md`. Acceso y 2FA: `docs/seguridad.md`.

## Deuda conocida

Se movieron todas las clases a su capa, pero estos controladores todavía contienen SQL y deben pasar a
`service` + `repository` cuando se toquen: `checkout` (GuestCheckout, CartReservation, OrderTracking,
OwnerWebOrders), `fiscal` (FiscalIssuer, BetaPosDocuments), `pos`, `crm`, `catalog` (CategoryResource,
PublicStoreCatalog, VariantLabels, PublicCatalogFile), `fulfillment`, `owner`, `tenant`, `platformadmin`.
Ya siguen el patrón completo: `whatsapp`, `subscription`, `payments` y `catalog/OwnerCatalog`.
