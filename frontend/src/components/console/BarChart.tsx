"use client";

import {useState} from "react";

/**
 * Barras verticales de una sola serie (sin leyenda: el título la nombra).
 * Puntas redondeadas de 4px, separación de 2px, rejilla tenue y tooltip al pasar el cursor o enfocar.
 */
export function BarChart({data, label, height = 180}: {data: {label: string; value: number; title?: string}[]; label: string; height?: number}) {
  const [active, setActive] = useState<number | null>(null);
  const max = Math.max(1, ...data.map(d => d.value));
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const width = 640, left = 28, bottom = 22, plotH = height - bottom - 8, slot = (width - left) / Math.max(data.length, 1);
  const barW = Math.max(4, Math.min(36, slot - 2));
  return <figure className="relative grid gap-2">
    <svg viewBox={`0 0 ${width} ${height}`} className="h-auto w-full" role="img" aria-label={label}>
      {ticks.map(t => {
        const y = 8 + plotH - (t / top) * plotH;
        return <g key={t}>
          <line x1={left} x2={width} y1={y} y2={y} className="stroke-border" strokeWidth={1}/>
          <text x={left - 6} y={y + 4} textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">{t}</text>
        </g>;
      })}
      {data.map((d, i) => {
        const h = (d.value / top) * plotH;
        const x = left + i * slot + (slot - barW) / 2;
        const y = 8 + plotH - h;
        return <g key={d.label} onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)}
          onFocus={() => setActive(i)} onBlur={() => setActive(null)} tabIndex={0} aria-label={(d.title ?? d.label) + ": " + d.value}
          className="outline-none">
          <rect x={left + i * slot} y={8} width={slot} height={plotH} fill="transparent"/>
          {h > 0 && <path d={roundedTop(x, y, barW, h, Math.min(4, h, barW / 2))}
            className={active === i ? "fill-brand-700" : "fill-brand-500"}/>}
          {(data.length <= 12 || i % 2 === 0) &&
            <text x={x + barW / 2} y={height - 6} textAnchor="middle" className="fill-muted-foreground text-[11px]">{d.label}</text>}
        </g>;
      })}
    </svg>
    {active !== null && <div role="status" className="pointer-events-none absolute right-0 top-0 rounded-lg border bg-popover px-3 py-2 text-sm shadow-pop">
      <p className="text-muted-foreground">{data[active].title ?? data[active].label}</p>
      <p className="font-semibold tabular-nums">{data[active].value}</p>
    </div>}
  </figure>;
}

function roundedTop(x: number, y: number, w: number, h: number, r: number) {
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function niceTicks(max: number): number[] {
  const step = max <= 4 ? 1 : max <= 10 ? 2 : Math.pow(10, Math.floor(Math.log10(max))) * (max / Math.pow(10, Math.floor(Math.log10(max))) > 5 ? 2 : 1);
  const out: number[] = [];
  for (let v = 0; v < max + step; v += step) out.push(v);
  return out;
}
