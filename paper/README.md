# Kiara DB manuscript for IEEE Access

This folder contains the LaTeX source for the IEEE Access manuscript
*"Kiara DB: Composite-Index-Free Multi-Attribute Retrieval over Read-Mostly
In-Memory Data with Depth-First-Numbered Attribute Tries."* The paper extends
the 2020 *Journal of Algorithms and Computation* article to the full Kiara 2.0.16
system and reports the benchmark suites recorded in [`../docs`](../docs) and
[`../benchmarks`](../benchmarks).

## Layout

| Path | Contents |
| --- | --- |
| `main.tex` | Preamble, front matter, abstract, keywords, biography |
| `sections/01-introduction.tex` | Motivation, open questions, contributions |
| `sections/02-related-work.tex` | Composite indexes, intersections, structural joins, engines, text search, benchmarking |
| `sections/03-model.tex` | Formal model, lemmas, reduction theorem, composite-index bound (Proposition 1) |
| `sections/04-system.tex` | Typed indexing, operators, Algorithm 1 (reduction), text layer, Algorithm 2 (keyword search), releases |
| `sections/05-cost-model.tex` | Build and query cost model, predictions P1--P6 |
| `sections/06-methodology.tex` | Suites S1--S7, datasets, oracle, protocol, statistics |
| `sections/07-results.tex` | Lucene, SQL engines, per-query anatomy, wide filters, correctness findings |
| `sections/08-discussion.tex` | Guidance, threats to validity, future work, conclusion, data availability, acknowledgment |
| `references.bib` | Bibliography (IEEEtran style) |

## Building

`main.tex` selects its document class automatically:

- **Submission build:** Download the official IEEE Access LaTeX template from
  <https://ieeeaccess.ieee.org/authors/preparing-your-article/>. Copy `ieeeaccess.cls`,
  `spotcolor.sty`, the logo and bullet images, and the template fonts into this
  folder. `ieeeaccess.cls` is then used automatically.
- **Proof build:** Without `ieeeaccess.cls`, the manuscript falls back to standard
  `IEEEtran` journal mode with Times-compatible `newtx` fonts. The content and
  numbering stay the same, but the page count and appearance differ from the final
  template.

With TeX Live or MiKTeX:

```powershell
latexmk -pdf main.tex
```

Or with [Tectonic](https://tectonic-typesetting.github.io/), which downloads packages on demand:

```powershell
tectonic -X compile main.tex
```

The proof build compiles without errors, overfull boxes, or undefined references.
It produces 14 pages.

## Before submission

- [ ] Replace the placeholder biography in `main.tex` with degrees, positions, and a
      photograph, as IEEE Access requires.
- [ ] Commit and push `benchmarks/` and `docs/` so that the data-availability
      reference (`kiarademo2026benchmarks`) resolves. These folders are currently
      untracked.
- [ ] Confirm that Kiara 2.0.16 is published to Maven Central, or adjust the software
      citation (`kiara2026software`).
- [ ] Review the generative-AI disclosure in the Acknowledgment. IEEE requires AI
      assistance to be disclosed there.
- [ ] Fill in `\history` and `\doi` after acceptance. IEEE supplies these values.
