const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { fixed } = require('../report-format.cjs');
const root = path.resolve(__dirname, '..', '..');
const raw = JSON.parse(fs.readFileSync(process.argv[2] || path.join(root, 'benchmarks', 'voltdb-untuned-comparison.json')));
const report = fs.readFileSync(process.argv[3] || path.join(root, 'docs', 'voltdb-untuned-comparison.md'), 'utf8');
const folder = process.argv[4] || path.join(root, 'benchmarks', 'target', 'voltdb-generic-execution');
const cfg = raw.configuration;
assert.equal(cfg.profile, 'generic');
assert(cfg.forks > 0);
assert.equal(raw.trials.length, cfg.datasets.length * cfg.forks * 2);
assert.equal(raw.tuning.length, cfg.datasets.length);
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const close = (a, b) => assert(Math.abs(a - b) <= Math.max(1e-9, Math.abs(b) * 1e-12), `${a} != ${b}`);
const pct = (a, p) => [...a].sort((x, y) => x - y)[Math.ceil(a.length * p) - 1];
const median = a => {
  assert(a.length);
  const s = [...a].sort((x, y) => x - y), m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
const q = (t, n) => {
  const found = t.observations.filter(o => o.query.scenario === n);
  assert.equal(found.length, 1);
  return found[0].query;
};
const metric = (ts, n) => median(ts.map(t => q(t, n).p50Us));
const ratio = (k, v, ns) => Math.exp(ns.reduce((s, n) => s + Math.log(metric(v, n) / metric(k, n)), 0) / ns.length);
class JavaRandom {
  constructor() { this.seed = (20261028n ^ 0x5deece66dn) & ((1n << 48n) - 1n); }
  next() {
    this.seed = (this.seed * 0x5deece66dn + 11n) & ((1n << 48n) - 1n);
    return Number(this.seed >> 17n);
  }
  nextInt(bound) {
    if ((bound & -bound) === bound) return Math.floor(bound * this.next() / 2 ** 31);
    for (;;) {
      const bits = this.next(), value = bits % bound;
      if (bits - value + bound - 1 < 2 ** 31) return value;
    }
  }
}
const bootstrap = (k, v, ns) => {
  const random = new JavaRandom(), values = [];
  for (let i = 0; i < 5000; i++) {
    values.push(ratio(Array.from({ length: k.length }, () => k[random.nextInt(k.length)]),
      Array.from({ length: v.length }, () => v[random.nextInt(v.length)]), ns));
  }
  return [pct(values, .025), pct(values, .975)];
};
const normalize = plan => plan.replace(/[0-9a-f]{32}/gi, '<instance>');
const choices = (t, preflight) => {
  assert.deepEqual(t.serverEvidence.additionalIndexDdl, []);
  assert.equal(t.serverEvidence.version, '10.1.1');
  const indexTable = t.serverEvidence.indexes[0];
  const col = indexTable.schema.findIndex(c => c.name === 'INDEX_NAME');
  assert(col >= 0);
  const indexes = [...new Set(indexTable.data.map(row => row[col].toLowerCase()))];
  assert.equal(indexes.length, 27);
  assert.equal(indexes.filter(n => n.startsWith('kiara_scalar_')).length, 17);
  assert.equal(indexes.filter(n => n.startsWith('kiara_bounds_')).length, 4);
  assert(indexes.every(n => !/_composite_|_upper$|_wide$/.test(n)));
  for (const kind of ['owner', 'text', 'number', 'range']) assert(indexes.some(n => n.startsWith(`kiara_${kind}_`)));
  for (const [name, choice] of Object.entries(t.choices)) {
    assert.equal(choice.selected, 'generic-fixed');
    assert.equal(choice.alternatives.length, 1);
    const a = choice.alternatives[0];
    assert.equal(a.candidate.name, 'generic-fixed');
    assert.equal(a.candidate.joinOrder, '');
    assert(a.plan.length && a.source.includes('voltExecuteSQL(true)'));
    if (preflight) {
      assert.deepEqual(a.samplesNs, []);
      assert.deepEqual(a.timerPairsNs, []);
    } else {
      const original = raw.tuning.find(x => x.dataset === t.dataset).choices[name].alternatives[0];
      assert.equal(normalize(a.plan), normalize(original.plan));
    }
  }
  assert.equal(t.serverEvidence.verifiedReadOnlySinglePartitionProcedures.length,
    new Set(Object.values(t.choices).map(c => c.alternatives[0].source)).size);
};
let jarHash, entries = 0, timeouts = 0;
for (const dataset of cfg.datasets) {
  const preflight = raw.tuning.filter(t => t.dataset === dataset);
  assert.equal(preflight.length, 1);
  assert.equal(preflight[0].phase, 'prepare');
  choices(preflight[0], true);
  const trials = raw.trials.filter(t => t.dataset === dataset);
  const k = trials.filter(t => t.engine === 'kiara'), v = trials.filter(t => t.engine === 'voltdb');
  assert.equal(k.length, cfg.forks); assert.equal(v.length, cfg.forks);
  const names = k[0].observations.map(o => o.query.scenario);
  assert.equal(new Set(names).size, names.length);
  for (const t of [...preflight, ...trials]) {
    assert.equal(t.environment.voltdbProfile, 'generic');
    assert.equal(t.environment.kiara, '2.0.16');
    assert.equal(t.dataSha256, preflight[0].dataSha256);
    const h = t.environment.benchmarkJarSha256;
    assert(/^[a-f0-9]{64}$/.test(h));
    if (!jarHash) jarHash = h;
    assert.equal(h, jarHash);
  }
  for (const ts of [k, v]) assert.deepEqual(ts.map(t => t.fork).sort((a, b) => a - b), Array.from({ length: cfg.forks }, (_, i) => i + 1));
  for (const t of trials) {
    assert.equal(t.phase, 'measure');
    assert.deepEqual(t.observations.map(o => o.query.scenario).sort(), [...names].sort());
    if (t.engine === 'voltdb') choices(t, false);
    for (const o of t.observations) {
      entries++;
      const m = o.query;
      assert(['PASS', 'UNSUPPORTED', 'KNOWN_DECIMAL_TRUNCATION', 'QUERY_TIMEOUT'].includes(m.validation.status));
      if (m.validation.status === 'QUERY_TIMEOUT') {
        assert.equal(t.engine, 'voltdb'); timeouts++;
        for (const key of ['matches', 'p50Us', 'p95Us', 'meanUs']) assert.equal(m[key], null);
        assert.equal(m.comparable, false);
        assert.deepEqual(m.samplesNs, []); assert.deepEqual(o.timerPairsNs, []);
        assert(o.notTimedReason.includes('Native VoltDB'));
      } else if (m.samplesNs.length) {
        assert(m.samplesNs.length >= 15 && m.samplesNs.length <= 101);
        assert.equal(m.samplesNs.length, o.timerPairsNs.length);
        assert(m.samplesNs.every(n => Number.isSafeInteger(n) && n > 0));
        assert(o.timerPairsNs.every(n => Number.isSafeInteger(n) && n >= 0));
        close(m.p50Us, pct(m.samplesNs, .5) / 1000);
        close(m.p95Us, pct(m.samplesNs, .95) / 1000);
        close(m.meanUs, m.samplesNs.reduce((a, b) => a + b, 0) / m.samplesNs.length / 1000);
        assert.equal(o.notTimedReason, null);
      } else assert(o.notTimedReason);
      if (m.comparable) assert.equal(m.validation.status, 'PASS');
      assert.equal(m.validation.expectedMatches, q(k[0], m.scenario).validation.expectedMatches);
    }
  }
  const eligible = names.filter(n => trials.every(t => q(t, n).comparable));
  const kw = eligible.filter(n => metric(k, n) < metric(v, n)).length;
  const vw = eligible.filter(n => metric(v, n) < metric(k, n)).length;
  const timeoutCount = v.flatMap(t => t.observations).filter(o => o.query.validation.status === 'QUERY_TIMEOUT').length;
  const ci = eligible.length ? bootstrap(k, v, eligible) : null;
  const r = eligible.length ? `${fixed(ratio(k, v, eligible), 3)}x` : 'n/a';
  const interval = ci ? `${fixed(ci[0], 3)}-${fixed(ci[1], 3)}x` : 'n/a';
  assert(report.includes(`| ${dataset} | ${cfg.forks} | ${eligible.length} | ${r} | ${interval} | ${kw} / ${vw} | ${timeoutCount} |`));
  for (const name of names) assert(report.includes(`| ${name} |`));
}
assert.equal(hash(fs.readFileSync(path.join(folder, `measured-${jarHash.slice(0, 8)}.jar`))), jarHash);
assert(report.includes('no symmetric deadline-win'));
console.log(JSON.stringify({ trials: raw.trials.length, entries, timeouts, jarHash }));
console.log('Independent generic VoltDB verification passed: samples, profiles, indexes, plans, ratios and bootstrap intervals.');
