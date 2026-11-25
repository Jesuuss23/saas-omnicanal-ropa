"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import CulqiAccount from "../CulqiAccount";

export default function Payments() {
  const {store} = useStore();
  return <>
    <PageHeader title="Cobros" description="Activa los cobros en línea: recibe los pagos con tarjeta de tus pedidos web directo en tu cuenta Culqi."/>
    <CulqiAccount store={store}/>
  </>;
}
