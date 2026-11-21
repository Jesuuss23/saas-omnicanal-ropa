import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async rewrites() {
    const base = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/, "");
    return [{source: "/api/public/stores/:store/orders/:order", destination: base + "/public/stores/:store/orders/:order"}, {source: "/api/public/stores/:store/reservation/guest", destination: base + "/public/stores/:store/reservation/guest"}, {source: "/api/public/stores/:store/reservation", destination: base + "/public/stores/:store/reservation"}, {source: "/api/public/stores/:store/availability", destination: base + "/public/stores/:store/availability"}, {source: "/api/owner/:path*", destination: base + "/owner/:path*"}, { source: "/api/superadmin/:path*", destination: base + "/superadmin/:path*" }];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
