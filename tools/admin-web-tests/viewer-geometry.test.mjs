import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fitImage, clampTransform, zoomAt, wheelScale } from '../../server/web/viewer-geometry.mjs';

function near(actual, expected, tolerance = 1e-9) {
  assert.ok(Math.abs(actual - expected) <= tolerance * Math.max(1, Math.abs(expected)), '实际值 ' + actual + ' 应接近 ' + expected);
}
function finite(transform) {
  for (const value of Object.values(transform)) assert.equal(Number.isFinite(value), true);
}
function noAvoidableBlank(transform, width, height, viewportWidth, viewportHeight) {
  for (const [offset, size, viewport] of [[transform.x, width, viewportWidth], [transform.y, height, viewportHeight]]) {
    if (size * transform.scale <= viewport) assert.equal(offset, 0);
    else {
      assert.ok(offset - size * transform.scale / 2 <= -viewport / 2 + 1e-8);
      assert.ok(offset + size * transform.scale / 2 >= viewport / 2 - 1e-8);
    }
  }
}

test('横图、竖图和全景图等比适应视口，小原图不放大', () => {
  const examples = [
    [4000, 3000, 1000, 600, 800, 600],
    [3000, 4000, 1000, 600, 450, 600],
    [8000, 1000, 1200, 900, 1200, 150],
    [1000, 8000, 1200, 900, 112.5, 900],
    [320, 200, 1200, 900, 320, 200],
    [1000, 600, 1000, 600, 1000, 600],
  ];
  for (const [nw, nh, vw, vh, width, height] of examples) {
    const fitted = fitImage(nw, nh, vw, vh);
    near(fitted.width, width); near(fitted.height, height);
    near(fitted.width / fitted.height, nw / nh);
    assert.ok(fitted.width <= nw && fitted.height <= nh);
    assert.ok(fitted.width <= vw && fitted.height <= vh);
  }
});

test('尚未加载、隐藏视口和异常尺寸不会产生无效坐标', () => {
  for (const invalid of [undefined, null, 0, -1, NaN, Infinity, -Infinity, '100']) {
    assert.deepEqual(fitImage(invalid, 400, 800, 600), { width: 0, height: 0 });
    assert.deepEqual(fitImage(400, invalid, 800, 600), { width: 0, height: 0 });
    assert.deepEqual(fitImage(400, 400, invalid, 600), { width: 0, height: 0 });
    assert.deepEqual(fitImage(400, 400, 800, invalid), { width: 0, height: 0 });
    const result = clampTransform({ scale: invalid, x: invalid, y: invalid }, 800, 600, 800, 600);
    finite(result); assert.equal(result.scale, 1); assert.equal(result.x, 0); assert.equal(result.y, 0);
    finite(zoomAt(null, invalid, invalid, invalid, 0, 0, 0, 0));
  }
  finite(fitImage(Number.MAX_VALUE, Number.MAX_VALUE, 1200, 900));
  finite(clampTransform({ scale: 8, x: Number.MAX_VALUE, y: -Number.MAX_VALUE }, Number.MAX_VALUE, Number.MAX_VALUE, 800, 600));
});

test('拖动边界按每个轴限制，小于视口的方向始终居中', () => {
  assert.deepEqual(clampTransform({ scale: 2, x: 900, y: -900 }, 800, 300, 800, 600), { scale: 2, x: 400, y: 0 });
  assert.deepEqual(clampTransform({ scale: 3, x: -900, y: 900 }, 200, 600, 800, 600), { scale: 3, x: 0, y: 600 });
  assert.deepEqual(clampTransform({ scale: 1, x: 50, y: 80 }, 200, 100, 800, 600), { scale: 1, x: 0, y: 0 });
  assert.deepEqual(clampTransform({ scale: 2, x: 40, y: -30 }, 800, 600, 800, 600), { scale: 2, x: 40, y: -30 });
  assert.deepEqual(clampTransform({ scale: 2, x: 40, y: -30 }, 800, 600, 0, -10), { scale: 2, x: 0, y: 0 });
});

test('缩放限于1到8倍，自定义上下限反向传入也不会倒置', () => {
  assert.equal(clampTransform({ scale: 0.1 }, 800, 600, 800, 600).scale, 1);
  assert.equal(clampTransform({ scale: 90 }, 800, 600, 800, 600).scale, 8);
  assert.equal(clampTransform({ scale: 5 }, 800, 600, 800, 600, 3, 0.5).scale, 3);
  assert.equal(clampTransform({ scale: 0.1 }, 800, 600, 800, 600, 3, 0.5).scale, 0.5);
  assert.equal(clampTransform({ scale: 2 }, 800, 600, 800, 600, NaN, Infinity).scale, 2);
  assert.deepEqual(zoomAt({ scale: 2, x: 30, y: -20 }, 0, 100, 50, 800, 600, 800, 600), { scale: 1, x: 0, y: 0 });
});

test('围绕不同鼠标锚点缩放，未触边时鼠标指向同一图片位置', () => {
  const start = { scale: 2, x: 40, y: -30 };
  for (const [anchorX, anchorY] of [[0, 0], [100, -50], [-160, 90], [230, 140]]) {
    const beforeX = (anchorX - start.x) / start.scale, beforeY = (anchorY - start.y) / start.scale;
    const next = zoomAt(start, 3.5, anchorX, anchorY, 800, 600, 800, 600);
    near((anchorX - next.x) / next.scale, beforeX);
    near((anchorY - next.y) / next.scale, beforeY);
    noAvoidableBlank(next, 800, 600, 800, 600);
  }
  assert.deepEqual(start, { scale: 2, x: 40, y: -30 }, '不会修改调用方的状态对象');
});

test('连续放大缩小再回到原比例，不累积位置漂移', () => {
  const original = { scale: 2, x: 20, y: -10 }; let state = original;
  for (let i = 0; i < 100; i++) {
    state = zoomAt(state, 5.5, -90, 60, 640, 480, 640, 480);
    state = zoomAt(state, 3.2, -90, 60, 640, 480, 640, 480);
    state = zoomAt(state, 2, -90, 60, 640, 480, 640, 480);
  }
  near(state.scale, original.scale); near(state.x, original.x); near(state.y, original.y);
});

test('边缘锚点和横竖图缩放后优先消除可避免空白', () => {
  for (const [nw, nh, vw, vh] of [[4000, 3000, 1000, 600], [3000, 4000, 1000, 600], [8000, 500, 900, 1200], [100, 100, 800, 600]]) {
    const { width, height } = fitImage(nw, nh, vw, vh);
    for (const nextScale of [1, 1.01, 2, 4, 8, 30, -1]) {
      for (const [anchorX, anchorY] of [[-vw / 2, -vh / 2], [vw / 2, vh / 2], [vw, -vh]]) {
        const start = clampTransform({ scale: 4, x: 99999, y: -99999 }, width, height, vw, vh);
        const next = zoomAt(start, nextScale, anchorX, anchorY, width, height, vw, vh);
        finite(next); noAvoidableBlank(next, width, height, vw, vh);
        assert.ok(next.scale >= 1 && next.scale <= 8);
      }
    }
  }
});

test('滚轮像素、行和页模式换算为同等滚动量', () => {
  near(wheelScale(2, 16, 0, 800), wheelScale(2, 1, 1, 800));
  near(wheelScale(4, 100, 0, 800), wheelScale(4, 0.125, 2, 800));
  near(wheelScale(2, -16, 0, 600), wheelScale(2, -1, 1, 600));
  near(wheelScale(3, 0.1, 2, 1000), wheelScale(3, 100, 0, 1000));
  near(wheelScale(3, 0.1, 2, 0), wheelScale(3, 80, 0, 800));
});

test('滚轮小幅变化平滑可逆，不受事件拆分影响', () => {
  const start = 3;
  const small = wheelScale(start, -0.1, 0, 600), larger = wheelScale(start, -1, 0, 600);
  assert.ok(small > start && larger > small && larger < 3.01);
  const whole = wheelScale(start, -80, 0, 600);
  let split = start;
  for (let i = 0; i < 8; i++) split = wheelScale(split, -10, 0, 600);
  near(split, whole);
  near(wheelScale(whole, 80, 0, 600), start);
  assert.equal(wheelScale(1.7, 0, 0, 600), 1.7);
});

test('滚轮极值和无效事件始终得到有限的1到8倍结果', () => {
  for (const mode of [0, 1, 2, 99]) {
    assert.equal(wheelScale(2, 1e300, mode, 600), 1);
    assert.equal(wheelScale(2, -1e300, mode, 600), 8);
    assert.equal(wheelScale(1, 100, mode, 600), 1);
    assert.equal(wheelScale(8, -100, mode, 600), 8);
    assert.equal(wheelScale(3, NaN, mode, 600), 3);
    assert.equal(wheelScale(3, Infinity, mode, 600), 3);
  }
  for (const scale of [undefined, null, -10, 0, NaN, Infinity, 100]) {
    for (const delta of [undefined, NaN, Infinity, -1, 0, 1]) {
      const next = wheelScale(scale, delta, 2, Number.MAX_VALUE);
      assert.ok(Number.isFinite(next) && next >= 1 && next <= 8);
    }
  }
});
