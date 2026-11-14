const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..', '..');
const genericFlag = process.argv[2] === '--generic';
const rawPath = (!genericFlag && process.argv[2]) || path.join(root, 'benchmarks', genericFlag ? 'sqlite-untuned-comparison.json' : 'sqlite-comparison.json');
const reportPath = (!genericFlag && process.argv[3]) || path.join(root, 'docs', genericFlag ? 'sqlite-untuned-comparison.md' : 'sqlite-comparison.md');
const raw = JSON.parse(fs.readFileSync(rawPath, 'utf8'));
const generic = raw.configuration.profile === 'generic';
if (genericFlag) assert(generic);
const report = fs.readFileSync(reportPath, 'utf8');
const old = JSON.parse(fs.readFileSync(path.join(root, 'benchmarks', 'voltdb-query-execution.json'), 'utf8'));
const tunedReference = generic ? JSON.parse(fs.readFileSync(path.join(root, 'benchmarks', 'sqlite-comparison.json'), 'utf8')) : null;
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const expectedJarHash = raw.trials[0].environment.benchmarkJarSha256;
let jarPath = path.join(root, 'benchmarks', 'target', 'kiara-lucene-benchmark-1.0-SNAPSHOT.jar');
if (hash(fs.readFileSync(jarPath)) !== expectedJarHash) {
  assert.match(expectedJarHash, /^[0-9a-f]{64}$/);
  jarPath = path.join(root, 'benchmarks', 'target', generic ? 'sqlite-generic-execution' : 'sqlite-execution',
    `measured-${expectedJarHash.slice(0, 8)}.jar`);
}
const jarHash = hash(fs.readFileSync(jarPath));
assert.equal(jarHash, expectedJarHash);
const datasets = raw.configuration.datasets;
const forks = raw.configuration.forks;
const defaults = ['retail-3788', 'catalog-10000', 'catalog-100000', 'schedule-10000', 'schedule-100000'];
if (!process.argv[2] || genericFlag) {
  assert.deepEqual(datasets, defaults);
  assert.equal(forks, 5);
}
const shape = dataset => dataset.startsWith('retail') ? [11, 7, 5] :
  dataset.startsWith('catalog') ? [33, 25, 21] : [23, 22, 21];
const percentile = (values, p) => [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1];
const median = values => {
  assert(values.length > 0);
  const sorted = [...values].sort((a, b) => a - b), middle = Math.floor(values.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};
const close = (left, right) => assert(Math.abs(left - right) <= Math.max(1e-9, Math.abs(right) * 1e-12), `${left} != ${right}`);
const observation = (trial, name) => {
  const found = trial.observations.filter(o => o.query.scenario === name);
  assert.equal(found.length, 1);
  return found[0];
};
const metric = (trials, name) => median(trials.map(t => observation(t, name).query.p50Us));
const deadlineWin = (completed, timedOut, name) => completed.length > 0 && completed.length === timedOut.length &&
  completed.every(t => observation(t, name).query.comparable) &&
  timedOut.every(t => observation(t, name).query.validation.status === 'QUERY_TIMEOUT');
const ratio = (k, s, names) => Math.exp(names.reduce((sum, name) => sum + Math.log(metric(s, name) / metric(k, name)), 0) / names.length);
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
const bootstrap = (k, s, names) => {
  const random = new JavaRandom(20261028), values = [];
  for (let i = 0; i < 5000; i++) {
    const ks = Array.from({ length: k.length }, () => k[random.nextInt(k.length)]);
    const ss = Array.from({ length: s.length }, () => s[random.nextInt(s.length)]);
    values.push(ratio(ks, ss, names));
  }
  return [percentile(values, .025), percentile(values, .975)];
};
const expectedIndexes = new Map();
for (const field of ['id', 'category', 'status', 'region', 'tier', 'brand', 'quantity', 'price', 'serial',
  'active', 'optional', 'date', 'timestamp', 'band', 'route', 'window', 'timeWindow']) {
  const numeric = ['quantity', 'price', 'serial', 'date', 'timestamp'].includes(field);
  expectedIndexes.set(`scalar_${field}`, [`f_${field}${numeric ? '_num' : ''}`, 'id']);
}
for (const field of ['band', 'route', 'window', 'timeWindow']) {
  expectedIndexes.set(`bounds_${field}`, [`f_${field}_prefix`, `f_${field}_lower`, `f_${field}_upper`, 'id']);
}
expectedIndexes.set('owner', ['id', 'field']);
expectedIndexes.set('text_values', ['field', 'text_value', 'id']);
expectedIndexes.set('numeric_values', ['field', 'number_value', 'id']);
expectedIndexes.set('range_values', ['field', 'prefix', 'lower_bound', 'upper_bound', 'id']);
expectedIndexes.set('range_upper', ['field', 'prefix', 'upper_bound', 'lower_bound', 'id']);
expectedIndexes.set('sqlite_autoindex_records_1', ['id']);
[
  'f_brand,f_status,f_region,id', 'f_active,f_category,id', 'f_tier,f_status,id',
  'f_optional,f_region,id', 'f_brand,f_optional,id', 'f_category,f_brand,id', 'f_brand,f_date_num,id',
  'f_brand,f_route_prefix,f_route_lower,f_route_upper,id',
  'f_category,f_brand,f_band_prefix,f_band_lower,f_band_upper,f_window_lower,f_window_upper,id',
  'f_category,f_band_prefix,f_band_lower,f_band_upper,id',
  'f_category,f_window_prefix,f_window_lower,f_window_upper,id'
].forEach((columns, index) => expectedIndexes.set(`composite_${index}`, columns.split(',')));
if (generic) {
  expectedIndexes.delete('range_upper');
  for (const name of [...expectedIndexes.keys()]) if (name.startsWith('composite_')) expectedIndexes.delete(name);
}
const checkDatabase = trial => {
  const evidence = trial.evidence;
  assert.equal(evidence.sqliteVersion, '3.53.4');
  assert.equal(evidence.jdbcVersion, '3.53.4.0');
  assert.match(evidence.sqliteSourceId, /^[0-9]{4}-[0-9]{2}-[0-9]{2} /);
  assert.equal(evidence.databases.find(db => db.name === 'main').file, '');
  assert.equal(evidence.rows[0].records, Number(trial.dataset.split('-').at(-1)));
  assert(evidence.rows[0].vals >= evidence.rows[0].records);
  assert.deepEqual(Object.keys(evidence.indexes).sort(), [...expectedIndexes.keys()].sort());
  for (const [name, columns] of expectedIndexes) {
    const actual = evidence.indexes[name].filter(row => row.key === 1).sort((a, b) => a.seqno - b.seqno);
    assert.deepEqual(actual.map(row => row.name), columns, name);
    assert(actual.every(row => row.desc === 0 && row.coll === 'BINARY'));
  }
  for (const [pragma, expected] of [['temp_store', 2], ['cache_size', -262144], ['foreign_keys', 1],
    ['automatic_index', 1], ['analysis_limit', 0], ['journal_mode', 'memory']]) {
    assert.equal(evidence[pragma][0][pragma], expected);
  }
  if (generic) {
    assert.equal(evidence.analyzed, false);
    assert.equal(evidence.statistics.length, 0);
    assert(!evidence.schema.some(row => row.name.startsWith('sqlite_stat')));
  } else {
    assert(evidence.statistics.length > 0);
  }
  for (const row of evidence.statistics) {
    if (row.tbl === 'records') assert.equal(Number(row.stat.split(' ')[0]), evidence.rows[0].records);
    if (row.tbl === 'vals') assert.equal(Number(row.stat.split(' ')[0]), evidence.rows[0].vals);
  }
};
const clocks = (durations, pairs, minimum, maximum) => {
  assert(durations.length >= minimum && durations.length <= maximum);
  assert.equal(durations.length, pairs.length);
  assert(durations.every(ns => Number.isSafeInteger(ns) && ns > 0));
  assert(pairs.every(ns => Number.isSafeInteger(ns) && ns >= 0));
};
const reference = choice => {
  const matches = choice.alternatives.filter(a => a.candidate.name === choice.selected);
  assert.equal(matches.length, 1);
  if (generic) {
    assert.equal(choice.selected, 'generic-original-sql');
    assert.equal(choice.alternatives.length, 1);
  }
  return matches[0];
};
const selected = choice => {
  const audit = reference(choice);
  assert.equal(audit.failure, null);
  assert.equal(audit.validation.status, 'PASS');
  assert(audit.samplesNs.length > 0);
  return audit;
};
const environment = trial => {
  assert.equal(trial.environment.sqliteProfile || 'tuned', generic ? 'generic' : 'tuned');
  if (generic) {
    assert.equal(trial.environment.queryTimeoutMs, raw.configuration.queryTimeoutMs);
    const expired = trial.observations.filter(o => o.query.validation.status === 'QUERY_TIMEOUT').length;
    assert.equal(trial.evidence.workerIncarnations, expired + 1);
  }
  assert.equal(trial.environment.benchmarkJarSha256, jarHash);
  assert.equal(trial.environment.kiara, '2.0.16');
  assert.equal(trial.environment.sqliteJdbc, '3.53.4.0');
  assert.match(trial.environment.os, /^Linux /);
  assert.equal(trial.environment.activeProcessors, 4);
  for (const flag of ['-Xms1g', '-Xmx4g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=4', '-Duser.timezone=UTC']) {
    assert(trial.environment.jvmArguments.includes(flag));
  }
};
assert.equal(raw.tuning.length, datasets.length);
assert.equal(raw.trials.length, datasets.length * forks * 2);
assert.equal(raw.configuration.platform.cpuQuota, 4);
assert.equal(raw.configuration.platform.memoryBytes, 8589934592);
assert.equal(raw.configuration.platform.network, 'none');
assert.match(raw.configuration.platform.workerImageId, /^sha256:[0-9a-f]{64}$/);
if (generic) {
  assert.equal(raw.configuration.platform.queryTimeoutMs, raw.configuration.queryTimeoutMs);
  assert(raw.configuration.queryTimeoutMs > 0);
  if (genericFlag) assert.equal(raw.configuration.queryTimeoutMs, 60000);
}
let timed = 0, samples = 0, candidates = 0, timeouts = 0;
const statuses = new Map();
for (const dataset of datasets) {
  const tuning = raw.tuning.filter(t => t.dataset === dataset);
  assert.equal(tuning.length, 1);
  const tuned = tuning[0];
  assert.equal(tuned.phase, generic ? 'prepare' : 'tune');
  assert.equal(tuned.engine, 'sqlite');
  environment(tuned);
  checkDatabase(tuned);
  const oldTrial = old.trials.find(t => t.dataset === dataset);
  if (oldTrial) assert.equal(tuned.dataSha256, oldTrial.dataSha256, 'Historical fixture changed');
  assert.equal(Object.keys(tuned.choices).length, shape(dataset)[1]);
  for (const [scenario, choice] of Object.entries(tuned.choices)) {
    if (generic) {
      assert.equal(choice.alternatives.length, 1);
      assert.equal(choice.selected, 'generic-original-sql');
      const historical = tunedReference.tuning.find(t => t.dataset === dataset);
      if (historical) {
        const baseline = historical.choices[scenario].alternatives.find(a => a.candidate.name === 'indexed-default');
        assert(baseline, 'Historical original SQL missing');
        assert.deepEqual(reference(choice).candidate.sql, baseline.candidate.sql, 'Generic SQL differs from original compiler output');
      }
    }
    const winner = generic ? reference(choice) : selected(choice);
    for (const audit of choice.alternatives) {
      candidates++;
      assert(audit.plan.length > 0 && audit.bytecode.length > 0);
      assert.equal((audit.candidate.sql.text.match(/\?/g) || []).length, audit.candidate.sql.parameters.length);
      if (audit.failure) {
        timeouts++;
        if (generic) {
          assert.equal(audit.failure, 'QUERY_TIMEOUT');
          assert.equal(audit.validation, null);
          assert.equal(observation(tuned, scenario).query.validation.status, 'QUERY_TIMEOUT');
        } else assert.match(audit.failure, /TuningTimeout/);
        assert.equal(audit.samplesNs.length, 0);
        assert.equal(audit.timerPairsNs.length, 0);
      } else {
        assert.equal(audit.validation.status, 'PASS');
        clocks(audit.samplesNs, audit.timerPairsNs, generic ? 1 : 21, generic ? 1 : 21);
        if (!generic) assert(percentile(winner.samplesNs, .5) <= percentile(audit.samplesNs, .5));
      }
    }
  }
  const k = [], s = [];
  let names;
  for (let fork = 1; fork <= forks; fork++) {
    for (const engine of ['kiara', 'sqlite']) {
      const found = raw.trials.filter(t => t.dataset === dataset && t.engine === engine && t.fork === fork);
      assert.equal(found.length, 1);
      const trial = found[0];
      (engine === 'kiara' ? k : s).push(trial);
      assert.equal(trial.phase, 'measure');
      assert.equal(trial.dataSha256, tuned.dataSha256);
      environment(trial);
      for (const key of ['java', 'vm', 'os', 'architecture', 'activeProcessors', 'jvmArguments']) {
        assert.deepEqual(trial.environment[key], tuned.environment[key]);
      }
      if (engine === 'sqlite') {
        checkDatabase(trial);
        for (const key of ['schema', 'indexes', 'statistics', 'compileOptions', 'sqliteSourceId']) {
          assert.deepEqual(trial.evidence[key], tuned.evidence[key]);
        }
      }
      assert.equal(trial.observations.length, shape(dataset)[0]);
      const currentNames = trial.observations.map(o => o.query.scenario).sort();
      assert.equal(new Set(currentNames).size, currentNames.length);
      if (names) assert.deepEqual(currentNames, names);
      else names = currentNames;
      let trialTimed = 0;
      for (const o of trial.observations) {
        const m = o.query;
        statuses.set(m.validation.status, (statuses.get(m.validation.status) || 0) + 1);
        assert(['PASS', 'UNSUPPORTED', 'KNOWN_DECIMAL_TRUNCATION', 'QUERY_TIMEOUT'].includes(m.validation.status));
        if (m.validation.status === 'QUERY_TIMEOUT') {
          assert(generic);
          assert.equal(m.matches, null);
          assert.equal(m.samplesNs.length, 0);
          assert.match(o.notTimedReason, new RegExp(`wall deadline ${raw.configuration.queryTimeoutMs} ms`));
          if (engine === 'sqlite') {
            const measured = reference(trial.choices[m.scenario]);
            assert.equal(measured.failure, 'QUERY_TIMEOUT');
            assert.equal(measured.validation, null);
            assert.deepEqual(measured.samplesNs, []);
            assert.deepEqual(measured.timerPairsNs, []);
            for (const key of ['candidate', 'plan', 'bytecode']) {
              assert.deepEqual(measured[key], reference(tuned.choices[m.scenario])[key]);
            }
          }
        }
        if (m.validation.status === 'KNOWN_DECIMAL_TRUNCATION') {
          assert.equal(engine, 'kiara');
          assert(m.scenario.startsWith('decimal-'));
          assert.equal(m.comparable, false);
        }
        if (!m.samplesNs.length) {
          assert.equal(m.comparable, false);
          assert(o.notTimedReason);
          assert.equal(m.p50Us, null);
          assert.equal(m.p95Us, null);
          assert.equal(m.meanUs, null);
          assert.equal(o.timerPairsNs.length, 0);
          continue;
        }
        timed++; trialTimed++; samples += m.samplesNs.length;
        assert.equal(o.notTimedReason, null);
        clocks(m.samplesNs, o.timerPairsNs, 15, 101);
        close(m.p50Us, percentile(m.samplesNs, .5) / 1000);
        close(m.p95Us, percentile(m.samplesNs, .95) / 1000);
        close(m.meanUs, m.samplesNs.reduce((a, b) => a + b, 0) / m.samplesNs.length / 1000);
        assert.equal(m.matches, m.validation.expectedMatches - m.validation.missing + m.validation.extra);
        assert.equal(m.comparable, m.validation.status === 'PASS' && !m.scenario.startsWith('decimal-') && !m.scenario.startsWith('limit-'));
        if (engine === 'sqlite') {
          const original = reference(tuned.choices[m.scenario]), measured = selected(trial.choices[m.scenario]);
          for (const key of ['candidate', 'plan', 'bytecode']) assert.deepEqual(measured[key], original[key]);
          assert.deepEqual(measured.samplesNs, m.samplesNs);
          assert.deepEqual(measured.timerPairsNs, o.timerPairsNs);
        }
      }
      assert.equal(trialTimed + trial.observations.filter(o => o.query.validation.status === 'QUERY_TIMEOUT').length, shape(dataset)[1]);
    }
  }
  const eligible = names.filter(name => [...k, ...s].every(t => observation(t, name).query.comparable));
  const expiredNames = names.filter(name => [...k, ...s].some(t => observation(t, name).query.validation.status === 'QUERY_TIMEOUT'));
  for (const name of expiredNames) {
    const kt = k.filter(t => observation(t, name).query.validation.status === 'QUERY_TIMEOUT').length;
    const st = s.filter(t => observation(t, name).query.validation.status === 'QUERY_TIMEOUT').length;
    const winner = deadlineWin(k, s, name) ? 'Kiara deadline win' :
      deadlineWin(s, k, name) ? 'SQLite deadline win' : 'No unanimous deadline winner';
    assert(report.includes(`| ${dataset} | ${name} | ${kt}/${forks} | ${st}/${forks} | ${winner} | Excluded from latency ratio |`));
  }
  if (generic) assert(eligible.length <= shape(dataset)[2]);
  else assert.equal(eligible.length, shape(dataset)[2]);
  for (const name of names) {
    const row = observation(k[0], name).query;
    const expectedEligible = !name.startsWith('decimal-') && !name.startsWith('limit-') &&
      [...k, ...s].every(t => observation(t, name).query.validation.status === 'PASS' && observation(t, name).query.samplesNs.length);
    assert.equal(eligible.includes(name), Boolean(expectedEligible), row.scenario);
  }
  for (const name of eligible) {
    assert.equal(new Set([...k, ...s].map(t => observation(t, name).query.matches)).size, 1);
  }
  const r = ratio(k, s, eligible), ci = bootstrap(k, s, eligible);
  const kw = eligible.filter(name => metric(k, name) < metric(s, name)).length;
  const sw = eligible.filter(name => metric(s, name) < metric(k, name)).length;
  const kd = names.filter(name => deadlineWin(k, s, name)).length;
  const sd = names.filter(name => deadlineWin(s, k, name)).length;
  const line = `| ${dataset} | ${eligible.length} | ${r.toFixed(3)}x | ${ci[0].toFixed(3)}-${ci[1].toFixed(3)}x | ${kw} / ${sw} |` +
    (generic ? ` ${kd} / ${sd} | ${kw + kd} / ${sw + sd} |` : '');
  assert(report.includes(line), `Summary mismatch: ${line}`);
  console.log(line);
}
assert(report.includes(`${raw.trials.length} measured trials`));
assert(report.includes(`${timed} timed scenario/trial entries`));
assert(report.includes(`${samples} raw execution samples`));
if (generic) assert(report.includes('Post hoc win-count amendment'));
console.log(JSON.stringify({ trials: raw.trials.length, tuning: raw.tuning.length, candidates, timeouts,
  timed, samples, statuses: Object.fromEntries(statuses), jarHash }, null, 2));
console.log('Independent verification passed: fixtures, provenance, SQLite indexes/plans, sample bounds, statistics and report.');
