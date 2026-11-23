"use client";

import {Suspense, useState, type FormEvent} from "react";
import Link from "next/link";
import {useSearchParams} from "next/navigation";
import {CheckCircle2} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {apiFetch, errorMessage, publicApi} from "@/lib/api";
import {useHash} from "@/lib/useHash";

export default function ResetPasswordPage() {
  return <div className="mx-auto max-w-lg px-4 py-20 sm:px-6"><div className="rounded-2xl border bg-card p-6 shadow-pop sm:p-8">
    <Suspense><ResetPassword/></Suspense>
  </div></div>;
}

function ResetPassword() {
  const store = useSearchParams().get("store") ?? "";
  const token = useHash();
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get("password"));
    if (password !== String(data.get("repeat"))) { setError("Las contraseñas no coinciden."); return; }
    setBusy(true); setError("");
    try {
      const res = await apiFetch(publicApi.url("password-reset/confirm"), {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({store, token, password})});
      if (!res.ok) { setError(await errorMessage(res, "El enlace no es válido.")); return; }
      setDone(true);
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  if (done) return <div className="grid justify-items-center gap-4 text-center">
    <CheckCircle2 className="size-12 text-success"/>
    <h1 className="text-2xl font-semibold">Contraseña actualizada</h1>
    <p className="text-muted-foreground">Ya puedes ingresar con tu nueva contraseña.</p>
    <Button asChild><Link href={"/propietario/" + store}>Ingresar a mi tienda</Link></Button>
  </div>;

  if (token !== null && (!token || !store)) return <div className="grid gap-4">
    <Alert variant="destructive">El enlace está incompleto. Ábrelo directamente desde el correo.</Alert>
    <Button asChild variant="outline"><Link href="/recuperar-acceso">Pedir un enlace nuevo</Link></Button>
  </div>;

  return <form onSubmit={submit} className="grid gap-5">
    <div><h1 className="text-2xl font-semibold tracking-tight">Crea una nueva contraseña</h1>
      <p className="mt-1 text-muted-foreground">Si tienes la verificación en dos pasos activa, la seguirás usando al ingresar.</p></div>
    <Field label="Nueva contraseña" htmlFor="password" hint="Al menos 12 caracteres.">
      <Input id="password" name="password" type="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/>
    </Field>
    <Field label="Repite la contraseña" htmlFor="repeat"><Input id="repeat" name="repeat" type="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/></Field>
    {error && <Alert variant="destructive">{error}</Alert>}
    <Button size="lg" type="submit" loading={busy}>Guardar contraseña</Button>
  </form>;
}
