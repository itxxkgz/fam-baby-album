import { fitImage, clampTransform, zoomAt, wheelScale } from './viewer-geometry.mjs';

// The fitted photograph stays in the document while its transform changes.
export function createPhotoViewport(stage, image, options = {}) {
  let disposed = false, frame = { width: 0, height: 0 }, fitted = { width: 0, height: 0 };
  let transform = { scale: 1, x: 0, y: 0 }, gesture = null;
  const pointers = new Map(), removers = [];
  const listen = (target, name, callback, settings) => {
    target.addEventListener(name, callback, settings);
    removers.push(() => target.removeEventListener(name, callback, settings));
  };
  const ready = () => fitted.width > 0 && fitted.height > 0 && frame.width > 0 && frame.height > 0;
  const constrain = value => clampTransform(value, fitted.width, fitted.height, frame.width, frame.height);
  function publish() {
    if (disposed) return;
    image.style.transform = `translate3d(${transform.x}px, ${transform.y}px, 0) scale(${transform.scale})`;
    stage.classList.toggle('is-zoomed', transform.scale > 1.001);
    options.onChange?.({ ...transform, ready: ready() });
  }
  function release(pointerId) {
    try { if (stage.hasPointerCapture?.(pointerId)) stage.releasePointerCapture(pointerId); } catch {}
  }
  function clearGesture() {
    const ids = [...pointers.keys()];
    pointers.clear(); gesture = null; stage.classList.remove('is-grabbing');
    ids.forEach(release);
  }
  function measure() {
    if (disposed || !stage.isConnected) return;
    const bounds = stage.getBoundingClientRect();
    const next = { width: Math.max(0, bounds.width), height: Math.max(0, bounds.height) };
    const nextFit = fitImage(image.naturalWidth || options.width, image.naturalHeight || options.height, next.width, next.height);
    if (next.width !== frame.width || next.height !== frame.height || nextFit.width !== fitted.width || nextFit.height !== fitted.height) {
      clearGesture();
      transform.x = fitted.width ? transform.x * nextFit.width / fitted.width : 0;
      transform.y = fitted.height ? transform.y * nextFit.height / fitted.height : 0;
      frame = next; fitted = nextFit;
      image.style.width = `${fitted.width}px`; image.style.height = `${fitted.height}px`;
      transform = constrain(transform);
    }
    publish();
  }
  function anchor(clientX, clientY) {
    const bounds = stage.getBoundingClientRect();
    return { x: clientX - bounds.left - frame.width / 2, y: clientY - bounds.top - frame.height / 2 };
  }
  function scaleTo(scale, point = { x: 0, y: 0 }) {
    if (!ready() || disposed) return;
    transform = zoomAt(transform, scale, point.x, point.y, fitted.width, fitted.height, frame.width, frame.height);
    publish();
  }
  function reset() {
    clearGesture(); transform = { scale: 1, x: 0, y: 0 }; publish();
  }
  function wheel(event) {
    if (!ready() || !event.deltaY || disposed) return;
    event.preventDefault(); clearGesture();
    scaleTo(wheelScale(transform.scale, event.deltaY, event.deltaMode, frame.height), anchor(event.clientX, event.clientY));
  }
  function doubleClick(event) {
    if (!ready() || disposed) return;
    event.preventDefault(); clearGesture();
    if (transform.scale > 1.001) reset();
    else scaleTo(2, anchor(event.clientX, event.clientY));
  }
  function startPinch() {
    const [first, second] = [...pointers.values()];
    if (!first || !second) return;
    const middle = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
    gesture = { mode: 'pinch', distance: Math.max(1, Math.hypot(first.x - second.x, first.y - second.y)),
      middle, anchor: anchor(middle.x, middle.y), initial: { ...transform } };
    stage.classList.add('is-grabbing');
  }
  function pointerDown(event) {
    if (disposed || !ready() || (event.button !== 0 && event.pointerType !== 'touch')) return;
    const point = { x: event.clientX, y: event.clientY, type: event.pointerType };
    pointers.set(event.pointerId, point);
    try { stage.setPointerCapture?.(event.pointerId); } catch {}
    if (pointers.size === 1) {
      gesture = { mode: transform.scale > 1.001 ? 'pan' : event.pointerType === 'touch' ? 'swipe' : 'idle',
        startX: point.x, startY: point.y, lastX: point.x, lastY: point.y };
      stage.classList.toggle('is-grabbing', gesture.mode === 'pan');
    } else startPinch();
  }
  function pointerMove(event) {
    if (disposed || !gesture || !pointers.has(event.pointerId)) return;
    const point = pointers.get(event.pointerId); point.x = event.clientX; point.y = event.clientY;
    if (pointers.size > 1 && gesture.mode === 'pinch') {
      event.preventDefault();
      const [first, second] = [...pointers.values()];
      const middle = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
      const distance = Math.hypot(first.x - second.x, first.y - second.y);
      const scale = constrain({ ...gesture.initial, scale: gesture.initial.scale * distance / gesture.distance }).scale;
      const ratio = scale / gesture.initial.scale;
      // Apply the moving midpoint before clamping, so a pinch at an edge does not drift.
      transform = constrain({ scale,
        x: gesture.initial.x * ratio + gesture.anchor.x * (1 - ratio) + middle.x - gesture.middle.x,
        y: gesture.initial.y * ratio + gesture.anchor.y * (1 - ratio) + middle.y - gesture.middle.y,
      }); publish();
    } else if (gesture.mode === 'pan') {
      event.preventDefault();
      transform = constrain({ scale: transform.scale, x: transform.x + point.x - gesture.lastX, y: transform.y + point.y - gesture.lastY });
      gesture.lastX = point.x; gesture.lastY = point.y; publish();
    }
  }
  function pointerUp(event) {
    if (disposed || !pointers.has(event.pointerId)) return;
    const active = gesture, single = pointers.size === 1;
    pointers.delete(event.pointerId); release(event.pointerId);
    if (single) {
      clearGesture();
      if (active?.mode === 'swipe') {
        const dx = event.clientX - active.startX, dy = event.clientY - active.startY;
        if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.3) options.onSwipe?.(dx > 0 ? -1 : 1);
      }
    } else if (pointers.size > 1) startPinch();
    else {
      const [remaining] = pointers.values();
      gesture = { mode: transform.scale > 1.001 ? 'pan' : 'idle', lastX: remaining.x, lastY: remaining.y };
      stage.classList.toggle('is-grabbing', gesture.mode === 'pan');
    }
  }
  listen(stage, 'wheel', wheel, { passive: false });
  listen(stage, 'dblclick', doubleClick);
  listen(stage, 'dragstart', event => event.preventDefault());
  listen(stage, 'pointerdown', pointerDown);
  listen(stage, 'pointermove', pointerMove);
  listen(stage, 'pointerup', pointerUp);
  listen(stage, 'pointercancel', clearGesture);
  listen(stage, 'lostpointercapture', event => { if (pointers.has(event.pointerId)) clearGesture(); });
  if (!window.PointerEvent) {
    const forward = (event, callback) => {
      for (const point of Array.from(event.changedTouches)) callback({ pointerId: point.identifier + 2, pointerType: 'touch',
        button: 0, clientX: point.clientX, clientY: point.clientY, preventDefault: () => event.preventDefault() });
    };
    listen(stage, 'touchstart', event => forward(event, pointerDown), { passive: false });
    listen(stage, 'touchmove', event => forward(event, pointerMove), { passive: false });
    listen(stage, 'touchend', event => forward(event, pointerUp));
    listen(stage, 'touchcancel', clearGesture);
  }
  listen(image, 'load', measure);
  listen(window, 'resize', measure);
  const observer = window.ResizeObserver ? new window.ResizeObserver(measure) : null;
  observer?.observe(stage);
  image.draggable = false;
  measure();
  return {
    zoomIn: () => { clearGesture(); scaleTo(transform.scale * 1.25); },
    zoomOut: () => { clearGesture(); scaleTo(transform.scale / 1.25); },
    reset, refresh: measure,
    dispose: () => {
      if (disposed) return;
      disposed = true; removers.forEach(remove => remove()); observer?.disconnect(); clearGesture();
    },
  };
}
