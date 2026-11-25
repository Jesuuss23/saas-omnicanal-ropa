"use client";
import {useCallback, useEffect, useState} from "react";
import {ApiError, getJson} from "./api";

type State<T> = {key: string | null; url: string | null; data: T | null; error: ApiError | null};

/**
 * Carga JSON de una URL y lo vuelve a pedir cuando la URL cambia o se llama a reload().
 * Pasa null como URL para no cargar (por ejemplo, mientras una sección está cerrada).
 *
 * El estado se guarda junto a la clave de la petición, así "cargando" se deriva sin
 * llamar a setState dentro del efecto. Al recargar la misma URL (refreshKey o reload) se siguen
 * mostrando los datos anteriores hasta que llegan los nuevos: la pantalla no parpadea ni se vacía.
 */
export function useApiData<T>(url: string | null, refreshKey: unknown = 0) {
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<State<T>>({key: null, url: null, data: null, error: null});
  const key = url === null ? null : url + "\u0000" + String(refreshKey) + "\u0000" + tick;

  useEffect(() => {
    if (url === null || key === null) return;
    const controller = new AbortController();
    getJson<T>(url, {signal: controller.signal}).then(
      data => { if (!controller.signal.aborted) setState({key, url, data, error: null}); },
      err => {
        if (controller.signal.aborted) return;
        const error = err instanceof ApiError ? err : new ApiError(0, "No se pudo conectar con el servidor.");
        // Si falla una recarga se conservan los datos que ya se mostraban.
        setState(s => ({key, url, data: s.url === url ? s.data : null, error}));
      });
    return () => controller.abort();
  }, [url, key]);

  const current = key !== null && state.key === key;
  const sameUrl = url !== null && state.url === url;
  const reload = useCallback(() => setTick(t => t + 1), []);
  /** Reemplaza los datos ya cargados (por ejemplo, tras guardar) sin volver a pedirlos. */
  const mutate = useCallback((data: T) => setState(s => ({...s, data, error: null})), []);
  return {
    data: current || sameUrl ? state.data : null,
    error: current ? state.error : null,
    loading: key !== null && !current,
    reload,
    mutate,
  };
}
