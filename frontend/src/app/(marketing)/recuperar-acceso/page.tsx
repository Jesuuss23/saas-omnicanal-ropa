"use client";

import {useState, type FormEvent} from "react";
import Link from "next/link";
import {MailCheck} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {apiFetch, errorMessage, publicApi} from "@/lib/api";

export default function RecoverAccess() {
  const [sent, setSent] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = String(new FormData(event.currentTarget).get("email"));
    setBusy(true); setError("");
    try {
      const res = await apiFetch(publicApi.url("password-reset"), {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({email})});
      if (!res.ok) { setError(await errorMessage(res)); return; }
      setSent(email);
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  return <div className="mx-auto max-w-lg px-4 py-20 sm:px-6">
    <div className="rounded-2xl border bg-card p-6 shadow-pop sm:p-8">
      {sent ? <div className="grid justify-items-center gap-4 text-center">
        <span className="grid size-14 place-items-center rounded-full bg-accent text-accent-foreground"><MailCheck className="size-7"/></span>
        <h1 className="text-2xl font-semibold">Revisa tu correo</h1>
        <p className="text-muted-foreground">Si <strong className="text-foreground">{sent}</strong> tiene una tienda, recibirás un enlace para crear una nueva contraseña. Vence en 1 hora.</p>
        <Button asChild variant="outline"><Link href="/ingresar">Volver a ingresar</Link></Button>
      </div> : <form onSubmit={submit} className="grid gap-5">
        <div><h1 className="text-2xl font-semibold tracking-tight">Recupera tu acceso</h1>
          <p className="mt-1 text-muted-foreground">Escribe el correo de tu cuenta y te enviaremos un enlace para crear una nueva contraseña.</p></div>
        <Field label="Correo electrónico" htmlFor="email"><Input id="email" name="email" type="email" required autoComplete="email" disabled={busy}/></Field>
        {error && <Alert variant="destructive">{error}</Alert>}
        <Button size="lg" type="submit" loading={busy}>Enviar enlace</Button>
        <p className="text-center text-sm text-muted-foreground">¿La recordaste? <Link href="/ingresar" className="font-medium text-primary hover:underline">Ingresa aquí</Link></p>
      </form>}
    </div>
  </div>;
}
