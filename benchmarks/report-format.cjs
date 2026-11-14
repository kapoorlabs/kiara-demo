function fixed(value, digits) {
  if (!Number.isFinite(value) || value < 0 || !Number.isInteger(digits) || digits < 0) {
    throw new RangeError('Expected a finite nonnegative value and nonnegative precision');
  }
  // Java Formatter rounds the shortest decimal representation, unlike JS toFixed's binary rounding.
  const [, integer, fraction = '', exponent = '0'] = /^(\d+)(?:\.(\d*))?(?:e([+-]?\d+))?$/.exec(String(value));
  const coefficient = BigInt(integer + fraction);
  const shift = digits - fraction.length + Number(exponent);
  const divisor = shift < 0 ? 10n ** BigInt(-shift) : 1n;
  const rounded = shift >= 0 ? coefficient * 10n ** BigInt(shift) : (coefficient + divisor / 2n) / divisor;
  const text = String(rounded).padStart(digits + 1, '0');
  return digits ? `${text.slice(0, -digits)}.${text.slice(-digits)}` : text;
}

module.exports = { fixed };
