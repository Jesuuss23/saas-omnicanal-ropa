/** Utilidades de marca de la tienda pública: el color lo elige el dueño, así que el texto sobre él se calcula. */
export type Brand = {tagline:string|null; description:string|null; primaryColor:string; whatsappPhone:string|null; instagram:string|null; logoVersion:number|null; logoUrl?:string|null};

const HEX=/^#[0-9a-f]{6}$/i;

/** Devuelve el color de texto legible (negro o blanco) sobre el color de la tienda, según su luminancia relativa. */
export function readableOn(hex:string):string {
  const channel=(i:number)=>{
    const c=parseInt(hex.slice(i,i+2),16)/255;
    return c<=0.03928 ? c/12.92 : ((c+0.055)/1.055)**2.4;
  };
  const luminance=0.2126*channel(1)+0.7152*channel(3)+0.0722*channel(5);
  return luminance>0.4 ? "#1c1917" : "#ffffff";
}

export function brandStyle(brand:Brand|null|undefined):Record<string,string> {
  const color=brand?.primaryColor && HEX.test(brand.primaryColor) ? brand.primaryColor : "#0f766e";
  return {"--store":color,"--store-fg":readableOn(color)};
}

export function whatsappLink(phone:string|null|undefined, text:string):string|null {
  const digits=(phone ?? "").replace(/\D/g,"");
  return digits.length>=8 ? "https://wa.me/"+digits+"?text="+encodeURIComponent(text) : null;
}

export function instagramLink(handle:string|null|undefined):string|null {
  const user=(handle ?? "").trim().replace(/^@/,"").replace(/^https?:\/\/(www\.)?instagram\.com\//i,"").replace(/\/.*$/,"");
  return /^[a-z0-9._]{1,30}$/i.test(user) ? "https://instagram.com/"+user : null;
}
