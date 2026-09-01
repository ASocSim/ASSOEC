# Comment convention

NetLogo has no documentation format of its own, so this is ours. It is deliberately
small: **one extra semicolon**, plus a handful of doxygen-style `@tags`.

The same comments do two jobs:

* the **NetLogo Intellisense** extension shows the block above a definition when you
  hover over its name anywhere in the model;
* **nlsdoc** (`docs/NlsDoc.java`) turns them into a browsable HTML reference.

[`utils/utils.nls`](../utils/utils.nls) is fully converted and is the reference example.

---

## The one rule

| Comment | Meaning |
| --- | --- |
| `; ...` | An implementation note. Ignored by the documentation. |
| `;; ...` | Documentation. Attaches to whatever is defined on the next line. |

A `;;` block must sit **directly above** the definition — no blank line in between, or
it is read as documentation for the file instead.

```netlogo
;; Restrict `number` to the closed range [`low`, `high`].
to-report clamp [low high number]
  ; a stray `;` note in here changes nothing
  report number
end
```

A bare `;;` is the blank line of a block, so prose can have paragraphs. Banner lines
(`;;;;;;;;;;;;`, `;; ---------`) are ignored on purpose; leave a blank line after a
section banner so it does not glue itself to the next definition.

## Procedures and reporters

First sentence is the summary — keep it to one line, it is what shows up in the symbol
index and the search box. Then optional prose, then tags.

```netlogo
;; Map `val` linearly from the input range onto the output range.
;;
;; Values outside the input range are pinned to the nearest output bound, so the
;; result never leaves [`min-map`, `max-map`].
;;
;; @param min-val  lower bound of the input range
;; @param max-val  upper bound of the input range
;; @param val      the value to map
;; @return the mapped value
;; @see clamp
to-report bounded-linear-map [min-val max-val min-map max-map val]
```

For commands, say what changes rather than what is returned:

```netlogo
;; Stop the profiler and write its report to `report.log` next to the model.
;;
;; @context observer
;; @side-effects replaces any existing `report.log`
to export-profiling
```

`@context` matters more here than in most codebases: half of these procedures only make
sense inside `ask people [ ... ]`, and nothing in the code says so.

## Variables

Inside `globals [...]` and any `<breed>-own [...]` block, a **trailing comment documents
the variable on that line**. Write `;;<` to be explicit (`<` points back at what it
describes, as in doxygen), or just use a plain trailing comment — both are picked up,
because a comment inside a variable block is never anything else.

```netlogo
gathering-points-own [
  gathering-type            ;;< which kind of place this is, see gathering_types.nls
  available-food-rations    ;;< rations of food available; mostly relevant for homes

  ;; Money the place holds. Companies keep enough to pay wages for two ticks.
  ;; @unit currency units
  amount-of-capital
]
```

Long blocks get unreadable fast, so split them with `@group`. It applies to every
variable below it until the next `@group`, and each group becomes its own table.

```netlogo
people-own [
  ;; @group Demographics
  age
  sex

  ;; @group Disease model
  time-when-infected        ;;< tick at which this person was infected, or -1
  infection-state
]
```

## Breeds

```netlogo
;; A place people can go to: a home, school, workplace, market or cemetery.
breed [gathering-points gathering-point]
```

## Regions

For the files that are simply too long to scroll, `;#region` / `;#endregion`
collapse a stretch of code. They nest, and the name shows on the folded line.

```netlogo
;#region Counter datastructure

to-report new-counter
  report []
end

;#endregion
```

Any number of leading semicolons works (`;#region`, `;; #region`), and the markers
are ignored by nlsdoc, so they never end up in the generated documentation. Fold
them all with `Ctrl+K Ctrl+8`, unfold with `Ctrl+K Ctrl+9`.

## File headers

A `;;` block at the top of the file, separated from the code by a blank line, documents
the file. Write `@file` first to make that explicit.

```netlogo
;; @file utils/utils.nls
;; @brief Small helpers that the model itself does not depend on.
;;
;; Extra mathematics, string handling, a counter "datastructure", and the
;; debugging and world-import/export commands wired to the interface buttons.
;;
;; @see all_utils.nls
```

The summary shows up next to the file on the overview page, so it is worth one good
sentence per file even before the procedures inside are documented.

## Tags

| Tag | Where | Notes |
| --- | --- | --- |
| `@brief <text>` | anywhere | Use when the summary should differ from the opening prose. |
| `@param <name> <text>` | procedures | Flagged in the HTML if `<name>` is not actually a parameter. |
| `@return <text>` | reporters | `@returns` also accepted. |
| `@context <agent>` | procedures | `observer`, `turtle`, `person`, `gathering-point`, `link`, ... |
| `@side-effects <text>` | procedures | What changes that is not the reported value. |
| `@example <code>` | procedures | Rendered as a code block. Fence it with ``` for several lines. |
| `@see <name> ...` | anywhere | Names are linked when they resolve to a definition. |
| `@note <text>` | anywhere | |
| `@todo <text>` | anywhere | |
| `@deprecated <text>` | anywhere | Adds a badge; say what to use instead. |
| `@internal` | anywhere | Adds a badge: an implementation detail, do not call from elsewhere. |
| `@since <text>` | anywhere | |
| `@unit <text>` | variables | Ticks, currency units, people, probability in [0,1], ... |
| `@group <name>` | variables | Splits a long `-own` block into sections. |
| `@file <path>` | file header | Marks the block as the file's documentation. |
| `@module <name>` | file header | Overrides the directory used to group files. Apply it to *every* file of the group, or not at all. |
| `@author`, `@since` | file header | |

A tag runs to the next `@tag`, so values can wrap over several lines. Backticks link to
other definitions: `` `clamp` `` becomes a link wherever `clamp` is defined.

Unknown tags are printed as-is rather than dropped, so a typo shows up in the output
instead of disappearing.

## Generating the HTML

```powershell
.\nlsdoc.ps1 -Open      # writes docs/api and opens it
.\nlsdoc.ps1 -Check     # coverage report, generates nothing
```

```sh
./nlsdoc.sh
./nlsdoc.sh --check --min-coverage 40   # exit 1 below the threshold, for CI
```

No install needed: the wrappers run `NlsDoc.java` directly on the JVM that ships with
NetLogo. Java 17 or newer, no build step, no dependencies. `docs/api/` is generated and
git-ignored.

Beyond the comments, the generated site cross-references what NetLogo itself will not
tell you: which procedures call each one, which call it back, which file each global
lives in, and what is still undocumented.

## Rolling it out

787 definitions are not going to be documented in one sitting. `--check` lists what is
missing per file, so the practical order is:

1. a `@brief` header on each file — 56 sentences, and the overview page stops being a
   list of filenames;
2. the `-own` blocks, since the trailing comments already there are picked up as soon as
   the file is touched;
3. procedures as you work on them, `@context` first.

## Editor hover: what works today

The hover in VS Code comes from
[netlogo-intellisense](https://github.com/CezaraPastrav/netlogo-intellisense), which
already collects the comment block above a definition and renders it as markdown. Prose
therefore hovers correctly straight away. Two rough edges are worth fixing there:

* markdown collapses single newlines, so a run of `@param` lines hovers as one long
  paragraph. In `collectDocComment`, joining with `"  \n"` (a markdown hard break)
  instead of `"\n"` fixes every multi-line comment at once.
* `globals` and `<breed>-own` variables are parsed with `documentation: ""` hard-coded,
  so variable comments never hover. Collecting the preceding `;;` lines and the trailing
  comment there — the same rule nlsdoc uses — would light up the largest group of
  symbols in this model.
