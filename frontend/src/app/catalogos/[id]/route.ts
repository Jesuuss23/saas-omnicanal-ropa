import {backendBase} from "@/lib/api";
const extensions:Record<string,string>={"application/pdf":"pdf","image/png":"png","image/jpeg":"jpg"};
// Solo se descargan archivos de Cloudinary (la URL viene del backend, pero no se sigue a ningún otro servidor).
const cloudinary=/^https:\/\/res\.cloudinary\.com\/[A-Za-z0-9_-]+\//;

export async function GET(request:Request,context:{params:Promise<{id:string}>}) {
  const {id}=await context.params;
  if (!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id)) return new Response(null,{status:404});
  const base=backendBase();
  try {
    const result=await fetch(base+"/public/catalog-files/"+id,{cache:"no-store"});
    if (!result.ok) return new Response(null,{status:result.status===404?404:502});
    const file=await result.json();
    if (!Object.prototype.hasOwnProperty.call(extensions,file.mediaType)) return new Response(null,{status:502});
    let content:Uint8Array;
    if (typeof file.content==="string") content=Uint8Array.from(Buffer.from(file.content,"base64"));
    else if (typeof file.url==="string" && cloudinary.test(file.url)) {
      // Archivo en Cloudinary: se entrega con el mismo nombre, descarga y aislamiento que uno guardado en la base.
      const stored=await fetch(file.url,{cache:"no-store",redirect:"error"});
      if (!stored.ok) return new Response(null,{status:502});
      content=new Uint8Array(await stored.arrayBuffer());
    } else return new Response(null,{status:502});
    const params=new URL(request.url).searchParams;
    const download=params.get("download")==="1" || (file.mediaType==="application/pdf" && params.get("view")!=="1");
    const disposition=download ? "attachment" : "inline";
    return new Response(content as BodyInit,{
      headers:{
        "Content-Type":file.mediaType,
        "Content-Disposition":disposition+'; filename="catalogo.'+extensions[file.mediaType]+'"',
        "X-Content-Type-Options":"nosniff",
        "Cache-Control":"no-store",
        "Content-Security-Policy":"sandbox; default-src 'none'"
      }
    });
  } catch {
    return new Response(null,{status:502});
  }
}
