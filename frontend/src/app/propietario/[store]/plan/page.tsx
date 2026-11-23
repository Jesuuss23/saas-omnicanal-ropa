"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import SubscriptionPlan from "../SubscriptionPlan";

export default function Plan() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Plan" description="Tu suscripción, lo que incluye y cómo cambiar de plan."/>
    <SubscriptionPlan store={store} send={send}/>
  </>;
}
