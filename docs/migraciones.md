# Migraciones de base de datos (Flyway + PostgreSQL)

Las migraciones viven en `backend/src/main/resources/db/migration` y se aplican al arrancar.

## Reglas

1. **Una migración aplicada no se edita nunca.** Flyway valida el checksum (`validate-on-migrate: true`):
   si alguien cambia un `V__` ya ejecutado, la aplicación no arranca. Para corregir, se crea una nueva versión.
2. **Versiones consecutivas**: `V{n}__descripcion_en_snake_case.sql`. Antes de crear una, revisa la última
   versión en `main` (hoy V32) y lee todas las migraciones como pide `AGENTS.md`.
3. **Todo dato de tienda lleva `tenant_id`** y las claves foráneas entre tablas de tienda usan
   `(tenant_id, id)` para que una fila nunca apunte a datos de otra tienda.
4. **Cambios compatibles hacia atrás**: agregar columnas con `DEFAULT` o nulas; eliminar en una versión
   posterior, cuando el código ya no las use.
5. **Restricciones con nombre** (`ck_...`, `uq_...`, `ix_...`) para poder modificarlas luego sin adivinar.
6. **Datos semilla** (planes, entitlements) van en migraciones; datos de prueba, nunca.
7. `ddl-auto: validate`: Hibernate solo comprueba, jamás modifica el esquema.
8. `baseline-on-migrate` está apagado. Solo se enciende (`FLYWAY_BASELINE_ON_MIGRATE=true`) para adoptar una
   base existente que no tenga historial de Flyway.

## Historial

| Versiones | Tema |
|-----------|------|
| V1–V6 | Tenant, productos, variantes, superadmin, dueño, SKU único por tienda |
| V7–V13 | POS, solicitud fiscal, emisor, SUNAT Beta |
| V14–V19 | WhatsApp (Evolution): mensajes, CRM, respuestas y acuses |
| V20–V21 | Recursos de catálogo por categoría |
| V22–V29 | Reservas, checkout web, recojo/delivery |
| V30 | WhatsApp Cloud API: credenciales cifradas, plantillas, pruebas, proveedor por mensaje |
| V31 | Planes, entitlements, suscripción SaaS, bandeja idempotente de webhooks de pago |
| V32 | Cuenta Mercado Pago de la tienda, intención y pagos de pedidos, estado `PAID` |
| V47 | Pipeline multimedia (transcripción, moderación, visión, incidentes, `ai_context`) y catálogo e-commerce (`active`, `material`, precio antes y costo por variante) |
| V48 | Cobros de tiendas con Culqi: limpia Mercado Pago (`store_payment_account` solo `CULQI`, sin tablas OAuth ni preferencias) y renombra la capacidad a `payments.online` |
| V49 | Facturación con Nubefact: `store_billing_account` por tienda (RUTA, TOKEN cifrado, ambiente y series), elimina `beta_company_registration` y libera las series del contador |

Nota: V2 repite `CREATE TABLE IF NOT EXISTS` de V1. Es inofensivo y no se toca porque ya está aplicada.

## Probar migraciones en local

```bash
createdb omni_dev
DB_URL=jdbc:postgresql://localhost:5432/omni_dev DB_USERNAME=... DB_PASSWORD=... ./mvnw test
```

Las pruebas `@SpringBootTest` aplican todas las migraciones y validan las entidades JPA.
