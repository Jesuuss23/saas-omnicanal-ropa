"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import MercadoPagoAccount from "../MercadoPagoAccount";

export default function Payments() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Cobros" description="Recibe pagos de tus pedidos web directo en tu cuenta de Mercado Pago."/>
    <MercadoPagoAccount store={store} send={send}/>
  </>;
}
