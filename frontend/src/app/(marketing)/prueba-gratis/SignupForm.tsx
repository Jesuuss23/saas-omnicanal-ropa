"use client";

import {useState, type FormEvent} from "react";
import Link from "next/link";
import {useSearchParams} from "next/navigation";
import {MailCheck} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input, NativeSelect} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {apiFetch, errorMessage, publicApi} from "@/lib/api";

export function SignupForm({plans}: {plans: {code: string; name: string}[]}) {
  const params = useSearchParams();
  const initialPlan = plans.some(p => p.code === params.get("plan")) ? params.get("plan")! : "";
  const [sentTo, setSentTo] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = Object.fromEntries(new FormData(event.currentTarget)) as Record<string, string>;
    setBusy(true); setError("");
    try {
      const res = await apiFetch(publicApi.url("signup"), {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({...data, acceptTerms: data.acceptTerms === "on"}),
      });
      if (!res.ok) { setError(await errorMessage(res, "Revisa los datos e inténtalo otra vez.")); return; }
      setSentTo(data.email);
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  if (sentTo) return <div className="grid justify-items-center gap-4 py-8 text-center">
    <span className="grid size-14 place-items-center rounded-full bg-accent text-accent-foreground"><MailCheck className="size-7"/></span>
    <h2 className="text-2xl font-semibold">Revisa tu correo</h2>
    <p className="max-w-sm text-muted-foreground">Enviamos un enlace a <strong className="text-foreground">{sentTo}</strong> para confirmar tu cuenta. Vence en 48 horas.</p>
    <p className="text-sm text-muted-foreground">¿No llega? Revisa la carpeta de spam o <button type="button" className="font-medium text-primary hover:underline" onClick={() => setSentTo("")}>vuelve a intentarlo</button>.</p>
  </div>;

  return <form onSubmit={submit} className="grid gap-5">
    <h2 className="text-xl font-semibold">Datos de tu tienda</h2>
    <Field label="Nombre de tu tienda" htmlFor="storeName"><Input id="storeName" name="storeName" required maxLength={100} placeholder="Ej. Moda Lucía" disabled={busy}/></Field>
    <div className="grid gap-5 sm:grid-cols-2">
      <Field label="Tu nombre" htmlFor="ownerName"><Input id="ownerName" name="ownerName" required maxLength={120} autoComplete="name" disabled={busy}/></Field>
      <Field label="Celular (opcional)" htmlFor="phone"><Input id="phone" name="phone" type="tel" inputMode="tel" maxLength={20} pattern="\+?[0-9 ]{6,20}" autoComplete="tel" placeholder="987 654 321" disabled={busy}/></Field>
    </div>
    <Field label="Correo electrónico" htmlFor="email" hint="Aquí te llegará el enlace para activar tu cuenta.">
      <Input id="email" name="email" type="email" required maxLength={254} autoComplete="email" disabled={busy}/>
    </Field>
    {plans.length > 0 && <Field label="Plan que te interesa" htmlFor="plan" hint="Puedes cambiarlo cuando quieras.">
      <NativeSelect id="plan" name="plan" defaultValue={initialPlan} disabled={busy}>
        <option value="">Aún no lo sé</option>
        {plans.map(p => <option key={p.code} value={p.code}>{p.name}</option>)}
      </NativeSelect>
    </Field>}
    {/* Campo trampa para bots: oculto a las personas y a los lectores de pantalla. */}
    <input type="text" name="website" tabIndex={-1} autoComplete="off" aria-hidden className="absolute left-[-9999px] h-0 w-0 opacity-0"/>
    <label className="flex items-start gap-2.5 text-sm text-muted-foreground">
      <input type="checkbox" name="acceptTerms" required className="mt-0.5 size-4 accent-[var(--primary)]" disabled={busy}/>
      <span>Acepto los <Link href="/terminos" className="text-primary hover:underline">términos y condiciones</Link> y la <Link href="/privacidad" className="text-primary hover:underline">política de privacidad</Link>.</span>
    </label>
    {error && <Alert variant="destructive">{error}</Alert>}
    <Button size="lg" type="submit" loading={busy}>Crear mi tienda gratis</Button>
    <p className="text-center text-sm text-muted-foreground">¿Ya tienes cuenta? <Link href="/ingresar" className="font-medium text-primary hover:underline">Ingresa aquí</Link></p>
  </form>;
}
