"use client";

import {useState} from "react";
import {Share2} from "lucide-react";

export default function ShareCatalog({path,title,label,tone="link"}:{path:string;title:string;label:string;tone?:"link"|"inherit"|"subtle"}) {
  const [notice,setNotice]=useState("");
  const [manualUrl,setManualUrl]=useState("");

  async function share() {
    const url=new URL(path,window.location.origin).href;
    setNotice("");
    setManualUrl("");
    if (navigator.share) {
      try {
        await navigator.share({title,url});
        return;
      } catch (error) {
        if (error instanceof Error && error.name==="AbortError") return;
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      setNotice("Enlace copiado.");
    } catch {
      setManualUrl(url);
    }
  }

  const style={link:"text-primary underline",inherit:"inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline",
    subtle:"text-xs text-muted-foreground underline-offset-4 hover:underline"}[tone];
  return <div className={tone==="link" ? "mt-3" : "inline-flex flex-col"}>
    <button type="button" onClick={share} className={style}>{tone==="inherit" && <Share2 aria-hidden className="size-4"/>}{label}</button>
    <p role="status" className="text-sm empty:hidden">{notice}</p>
    {manualUrl && <label className="mt-2 block">Copia el enlace
      <input readOnly value={manualUrl} onFocus={event=>event.target.select()}
        className="block w-full rounded border border-input bg-card p-2 text-foreground"/>
    </label>}
  </div>;
}
