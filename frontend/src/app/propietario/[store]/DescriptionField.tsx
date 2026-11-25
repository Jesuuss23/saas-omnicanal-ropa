"use client";

import {useState} from "react";
import {ListRestart, Sparkles} from "lucide-react";
import {Button} from "@/components/ui/button";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";

export type DescriptionFacts = {
  name: string; category?: string | null; subcategory?: string | null; material?: string | null;
  colors: string[]; sizes: string[];
};

function join(items: string[]) {
  const list = [...new Set(items.map(i => i.trim()).filter(Boolean))];
  if (list.length <= 1) return list[0] ?? "";
  return list.slice(0, -1).join(", ") + " y " + list[list.length - 1];
}

/** Borrador con los datos de la ficha; es el mismo que arma el backend cuando la IA no está configurada. */
export function draftDescription(f: DescriptionFacts) {
  if (!f.name.trim()) return "";
  let s = f.name.trim();
  if (f.material?.trim()) s += " en " + f.material.trim().toLowerCase();
  s += ".";
  if (f.category?.trim()) s += " Categoría " + f.category.trim() + (f.subcategory?.trim() ? " · " + f.subcategory.trim() : "") + ".";
  const colors = join(f.colors), sizes = join(f.sizes);
  if (colors) s += " Colores: " + colors + ".";
  if (sizes) s += " Tallas: " + sizes + ".";
  return s;
}

/** Descripción del producto: editable, con borrador desde los datos y redacción con IA. */
export function DescriptionField({id, store, value, onChange, facts}: {
  id: string; store: string; value: string; onChange: (value: string) => void; facts: DescriptionFacts;
}) {
  const [busy, setBusy] = useState(false);

  async function generate() {
    if (!facts.name.trim()) { toast.error("Escribe primero el nombre de la prenda."); return; }
    setBusy(true);
    try {
      const res = await ownerApi.json<{description: string; ai: boolean}>("stores/" + store + "/catalog/description/generate", {
        name: facts.name.trim(), category: facts.category || null, subcategory: facts.subcategory || null,
        material: facts.material || null, colors: facts.colors.slice(0, 30), sizes: facts.sizes.slice(0, 30),
        notes: value.trim() && value.trim() !== draftDescription(facts) ? value.trim().slice(0, 500) : null,
      });
      onChange(res.description);
      if (res.ai) toast.success("Descripción generada", {description: "Revísala y ajústala antes de guardar."});
      else toast.info("La IA no está configurada", {description: "Te dejamos un borrador con los datos de la prenda."});
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "No se pudo generar la descripción.");
    } finally { setBusy(false); }
  }

  return <div className="grid gap-1.5">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <label htmlFor={id} className="text-sm font-medium">Descripción</label>
      <span className="flex flex-wrap gap-1.5">
        <Button type="button" size="sm" variant="ghost" onClick={() => onChange(draftDescription(facts))} disabled={busy || !facts.name.trim()}>
          <ListRestart/>Usar datos de la prenda</Button>
        <Button type="button" size="sm" variant="outline" loading={busy} onClick={() => void generate()}>
          <Sparkles/>Generar descripción con IA</Button>
      </span>
    </div>
    <textarea id={id} value={value} onChange={e => onChange(e.target.value)} maxLength={2000} rows={4}
      placeholder="Cuenta cómo es la prenda, para qué ocasión sirve y cómo combinarla."
      className="rounded-lg border border-input bg-background px-3 py-2 text-sm"/>
    <p className="text-xs text-muted-foreground">{value.length}/2000. Se muestra en la ficha de tu tienda y la IA de WhatsApp la usa para responder.</p>
  </div>;
}
