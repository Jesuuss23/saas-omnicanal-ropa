# SaaS omnicanal para tiendas de ropa

Web propia, vendedor por WhatsApp y POS sobre un mismo catálogo, inventario y facturación.

- `backend/`: Spring Boot + PostgreSQL (Flyway). `./mvnw test` con `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- `frontend/`: Next.js + Tailwind. `npm run dev` (usa `BACKEND_API_URL`).
- `infra/`: Evolution API para la conexión por QR.

Documentación: [roadmap](docs/ROADMAP.md) · [arquitectura](docs/arquitectura.md) · [migraciones](docs/migraciones.md) · [integraciones](docs/integraciones.md) · [configuración y variables de entorno](docs/configuracion.md)
