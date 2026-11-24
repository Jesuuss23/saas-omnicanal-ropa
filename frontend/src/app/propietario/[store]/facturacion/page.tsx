"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import FiscalIssuer from "../FiscalIssuer";
import ProductionSetup from "./ProductionSetup";

export default function Billing() {
  const {store, send, profile} = useStore();
  return <>
    <PageHeader title="Facturación" description="Datos de tu empresa para emitir boletas y facturas electrónicas ante SUNAT."/>
    <div className="grid gap-6">
      <FiscalIssuer store={store} send={send}/>
      {profile.role === "OWNER" && <ProductionSetup store={store}/>}
    </div>
  </>;
}
