"use client";

import {useCallback, useEffect, useMemo, useState, useSyncExternalStore, type FormEvent} from "react";
import {useRouter} from "next/navigation";
import {AuthShell} from "@/components/auth/AuthShell";
import {OwnerLoginForm} from "@/components/auth/OwnerLoginForm";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Spinner} from "@/components/ui/skeleton";
import {API_EVENTS, apiFetch, ownerApi} from "@/lib/api";
import {StoreProvider, type Profile} from "./StoreContext";
import {StoreShell} from "./StoreShell";

function subscribeHash(onChange: () => void) {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
}

/** Muestra el ingreso, la activación por invitación o el panel según la sesión del propietario. */
export function OwnerGate({store, children}: {store: string; children: React.ReactNode}) {
  // El enlace de invitación trae el token en el fragmento (#token), que nunca viaja al servidor.
  const token = useSyncExternalStore(subscribeHash, () => location.hash.slice(1), () => "");
  const [profile, setProfile] = useState<Profile | null>(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const router = useRouter();

  const loadProfile = useCallback(async () => {
    const res = await apiFetch(ownerApi.url("me"));
    if (!res.ok) return null;
    const data: Profile = await res.json();
    return data.id === store ? data : null;
  }, [store]);

  useEffect(() => {
    let current = true;
    const invitation = location.hash.slice(1);
    loadProfile().then(data => { if (current && !invitation) setProfile(data); })
      .catch(() => { if (current) setMessage("No se pudo consultar la sesión."); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [loadProfile]);

  useEffect(() => {
    const expired = () => { setProfile(null); setMessage("Tu sesión terminó. Vuelve a ingresar."); };
    window.addEventListener(API_EVENTS.unauthorized, expired);
    return () => window.removeEventListener(API_EVENTS.unauthorized, expired);
  }, []);

  const refreshProfile = useCallback(() => { loadProfile().then(p => { if (p) setProfile(p); }).catch(() => {}); }, [loadProfile]);
  const context = useMemo(() => profile && {store, profile, send: ownerApi.send, refreshProfile}, [store, profile, refreshProfile]);

  // Invitación de un miembro del equipo: #m.<id>.<token>. La del propietario es solo el token.
  const memberInvite = /^m\.([0-9a-f-]{36})\.(.+)$/i.exec(token);

  async function activate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const password = String(new FormData(form).get("password"));
    setBusy(true); setMessage("");
    try {
      const res = memberInvite
        ? await ownerApi.send("members/activate", JSON.stringify({store, member: memberInvite[1], token: memberInvite[2], password}))
        : await ownerApi.send("activate", JSON.stringify({store, token, password}));
      if (!res.ok) { setMessage("Verifica la contraseña y la vigencia de la invitación."); return; }
      history.replaceState(null, "", location.pathname);
      window.dispatchEvent(new HashChangeEvent("hashchange"));
      form.reset(); setMessage("Cuenta activada. Ingresa con tu correo y tu nueva contraseña.");
    } catch { setMessage("No se pudo confirmar la operación."); }
    finally { setBusy(false); }
  }

  async function signedIn(target: string) {
    if (target !== store) { router.push("/propietario/" + encodeURIComponent(target)); return; }
    try {
      const data = await loadProfile();
      if (!data) throw new Error();
      setMessage(""); setProfile(data);
    } catch { setMessage("No se pudo abrir la sesión."); }
  }

  async function logout() {
    try {
      if (!(await ownerApi.send("logout")).ok) throw new Error();
      setProfile(null); setMessage("");
    } catch { setMessage("No se pudo cerrar la sesión."); }
  }

  if (loading) return <main className="grid min-h-dvh place-items-center"><Spinner label="Cargando tu tienda…"/></main>;

  if (!context) return token
    ? <AuthShell eyebrow="Invitación" title={memberInvite ? "Únete al equipo" : "Activa tu acceso"}
      description={memberInvite ? "Crea tu contraseña para ingresar al panel de la tienda con tu correo." : "Crea la contraseña con la que administrarás tu tienda."}>
      <form onSubmit={activate} className="grid gap-5">
        <Field label="Nueva contraseña" htmlFor="new-password" hint="Al menos 12 caracteres. Usa una frase que solo tú conozcas.">
          <Input id="new-password" type="password" name="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/>
        </Field>
        {message && <Alert>{message}</Alert>}
        <Button size="lg" type="submit" loading={busy}>Activar cuenta</Button>
      </form>
    </AuthShell>
    : <AuthShell eyebrow="Panel de tu tienda" title="Ingresa a tu tienda" description="Usa el correo con el que registraste tu tienda.">
      {message && <Alert className="mb-5">{message}</Alert>}
      <OwnerLoginForm store={store} onSuccess={target => void signedIn(target)}/>
    </AuthShell>;

  return <StoreProvider value={context}>
    <StoreShell onLogout={logout}>{children}</StoreShell>
  </StoreProvider>;
}
