import type {NextConfig} from "next";

const backend = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/, "");

/**
 * Rutas que el navegador llama en /api/... y Next reenvía al backend Spring.
 * Las sesiones de propietario y superadmin viajan como cookie del mismo origen.
 * Las rutas públicas se listan una por una para no exponer nada más del backend.
 */
const proxied = [
  "/owner/:path*",
  "/superadmin/:path*",
  "/public/stores/:store/availability",
  "/public/stores/:store/products/:product",
  "/public/stores/:store/images/:image",
  "/public/stores/:store/logo",
  "/public/stores/:store/account",
  "/public/stores/:store/account/config",
  "/public/stores/:store/account/google",
  "/public/stores/:store/account/logout",
  "/public/stores/:store/account/preferences",
  "/public/stores/:store/reservation",
  "/public/stores/:store/reservation/guest",
  "/public/stores/:store/reservation/guest/quote",
  "/public/stores/:store/orders/:order",
  "/public/stores/:store/orders/:order/culqi/pay",
  "/public/stores/:store/payment-config",
  "/public/plans",
  "/public/signup",
  "/public/signup/preview",
  "/public/signup/confirm",
  "/public/password-reset",
  "/public/password-reset/confirm",
  "/public/unsubscribe/:token",
];

const nextConfig: NextConfig = {
  async rewrites() {
    return proxied.map(path => ({source: "/api" + path, destination: backend + path}));
  },
  turbopack: {
    rules: {
      "*.css": {loaders: ["@tailwindcss/turbopack"], as: "*.css"},
    },
  },
};

export default nextConfig;
