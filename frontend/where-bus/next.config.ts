import type { NextConfig } from "next";

/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      {
        // Whenever the frontend asks for /api/transit/...
        source: '/api/transit/:path*',
        // Silently proxy the request to the Spring Boot server
        destination: 'http://localhost:8080/api/transit/:path*',
      },
    ]
  },
};

export default nextConfig;
