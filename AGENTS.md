# Migraciones y entidades

Antes de crear o modificar una migración Flyway o modificar una entidad JPA, enumera y lee explícitamente el contenido completo de todos los archivos SQL de `backend/src/main/resources/db/migration/` en la rama de trabajo. Verifica las tablas, columnas y restricciones involucradas contra ese historial. Si el estado de la base difiere del historial, identifica la discrepancia antes de diseñar el cambio.
