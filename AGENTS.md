# Migraciones y entidades

Antes de crear o modificar una migración Flyway o modificar una entidad JPA, enumera y lee explícitamente el contenido completo de todos los archivos SQL de `backend/src/main/resources/db/migration/` en la rama de trabajo. Verifica las tablas, columnas y restricciones involucradas contra ese historial. Si el estado de la base difiere del historial, identifica la discrepancia antes de diseñar el cambio.

# Estructura por módulos

El backend se organiza en `com.saas.omnicanal.<modulo>.{model,repository,service,controller,config}` (ver `docs/arquitectura.md`). El SQL va en `repository`, las reglas y transacciones en `service`, y los controladores solo validan, comprueban el dueño con `OwnerAccess.requireOwner` y delegan. Los secretos de integraciones se guardan con `SecretCipher`, nunca en texto plano. Las capacidades por plan se consultan con `EntitlementService`, nunca comparando el nombre del plan.

# Frontend

Todo el frontend vive en `frontend/` (Next.js, App Router, Tailwind 4). Convenciones (detalle en `docs/frontend.md`):

- Llamadas al backend solo con `src/lib/api.ts` (`ownerApi`, `superadminApi`, `apiFetch`, `getJson`). Nunca `fetch` directo a `/api/...` ni lectura manual del token CSRF.
- Cargas de datos en componentes con `useApiData` (`src/lib/useApiData.ts`). No llames a `setState` de forma síncrona dentro de un `useEffect`: el lint lo rechaza.
- Componentes visuales base en `src/components/ui` y colores con los tokens de `globals.css` (`bg-primary`, `text-muted-foreground`…), no con colores fijos.
- Antes de abrir un PR: `npm run lint`, `npm run typecheck`, `npm run build` y, con backend levantado, `npm run e2e`.

# Roadmap

El orden de desarrollo está en `docs/ROADMAP.md`. Actualiza el estado del módulo al terminar un incremento.
