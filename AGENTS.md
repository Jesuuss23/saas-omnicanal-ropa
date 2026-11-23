# Migraciones y entidades

Antes de crear o modificar una migración Flyway o modificar una entidad JPA, enumera y lee explícitamente el contenido completo de todos los archivos SQL de `backend/src/main/resources/db/migration/` en la rama de trabajo. Verifica las tablas, columnas y restricciones involucradas contra ese historial. Si el estado de la base difiere del historial, identifica la discrepancia antes de diseñar el cambio.

# Estructura por módulos

El backend se organiza en `com.saas.omnicanal.<modulo>.{model,repository,service,controller,config}` (ver `docs/arquitectura.md`). El SQL va en `repository`, las reglas y transacciones en `service`, y los controladores solo validan, comprueban el dueño con `OwnerAccess.requireOwner` y delegan. Los secretos de integraciones se guardan con `SecretCipher`, nunca en texto plano. Las capacidades por plan se consultan con `EntitlementService`, nunca comparando el nombre del plan.

# Roadmap

El orden de desarrollo está en `docs/ROADMAP.md`. Actualiza el estado del módulo al terminar un incremento.
