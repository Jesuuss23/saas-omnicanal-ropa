"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {useStore} from "../_panel/StoreContext";
import WebOrders from "../WebOrders";
import PickupSettings from "../PickupSettings";

export default function Orders() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Pedidos web" description="Pedidos de tu tienda en línea, con su pago, cotización de delivery y entrega."/>
    <Tabs defaultValue="pedidos">
      <TabsList><TabsTrigger value="pedidos">Pedidos</TabsTrigger><TabsTrigger value="entrega">Recojo y delivery</TabsTrigger></TabsList>
      <TabsContent value="pedidos"><WebOrders store={store} send={send}/></TabsContent>
      <TabsContent value="entrega"><PickupSettings store={store} send={send}/></TabsContent>
    </Tabs>
  </>;
}
