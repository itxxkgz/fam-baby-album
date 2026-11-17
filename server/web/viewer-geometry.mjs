// Image dimensions are the fitted size. Offsets are relative to the viewport centre.
const DEFAULT_MIN_SCALE = 1;
const DEFAULT_MAX_SCALE = 8;
const LINE_PIXELS = 16;
const FALLBACK_PAGE_PIXELS = 800;
const WHEEL_SENSITIVITY = 0.002;

const positive = value => Number.isFinite(value) && value > 0;
const finiteOr = (value, fallback) => Number.isFinite(value) ? value : fallback;

function scaleLimits(minScale, maxScale) {
  const first = positive(minScale) ? minScale : DEFAULT_MIN_SCALE;
  const second = positive(maxScale) ? maxScale : DEFAULT_MAX_SCALE;
  return { min: Math.min(first, second), max: Math.max(first, second) };
}

function limitScale(value, min = DEFAULT_MIN_SCALE, max = DEFAULT_MAX_SCALE) {
  return Math.min(max, Math.max(min, finiteOr(value, min)));
}

function limitOffset(offset, imageSize, viewportSize, scale) {
  if (!positive(imageSize) || !positive(viewportSize)) return 0;
  const limit = Math.max(0, (imageSize * scale - viewportSize) / 2);
  if (limit === 0) return 0;
  return Math.min(limit, Math.max(-limit, finiteOr(offset, 0)));
}

export function fitImage(naturalWidth, naturalHeight, viewportWidth, viewportHeight) {
  if (![naturalWidth, naturalHeight, viewportWidth, viewportHeight].every(positive)) return { width: 0, height: 0 };
  const ratio = Math.min(1, viewportWidth / naturalWidth, viewportHeight / naturalHeight);
  return {
    width: Math.min(naturalWidth, viewportWidth, naturalWidth * ratio),
    height: Math.min(naturalHeight, viewportHeight, naturalHeight * ratio),
  };
}

export function clampTransform(transform, imageWidth, imageHeight, viewportWidth, viewportHeight, minScale = DEFAULT_MIN_SCALE, maxScale = DEFAULT_MAX_SCALE) {
  const limits = scaleLimits(minScale, maxScale);
  const scale = limitScale(transform?.scale, limits.min, limits.max);
  return {
    scale,
    x: limitOffset(transform?.x, imageWidth, viewportWidth, scale),
    y: limitOffset(transform?.y, imageHeight, viewportHeight, scale),
  };
}

export function zoomAt(transform, nextScale, anchorX, anchorY, imageWidth, imageHeight, viewportWidth, viewportHeight) {
  const current = clampTransform(transform, imageWidth, imageHeight, viewportWidth, viewportHeight);
  const scale = limitScale(nextScale);
  if (scale === current.scale) return current;
  const ratio = scale / current.scale;
  // A point under the cursor has image coordinate (anchor - offset) / scale.
  const x = current.x * ratio + finiteOr(anchorX, 0) * (1 - ratio);
  const y = current.y * ratio + finiteOr(anchorY, 0) * (1 - ratio);
  return clampTransform({ scale, x, y }, imageWidth, imageHeight, viewportWidth, viewportHeight);
}

export function wheelScale(scale, deltaY, deltaMode, viewportHeight) {
  const current = limitScale(scale);
  if (!Number.isFinite(deltaY) || deltaY === 0) return current;
  const unit = deltaMode === 1 ? LINE_PIXELS : deltaMode === 2 ? (positive(viewportHeight) ? viewportHeight : FALLBACK_PAGE_PIXELS) : 1;
  // Work in logarithms so even a very large wheel delta cannot overflow the scale.
  const next = Math.log(current) - deltaY * unit * WHEEL_SENSITIVITY;
  if (next <= 0) return DEFAULT_MIN_SCALE;
  if (next >= Math.log(DEFAULT_MAX_SCALE)) return DEFAULT_MAX_SCALE;
  return Math.exp(next);
}
