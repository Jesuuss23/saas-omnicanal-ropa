"use client";

import {useEffect, useRef} from "react";

type Detector = {detect:(video: HTMLVideoElement)=>Promise<{rawValue:string}[]>};
type DetectorClass = new (opts:{formats:string[]})=>Detector;

export default function QrCamera({onScan,onError}:{onScan:(value:string)=>void;onError:(msg:string)=>void}) {
  const video = useRef<HTMLVideoElement>(null);
  useEffect(()=>{
    let closed = false;
    let stream:MediaStream|undefined;
    let timer:ReturnType<typeof setTimeout>|undefined;
    function stop() {
      closed = true;
      clearTimeout(timer);
      stream?.getTracks().forEach(track=>track.stop());
    }
    async function start() {
      const Detector = (window as Window & {BarcodeDetector?:DetectorClass}).BarcodeDetector;
      if (!Detector || !navigator.mediaDevices?.getUserMedia) {
        onError("La lectura por cámara no está disponible en este navegador. Ingresa el SKU.");
        return;
      }
      try {
        const detector = new Detector({formats:["qr_code"]});
        stream = await navigator.mediaDevices.getUserMedia({audio:false, video:{facingMode:{ideal:"environment"}}});
        if (closed || !video.current) {stop(); return;}
        const element = video.current;
        element.srcObject = stream;
        await element.play();
        async function read() {
          if (closed) return;
          try {
            const results = await detector.detect(element);
            if (closed) return;
            if (results[0]?.rawValue) {
              stop();
              onScan(results[0].rawValue);
            } else timer = setTimeout(read,200);
          } catch {
            if (!closed) {stop(); onError("No se pudo leer la cámara. Puedes ingresar el SKU.");}
          }
        }
        void read();
      } catch {
        if (!closed) {stop(); onError("No se pudo abrir la cámara. Revisa el permiso o ingresa el SKU.");}
      }
    }
    void start();
    return stop;
  },[onScan,onError]);
  return <video ref={video} muted playsInline aria-label="Escáner de etiquetas" className="mt-3 max-h-64 w-full rounded-lg bg-stone-900"/>;
}
