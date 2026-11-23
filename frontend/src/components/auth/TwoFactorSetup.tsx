"use client";
import {useState} from "react";
import {Check, Copy, Download, KeyRound, QrCode, ShieldCheck} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {CodeInput} from "./CodeInput";
import {ApiError, type ApiClient} from "@/lib/api";
import {brand} from "@/lib/brand";

type Setup = {secret: string; otpauthUri: string; qrDataUrl: string};

/**
 * Enrolamiento de la app autenticadora en tres pasos: escanear el QR, confirmar un código
 * y guardar los códigos de recuperación. Sirve para superadmin y propietarios.
 */
export function TwoFactorSetup({client, base, onDone, doneLabel = "Continuar"}: {
  client: ApiClient; base: string; onDone: () => void; doneLabel?: string;
}) {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function start() {
    setBusy(true); setError("");
    try { setSetup(await client.json<Setup>(base + "/setup", {})); }
    catch (err) { setError(err instanceof ApiError ? err.message : "No se pudo generar el código QR."); }
    finally { setBusy(false); }
  }

  async function confirm(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get("code") ?? "");
    setBusy(true); setError("");
    try { setCodes((await client.json<{recoveryCodes: string[]}>(base + "/confirm", {code})).recoveryCodes); }
    catch (err) { setError(err instanceof ApiError ? err.message : "No se pudo confirmar el código."); }
    finally { setBusy(false); }
  }

  if (codes) return <RecoveryCodes codes={codes} onDone={onDone} doneLabel={doneLabel}/>;

  if (!setup) return <div className="grid gap-5">
    <ol className="grid gap-4 text-sm">
      {[
        [QrCode, "Escanea un código QR", "Con Google Authenticator, Microsoft Authenticator, Authy o 1Password."],
        [KeyRound, "Confirma con un código", "Escribe los 6 dígitos que muestra la app."],
        [ShieldCheck, "Guarda tus códigos de recuperación", "Te sirven si pierdes el celular."],
      ].map(([Icon, title, text], i) => {
        const I = Icon as typeof QrCode;
        return <li key={i} className="flex gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-full bg-accent text-accent-foreground"><I className="size-4"/></span>
          <span><span className="font-medium">{title as string}</span><br/><span className="text-muted-foreground">{text as string}</span></span></li>;
      })}
    </ol>
    {error && <Alert variant="destructive">{error}</Alert>}
    <Button size="lg" onClick={() => void start()} loading={busy}>Empezar configuración</Button>
  </div>;

  return <div className="grid gap-6">
    <div className="grid justify-items-center gap-4 rounded-xl border bg-card p-6 shadow-card">
      {/* eslint-disable-next-line @next/next/no-img-element -- data URL generada por el backend */}
      <img src={setup.qrDataUrl} alt="Código QR para tu app autenticadora" width={200} height={200} className="size-50 rounded-lg bg-white p-2"/>
      <p className="text-center text-sm text-muted-foreground">Abre tu app autenticadora, toca <strong>Agregar cuenta</strong> y escanea el código.
        Aparecerá como <strong>{brand.name}</strong>.</p>
      <ManualSecret secret={setup.secret}/>
      <a href={setup.otpauthUri} className="text-sm font-medium text-primary underline-offset-4 hover:underline sm:hidden">Abrir en la app autenticadora de este celular</a>
    </div>
    <form onSubmit={confirm} className="grid gap-3">
      <label htmlFor="mfa-code" className="text-sm font-medium">Código de 6 dígitos</label>
      <CodeInput id="mfa-code" name="code" required autoFocus disabled={busy}/>
      {error && <Alert variant="destructive">{error}</Alert>}
      <Button size="lg" type="submit" loading={busy}>Activar verificación</Button>
    </form>
  </div>;
}

function ManualSecret({secret}: {secret: string}) {
  const [copied, setCopied] = useState(false);
  const grouped = secret.match(/.{1,4}/g)?.join(" ") ?? secret;
  return <details className="w-full text-sm">
    <summary className="cursor-pointer text-center text-muted-foreground">¿No puedes escanear? Ingresa la clave a mano</summary>
    <div className="mt-3 flex items-center gap-2 rounded-lg bg-muted p-3">
      <code className="flex-1 break-all font-mono text-xs">{grouped}</code>
      <Button size="sm" variant="outline" type="button" onClick={() => {
        void navigator.clipboard?.writeText(secret).then(() => { setCopied(true); setTimeout(() => setCopied(false), 2000); });
      }}>{copied ? <Check/> : <Copy/>}{copied ? "Copiada" : "Copiar"}</Button>
    </div>
  </details>;
}

export function RecoveryCodes({codes, onDone, doneLabel = "Continuar"}: {codes: string[]; onDone: () => void; doneLabel?: string}) {
  const [saved, setSaved] = useState(false);
  const [copied, setCopied] = useState(false);
  const text = brand.name + " · códigos de recuperación\nCada código sirve una sola vez.\n\n" + codes.join("\n") + "\n";
  function download() {
    const url = URL.createObjectURL(new Blob([text], {type: "text/plain"}));
    const link = Object.assign(document.createElement("a"), {href: url, download: "codigos-recuperacion-" + brand.name.toLowerCase() + ".txt"});
    link.click();
    URL.revokeObjectURL(url);
  }
  return <div className="grid gap-5">
    <Alert variant="success" title="Verificación en dos pasos activada">
      Guarda estos códigos en un lugar seguro. Si pierdes tu celular, cada uno te deja entrar una vez.
    </Alert>
    <ul className="grid grid-cols-2 gap-2 rounded-xl border bg-card p-4 font-mono text-sm shadow-card">
      {codes.map(c => <li key={c} className="rounded-md bg-muted px-3 py-2 text-center tracking-wider">{c}</li>)}
    </ul>
    <div className="flex flex-wrap gap-2">
      <Button variant="outline" type="button" onClick={download}><Download/>Descargar .txt</Button>
      <Button variant="outline" type="button" onClick={() => {
        void navigator.clipboard?.writeText(codes.join("\n")).then(() => setCopied(true));
      }}>{copied ? <Check/> : <Copy/>}{copied ? "Copiados" : "Copiar"}</Button>
    </div>
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" className="size-4 accent-[var(--primary)]" checked={saved} onChange={e => setSaved(e.target.checked)}/>
      Ya guardé mis códigos de recuperación
    </label>
    <Button size="lg" disabled={!saved} onClick={onDone}>{doneLabel}</Button>
  </div>;
}
