# Arquitectura del frontend

Next.js (App Router) con React 19 y Tailwind 4. El navegador nunca llama al backend directo: Next
reenvía `/api/owner/*`, `/api/superadmin/*` y una lista cerrada de rutas públicas (ver `next.config.ts`).
Las páginas de servidor usan `backendBase()` para hablar con el backend por la red interna.

## Carpetas

| Carpeta | Contenido |
|---|---|
| `src/app` | Rutas. Cada superficie tiene su carpeta: `superadmin`, `propietario/[store]`, `tienda/[store]`. |
| `src/components/ui` | Sistema de diseño: Button, Input, Field, Card, Badge, Alert, Dialog, DropdownMenu, Tabs, Table, Skeleton, EmptyState, Toaster. |
| `src/components/console` | Estructura de los paneles: `ConsoleShell` (barra lateral y menú móvil), `PageHeader`, `StatCard`, `BarChart`, `Pagination`. |
| `src/components` | Piezas compartidas entre superficies (por ejemplo `OrderAmounts`). |
| `src/lib` | `api.ts` (cliente HTTP), `useApiData.ts` (carga de datos), `utils.ts` (`cn`, formatos de moneda y fecha), `brand.ts` (nombre y textos de marca). |
| `e2e` | Pruebas de humo con Playwright y `seed.sql` con la tienda de prueba. |

## Cliente de API (`src/lib/api.ts`)

- `ownerApi` y `superadminApi` piden el token CSRF de la sesión y lo adjuntan en cada escritura.
- `send(path, body, method)` devuelve la `Response`; `json(path, body)` devuelve el JSON o lanza `ApiError` con el mensaje del backend.
- Un 401 fuera del login emite el evento `api:unauthorized` (el panel cierra la sesión en pantalla) un 423 (tienda suspendida por la plataforma) emite `api:suspended` y un 402 emite `api:quota` (el `Toaster` muestra el aviso de plan).

## Consola del superadmin

Rutas en `src/app/superadmin/(console)`: el `layout.tsx` del grupo exige sesión completa (contraseña + 2FA) y
envuelve cada página en `SuperadminShell`. Secciones: Resumen (KPIs, tiendas nuevas por semana, estados),
Tiendas (búsqueda, filtro y ficha con suspensión, ajustes de suscripción y capacidades), Planes (vínculo con
Culqi), Pagos (webhooks de Culqi y Mercado Pago), Auditoría y Seguridad. Los tipos de las respuestas están en
`src/lib/superadmin.ts`. En CI el backend arranca con un superadmin de prueba (`admin@e2e.test`).

## Panel de la tienda

`src/app/propietario/[store]/layout.tsx` monta `OwnerGate`: muestra ingreso o activación por invitación hasta que hay
sesión, y luego `StoreShell` (barra lateral) con `useStore()` disponible para cada página. Secciones: Inicio (métricas,
ventas por día y pasos de configuración), Pedidos web, Punto de venta, Catálogo (con fotos y etiquetas QR), Clientes,
WhatsApp, Apariencia (nombre, logo, color, contacto), Asistente IA, Marketing, Cobros, Facturación, Equipo, Plan y Cuenta. Los componentes de cada
módulo (`Catalog`, `WebOrders`, `PointOfSale`…) siguen en la carpeta de la ruta.

El menú depende de `profile.role` (`roleAccess` en `StoreContext`): el vendedor solo ve las secciones de venta y el
encargado no ve Cobros, Equipo, Plan ni Cuenta. Si alguien abre por enlace una sección fuera de su rol, `StoreShell`
muestra un aviso en vez de la página. La invitación de un miembro llega como `#m.<id>.<token>` y la del propietario como
`#<token>`; ambas se activan en `OwnerGate`.

La página WhatsApp tiene la bandeja (`whatsapp/Inbox.tsx`): conversaciones por estado, hilo con mensajes del cliente,
de la IA y del equipo, y acciones para tomar, devolver a la IA con nota, pausar la IA o cerrar. Responder desde la
bandeja toma la conversación primero, así la IA no contesta encima de una persona. Asistente IA configura la IA de la
tienda y permite probarla sin enviar nada.

Marketing (`marketing/page.tsx`) crea borradores de campaña con segmento y conteo previo, los lanza o cancela, y configura el
recordatorio de carrito abandonado. Facturación suma, solo para el propietario, el alta para producción en 4 pasos
(`facturacion/ProductionSetup.tsx`).

## Tienda pública

`src/app/tienda/[store]` se renderiza en el servidor. La cabecera usa la apariencia de la tienda: el color del dueño
llega como `--store` y el texto encima (`--store-fg`) se calcula por luminancia en `brand.ts`. `CatalogCart` agrupa las
variantes por producto, deja elegir talla y color, y mantiene la reserva de 15 minutos y el contacto del invitado.
`tienda/[store]/cuenta` es la cuenta del comprador: botón de Google Identity Services (si el backend devuelve
`googleClientId`) y "Mis pedidos" con su enlace de seguimiento, más sus preferencias de novedades. El checkout pide aceptación expresa de novedades por WhatsApp. `/baja/[token]` es la baja desde el enlace de cada correo (pide confirmar). La pestaña Contactos de Clientes muestra el contacto
unificado, los posibles duplicados y el historial de fusiones con opción de deshacer.

## Diseño

Los colores, radios y sombras son tokens en `src/app/globals.css` (marca teal, modo oscuro con la clase `.dark`).
La fuente es Inter, servida desde el propio sitio con `@fontsource-variable/inter`.
Los componentes base siguen el estilo de shadcn/ui sobre Radix (`radix-ui`) y usan íconos de `lucide-react`.

## Calidad

- `npm run lint` y `npm run typecheck` deben quedar en 0 errores.
- `npm run e2e` corre las pruebas de humo contra `E2E_BASE_URL` (por defecto `http://localhost:3000`). Con `E2E_SEEDED=1` y `e2e/seed.sql` cargado, también prueba el panel del propietario.
- GitHub Actions (`.github/workflows/ci.yml`) corre backend, frontend y las pruebas de humo en cada PR y en cada push a main.
