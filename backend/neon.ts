import { defineConfig } from "@neon/config/v1";

export default defineConfig({
  // Nur PostgreSQL; keine zusaetzliche Authentifizierung aktivieren.
  auth: false,
});
