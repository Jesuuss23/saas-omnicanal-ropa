"use client";

import {useState, type FormEvent} from "react";
import Link from "next/link";
import {ArrowLeft, ChevronRight, LogIn, Store} from "lucide-react";
import {CodeInput} from "./CodeInput";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {ownerApi} from "@/lib/api";

type StoreOption = {id: string; name: string};
type Step = {kind: "credentials"} | {kind: "store"; stores: StoreOption[]} | {kind: "code"};

/**
 * Ingreso del propietario: correo y contraseña, elección de tienda si el correo administra varias,
 * y código de la app autenticadora si activó la verificación en dos pasos.
 */
export function OwnerLoginForm({store, onSuccess}: {store?: string; onSuccess: (store: string) => void}) {
  const [step, setStep] = useState<Step>({kind: "credentials"});
  const [fields, setFields] = useState<Record<string, string>>({});
  const [recovery, setRecovery] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(next: Record<string, string>) {
    setFields(next); setBusy(true); setError("");
    try {
      const res = await ownerApi.send("login", new URLSearchParams(next));
      const body = await res.json().catch(() => ({}));
      if (res.ok) { onSuccess(body.store ?? next.store); return; }
      if (body.status === "choose_store") { setStep({kind: "store", stores: body.stores}); return; }
      if (body.status === "mfa_required") { setStep({kind: "code"}); return; }
      setError(step.kind === "code" ? "El código no es válido o ya se usó. Espera el siguiente código de tu app."
        : "Correo o contraseña incorrectos. Tras 5 intentos fallidos, espera 5 minutos.");
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  const back = <Button type="button" variant="link" className="h-auto px-0" onClick={() => { setStep({kind: "credentials"}); setError(""); }}><ArrowLeft/>Volver</Button>;

  if (step.kind === "store") return <div className="grid gap-4">
    <p className="text-sm text-muted-foreground">Tu correo administra varias tiendas. Elige a cuál quieres entrar.</p>
    <ul className="grid gap-2">
      {step.stores.map(s => <li key={s.id}>
        <button type="button" disabled={busy} onClick={() => void submit({...fields, store: s.id})}
          className="flex w-full items-center gap-3 rounded-xl border bg-card p-4 text-left shadow-card transition-colors hover:border-primary disabled:opacity-60">
          <span className="grid size-10 place-items-center rounded-lg bg-accent text-accent-foreground"><Store className="size-5"/></span>
          <span className="flex-1 font-medium">{s.name}</span><ChevronRight className="size-4 text-muted-foreground"/>
        </button>
      </li>)}
    </ul>
    {error && <Alert variant="destructive">{error}</Alert>}
    {back}
  </div>;

  if (step.kind === "code") return <form className="grid gap-4" onSubmit={(e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    void submit({...fields, code: String(new FormData(e.currentTarget).get("code"))});
  }}>
    <p className="text-sm text-muted-foreground">{recovery ? "Escribe uno de tus códigos de recuperación." : "Escribe el código de 6 dígitos de tu app autenticadora."}</p>
    <CodeInput key={String(recovery)} name="code" recovery={recovery} required autoFocus disabled={busy} aria-label="Código de verificación"/>
    {error && <Alert variant="destructive">{error}</Alert>}
    <Button size="lg" type="submit" loading={busy}>Verificar e ingresar</Button>
    <div className="flex flex-wrap justify-between gap-2 text-sm">{back}
      <Button type="button" variant="link" className="h-auto px-0" onClick={() => setRecovery(r => !r)}>
        {recovery ? "Usar código de la app" : "Usar un código de recuperación"}</Button>
    </div>
  </form>;

  return <form className="grid gap-5" onSubmit={(e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    void submit({username: String(data.get("username")).trim(), password: String(data.get("password")), ...(store ? {store} : {})});
  }}>
    <Field label="Correo electrónico" htmlFor="owner-email">
      <Input id="owner-email" name="username" type="email" autoComplete="username" required defaultValue={fields.username} disabled={busy}/>
    </Field>
    <Field label="Contraseña" htmlFor="owner-password">
      <Input id="owner-password" name="password" type="password" autoComplete="current-password" required disabled={busy}/>
    </Field>
    <Link href="/recuperar-acceso" className="-mt-2 justify-self-end text-sm font-medium text-primary hover:underline">¿Olvidaste tu contraseña?</Link>
    {error && <Alert variant="destructive">{error}</Alert>}
    <Button size="lg" type="submit" loading={busy}><LogIn/>Ingresar</Button>
  </form>;
}
