"use client";

import {useRef, useState} from "react";
import {ArrowLeft, ArrowRight, ImagePlus, Star, Trash2} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, errorMessage, ownerApi, publicApi} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";

type Photo = {id: string; position: number; mediaType: string; bytes: number};
const MAX = 8;
const MAX_BYTES = 2 * 1024 * 1024;

export function photoUrl(store: string, id: string) {
  return publicApi.url("stores/" + store + "/images/" + id);
}

/** Fotos de un producto: subir (JPG, PNG o WebP de hasta 2 MB), ordenar y quitar. La primera es la portada. */
export function ProductPhotos({store, product, name, open, onOpenChange, onChanged}: {
  store: string; product: string; name: string; open: boolean; onOpenChange: (open: boolean) => void; onChanged: () => void;
}) {
  const path = "stores/" + store + "/catalog/products/" + product + "/images";
  const {data, error, mutate} = useApiData<Photo[]>(open ? ownerApi.url(path) : null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  async function upload(files: FileList | null) {
    if (!files?.length || !data) return;
    const list = Array.from(files).slice(0, MAX - data.length);
    setBusy(true);
    try {
      for (const file of list) {
        if (file.size > MAX_BYTES) { toast.error(file.name + " supera 2 MB."); continue; }
        const body = new FormData();
        body.append("file", file);
        const res = await ownerApi.send(path, body);
        if (!res.ok) { toast.error(await errorMessage(res, "No se pudo subir " + file.name)); continue; }
        mutate(await res.json());
      }
      onChanged();
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  async function change(run: () => Promise<Photo[]>) {
    setBusy(true);
    try { mutate(await run()); onChanged(); }
    catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo guardar el cambio."); }
    finally { setBusy(false); }
  }

  function move(index: number, delta: number) {
    if (!data) return;
    const ids = data.map(p => p.id);
    const [item] = ids.splice(index, 1);
    ids.splice(index + delta, 0, item);
    void change(() => ownerApi.json<Photo[]>(path + "/order", {ids}, "PUT"));
  }

  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent className="max-w-3xl">
      <DialogHeader><DialogTitle>Fotos de {name}</DialogTitle>
        <DialogDescription>Hasta {MAX} fotos en JPG, PNG o WebP de máximo 2 MB. La primera es la portada en tu tienda.</DialogDescription></DialogHeader>
      {error && <Alert variant="destructive">{error.message}</Alert>}
      {!data && !error && <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="aspect-[3/4]"/>)}</div>}
      {data && <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {data.map((p, i) => <li key={p.id} className="group relative overflow-hidden rounded-lg border bg-muted">
          {/* eslint-disable-next-line @next/next/no-img-element -- imágenes servidas por el backend */}
          <img src={photoUrl(store, p.id)} alt={"Foto " + (i + 1) + " de " + name} className="aspect-[3/4] w-full object-cover"/>
          {i === 0 && <Badge className="absolute left-2 top-2 shadow-card"><Star/>Portada</Badge>}
          <div className="absolute inset-x-0 bottom-0 flex justify-between gap-1 bg-gradient-to-t from-black/60 to-transparent p-2">
            <span className="flex gap-1">
              <Button size="icon" variant="secondary" className="size-8" disabled={busy || i === 0} aria-label="Mover a la izquierda" onClick={() => move(i, -1)}><ArrowLeft/></Button>
              <Button size="icon" variant="secondary" className="size-8" disabled={busy || i === data.length - 1} aria-label="Mover a la derecha" onClick={() => move(i, 1)}><ArrowRight/></Button>
            </span>
            <Button size="icon" variant="destructive" className="size-8" disabled={busy} aria-label={"Quitar foto " + (i + 1)}
              onClick={() => change(() => ownerApi.json<Photo[]>(path + "/" + p.id, undefined, "DELETE"))}><Trash2/></Button>
          </div>
        </li>)}
        {data.length < MAX && <li>
          <button type="button" disabled={busy} onClick={() => input.current?.click()}
            className="flex aspect-[3/4] w-full flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed text-sm text-muted-foreground transition-colors hover:border-primary hover:text-primary disabled:opacity-50">
            <ImagePlus className="size-6"/>{busy ? "Subiendo…" : "Agregar fotos"}
          </button>
          <input ref={input} type="file" accept="image/jpeg,image/png,image/webp" multiple hidden aria-label="Elegir fotos"
            onChange={e => void upload(e.target.files)}/>
        </li>}
      </ul>}
    </DialogContent>
  </Dialog>;
}
