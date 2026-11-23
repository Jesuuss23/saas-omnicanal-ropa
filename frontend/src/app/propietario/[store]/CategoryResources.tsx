"use client";
import {ownerApi,type Send} from "@/lib/api";

import {useState,type FormEvent} from "react";
import {useApiData} from "@/lib/useApiData";

const labels={NONE:"Ninguno",LINK:"Enlace",PDF:"PDF",IMAGE:"Imagen"};
type Kind=keyof typeof labels;
type Resource={category:string;link:string|null;pdf:string|null;image:string|null;active:Kind;revision:number};
type Props={store:string;chat?:string;disabled?:boolean;insert?:(line:string)=>void;
  send?:Send};

function activeUrl(resource:Resource) {
  switch (resource.active) {
    case "LINK": return resource.link;
    case "PDF": return resource.pdf;
    case "IMAGE": return resource.image;
    default: return null;
  }
}

function normalizeWords(text:string) {
  return text.toLocaleLowerCase("es").normalize("NFD").replace(/[\u0300-\u036f]/g,"")
    .replace(/[^a-z0-9]+/g," ").trim();
}

async function fileData(field:FormDataEntryValue|null):Promise<string|null> {
  if (!(field instanceof File) || !field.name) return null;
  if (!field.size || field.size>2*1024*1024) throw new Error("Elige un archivo de hasta 2 MiB con contenido.");
  return new Promise((resolve,reject)=>{
    const reader=new FileReader();
    reader.onload=()=>resolve(String(reader.result).split(",")[1]);
    reader.onerror=()=>reject(new Error("No se pudo leer el archivo."));
    reader.readAsDataURL(field);
  });
}

export default function CategoryResources({store,chat,disabled,insert,send}:Props) {
  const path="stores/"+store+"/catalog/resources";
  const [open,setOpen]=useState(false);
  const [category,setCategory]=useState("");
  const [message,setMessage]=useState("");
  const [busy,setBusy]=useState(false);
  const [refresh,setRefresh]=useState(0);
  const resources=useApiData<Resource[]>(open?ownerApi.url(path):null,refresh);
  const rows=resources.data;
  const history=useApiData<{text:string;receivedAt:string}[]>(
    open && chat ? ownerApi.url("stores/"+store+"/crm/messages?"+new URLSearchParams({chat})) : null,refresh);
  const historyFailed=history.error!==null;
  const recent=[...(history.data ?? [])].sort((a,b)=>Date.parse(b.receivedAt)-Date.parse(a.receivedAt))
    .slice(0,5).map(m=>normalizeWords(m.text));
  const status=message || (resources.error ? "No se pudieron cargar las categorías." : "");

  const options=rows ?? [];
  const suggested=recent.flatMap(message=>options.filter(row=>{
    const phrase=normalizeWords(row.category);
    return phrase && (" "+message+" ").includes(" "+phrase+" ");
  })).filter((row,index,all)=>all.indexOf(row)===index);
  const selected=options.find(row=>row.category===category) || (insert && options.find(row=>activeUrl(row))) || options[0];
  const resourceUrl=selected ? activeUrl(selected) : null;

  const shareUrl=selected?.active==="PDF" && resourceUrl?.startsWith("/catalogos/")
    ? resourceUrl+"?view=1" : resourceUrl;

  const categoryPath=selected
    ? "/tienda/"+encodeURIComponent(store)+"?"+new URLSearchParams({category:selected.category})
    : "";

  async function save(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!selected || !send || busy) return;
    const fields=new FormData(e.currentTarget);
    setBusy(true);setMessage("");
    try {
      const resource={...selected,link:fields.get("link"),pdf:fields.get("pdf"),image:fields.get("image"),active:fields.get("active")};
      const [pdfData,imageData]=await Promise.all([fileData(fields.get("pdfFile")),fileData(fields.get("imageFile"))]);
      const uploading=Boolean(pdfData || imageData);
      const res=await send(uploading?path+"/files":path,JSON.stringify(uploading?{resource,pdfData,imageData}:resource),"PUT");
      if (res.status===415) throw new Error("El contenido debe corresponder a un PDF, PNG o JPEG.");
      if (res.status===413) throw new Error("El archivo supera el límite de carga.");
      if (res.status===409) throw new Error("La categoría cambió. Pulsa Actualizar para cargar la versión vigente.");
      if (!res.ok) throw new Error("Revisa las URL HTTPS y comprueba que el recurso activo tenga una dirección.");
      const saved:Resource=await res.json();
      resources.mutate((rows || []).map(row=>row.category===saved.category?saved:row));
      setMessage("Recursos guardados.");
    } catch(err) {setMessage(err instanceof Error?err.message:"No se pudo guardar.");}
    finally {setBusy(false);}
  }

  return <details className="my-3 rounded-lg border p-3" onToggle={e=>{e.stopPropagation();setOpen(e.currentTarget.open);}}>
    <summary className="cursor-pointer font-semibold">{insert?"Agregar catálogo al mensaje":"Recursos de catálogo por categoría"}</summary>
    {open && <div className="mt-2 grid max-w-xl gap-2">
      <button type="button" disabled={busy} className="w-fit underline" onClick={()=>{setMessage("");setRefresh(n=>n+1);}}>Actualizar categorías</button>
      {selected ? <>
        {insert && suggested.length>0 && <fieldset className="flex flex-wrap gap-2">
          <legend className="text-sm font-medium">Categorías mencionadas por el cliente</legend>
          {suggested.map(row=><button key={row.category} type="button" disabled={disabled}
            aria-pressed={selected.category===row.category} onClick={()=>setCategory(row.category)}
            className="rounded border px-2 py-1 aria-pressed:border-teal-800">{row.category}</button>)}
        </fieldset>}
        {historyFailed && <p role="status">No se pudieron leer los mensajes recientes. Puedes elegir la categoría manualmente.</p>}
        <label>Categoría <select disabled={busy} value={selected.category} onChange={e=>{setCategory(e.target.value);setMessage("");}} className="rounded border p-1">
          {options.map(row=><option key={row.category}>{row.category}</option>)}
        </select></label>
        {insert ? <div className="flex flex-wrap gap-3">
          {shareUrl && <>
            <a href={shareUrl} target="_blank" rel="noopener noreferrer" className="text-teal-800 underline">
              Revisar {labels[selected.active].toLowerCase()} de {selected.category}
            </a>
            <button type="button" disabled={disabled} className="text-teal-800 underline"
              onClick={()=>insert("Catálogo de "+selected.category+": "+new URL(shareUrl,window.location.origin).href)}>
              Agregar {labels[selected.active].toLowerCase()} al borrador
            </button>
          </>}
          <button type="button" disabled={disabled} className="text-teal-800 underline" onClick={()=>{
            const url=new URL(categoryPath,window.location.origin);
            insert("Ver "+selected.category+" en la tienda: "+url.href);
          }}>Agregar enlace a la categoría en la tienda</button>
        </div> :
          <form key={selected.category+":"+selected.revision} onSubmit={save} className="grid gap-2">
            <div className="rounded border p-3">
              <p>Recurso publicado: {labels[selected.active]}</p>
              <a href={categoryPath} target="_blank" rel="noopener noreferrer" className="text-teal-800 underline">
                Ver categoría en la tienda pública
              </a>
            </div>
            {(["link","pdf","image"] as const).map((field,i)=><label key={field}>{["Enlace al catálogo","URL del PDF","URL de la imagen"][i]}
              <input type={selected[field]?.startsWith("/catalogos/")?"text":"url"} inputMode="url" name={field} maxLength={2048} placeholder="https://" defaultValue={selected[field] || ""}
                disabled={busy} className="block w-full rounded border p-2"/></label>)}
            <p>Para reemplazar una URL por un archivo, selecciónalo aquí. Máximo 2 MiB por archivo.</p>
            <label>Subir PDF <input type="file" name="pdfFile" accept="application/pdf" disabled={busy}/></label>
            <label>Subir imagen <input type="file" name="imageFile" accept="image/png,image/jpeg" disabled={busy}/></label>
            <label>Recurso para compartir <select name="active" defaultValue={selected.active} disabled={busy} className="rounded border p-1">
              {Object.entries(labels).map(([key,label])=><option key={key} value={key}>{label}</option>)}
            </select></label>
            <button disabled={busy} className="rounded bg-teal-800 px-3 py-2 text-white">Guardar recursos</button>
          </form>}
      </> : rows ? <p>{insert?"Aún no hay categorías para compartir.":"Asigna una categoría a un producto para configurar sus recursos."}</p> :
        !status && <p>Cargando categorías…</p>}
      <p role="status">{status}</p>
    </div>}
  </details>;
}
