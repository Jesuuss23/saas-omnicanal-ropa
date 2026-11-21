import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async rewrites() {
    const base = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/, "");
    return [{source: "/api/owner/:path*", destination: base + "/owner/:path*"}, { source: "/api/superadmin/:path*", destination: base + "/superadmin/:path*" }];
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
