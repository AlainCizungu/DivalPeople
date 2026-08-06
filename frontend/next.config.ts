import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,

  // Emits a self-contained server with only the dependencies actually reached, so the runtime
  // image carries neither node_modules nor the build toolchain. It also means the container
  // cannot install anything at start-up, which is the point.
  output: "standalone",

  // Security headers. CSP is deliberately strict; widen it consciously, not by accident.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
        ],
      },
    ];
  },
};

export default nextConfig;
