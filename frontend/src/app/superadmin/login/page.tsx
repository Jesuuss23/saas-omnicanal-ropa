"use client";

import {useState, type FormEvent} from "react";
import {ArrowLeft, LockKeyhole} from "lucide-react";
import {AuthShell} from "@/components/auth/AuthShell";
import {CodeInput} from "@/components/auth/CodeInput";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {getJson, superadminApi} from "@/lib/api";

type Step = "credentials" | "code";

export default function Login() {
  const [step, setStep] = useState<Step>("credentials");
  const [credentials, setCredentials] = useState({username: "", password: ""});
  const [recovery, setRecovery] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(fields: URLSearchParams) {
    setBusy(true); setError("");
    try {
      const res = await superadminApi.send("login", fields);
      if (res.ok) {
        const me = await getJson<{setupRequired: boolean}>(superadminApi.url("me"));
        window.location.replace(me.setupRequired ? "/superadmin/seguridad/activar" : "/superadmin");
        return;
      }
      const body = await res.json().catch(() => ({}));
      if (body.status === "mfa_required") { setStep("code"); return; }
      setError(step === "code"
        ? "El código no es válido o ya se usó. Espera el siguiente código de tu app."
        : "Correo o contraseña incorrectos. Tras 5 intentos fallidos, espera 5 minutos.");
    } catch { setError("No se pudo conectar con el servidor."); }
    finally { setBusy(false); }
  }

  function enter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = {username: String(data.get("username")), password: String(data.get("password"))};
    setCredentials(next);
    void submit(new URLSearchParams(next));
  }

  function verify(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get("code"));
    void submit(new URLSearchParams({...credentials, code}));
  }

  if (step === "code") return <AuthShell eyebrow="Verificación en dos pasos" title="Ingresa tu código"
    description={recovery ? "Usa uno de tus códigos de recuperación. Cada uno sirve una sola vez." : "Abre tu app autenticadora y escribe el código de 6 dígitos."}>
    <form onSubmit={verify} className="grid gap-4">
      <CodeInput key={String(recovery)} name="code" recovery={recovery} required autoFocus disabled={busy} aria-label="Código de verificación"/>
      {error && <Alert variant="destructive">{error}</Alert>}
      <Button size="lg" type="submit" loading={busy}>Verificar e ingresar</Button>
      <div className="flex flex-wrap justify-between gap-2 text-sm">
        <Button type="button" variant="link" className="h-auto px-0" onClick={() => { setStep("credentials"); setError(""); }}><ArrowLeft/>Volver</Button>
        <Button type="button" variant="link" className="h-auto px-0" onClick={() => { setRecovery(r => !r); setError(""); }}>
          {recovery ? "Usar código de la app" : "Usar un código de recuperación"}
        </Button>
      </div>
    </form>
  </AuthShell>;

  return <AuthShell eyebrow="Administración de plataforma" title="Iniciar sesión"
    description="Acceso exclusivo para el equipo que administra la plataforma.">
    <form onSubmit={enter} className="grid gap-5">
      <Field label="Correo electrónico" htmlFor="username">
        <Input id="username" name="username" type="email" autoComplete="username" required defaultValue={credentials.username} disabled={busy}/>
      </Field>
      <Field label="Contraseña" htmlFor="password">
        <Input id="password" name="password" type="password" autoComplete="current-password" required disabled={busy}/>
      </Field>
      {error && <Alert variant="destructive">{error}</Alert>}
      <Button size="lg" type="submit" loading={busy}><LockKeyhole/>Continuar</Button>
      <p className="text-center text-xs text-muted-foreground">Si es tu primer ingreso, te pediremos configurar tu app autenticadora con un código QR.</p>
    </form>
  </AuthShell>;
}
