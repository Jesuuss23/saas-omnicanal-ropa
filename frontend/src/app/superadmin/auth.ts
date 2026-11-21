export async function submitAuth(action: "login" | "logout", fields = new URLSearchParams()) {
  const csrf = await fetch("/api/superadmin/csrf", {cache: "no-store"});
  if (!csrf.ok) throw new Error("No se pudo iniciar la conexión.");
  const {token} = await csrf.json();
  return fetch("/api/superadmin/" + action, {
    method: "POST", headers: {"X-CSRF-TOKEN": token}, body: fields, cache: "no-store",
  });
}
