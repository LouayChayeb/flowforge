"use client"

import { useEffect, useId, useState, type RefObject } from "react"
import { motion } from "motion/react"

import { cn } from "@/lib/utils"

// Magic UI's AnimatedBeam (https://magicui.design/docs/components/animated-beam), adapted for FlowForge:
// the gradient now travels along the beam itself — vertical beams included, in whichever direction the
// beam points — instead of sweeping left to right across the whole container.

export interface AnimatedBeamProps {
  className?: string
  containerRef: RefObject<HTMLElement | null> // Container ref
  fromRef: RefObject<HTMLElement | null>
  toRef: RefObject<HTMLElement | null>
  curvature?: number
  pathColor?: string
  pathWidth?: number
  pathOpacity?: number
  gradientStartColor?: string
  gradientStopColor?: string
  delay?: number
  duration?: number
  repeat?: number
  repeatDelay?: number
  startXOffset?: number
  startYOffset?: number
  endXOffset?: number
  endYOffset?: number
  /** Where on each element the beam starts / ends, as fractions of its box; [0.5, 0.5] is the centre. */
  startAnchor?: [number, number]
  endAnchor?: [number, number]
}

interface Geometry {
  d: string
  width: number
  height: number
  start: [number, number]
  end: [number, number]
}

export const AnimatedBeam: React.FC<AnimatedBeamProps> = ({
  className,
  containerRef,
  fromRef,
  toRef,
  curvature = 0,
  duration = 2.4,
  delay = 0,
  pathColor = "gray",
  pathWidth = 2,
  pathOpacity = 0.2,
  gradientStartColor = "#ffaa40",
  gradientStopColor = "#9c40ff",
  repeat = Infinity,
  repeatDelay = 0,
  startXOffset = 0,
  startYOffset = 0,
  endXOffset = 0,
  endYOffset = 0,
  startAnchor = [0.5, 0.5],
  endAnchor = [0.5, 0.5],
}) => {
  const [geometry, setGeometry] = useState<Geometry | null>(null)

  useEffect(() => {
    const updatePath = () => {
      if (!containerRef.current || !fromRef.current || !toRef.current) return
      const containerRect = containerRef.current.getBoundingClientRect()
      const rectA = fromRef.current.getBoundingClientRect()
      const rectB = toRef.current.getBoundingClientRect()
      const startX = rectA.left - containerRect.left + rectA.width * startAnchor[0] + startXOffset
      const startY = rectA.top - containerRect.top + rectA.height * startAnchor[1] + startYOffset
      const endX = rectB.left - containerRect.left + rectB.width * endAnchor[0] + endXOffset
      const endY = rectB.top - containerRect.top + rectB.height * endAnchor[1] + endYOffset
      const controlY = startY - curvature
      setGeometry({
        d: `M ${startX},${startY} Q ${(startX + endX) / 2},${controlY} ${endX},${endY}`,
        width: containerRect.width,
        height: containerRect.height,
        start: [startX, startY],
        end: [endX, endY],
      })
    }

    const resizeObserver = new ResizeObserver(updatePath)
    if (containerRef.current) resizeObserver.observe(containerRef.current)
    updatePath()
    return () => resizeObserver.disconnect()
  }, [containerRef, fromRef, toRef, curvature, startXOffset, startYOffset, endXOffset, endYOffset, startAnchor[0], startAnchor[1], endAnchor[0], endAnchor[1]])

  if (!geometry) return null

  return (
    <svg
      fill="none"
      width={geometry.width}
      height={geometry.height}
      xmlns="http://www.w3.org/2000/svg"
      className={cn("pointer-events-none absolute top-0 left-0 transform-gpu stroke-2", className)}
      viewBox={`0 0 ${geometry.width} ${geometry.height}`}
    >
      <AnimatedBeamPath d={geometry.d} start={geometry.start} end={geometry.end} pathColor={pathColor} pathWidth={pathWidth}
        pathOpacity={pathOpacity} gradientStartColor={gradientStartColor} gradientStopColor={gradientStopColor}
        delay={delay} duration={duration} repeat={repeat} repeatDelay={repeatDelay} />
    </svg>
  )
}

export interface AnimatedBeamPathProps {
  /** The path, in the coordinates of the <svg> it is drawn in. */
  d: string
  start: [number, number]
  end: [number, number]
  pathColor?: string
  pathWidth?: number
  pathOpacity?: number
  gradientStartColor?: string
  gradientStopColor?: string
  delay?: number
  duration?: number
  repeat?: number
  repeatDelay?: number
  className?: string
}

/**
 * The beam itself, for a path you already know: drawn inside an existing <svg>, in its coordinates, so it can never
 * drift from the line it follows — nothing is measured in the DOM.
 */
export function AnimatedBeamPath({
  d, start, end, pathColor = "gray", pathWidth = 2, pathOpacity = 0.2, gradientStartColor = "#ffaa40",
  gradientStopColor = "#9c40ff", delay = 0, duration = 2.4, repeat = Infinity, repeatDelay = 0, className,
}: AnimatedBeamPathProps) {
  const id = useId()
  // Sweep a short bright band from the start of the beam to its end, along its main axis.
  const [sx, sy] = start
  const [ex, ey] = end
  const vertical = Math.abs(ey - sy) > Math.abs(ex - sx)
  const sweep = (a: number, b: number) => {
    const length = b - a
    return { lead: [a + 0.25 * length, b + 0.25 * length], tail: [a - 0.1 * length, b - 0.1 * length] }
  }
  const animate = vertical
    ? (() => {
        const s = sweep(sy, ey)
        return { x1: [sx, sx], x2: [sx, sx], y1: s.tail, y2: s.lead }
      })()
    : (() => {
        const s = sweep(sx, ex)
        return { x1: s.tail, x2: s.lead, y1: [sy, sy], y2: [sy, sy] }
      })()

  return (
    <g className={cn("pointer-events-none", className)} fill="none">
      <path d={d} stroke={pathColor} strokeWidth={pathWidth} strokeOpacity={pathOpacity} strokeLinecap="round" />
      <path d={d} strokeWidth={pathWidth} stroke={`url(#${id})`} strokeOpacity="1" strokeLinecap="round" />
      <defs>
        <motion.linearGradient
          className="transform-gpu"
          id={id}
          gradientUnits="userSpaceOnUse"
          initial={{ x1: animate.x1[0], x2: animate.x2[0], y1: animate.y1[0], y2: animate.y2[0] }}
          animate={animate}
          transition={{ delay, duration, ease: "linear", repeat, repeatDelay }}
        >
          {/* colours go through style so CSS variables (theme tokens) work too */}
          <stop style={{ stopColor: gradientStartColor, stopOpacity: 0 }} />
          <stop offset="40%" style={{ stopColor: gradientStartColor }} />
          <stop offset="75%" style={{ stopColor: gradientStopColor }} />
          <stop offset="100%" style={{ stopColor: gradientStopColor, stopOpacity: 0 }} />
        </motion.linearGradient>
      </defs>
    </g>
  )
}
