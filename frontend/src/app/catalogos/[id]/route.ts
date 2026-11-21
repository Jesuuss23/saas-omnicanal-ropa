const extensions:Record<string,string>={"application/pdf":"pdf","image/png":"png","image/jpeg":"jpg"};

export async function GET(request:Request,context:{params:Promise<{id:string}>}) {
  const {id}=await context.params;
  if (!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id)) return new Response(null,{status:404});
  const base=(process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/,"");
  try {
    const result=await fetch(base+"/public/catalog-files/"+id,{cache:"no-store"});
    if (!result.ok) return new Response(null,{status:result.status===404?404:502});
    const file=await result.json();
    if (!Object.prototype.hasOwnProperty.call(extensions,file.mediaType) || typeof file.content!=="string") return new Response(null,{status:502});
    const download=new URL(request.url).searchParams.get("download")==="1";
    const disposition=file.mediaType==="application/pdf" || download ? "attachment" : "inline";
    return new Response(Uint8Array.from(Buffer.from(file.content,"base64")),{
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
