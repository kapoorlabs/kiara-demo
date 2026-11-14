const assert = require('node:assert/strict');
const test = require('node:test');
const { fixed } = require('./report-format.cjs');

test('matches Java decimal rounding at report boundaries and scientific notation', () => {
  for (const [value, digits, expected] of [
    [87.207 / 2.4, 4, '36.3363'],
    [1.005, 2, '1.01'],
    [9.9995, 3, '10.000'],
    [1e-7, 4, '0.0000'],
    [1e21, 2, '1000000000000000000000.00'],
    [0, 3, '0.000'],
    [2.5, 0, '3'],
  ]) assert.equal(fixed(value, digits), expected);
});

test('rejects invalid values and precision', () => {
  for (const value of [-1, Infinity, NaN]) assert.throws(() => fixed(value, 3), RangeError);
  for (const digits of [-1, 1.5]) assert.throws(() => fixed(1, digits), RangeError);
});
