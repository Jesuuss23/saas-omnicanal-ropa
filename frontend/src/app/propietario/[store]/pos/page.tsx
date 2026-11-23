"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import PointOfSale from "../PointOfSale";

export default function Pos() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Punto de venta" description="Vende en tienda física con el mismo stock del catálogo web. Escanea la etiqueta QR o busca la prenda."/>
    <PointOfSale store={store} send={send}/>
  </>;
}
