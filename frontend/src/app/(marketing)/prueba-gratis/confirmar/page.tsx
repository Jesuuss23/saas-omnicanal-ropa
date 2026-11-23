"use client";

import {useEffect, useState, type FormEvent} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {PartyPopper} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Spinner} from "@/components/ui/skeleton";
import {apiFetch, errorMessage, ownerApi, publicApi} from "@/lib/api";
import {useHash} from "@/lib/useHash";

type Preview = {storeName: string; email: string; ownerName: string};

function post(path: string, body: unknown) {
  return apiFetch(publicApi.url(path), {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
}

export default function ConfirmSignup() {
  const token = useHash();
  const router = useRouter();
  const [preview, setPreview] = useState<{token: string; data: Preview | null; error: string} | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!token) return;
    let current = true;
    post("signup/preview", {token}).then(async res => {
      const result = res.ok ? {token, data: await res.json() as Preview, error: ""}
        : {token, data: null, error: await errorMessage(res, "El enlace no es válido.")};
      if (current) setPreview(result);
    }).catch(() => { if (current) setPreview({token, data: null, error: "No se pudo conectar con el servidor."}); });
    return () => { current = false; };
  }, [token]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get("password"));
    if (password !== String(data.get("repeat"))) { setError("Las contraseñas no coinciden."); return; }
    setBusy(true); setError("");
    try {
      const res = await post("signup/confirm", {token, password});
      if (!res.ok) { setError(await errorMessage(res)); return; }
      const created = await res.json() as {store: string; email: string};
      const login = await ownerApi.send("login", new URLSearchParams({username: created.email, password, store: created.store}));
      router.replace(login.ok ? "/propietario/" + created.store + "?bienvenida=1" : "/ingresar");
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  const loaded = preview && preview.token === token ? preview : null;
  return <div className="mx-auto max-w-lg px-4 py-20 sm:px-6">
    <div className="rounded-2xl border bg-card p-6 shadow-pop sm:p-8">
      {token === null ? <Spinner/> : !token ? <Alert variant="destructive">Abre el enlace completo que te enviamos por correo.</Alert>
        : !loaded ? <Spinner label="Verificando tu enlace…"/>
        : !loaded.data ? <div className="grid gap-4">
          <Alert variant="destructive">{loaded.error}</Alert>
          <Button asChild variant="outline"><Link href="/prueba-gratis">Solicitar un nuevo enlace</Link></Button>
        </div>
        : <form onSubmit={submit} className="grid gap-5">
          <span className="grid size-12 place-items-center rounded-full bg-accent text-accent-foreground"><PartyPopper className="size-6"/></span>
          <div><h1 className="text-2xl font-semibold tracking-tight">¡Hola, {loaded.data.ownerName}!</h1>
            <p className="mt-1 text-muted-foreground">Crea tu contraseña para activar <strong className="text-foreground">{loaded.data.storeName}</strong>.</p></div>
          <Field label="Correo" htmlFor="email"><Input id="email" value={loaded.data.email} readOnly autoComplete="username"/></Field>
          <Field label="Contraseña" htmlFor="password" hint="Al menos 12 caracteres. Una frase fácil de recordar funciona bien.">
            <Input id="password" name="password" type="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/>
          </Field>
          <Field label="Repite la contraseña" htmlFor="repeat"><Input id="repeat" name="repeat" type="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/></Field>
          {error && <Alert variant="destructive">{error}</Alert>}
          <Button size="lg" type="submit" loading={busy}>Activar mi tienda</Button>
        </form>}
    </div>
  </div>;
}
