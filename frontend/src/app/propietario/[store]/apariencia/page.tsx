"use client";

import {useRef, useState, type FormEvent} from "react";
import {AtSign, ImagePlus, MessageCircle, Trash2} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input, Textarea} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, errorMessage, ownerApi, publicApi} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";
import {useStore} from "../_panel/StoreContext";

type Appearance = {name: string; tagline: string | null; description: string | null; primaryColor: string;
  whatsappPhone: string | null; instagram: string | null; hasLogo: boolean; logoVersion: number; revision: number;
  logoUrl?: string | null};

const swatches = ["#0f766e", "#1d4ed8", "#7c3aed", "#be185d", "#c2410c", "#15803d", "#111827"];

export default function AppearancePage() {
  const {store, refreshProfile} = useStore();
  const path = "stores/" + store + "/appearance";
  const {data, error, mutate} = useApiData<Appearance>(ownerApi.url(path));
  return <>
    <PageHeader title="Apariencia" description="Cómo se ve tu tienda en línea: nombre, logo, color y datos de contacto."/>
    {error && <Alert variant="destructive">{error.message}</Alert>}
    {!data && !error && <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]"><Skeleton className="h-96"/><Skeleton className="h-96"/></div>}
    {data && <AppearanceForm key={data.revision} store={store} path={path} initial={data}
      onSaved={a => { mutate(a); refreshProfile(); }}/>}
  </>;
}

function AppearanceForm({store, path, initial, onSaved}: {store: string; path: string; initial: Appearance; onSaved: (a: Appearance) => void}) {
  const [draft, setDraft] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const file = useRef<HTMLInputElement>(null);
  const set = <K extends keyof Appearance>(key: K, value: Appearance[K]) => setDraft(d => ({...d, [key]: value}));
  const logoUrl = initial.hasLogo ? initial.logoUrl ?? publicApi.url("stores/" + store + "/logo") + "?v=" + initial.logoVersion : null;

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError("");
    try {
      onSaved(await ownerApi.json<Appearance>(path, {name: draft.name, tagline: draft.tagline, description: draft.description,
        primaryColor: draft.primaryColor, whatsappPhone: draft.whatsappPhone, instagram: draft.instagram, revision: initial.revision}, "PUT"));
      toast.success("Apariencia guardada");
    } catch (e) { setError(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  async function uploadLogo(files: FileList | null) {
    const f = files?.[0];
    if (!f) return;
    if (f.size > 512 * 1024) { toast.error("El logo debe pesar menos de 512 KB."); return; }
    const body = new FormData();
    body.append("file", f);
    setBusy(true);
    try {
      const res = await ownerApi.send(path + "/logo", body);
      if (!res.ok) { toast.error(await errorMessage(res, "No se pudo subir el logo.")); return; }
      onSaved(await res.json());
      toast.success("Logo actualizado");
    } finally { setBusy(false); if (file.current) file.current.value = ""; }
  }

  async function removeLogo() {
    setBusy(true);
    try { onSaved(await ownerApi.json<Appearance>(path + "/logo", undefined, "DELETE")); }
    catch { toast.error("No se pudo quitar el logo."); }
    finally { setBusy(false); }
  }

  return <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
    <form onSubmit={save} className="grid gap-6">
      <Card><CardHeader><CardTitle>Identidad</CardTitle></CardHeader><CardContent className="grid gap-4">
        <Field label="Nombre de la tienda" htmlFor="ap-name"><Input id="ap-name" value={draft.name} maxLength={100} required onChange={e => set("name", e.target.value)}/></Field>
        <Field label="Frase corta" htmlFor="ap-tagline" hint="Aparece bajo el nombre. Ej.: Moda urbana para todos los días.">
          <Input id="ap-tagline" value={draft.tagline ?? ""} maxLength={140} onChange={e => set("tagline", e.target.value)}/></Field>
        <Field label="Sobre la tienda" htmlFor="ap-desc"><Textarea id="ap-desc" value={draft.description ?? ""} maxLength={1000} onChange={e => set("description", e.target.value)}/></Field>
        <div className="grid gap-2">
          <p className="text-sm font-medium">Color principal</p>
          <div className="flex flex-wrap items-center gap-2">
            {swatches.map(c => <button key={c} type="button" aria-label={"Usar " + c} aria-pressed={draft.primaryColor === c}
              onClick={() => set("primaryColor", c)} style={{background: c}}
              className="size-8 rounded-full ring-offset-2 ring-offset-card aria-pressed:ring-2 aria-pressed:ring-foreground"/>)}
            <label className="ml-2 flex items-center gap-2 text-sm text-muted-foreground">Otro
              <input type="color" value={draft.primaryColor} onChange={e => set("primaryColor", e.target.value)} className="h-8 w-10 cursor-pointer rounded border bg-card"/></label>
          </div>
        </div>
      </CardContent></Card>
      <Card><CardHeader><CardTitle>Contacto</CardTitle><CardDescription>Se muestra en tu tienda para que te escriban.</CardDescription></CardHeader>
        <CardContent className="grid items-start gap-4 sm:grid-cols-2">
          <Field label="WhatsApp" htmlFor="ap-wa" hint="Con código de país, ej.: 51987654321."><Input id="ap-wa" inputMode="tel" value={draft.whatsappPhone ?? ""} maxLength={20} onChange={e => set("whatsappPhone", e.target.value)}/></Field>
          <Field label="Instagram" htmlFor="ap-ig"><Input id="ap-ig" value={draft.instagram ?? ""} maxLength={31} placeholder="@tutienda" onChange={e => set("instagram", e.target.value)}/></Field>
        </CardContent></Card>
      {error && <Alert variant="destructive">{error}</Alert>}
      <div><Button type="submit" loading={busy}>Guardar cambios</Button></div>
    </form>

    <div className="grid content-start gap-6">
      <Card><CardHeader><CardTitle>Logo</CardTitle><CardDescription>PNG, JPG o WebP de hasta 512 KB. Mejor cuadrado.</CardDescription></CardHeader>
        <CardContent className="flex items-center gap-4">
          <div className="grid size-20 place-items-center overflow-hidden rounded-xl border bg-muted text-2xl font-semibold text-muted-foreground">
            {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
            {logoUrl ? <img src={logoUrl} alt="Logo actual" className="size-full object-contain"/> : draft.name.slice(0, 1).toUpperCase()}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" disabled={busy} onClick={() => file.current?.click()}><ImagePlus/>{initial.hasLogo ? "Cambiar" : "Subir logo"}</Button>
            {initial.hasLogo && <Button type="button" variant="ghost" disabled={busy} onClick={removeLogo}><Trash2/>Quitar</Button>}
            <input ref={file} type="file" hidden accept="image/png,image/jpeg,image/webp" aria-label="Elegir logo" onChange={e => void uploadLogo(e.target.files)}/>
          </div>
        </CardContent></Card>

      <Card className="overflow-hidden"><CardHeader><CardTitle>Vista previa</CardTitle></CardHeader>
        <CardContent>
          <div className="overflow-hidden rounded-xl border">
            <div className="flex items-center gap-3 p-4 text-white" style={{background: draft.primaryColor}}>
              <div className="grid size-10 place-items-center overflow-hidden rounded-lg bg-white/20 font-semibold">
                {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
                {logoUrl ? <img src={logoUrl} alt="" className="size-full object-contain bg-white"/> : draft.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0"><p className="truncate font-semibold">{draft.name || "Tu tienda"}</p>
                {draft.tagline && <p className="truncate text-sm text-white/80">{draft.tagline}</p>}</div>
            </div>
            <div className="grid grid-cols-3 gap-2 p-4">{[0, 1, 2].map(i => <div key={i} className="grid gap-1">
              <div className="aspect-[3/4] rounded-md bg-muted"/><div className="h-2 w-3/4 rounded bg-muted"/>
              <div className="h-2 w-1/3 rounded" style={{background: draft.primaryColor}}/></div>)}</div>
            <div className="flex gap-4 border-t px-4 py-3 text-xs text-muted-foreground">
              {draft.whatsappPhone && <span className="flex items-center gap-1"><MessageCircle className="size-3"/>WhatsApp</span>}
              {draft.instagram && <span className="flex items-center gap-1"><AtSign className="size-3"/>@{draft.instagram.replace(/^@/, "")}</span>}
              {!draft.whatsappPhone && !draft.instagram && <span>Agrega tus datos de contacto</span>}
            </div>
          </div>
        </CardContent></Card>
    </div>
  </div>;
}
