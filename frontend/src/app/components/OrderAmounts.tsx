type Props={merchandise:number;method?:string|null;freeDelivery?:boolean;fee?:number|null};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

export function getOrderAmounts({merchandise,method,freeDelivery,fee}:Props) {
  let shipping:number|null=null;
  let label="";
  if (method==="PICKUP") {
    shipping=0;
    label="Recojo en tienda sin cargo";
  } else if (method==="DELIVERY") {
    shipping=freeDelivery ? 0 : fee ?? null;
    if (freeDelivery) label="Delivery gratis";
    else if (shipping!==null) label="Envío "+money.format(shipping);
    else label="Envío pendiente de cotización";
  }
  return {label,total:shipping===null ? null : merchandise+shipping};
}

export default function OrderAmounts(props:Props) {
  const {label,total}=getOrderAmounts(props);
  return <div>
    <p>Prendas · {money.format(props.merchandise)}</p>
    {label && <p>{label}</p>}
    {total!==null && <p className="font-semibold">Total del pedido · {money.format(total)}</p>}
  </div>;
}
