"use client";

import {useEffect,useState,type FormEvent} from "react";

const labels={NONE:"Ninguno",LINK:"Enlace",PDF:"PDF",IMAGE:"Imagen"};
type Kind=keyof typeof labels;
type Resource={category:string;link:string|null;pdf:string|null;image:string|null;active:Kind;revision:number};
type Props={store:string;disabled?:boolean;insert?:(line:string)=>void;
  send?:(path:string,body:string,method:"PUT")=>Promise<Response>};

function activeUrl(resource:Resource) {
  switch (resource.active) {
    case "LINK": return resource.link;
    case "PDF": return resource.pdf;
    case "IMAGE": return resource.image;
    default: return null;
  }
}

export default function CategoryResources({store,disabled,insert,send}:Props) {
  const path="stores/"+store+"/catalog/resources";
  const [open,setOpen]=useState(false);
  const [rows,setRows]=useState<Resource[]|null>(null);
  const [category,setCategory]=useState("");
  const [message,setMessage]=useState("");
  const [busy,setBusy]=useState(false);
  const [refresh,setRefresh]=useState(0);
  useEffect(()=>{
    if (!open) return;
    const controller=new AbortController();
    setRows(null);setMessage("");
    async function load() {
      try {
        const res=await fetch("/api/owner/"+path,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const data:Resource[]=await res.json();
        if (!controller.signal.aborted) setRows(data);
      } catch {if (!controller.signal.aborted) setMessage("No se pudieron cargar las categorías.");}
    }
    void load();
    return ()=>controller.abort();
  },[open,path,refresh]);
  const options=(rows || []).filter(row=>!insert || activeUrl(row));
  const selected=options.find(row=>row.category===category) || options[0];

  async function save(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!selected || !send || busy) return;
    const fields=new FormData(e.currentTarget);
    setBusy(true);setMessage("");
    try {
      const res=await send(path,JSON.stringify({...selected,link:fields.get("link"),pdf:fields.get("pdf"),
        image:fields.get("image"),active:fields.get("active")}),"PUT");
      if (res.status===409) throw new Error("La categoría cambió. Pulsa Actualizar para cargar la versión vigente.");
      if (!res.ok) throw new Error("Revisa las URL HTTPS y comprueba que el recurso activo tenga una dirección.");
      const saved:Resource=await res.json();
      setRows(current=>(current || []).map(row=>row.category===saved.category?saved:row));
      setMessage("Recursos guardados.");
    } catch(err) {setMessage(err instanceof Error?err.message:"No se pudo guardar.");}
    finally {setBusy(false);}
  }

  return <details className="my-3 rounded-lg border p-3" onToggle={e=>{e.stopPropagation();setOpen(e.currentTarget.open);}}>
    <summary className="cursor-pointer font-semibold">{insert?"Agregar catálogo al mensaje":"Recursos de catálogo por categoría"}</summary>
    {open && <div className="mt-2 grid max-w-xl gap-2">
      <button type="button" disabled={busy} className="w-fit underline" onClick={()=>setRefresh(n=>n+1)}>Actualizar categorías</button>
      {selected ? <>
        <label>Categoría <select disabled={busy} value={selected.category} onChange={e=>{setCategory(e.target.value);setMessage("");}} className="rounded border p-1">
          {options.map(row=><option key={row.category}>{row.category}</option>)}
        </select></label>
        {insert ? <button type="button" disabled={disabled} className="w-fit text-teal-800 underline"
          onClick={()=>insert("Catálogo de "+selected.category+": "+activeUrl(selected))}>
          Agregar {labels[selected.active].toLowerCase()} al borrador</button> :
          <form key={selected.category+":"+selected.revision} onSubmit={save} className="grid gap-2">
            {(["link","pdf","image"] as const).map((field,i)=><label key={field}>{["Enlace al catálogo","URL del PDF","URL de la imagen"][i]}
              <input type="url" name={field} maxLength={2048} placeholder="https://" defaultValue={selected[field] || ""}
                disabled={busy} className="block w-full rounded border p-2"/></label>)}
            <label>Recurso para compartir <select name="active" defaultValue={selected.active} disabled={busy} className="rounded border p-1">
              {Object.entries(labels).map(([key,label])=><option key={key} value={key}>{label}</option>)}
            </select></label>
            <button disabled={busy} className="rounded bg-teal-800 px-3 py-2 text-white">Guardar recursos</button>
          </form>}
      </> : rows ? <p>{insert?"Aún no hay catálogos activos para compartir.":"Asigna una categoría a un producto para configurar sus recursos."}</p> :
        !message && <p>Cargando categorías…</p>}
      <p role="status">{message}</p>
    </div>}
  </details>;
}
