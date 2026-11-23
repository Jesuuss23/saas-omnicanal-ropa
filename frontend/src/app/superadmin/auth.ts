import {superadminApi} from "@/lib/api";

export const csrfToken = superadminApi.csrf;

export function submitAuth(action: "login" | "logout", fields = new URLSearchParams()) {
  return superadminApi.send(action, fields);
}
