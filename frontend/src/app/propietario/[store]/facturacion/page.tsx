"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import FiscalIssuer from "../FiscalIssuer";

export default function Billing() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Facturación" description="Datos de tu empresa para emitir boletas y facturas electrónicas ante SUNAT."/>
    <FiscalIssuer store={store} send={send}/>
  </>;
}
