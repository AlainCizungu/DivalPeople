/** The DIP four-square mark. Decorative, so hidden from assistive technology. */
export function BrandMark({ size = 28 }: { size?: number }) {
  const gap = Math.max(2, Math.round(size * 0.09));
  const cell = (size - gap) / 2;
  const radius = Math.round(cell * 0.28);
  const squares = [
    { x: 0, y: 0, fill: "#5bb4ff" },
    { x: cell + gap, y: 0, fill: "#21c7a8" },
    { x: 0, y: cell + gap, fill: "#2a75e8" },
    { x: cell + gap, y: cell + gap, fill: "#5c2d91" },
  ];

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true" focusable="false">
      {squares.map((square) => (
        <rect
          key={`${square.x}-${square.y}`}
          x={square.x}
          y={square.y}
          width={cell}
          height={cell}
          rx={radius}
          fill={square.fill}
        />
      ))}
    </svg>
  );
}
