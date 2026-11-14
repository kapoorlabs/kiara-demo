const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { fixed } = require('../report-format.cjs');
const root = path.resolve(__dirname, '..', '..');
const raw = JSON.parse(fs.readFileSync(process.argv[2] || path.join(root, 'benchmarks', 'wide-filter-comparison.json')));
const report = fs.readFileSync(process.argv[3] || path.join(root, 'docs', 'wide-filter-comparison.md'), 'utf8');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const lists = new Set(['tags', 'numbers', 'dates', 'timestamps', 'bands', 'windows', 'timewindows']);
assert.deepEqual(raw.widths, [2, 4, 8, 12, 16]);
assert.deepEqual(Object.keys(raw.comparisons).sort(), ['lucene', 'sqlite-generic', 'sqlite-tuned', 'voltdb']);
assert(raw.forks > 0);
assert.deepEqual(raw.versions, { kiara: '2.0.16', lucene: '10.5.2', sqliteJdbc: '3.53.4.0', voltdbCE: '10.1.1' });
const close = (a, b) => assert(Math.abs(a - b) <= Math.max(1e-9, Math.abs(b) * 1e-12), `${a} != ${b}`);
const percentile = (values, p) => [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1];
const median = values => {
  assert(values.length);
  const sorted = [...values].sort((a, b) => a - b), m = Math.floor(values.length / 2);
  return sorted.length % 2 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
};
const query = (trial, name) => {
  const found = trial.observations.filter(o => o.query.scenario === name);
  assert.equal(found.length, 1);
  return found[0].query;
};
const complete = (trials, name) => trials.length > 0 && trials.every(t => query(t, name).comparable);
const deadlineWin = (a, b, name) => a.length === b.length && complete(a, name) &&
  b.every(t => query(t, name).validation.status === 'QUERY_TIMEOUT');
const metric = (trials, name) => median(trials.map(t => query(t, name).p50Us));
const ratio = (a, b, names) => Math.exp(names.reduce((s, n) => s + Math.log(metric(b, n) / metric(a, n)), 0) / names.length);
class JavaRandom {
  constructor(seed) { this.seed = (BigInt(seed) ^ 0x5deece66dn) & ((1n << 48n) - 1n); }
  next(bits) {
    this.seed = (this.seed * 0x5deece66dn + 11n) & ((1n << 48n) - 1n);
    return Number(this.seed >> BigInt(48 - bits));
  }
  nextInt(bound) {
    if ((bound & -bound) === bound) return Math.floor(bound * this.next(31) / 2 ** 31);
    for (;;) {
      const bits = this.next(31), value = bits % bound;
      if (bits - value + bound - 1 < 2 ** 31) return value;
    }
  }
}
const bootstrap = (a, b, names) => {
  const random = new JavaRandom(20261028), values = [];
  for (let i = 0; i < 5000; i++) {
    const aa = Array.from({ length: a.length }, () => a[random.nextInt(a.length)]);
    const bb = Array.from({ length: b.length }, () => b[random.nextInt(b.length)]);
    values.push(ratio(aa, bb, names));
  }
  return [percentile(values, .025), percentile(values, .975)];
};
const clocks = (durations, pairs, min, max) => {
  assert(durations.length >= min && durations.length <= max);
  assert.equal(durations.length, pairs.length);
  assert(durations.every(ns => Number.isSafeInteger(ns) && ns > 0));
  assert(pairs.every(ns => Number.isSafeInteger(ns) && ns >= 0));
};
const audit = choice => {
  assert(choice);
  const selected = choice.alternatives.filter(a => a.candidate.name === choice.selected);
  assert.equal(selected.length, 1);
  return selected[0];
};
let jarHash, samples = 0, timeouts = 0, entries = 0, trialCount = 0;
const runtime = {};
for (const [dataset, metadata] of Object.entries(raw.datasets)) {
  assert.match(dataset, /^wide-[1-9][0-9]*$/);
  assert.equal(metadata.rows, Number(dataset.split('-')[1]));
  assert.match(metadata.dataSha256, /^[0-9a-f]{64}$/);
  assert.equal(metadata.scenarios.length, 39);
  assert.equal(new Set(metadata.scenarios.map(s => s.name)).size, 39);
  for (const s of metadata.scenarios) {
    const match = /^(scalar|either|all)-w(2|4|8|12|16)-s(1|10|100)$/.exec(s.name);
    assert(match);
    const [, family, width, target] = match;
    assert.equal(s.attributes, Number(width));
    assert.equal(s.conditions.length, Number(width));
    assert.equal(new Set(s.conditions.map(c => c.columnName.toLowerCase())).size, Number(width));
    assert(s.expectedMatches > 0 && s.expectedMatches <= metadata.rows);
    close(s.actualSelectivity, s.expectedMatches / metadata.rows);
    const memberships = s.conditions.filter(c => lists.has(c.columnName.toLowerCase()));
    assert(family === 'scalar' ? memberships.length === 0 && Number(width) <= 8 : memberships.length > 0);
    for (const c of memberships) {
      assert.equal(c.operator, family === 'all' ? 'CONTAINS_ALL' : 'CONTAINS_EITHER');
      assert.equal(new Set(c.value).size, 2);
    }
    const accept = .95 ** (Number(width) - 1 - memberships.length) * (family === 'all' ? .90 : .95) ** memberships.length;
    const selector = s.conditions.find(c => c.columnName.toLowerCase() === 'quantity');
    assert.equal(selector.operator, 'LESS_THAN');
    assert.equal(Number(selector.value[0]), Math.floor(Number(target) / 1000 / accept * 1000000));
  }
}
for (const [comparison, data] of Object.entries(raw.comparisons)) {
  const opponent = comparison.startsWith('sqlite') ? 'sqlite' : comparison;
  assert.equal(data.trials.length, Object.keys(raw.datasets).length * raw.forks * 2);
  const unique = new Set();
  for (const t of data.trials) {
    trialCount++;
    const metadata = raw.datasets[t.dataset];
    assert(metadata);
    assert(['kiara', opponent].includes(t.engine));
    assert.equal(t.phase, 'measure');
    assert(t.fork >= 1 && t.fork <= raw.forks);
    const key = `${t.dataset}/${t.engine}/${t.fork}`;
    assert(!unique.has(key)); unique.add(key);
    assert.equal(t.dataSha256, metadata.dataSha256);
    jarHash ??= t.environment.benchmarkJarSha256;
    assert.equal(t.environment.benchmarkJarSha256, jarHash);
    assert.match(t.environment.os, /^Linux /);
    assert.equal(t.environment.activeProcessors, 4);
    assert.equal(t.environment.kiara, '2.0.16');
    for (const flag of ['-Xms1g', '-Xmx4g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=4', '-Duser.timezone=UTC']) {
      assert(t.environment.jvmArguments.includes(flag));
    }
    for (const field of ['java', 'vm', 'os', 'architecture', 'jvmArguments']) {
      runtime[field] ??= t.environment[field];
      assert.deepEqual(t.environment[field], runtime[field]);
    }
    assert.equal(t.observations.length, metadata.scenarios.length);
    const preparation = comparison === 'lucene' ? null : data.tuning.find(p => p.dataset === t.dataset);
    if (preparation) {
      assert.equal(preparation.dataSha256, metadata.dataSha256);
      assert.equal(preparation.environment.benchmarkJarSha256, jarHash);
      assert.equal(Object.keys(preparation.choices).length, metadata.scenarios.length);
      for (const choice of Object.values(preparation.choices)) {
        const winner = audit(choice);
        if (comparison === 'sqlite-generic') {
          assert.equal(choice.selected, 'generic-original-sql');
          assert.equal(choice.alternatives.length, 1);
        } else {
          assert.equal(winner.failure, null);
          assert.equal(winner.validation.status, 'PASS');
          for (const candidate of choice.alternatives) {
            if (candidate.failure) {
              assert.equal(candidate.validation, null);
              assert.deepEqual(candidate.samplesNs, []);
            } else {
              clocks(candidate.samplesNs, candidate.timerPairsNs, 21, 21);
              assert(percentile(winner.samplesNs, .5) <= percentile(candidate.samplesNs, .5));
            }
          }
        }
      }
    }
    if (t.engine === 'sqlite') {
      const tuned = comparison === 'sqlite-tuned';
      assert.equal(t.evidence.sqliteVersion, '3.53.4');
      assert.equal(t.evidence.analyzed, tuned);
      assert.equal(Object.keys(t.evidence.indexes).length, tuned ? 39 : 26);
      assert.equal(Boolean(t.evidence.indexes.wide_covering), tuned);
      assert.equal(t.evidence.statistics.length > 0, tuned);
      assert.equal(t.evidence.databases.find(db => db.name === 'main').file, '');
      for (const field of ['schema', 'indexes', 'statistics', 'compileOptions', 'sqliteSourceId']) {
        assert.deepEqual(t.evidence[field], preparation.evidence[field]);
      }
    }
    if (t.engine === 'voltdb') {
      assert.equal(t.serverEvidence.clientProcedureTimeoutMs, 600000);
      assert(t.serverEvidence.additionalIndexDdl.some(ddl => ddl.includes('_wide ') && ddl.includes('f_quantity_num')));
      assert.equal(t.serverEvidence.verifiedReadOnlySinglePartitionProcedures.length,
        new Set(Object.values(t.choices).map(choice => audit(choice).source)).size);
    }
    for (const s of metadata.scenarios) {
      const m = query(t, s.name), o = t.observations.find(o => o.query.scenario === s.name);
      assert.equal(m.validation.expectedMatches, s.expectedMatches);
      const expired = m.validation.status === 'QUERY_TIMEOUT';
      if (expired) {
        timeouts++;
        assert.equal(comparison, 'sqlite-generic');
        assert.equal(m.comparable, false);
        assert.equal(m.matches, null);
        assert.deepEqual(m.samplesNs, []);
        assert.deepEqual(o.timerPairsNs, []);
        for (const field of ['p50Us', 'p95Us', 'meanUs']) assert.equal(m[field], null);
        assert.match(o.notTimedReason, /wall deadline 60000 ms/);
      } else {
        entries++;
        assert.equal(m.validation.status, 'PASS');
        assert.equal(m.comparable, true);
        assert.equal(m.validation.missing, 0);
        assert.equal(m.validation.extra, 0);
        assert.equal(m.matches, s.expectedMatches);
        assert.equal(o.notTimedReason, null);
        clocks(m.samplesNs, o.timerPairsNs, 15, 101);
        samples += m.samplesNs.length;
        close(m.p50Us, percentile(m.samplesNs, .5) / 1000);
        close(m.p95Us, percentile(m.samplesNs, .95) / 1000);
        close(m.meanUs, m.samplesNs.reduce((a, b) => a + b, 0) / m.samplesNs.length / 1000);
      }
      if (t.engine !== 'kiara' && comparison !== 'lucene') {
        const measured = audit(t.choices[s.name]), frozen = audit(preparation.choices[s.name]);
        assert.deepEqual(measured.candidate, frozen.candidate);
        if (comparison === 'voltdb') {
          assert.equal(measured.plan.replace(/[0-9a-f]{32}/gi, '<instance>'), frozen.plan.replace(/[0-9a-f]{32}/gi, '<instance>'));
          assert(measured.source.includes('voltExecuteSQL'));
        } else {
          assert(measured.plan.length > 0 && measured.bytecode.length > 0);
          assert.deepEqual(measured.plan, frozen.plan);
          assert.deepEqual(measured.bytecode, frozen.bytecode);
        }
        assert.equal(measured.failure, expired ? 'QUERY_TIMEOUT' : null);
        assert.deepEqual(measured.samplesNs, m.samplesNs);
        assert.deepEqual(measured.timerPairsNs, o.timerPairsNs);
      }
    }
  }
  for (const [dataset, metadata] of Object.entries(raw.datasets)) {
    const k = data.trials.filter(t => t.dataset === dataset && t.engine === 'kiara');
    const o = data.trials.filter(t => t.dataset === dataset && t.engine === opponent);
    assert.equal(k.length, raw.forks); assert.equal(o.length, raw.forks);
    for (const family of ['scalar', 'either', 'all']) for (const width of raw.widths) {
      const names = metadata.scenarios.filter(s => s.name.startsWith(`${family}-w${width}-`)).map(s => s.name);
      if (!names.length) continue;
      assert.equal(names.length, 3);
      const eligible = names.filter(n => complete(k, n) && complete(o, n));
      const r = eligible.length ? ratio(k, o, eligible) : null;
      const ci = eligible.length ? bootstrap(k, o, eligible) : null;
      const kw = eligible.filter(n => metric(k, n) < metric(o, n)).length;
      const ow = eligible.filter(n => metric(o, n) < metric(k, n)).length;
      const kd = names.filter(n => deadlineWin(k, o, n)).length;
      const od = names.filter(n => deadlineWin(o, k, n)).length;
      const line = `| ${dataset} | ${family} | ${width} | ${eligible.length}/3 | ${r === null ? 'n/a' : fixed(r, 3) + 'x'} | ` +
        `${ci === null ? 'n/a' : fixed(ci[0], 3) + '-' + fixed(ci[1], 3) + 'x'} | ${kw} / ${ow} | ${kd} / ${od} |`;
      const section = report.split(`## Kiara vs ${comparison}\n`)[1]?.split('\n## Kiara vs ')[0];
      assert(section?.includes(line), `Summary mismatch for ${comparison}: ${line}`);
    }
    const section = report.split(`## Kiara vs ${comparison}\n`)[1]?.split('\n## Kiara vs ')[0];
    for (const s of metadata.scenarios) {
      const name = s.name;
      const both = complete(k, name) && complete(o, name);
      const value = both ? metric(o, name) / metric(k, name) : null;
      const outcome = both ? value > 1 ? 'Kiara lower p50' : value < 1 ? 'Opponent lower p50' : 'Tie' :
        deadlineWin(k, o, name) ? 'Kiara deadline win' : deadlineWin(o, k, name) ? 'Opponent deadline win' :
          'Mixed timeout; no unanimous winner';
      const display = trials => {
        const count = trials.filter(t => query(t, name).validation.status === 'QUERY_TIMEOUT').length;
        return count ? `TIMEOUT ${count}/${trials.length}` : fixed(metric(trials, name), 3);
      };
      const line = `| ${dataset} | ${name} | ${s.attributes} | ${s.expectedMatches} | ${fixed(s.actualSelectivity * 100, 4)}% | ` +
        `${display(k)} | ${display(o)} | ${value === null ? 'n/a' : fixed(value, 4) + 'x'} | ${outcome} |`;
      assert(section?.includes(line), `Detail mismatch for ${comparison}: ${line}`);
    }
  }
}
assert.match(jarHash, /^[0-9a-f]{64}$/);
const prefix = raw.forks === 1 ? 'wide-smoke' : 'wide';
const executable = path.join(root, 'benchmarks', 'target', prefix, `measured-${jarHash.slice(0, 8)}.jar`);
assert.equal(hash(fs.readFileSync(executable)), jarHash);
console.log(JSON.stringify({ trialCount, entries, samples, timeouts, jarHash }, null, 2));
console.log('Independent wide-filter verification passed: widths, selectivity, correctness evidence, samples, plans, ratios and bootstrap intervals.');
