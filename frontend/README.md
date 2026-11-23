# Frontend · Omnicanal

Sitio de venta, superadmin, panel de cada tienda y catálogo público. Next.js 16, React 19 y Tailwind 4.

## Levantar en local

```bash
npm install
BACKEND_API_URL=http://localhost:8080/api npm run dev
```

`BACKEND_API_URL` es la URL del backend Spring (por defecto `http://localhost:8080/api`). El navegador
solo habla con Next, que reenvía las rutas `/api/...` al backend.

## Scripts

| Script | Qué hace |
|---|---|
| `npm run dev` | Servidor de desarrollo en http://localhost:3000 |
| `npm run build` / `npm start` | Build y servidor de producción |
| `npm run lint` | ESLint (debe quedar en 0 errores) |
| `npm run typecheck` | Revisión de tipos de TypeScript |
| `npm run e2e` | Pruebas de humo con Playwright (necesita frontend y backend levantados) |

Para probar también el panel del propietario: carga `e2e/seed.sql` en la base y corre `E2E_SEEDED=1 npm run e2e`.

## Rutas

| Ruta | Para quién |
|---|---|
| `/` | Portada |
| `/superadmin` | Administración de la plataforma |
| `/propietario/[tienda]` | Panel del dueño de la tienda |
| `/tienda/[tienda]` | Catálogo público y carrito del comprador |
| `/tienda/[tienda]/pedido/[pedido]` | Seguimiento privado del pedido |

La estructura y las convenciones están en [`docs/frontend.md`](../docs/frontend.md).
