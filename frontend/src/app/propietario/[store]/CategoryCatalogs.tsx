"use client";

import {useState, type FormEvent} from "react";
import {Download, ExternalLink, FileText, Image as ImageIcon, Link2, Lock, Pencil, RefreshCw, Sparkles, Wand2} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {apiFetch, errorMessage, ownerApi, type Send} from "@/lib/api";
import {cn} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Kind = "NONE" | "PDF" | "IMAGE" | "LINK";
type Resource = {category: string; link: string | null; pdf: string | null; image: string | null; active: Kind; revision: number};

const MAX_PDF = 10 * 1024 * 1024;
const MAX_IMAGE = 2 * 1024 * 1024;
const kindLabel: Record<Exclude<Kind, "NONE">, string> = {PDF: "PDF", IMAGE: "Imagen", LINK: "Enlace"};

/** Recurso que se usa: el elegido o, en automático, el primero cargado en orden PDF → imagen → enlace. */
export function effectiveKind(r: Pick<Resource, "active" | "pdf" | "image" | "link">): Exclude<Kind, "NONE"> | null {
  if (r.active !== "NONE") return r.active;
  return r.pdf ? "PDF" : r.image ? "IMAGE" : r.link ? "LINK" : null;
}

const urlOf = (r: Resource, k: Exclude<Kind, "NONE">) => k === "PDF" ? r.pdf : k === "IMAGE" ? r.image : r.link;
const uploaded = (url: string | null) => !!url && url.startsWith("/catalogos/");

function base64(file: File, max: number): Promise<string> {
  if (!file.size || file.size > max) return Promise.reject(new Error("El archivo debe pesar hasta " + Math.round(max / 1024 / 1024) + " MB."));
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.onerror = () => reject(new Error("No se pudo leer el archivo."));
    reader.readAsDataURL(file);
  });
}

/**
 * Catálogo de cada categoría para la IA y la tienda: PDF, imagen o enlace. Aunque haya varios, se usa uno solo: el que
 * elijas, o en automático el primero en orden PDF → imagen → enlace. El PDF se puede generar desde el catálogo.
 */
export default function CategoryCatalogs({store, send, compact}: {store: string; send: Send; compact?: boolean}) {
  const path = "stores/" + store + "/catalog";
  const [refresh, setRefresh] = useState(0);
  const resources = useApiData<Resource[]>(ownerApi.url(path + "/resources"), refresh);
  const subcategories = useApiData<Record<string, string[]>>(ownerApi.url(path + "/subcategories"), refresh).data ?? {};
  const caps = useApiData<{send: boolean}>(ownerApi.url("stores/" + store + "/whatsapp/media/capabilities")).data;
  const [editing, setEditing] = useState<Resource | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  async function download(category: string) {
    setBusy("download:" + category);
    try {
      const res = await apiFetch(ownerApi.url(path + "/pdf?" + new URLSearchParams({category})));
      if (!res.ok) throw new Error(await errorMessage(res, "No se pudo generar el PDF."));
      const url = URL.createObjectURL(await res.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = "Catalogo " + category + ".pdf";
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (e) { toast.error(e instanceof Error ? e.message : "No se pudo generar el PDF."); }
    finally { setBusy(null); }
  }

  async function saveGenerated(category: string) {
    setBusy("generate:" + category);
    try {
      const res = await send(path + "/resources/generated-pdf?" + new URLSearchParams({category}), undefined, "POST");
      if (!res.ok) throw new Error(await errorMessage(res, "No se pudo generar el PDF."));
      toast.success("PDF de " + category + " generado", {description: "Ya es el PDF de la categoría. Vuelve a generarlo cuando cambien precios o stock."});
      setRefresh(n => n + 1);
    } catch (e) { toast.error(e instanceof Error ? e.message : "No se pudo generar el PDF."); }
    finally { setBusy(null); }
  }

  const rows = resources.data;
  return <Card>
    <CardHeader className="flex-row items-start justify-between gap-4">
      <div className="grid gap-1">
        <CardTitle className="flex items-center gap-2"><FileText className="size-5 text-primary"/>Catálogos por categoría</CardTitle>
        <CardDescription>
          Carga un PDF, una imagen o un enlace por categoría. La IA lo envía cuando un cliente lo pide. Aunque cargues los tres, se usa uno solo:
          el que elijas o, en automático, el primero en este orden: PDF, imagen y enlace.
        </CardDescription>
      </div>
      <Button variant="ghost" size="sm" onClick={() => setRefresh(n => n + 1)} aria-label="Actualizar categorías"><RefreshCw/></Button>
    </CardHeader>
    <CardContent className="grid gap-3">
      {caps && !caps.send && <p className="flex items-start gap-2 rounded-lg bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
        <Lock className="mt-0.5 size-3.5 shrink-0"/>Con tu plan, la IA envía el PDF o el enlace. Las imágenes por WhatsApp están en el plan Premium: si la imagen es el recurso elegido, la IA usa el siguiente disponible.</p>}
      {resources.error && <Alert variant="destructive">{resources.error.message}</Alert>}
      {!rows && !resources.error && Array.from({length: 2}, (_, i) => <Skeleton key={i} className="h-28"/>)}
      {rows && !rows.length && <EmptyState icon={FileText} title="Aún no hay categorías" description="Asigna una categoría a tus prendas en el catálogo para cargar su PDF, imagen o enlace."/>}
      {rows && rows.length > 0 && <ul className={cn("grid gap-3", !compact && "lg:grid-cols-2")}>
        {rows.map(r => {
          const used = effectiveKind(r);
          const subs = Object.entries(subcategories).find(([k]) => k.toLowerCase() === r.category.toLowerCase())?.[1] ?? [];
          return <li key={r.category} className="grid gap-3 rounded-xl border bg-card p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate font-semibold">{r.category}</p>
                {subs.length > 0 && <p className="mt-0.5 truncate text-xs text-muted-foreground">{subs.join(" · ")}</p>}
              </div>
              {used ? <Badge variant="success">Se usa: {kindLabel[used]}{r.active === "NONE" ? " (automático)" : ""}</Badge>
                : <Badge variant="neutral">Sin catálogo</Badge>}
            </div>
            <div className="grid grid-cols-3 gap-2 text-xs">
              {(["PDF", "IMAGE", "LINK"] as const).map(k => {
                const url = urlOf(r, k);
                const Icon = k === "PDF" ? FileText : k === "IMAGE" ? ImageIcon : Link2;
                return <div key={k} className={cn("flex items-center gap-1.5 rounded-lg border px-2 py-1.5", used === k ? "border-primary bg-primary/5" : url ? "" : "border-dashed text-muted-foreground")}>
                  <Icon className="size-3.5 shrink-0"/><span className="truncate">{kindLabel[k]}{url ? (uploaded(url) ? " · archivo" : " · URL") : " · vacío"}</span>
                </div>;
              })}
            </div>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" onClick={() => setEditing(r)}><Pencil/>Editar</Button>
              <Button size="sm" variant="outline" loading={busy === "download:" + r.category} disabled={!!busy} onClick={() => download(r.category)}><Download/>Descargar PDF</Button>
              <Button size="sm" variant="outline" loading={busy === "generate:" + r.category} disabled={!!busy} onClick={() => saveGenerated(r.category)}
                title="Genera el PDF con tus prendas en stock y lo guarda como el PDF de la categoría"><Wand2/>Usar PDF generado</Button>
              {used && urlOf(r, used) && <Button size="sm" variant="ghost" asChild>
                <a href={uploaded(urlOf(r, used)) ? urlOf(r, used) + "?view=1" : urlOf(r, used)!} target="_blank" rel="noopener noreferrer"><ExternalLink/>Ver</a></Button>}
            </div>
          </li>;
        })}
      </ul>}
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Sparkles className="size-3.5"/>El PDF generado incluye foto, nombre, subcategoría, precio, tallas y colores de las prendas con stock.</p>
    </CardContent>
    {editing && <EditDialog key={editing.category + editing.revision} resource={editing} path={path + "/resources"} send={send}
      onClose={() => setEditing(null)} onSaved={() => { setEditing(null); setRefresh(n => n + 1); }}/>}
  </Card>;
}

function EditDialog({resource, path, send, onClose, onSaved}: {resource: Resource; path: string; send: Send; onClose: () => void; onSaved: () => void}) {
  const [link, setLink] = useState(resource.link ?? "");
  const [pdf, setPdf] = useState(uploaded(resource.pdf) ? "" : resource.pdf ?? "");
  const [image, setImage] = useState(uploaded(resource.image) ? "" : resource.image ?? "");
  const [keepPdf, setKeepPdf] = useState(uploaded(resource.pdf));
  const [keepImage, setKeepImage] = useState(uploaded(resource.image));
  const [pdfFile, setPdfFile] = useState<File | null>(null);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [active, setActive] = useState<Kind>(resource.active);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const has = {PDF: !!pdfFile || keepPdf || !!pdf.trim(), IMAGE: !!imageFile || keepImage || !!image.trim(), LINK: !!link.trim()};

  async function save(event: FormEvent) {
    event.preventDefault();
    if (active !== "NONE" && !has[active]) { setError("El recurso elegido no tiene archivo ni URL."); return; }
    setBusy(true); setError("");
    try {
      const body = {
        category: resource.category, link: link.trim() || null, revision: resource.revision, active,
        pdf: keepPdf ? resource.pdf : pdf.trim() || null, image: keepImage ? resource.image : image.trim() || null,
      };
      const [pdfData, imageData] = await Promise.all([pdfFile ? base64(pdfFile, MAX_PDF) : null, imageFile ? base64(imageFile, MAX_IMAGE) : null]);
      const files = !!(pdfData || imageData);
      const res = await send(files ? path + "/files" : path, JSON.stringify(files ? {resource: body, pdfData, imageData} : body), "PUT");
      if (res.status === 415) throw new Error("El archivo debe ser un PDF, PNG o JPG real.");
      if (res.status === 413) throw new Error("El archivo supera el límite: PDF hasta 10 MB, imagen hasta 2 MB.");
      if (res.status === 409) throw new Error("Alguien cambió esta categoría. Cierra y vuelve a abrir para ver la versión actual.");
      if (!res.ok) throw new Error(await errorMessage(res, "Revisa que las URL empiecen con https://."));
      toast.success("Catálogo de " + resource.category + " guardado");
      onSaved();
    } catch (e) { setError(e instanceof Error ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  const choices: {value: Kind; label: string; hint: string}[] = [
    {value: "NONE", label: "Automático", hint: "PDF → imagen → enlace, el primero que esté cargado"},
    {value: "PDF", label: "PDF", hint: has.PDF ? "Se envía como archivo" : "Carga un PDF para elegirlo"},
    {value: "IMAGE", label: "Imagen", hint: has.IMAGE ? "Se envía como foto (plan Premium)" : "Carga una imagen para elegirla"},
    {value: "LINK", label: "Enlace", hint: has.LINK ? "Se comparte como enlace" : "Escribe un enlace para elegirlo"},
  ];

  return <Dialog open onOpenChange={o => { if (!o && !busy) onClose(); }}>
    <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
      <DialogHeader><DialogTitle>Catálogo de {resource.category}</DialogTitle>
        <DialogDescription>Puedes cargar los tres. La IA y la tienda usan uno solo: el que elijas abajo.</DialogDescription></DialogHeader>
      <form onSubmit={save}>
        <fieldset disabled={busy} className="grid gap-4">
          <Field label="PDF" htmlFor="cc-pdf" hint="Hasta 10 MB. También puedes generarlo desde el catálogo con «Usar PDF generado».">
            {keepPdf ? <div className="flex items-center justify-between gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm">
              <span className="flex items-center gap-2"><FileText className="size-4"/>PDF cargado</span>
              <Button type="button" size="sm" variant="ghost" onClick={() => setKeepPdf(false)}>Reemplazar o quitar</Button></div>
              : <div className="grid gap-2">
                <Input id="cc-pdf" type="url" value={pdf} onChange={e => setPdf(e.target.value)} placeholder="https://… (o sube un archivo)" maxLength={2048} disabled={!!pdfFile}/>
                <input type="file" accept="application/pdf" aria-label="Subir PDF" className="text-sm" onChange={e => setPdfFile(e.target.files?.[0] ?? null)}/>
              </div>}
          </Field>
          <Field label="Imagen" htmlFor="cc-image" hint="PNG o JPG hasta 2 MB. Por WhatsApp solo se envía con el plan Premium.">
            {keepImage ? <div className="flex items-center justify-between gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm">
              <span className="flex items-center gap-2"><ImageIcon className="size-4"/>Imagen cargada</span>
              <Button type="button" size="sm" variant="ghost" onClick={() => setKeepImage(false)}>Reemplazar o quitar</Button></div>
              : <div className="grid gap-2">
                <Input id="cc-image" type="url" value={image} onChange={e => setImage(e.target.value)} placeholder="https://… (o sube un archivo)" maxLength={2048} disabled={!!imageFile}/>
                <input type="file" accept="image/png,image/jpeg" aria-label="Subir imagen" className="text-sm" onChange={e => setImageFile(e.target.files?.[0] ?? null)}/>
              </div>}
          </Field>
          <Field label="Enlace" htmlFor="cc-link" hint="Por ejemplo, un álbum o una carpeta con fotos. Debe empezar con https://.">
            <Input id="cc-link" type="url" value={link} onChange={e => setLink(e.target.value)} placeholder="https://" maxLength={2048}/></Field>
          <fieldset className="grid gap-2">
            <legend className="mb-1 text-sm font-medium">Qué se usa</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {choices.map(c => {
                const disabled = c.value !== "NONE" && !has[c.value];
                return <label key={c.value} className={cn("flex cursor-pointer items-start gap-2 rounded-lg border p-3 text-sm",
                  active === c.value && "border-primary bg-primary/5", disabled && "cursor-not-allowed opacity-50")}>
                  <input type="radio" name="active" value={c.value} checked={active === c.value} disabled={disabled}
                    onChange={() => setActive(c.value)} className="mt-0.5 accent-primary"/>
                  <span><span className="font-medium">{c.label}</span><span className="block text-xs text-muted-foreground">{c.hint}</span></span>
                </label>;
              })}
            </div>
          </fieldset>
          {error && <Alert variant="destructive">{error}</Alert>}
        </fieldset>
        <DialogFooter className="mt-4">
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>Cancelar</Button>
          <Button type="submit" loading={busy}>Guardar</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}
