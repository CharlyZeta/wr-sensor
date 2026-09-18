import { useMemo } from 'react'
import type { Lectura } from '../../api/tipos'
import { estilo, normalizarSeveridad } from '../../dominio/severidad'
import { fechaHora, numero } from '../../dominio/formato'

/**
 * Serie temporal de las últimas horas (FEAT-0014 Main Flow 3, BR-005/AF-05).
 *
 * SVG propio (sin librería de charts): la ventana y la resolución son conocidas y así el gráfico es
 * chico, testeable y accesible. **Decimación**: si hay más puntos que píxeles útiles se dibuja un
 * punto por columna (el dato completo sigue disponible en la tabla, que es la fuente de verdad).
 *
 * Las lecturas con `calidad=ERROR_SENSOR` **no** se grafican como valores normales: se dibujan como
 * marcas de error (AF-05). Valores no finitos se descartan.
 */

const ANCHO = 720
const ALTO = 220
const MARGEN = { arriba: 12, derecha: 12, abajo: 26, izquierda: 44 }

export type Punto = { ts: number; valor: number; severidad: string | null; calidad: string | null }

export function puntosGraficables(lecturas: Lectura[]): { validos: Punto[]; invalidos: Punto[] } {
  const validos: Punto[] = []
  const invalidos: Punto[] = []
  for (const l of lecturas) {
    const ts = Date.parse(l.timestamp)
    if (Number.isNaN(ts)) {
      continue
    }
    const punto: Punto = {
      ts,
      valor: typeof l.valor === 'number' ? l.valor : Number.NaN,
      severidad: l.severidad,
      calidad: l.calidad,
    }
    if (l.calidad === 'ERROR_SENSOR') {
      invalidos.push(punto)
    } else if (Number.isFinite(punto.valor)) {
      validos.push(punto)
    }
  }
  return { validos, invalidos }
}

/** Decima manteniendo el mínimo y el máximo de cada columna (no pierde picos). */
function decimar(puntos: Punto[], columnas: number): Punto[] {
  if (puntos.length <= columnas) {
    return puntos
  }
  const desde = puntos[0]?.ts ?? 0
  const hasta = puntos[puntos.length - 1]?.ts ?? 1
  const ancho = Math.max(1, (hasta - desde) / columnas)
  const cubos = new Map<number, Punto[]>()
  for (const p of puntos) {
    const cubo = Math.min(columnas - 1, Math.floor((p.ts - desde) / ancho))
    const lista = cubos.get(cubo)
    if (lista === undefined) {
      cubos.set(cubo, [p])
    } else {
      lista.push(p)
    }
  }
  const salida: Punto[] = []
  for (const cubo of [...cubos.keys()].sort((a, b) => a - b)) {
    const lista = cubos.get(cubo) ?? []
    let min = lista[0]
    let max = lista[0]
    for (const p of lista) {
      if (min === undefined || p.valor < min.valor) min = p
      if (max === undefined || p.valor > max.valor) max = p
    }
    if (min !== undefined) salida.push(min)
    if (max !== undefined && max !== min) salida.push(max)
  }
  return salida.sort((a, b) => a.ts - b.ts)
}

export function SerieTemporal({
  lecturas,
  unidad,
  truncado,
}: {
  lecturas: Lectura[]
  unidad: string | null
  truncado: boolean
}): React.JSX.Element {
  const { validos, invalidos } = useMemo(() => puntosGraficables(lecturas), [lecturas])

  const dibujo = useMemo(() => {
    if (validos.length === 0) {
      return null
    }
    const desde = validos[0]?.ts ?? 0
    const hasta = validos[validos.length - 1]?.ts ?? desde + 1
    const valores = validos.map((p) => p.valor)
    const min = Math.min(...valores)
    const max = Math.max(...valores)
    const rango = max - min === 0 ? 1 : max - min
    const anchoUtil = ANCHO - MARGEN.izquierda - MARGEN.derecha
    const altoUtil = ALTO - MARGEN.arriba - MARGEN.abajo
    const x = (ts: number): number =>
      MARGEN.izquierda + (hasta === desde ? anchoUtil / 2 : ((ts - desde) / (hasta - desde)) * anchoUtil)
    const y = (valor: number): number =>
      MARGEN.arriba + altoUtil - ((valor - min) / rango) * altoUtil

    const puntos = decimar(validos, anchoUtil)
    const linea = puntos.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.ts).toFixed(1)},${y(p.valor).toFixed(1)}`).join(' ')
    const area = `${linea} L${x(puntos[puntos.length - 1]?.ts ?? hasta).toFixed(1)},${(MARGEN.arriba + altoUtil).toFixed(1)} L${x(puntos[0]?.ts ?? desde).toFixed(1)},${(MARGEN.arriba + altoUtil).toFixed(1)} Z`

    return { desde, hasta, min, max, x, y, linea, area, invalidos: invalidos.map((p) => ({ ...p, x: x(p.ts) })) }
  }, [validos, invalidos])

  if (dibujo === null) {
    return (
      <div className="estado estado-vacio" role="status">
        <p className="estado-titulo">Sin lecturas válidas en la ventana</p>
        <p className="estado-detalle">
          El sensor no reportó datos utilizables en el período pedido. La tabla muestra lo que haya
          llegado.
        </p>
      </div>
    )
  }

  const etiquetaY = (v: number): string => numero(v)

  return (
    <figure className="serie">
      <svg
        viewBox={`0 0 ${ANCHO} ${ALTO}`}
        role="img"
        aria-label={`Serie de ${validos.length} lecturas entre ${fechaHora(new Date(dibujo.desde).toISOString())} y ${fechaHora(new Date(dibujo.hasta).toISOString())}`}
        className="serie-svg"
      >
        <line
          x1={MARGEN.izquierda}
          y1={MARGEN.arriba + (ALTO - MARGEN.arriba - MARGEN.abajo)}
          x2={ANCHO - MARGEN.derecha}
          y2={MARGEN.arriba + (ALTO - MARGEN.arriba - MARGEN.abajo)}
          className="serie-eje"
        />
        <text x={4} y={MARGEN.arriba + 4} className="serie-texto">
          {etiquetaY(dibujo.max)} {unidad ?? ''}
        </text>
        <text x={4} y={ALTO - MARGEN.abajo} className="serie-texto">
          {etiquetaY(dibujo.min)}
        </text>
        <path d={dibujo.area} className="serie-area" />
        <path d={dibujo.linea} className="serie-linea" />
        {dibujo.invalidos.map((p) => (
          <line
            key={`inv-${p.ts}`}
            x1={p.x}
            y1={MARGEN.arriba}
            x2={p.x}
            y2={ALTO - MARGEN.abajo}
            className={`serie-invalido ${estilo('INVALIDO').clase}`}
          >
            <title>Lectura inválida (calidad ERROR_SENSOR) en {fechaHora(new Date(p.ts).toISOString())}</title>
          </line>
        ))}
        <text x={MARGEN.izquierda} y={ALTO - 6} className="serie-texto">
          {fechaHora(new Date(dibujo.desde).toISOString())}
        </text>
        <text x={ANCHO - MARGEN.derecha} y={ALTO - 6} textAnchor="end" className="serie-texto">
          {fechaHora(new Date(dibujo.hasta).toISOString())}
        </text>
      </svg>
      <figcaption className="estado-detalle">
        {validos.length} lecturas válidas
        {invalidos.length > 0 ? ` · ${invalidos.length} inválidas (marcadas en rojo violeta)` : ''}
        {truncado ? ' · serie truncada por el tope configurado' : ''}. Severidad del último dato:{' '}
        {estilo(normalizarSeveridad(validos[validos.length - 1]?.severidad)).etiqueta}.
      </figcaption>
    </figure>
  )
}