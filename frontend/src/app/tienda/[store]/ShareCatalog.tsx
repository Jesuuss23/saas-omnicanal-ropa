"use client";

import {useState} from "react";

export default function ShareCatalog({path,storeName}:{path:string;storeName:string}) {
  const [notice,setNotice]=useState("");
  const [manualUrl,setManualUrl]=useState("");

  async function share() {
    const url=new URL(path,window.location.origin).href;
    setNotice("");
    setManualUrl("");
    if (navigator.share) {
      try {
        await navigator.share({title:"Catálogo · "+storeName,url});
        return;
      } catch (error) {
        if (error instanceof Error && error.name==="AbortError") return;
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      setNotice("Enlace del catálogo copiado.");
    } catch {
      setManualUrl(url);
    }
  }

  return <div className="mt-3">
    <button type="button" onClick={share} className="text-teal-800 underline">Compartir catálogo</button>
    <p role="status" className="text-sm">{notice}</p>
    {manualUrl && <label className="mt-2 block">Copia el enlace del catálogo
      <input readOnly value={manualUrl} onFocus={event=>event.target.select()}
        className="block w-full rounded border p-2"/>
    </label>}
  </div>;
}
