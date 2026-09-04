# Work in progress: open issues

Raised 2 September 2026, from a read of the thirteen `.nls` files touched on 1-2
September while adapting the COVID model to Ebola. It covers **only** those files.

Everything listed here is a place where the code and its own names, comments or
callers disagree, so no honest documentation comment can be written for it yet.
Each entry has a matching stub in the source:

```netlogo
;; Work in progress.
;;
;; work in progress
;;
;; @context work in progress
;; @return work in progress
;; @todo <the one-line problem>. See docs/WIP_ISSUES.md#<anchor>.
```

The rest of the definitions in those files are documented normally, per
[COMMENT_CONVENTION.md](COMMENT_CONVENTION.md).

**When you settle one of these:** replace the `;; Work in progress.` block with a
real documentation comment and delete the entry from this file. `nlsdoc --check`
counts a WIP stub as documented, so this file is the only record of what is still
open.

---

## Cross-cutting

Two problems that are not confined to one procedure. Both invalidate a lot of
code at once, and several entries below are only symptoms of them.

<a id="age-is-never-assigned"></a>

### `age` is never assigned

`set age` does not appear anywhere in the model, and `worker-age`,
`student-age` and `retired-age` are commented out in
[people/classes.nls](../people/classes.nls). Only `young-age` survives, and
nothing assigns it either.

Everything that tests `age` is therefore permanently false or empty:
`is-worker?`, `is-student?`, `is-adult?`, `is-child?`, `is-young?`,
`is-parent?`, `workers`, `children`, `students`, `youngs`, `parents`, every
`#workers-working-at-*` and `#*-workers` reporter, `my-colleagues`,
`is-I-have-contractual-obligations?`, `is-currently-in-situation-of-active-dependence?`,
`is-currently-watching-a-child?` and `is-currently-watched-by-an-adult?`. The
four `*-average-amount-of-capital` reporters divide by zero if called.

**Needed:** decide whether the Ebola model keeps age classes at all. If it does,
`setup-person` is where they would be assigned, and the age reporters in
`people/classes.nls` need uncommenting. If it does not, the predicates above
should go rather than sit permanently false.

<a id="motive-strings-have-forked"></a>

### Motive strings have forked

[motives.nls](../motives.nls) defines the motive vocabulary as constants:
`work-motive` is `"contractual obligation"`, `treatment-motive` is
`"get treatment"`, plus `travelling-motive`, `learning-motive` and the
`"mandatory"` forced motive.

[decision/select_activity.nls](../decision/select_activity.nls) does not use
them. The motives it actually produces are the bare strings `"rest"`,
`"get food"`, `"work"` and `"attend religious gathering"`.

Consequences: `is-working?` and `is-working-motive?` never fire, so
`ratio-personnel-here` and every `#workers-working*` reporter stay at zero
independently of the `age` problem. In [need_management.nls](../need_management.nls)
the `SUE-*` reporters test a third vocabulary again - `"relaxing"`,
`"religious gathering"`, `"organize funeral"`, `"care for the sick"`,
`"visits"`, `"providing"`, `"get treatmet"`, `"get treatment at home"`,
`"get treatment at hospital"`, `"get treatment get at home"` - most of which
nothing produces, so those branches are dead and the needs they were meant to
serve never move.

**Needed:** one vocabulary. The cheapest fix is to make `select_activity` emit
the constants from `motives.nls`, add constants for the Ebola-specific motives
(food, religious attendance, funerals, care, visits), and then sweep the `SUE-*`
reporters onto them. Until that happens, activity scoring is running on a small
subset of the rules that are written down.

---

## Red: the code contradicts itself

<a id="my-available-activity-descriptors"></a>

### `my-available-activity-descriptors`

[decision/select_activity.nls](../decision/select_activity.nls)

Three separate problems in the one reporter.

1. **Religious gatherings happen every afternoon.** The condition is
   `(day-of-the-week = "sunday" and slice-of-the-day = "morning" or slice-of-the-day = "afternoon")`.
   NetLogo's `and` and `or` have equal precedence and associate left to right, so
   this reads `(sunday and morning) or afternoon` - true every afternoon of the
   week. The comment above it says "on sunday we can go to religious gathering".
   Parenthesise to fix.
2. **Activities point at random places, not this person's.** The descriptors are
   built from `one-of home-gathering-type`, `one-of stores-gathering-type` and
   `one-of churches-gathering-type`, which draw from every such place in the
   world. `my-home`, `my-store` and `my-church` exist and are unused, so a woman
   "works" at a stranger's house and people shop wherever chance sends them. This
   also defeats the gathering links, since the place chosen is usually not one
   this person is linked to.
3. **The trailing `foreach` appends a hardcoded `false`** to every descriptor and
   reverses the list with `fput`. That third element is what
   `social-distancing-of` reads. Is the flag meant to stay constant, and does the
   reversal matter?

The market condition
(`saturday or sunday and afternoon` → `(sat or sun) and afternoon`) and the store
condition (`morning or afternoon and not sunday` → `(morning or afternoon) and not sunday`)
happen to parse the way the comments intend, but only by accident of
left-associativity. Worth parenthesising all four while you are in there.

<a id="perform-people-activities"></a>

### `perform-people-activities`

[activity_model.nls](../activity_model.nls)

The capacity pass reads:

```netlogo
let all-people (gatherers with [current-activity != myself])
```

Inside `ask gathering-points`, that selects the people attached to this place who
are **not** here, and then caps *them* against the place's capacity and sets
`is-activity-successful?` on them. The people who actually turned up are never
capped. `!=` looks like it should be `=`.

Two smaller things in the same procedure: it calls `reset-timer` and prints
`"performing activities for N people"` on every tick, both left over from
profiling.

<a id="decay-satisfaction-level-ebo"></a>

### `decay-satisfaction-level-ebo`

[need_management.nls](../need_management.nls)

**Belonging decays by subtraction.** Every other need decays multiplicatively
(`level * 0.8`), but group- and family-belonging use `level - 0.8`. Since levels
live in [0, 1], both tanks empty completely in one tick from any level at or
below 0.8, then clamp to 0. Belonging is effectively always maximally urgent.

**Social status and providing never decay at all.** `social-status-decay` and
`providing-decay` are declared, and adjusted for hospitalised men with the
comment "Double the decay", but neither is ever applied to
`social-status-satisfaction-level` or `providing-satisfaction-level`. The two
lines that would do it are missing rather than commented out.

Also worth deciding while you are here: every decay factor is the same 0.8 except
autonomy at 0.99, and the comment about age and gender exclusions at the bottom
is unimplemented.

<a id="the-sue-family"></a>

### The `SUE-*` family

[need_management.nls](../need_management.nls)

Beyond the motive vocabulary problem above, four of these compare a
gathering-point agent against a string, which is never true:

| Reporter | Line | Effect |
| --- | --- | --- |
| `SUE-group-belonging` | `ifelse (gp = "home")` | the caring-for-the-sick branch always takes the `else` |
| `SUE-family-belonging` | `if gp = "home"`, `if gp = "hospital"` | the care branch always falls through to 0 |
| `SUE-physical-safety` | `if gp = "hospital"` | unreachable anyway, see below |
| `SUE-social-status` | `if ([gathering-type] of gp = my-work)` | compares a type string against an agent |

`SUE-physical-safety` also has its hospital and school lines *inside* the
`if motive = "work"` block but *after* an `ifelse` in which both branches report,
so they can never run. `SUE-health` tests the motive `"get treatmet"`.
`SUE-providing`'s male branch reports `-0.4` for every activity that is not
tagged `"work"` at a place whose type is `"work"` - a type that does not exist -
so men lose providing satisfaction whatever they do.

**Needed:** these read as a first pass written against the intended Ebola motive
set rather than the one the model emits. Probably best fixed as one sweep after
the motive vocabulary is settled, comparing `[gathering-type] of gp` against type
strings throughout.

<a id="sue-autonomy"></a>

### `SUE-autonomy`

[need_management.nls](../need_management.nls)

```netlogo
ifelse (motive = "organize funeral") [report 0.5] [report 0.5]
```

Both branches report the same value, so the reporter returns 0.5 for every
activity that reaches this line, and the treatment logic below it is unreachable.
The commented-out lines around it suggest the intent was for a safe-and-dignified
burial to damage autonomy and a traditional rite to raise it, which needs
`funeral-type` to exist first.

The working block above it also has a hole: if the motive is `"work"` but the
place is not `my-work`, no branch reports and execution falls through.

<a id="sue-financial-survival"></a>

### `SUE-financial-survival`

[need_management.nls](../need_management.nls)

The reporter opens with

```netlogo
ask people [set my-expected-income 10]
```

A reporter that writes to every agent in the world, on every call - and it is
called once per candidate activity, per person, per tick. Besides being O(n²) on
the hot path, it means `my-expected-income` can never hold anything but 10.

The rest of the body is mostly commented out: the `"get food"` branch reports 0
with the real calculation disabled, and the `"work"` branch reports
`min (expected income / eating costs) 1`.

<a id="workers-working-at-mine-agriculture-house"></a>

### `#workers-working-at-mine`, `-at-agriculture`, `-at-house`

[activity_model.nls](../activity_model.nls)

Three gathering-type strings that do not exist:

| Reporter | Tests | Should be |
| --- | --- | --- |
| `#workers-working-at-mine` | `"workplace-mines"` | `"workplace-mine"` |
| `#workers-working-at-agriculture` | `"agriculture"` | `"workplace-agriculture"` |
| `#workers-working-at-house` | `"house"` | `"home"` |

A one-word fix each, but note they would still report 0 because of `age` and the
motive fork, so fixing the strings alone will not make them work.

<a id="proximity-factor"></a>

### `proximity-factor`

[gathering_points.nls](../gathering_points.nls)

Every branch compares the string `gathering-type` against a reporter from
[gathering_points/gathering_types.nls](../gathering_points/gathering_types.nls),
which returns an **agentset**:

```netlogo
if gathering-type = hospital-gathering-type [report density-factor-hospitals]
```

No branch can ever match, so every call reaches `error (word gathering-type " not defined")`.
This is live: [contagion/contagion.nls](../contagion/contagion.nls) calls it as
`[proximity-factor] of context`.

**Fix:** compare against the type strings (`"hospital"`, `"school"`, ...) or
against the existing predicates (`is-hospital?`, `is-school?`, ...). The
`gathering-type` reporters are agentset lookups and are correctly used as such in
`select_activity`, so it is this file that has the wrong idea of them.

<a id="is-alone-at-"></a>

### `is-alone-at?`

[gathering_points.nls](../gathering_points.nls)

```netlogo
to-report is-alone-at? [gp]
  report any? other [in-gathering-link-neighbors] of gp
end
```

Reports true when somebody **else** is attached to `gp` - the opposite of the
name. It also tests who is linked to the place rather than who is currently
there, which the comment above it already flags ("might be to be revised later as
to-be-expected-to-be-alone-at"). Nothing calls it at the moment, so fixing it is
free.

<a id="update-alive-status"></a>

### `update-alive-status`

[people_management.nls](../people_management.nls)

```netlogo
if random 100 > 80 [ ask one-of people [ die ] ]
```

One random person dies on roughly 19% of ticks, chosen without regard to
infection, symptoms, age or anything else, and `#dead-people` is not incremented.
The comment says this is scaffolding for the funeral work, which is fair - but as
it stands it is a background mortality process running alongside the disease
model and quietly shrinking the population.

**Needed:** either gate it behind a `debug`-style switch until deaths come from
the disease model, or replace it now with a per-person mortality hazard.

<a id="calibrate-importance-weights-of-needs"></a>

### `calibrate-importance-weights-of-needs`

[country_specific_features/cultural_model.nls](../country_specific_features/cultural_model.nls)

The file's own comments already say "calculate wrong" and "not calculate
correctly, review". Concretely:

- `importance-weight-autonomy` is assigned twice - once in the safety block, once
  in the esteem block. The second wins, so the safety-block formula is dead.
- The normalising sum lists `importance-weight-autonomy` **twice** and omits
  `importance-weight-social-safety` and `importance-weight-providing` - yet both
  of those are divided by that sum afterwards. The eleven weights therefore do not
  sum to 1.
- `importance-weight-providing` is scaled by `weight-safety-needs-category` while
  sitting under the esteem heading.
- `importance-weight-social-status` uses the conformity/tradition value under the
  safety category, which looks like a copy of the autonomy line above it.

There is a further problem upstream: `set-values-based-on-culture` is commented
out of the setup path, so all nine `importance-given-to-*-value` variables are 0
when this runs. Every weight derived from them is 0, and only the four survival
weights - which do not depend on values - are non-zero. In effect the population
currently has survival needs and nothing else.

**Needed:** this one probably wants rewriting rather than patching, alongside the
decision about whether the Schwartz layer is in or out for Ebola.

<a id="social-distancing-of"></a>

### `social-distancing-of`

[activity_model.nls](../activity_model.nls)

Reads `item 2` of an activity descriptor, but `activity-descriptor` builds a
two-element list. The third element is appended later, and only for descriptors
that pass through `my-available-activity-descriptors`, where it is hardcoded to
`false`. A descriptor built anywhere else raises an index error here. The
question in the original comment - "Why only 2 elements in the returned list?" -
is still open, and the answer decides whether the flag belongs in
`activity-descriptor` itself.

<a id="is-performing-essential-shopping--and-is-essential-shop-gathering-point-"></a>

### `is-performing-essential-shopping?` and `is-essential-shop-gathering-point?`

[activity_model.nls](../activity_model.nls)

`is-performing-essential-shopping?` passes `current-motivation` to
`is-essential-shop-gathering-point?`, which is named for a place but compares its
argument against the motive string `"essential shopping"`. One of the two names
is wrong. Neither string is produced by the Ebola build, so both are inert; the
Ebola equivalent is the `"get food"` motive at a store or market.

<a id="current-capacity"></a>

### `current-capacity`

[gathering_points.nls](../gathering_points.nls)

```netlogo
to-report current-capacity
  if capacity = disabled-capacity [report capacity]
  report capacity
end
```

Both paths report the same thing, so the guard does nothing and the reporter is
an alias for `capacity`. Since `disabled-capacity` is the string
`"disabled-capacity"`, `perform-people-activities` ends up comparing a count
against a string when a place is uncapped - it guards against that with
`capacity != disabled-capacity` before the comparison, so nothing breaks today,
but the intent of this reporter is missing. Most likely it was meant to report a
very large number, or `false`, for an uncapped place.

---

## Amber: needs a decision, not a fix

<a id="is-away--and-is-at-work-"></a>

### `is-away?` and `is-at-work?`

[gathering_points.nls](../gathering_points.nls)

Both live in the gathering-points file but read `people-own` variables
(`current-activity`, `my-work`), so they only run in a person context. Either
they belong in `people_management.nls`, or they should take the person as an
argument. `activity_model.nls` already has `is-being-away? [a]` doing the same
job as a parameterised reporter, which suggests `is-away?` is the older of the
two.

**Needed:** where do they live, and which of the two `away` reporters survives.

<a id="is-technically-feasible-to-work-from-home-"></a>

### `is-technically-feasible-to-work-from-home?`

[people_decision.nls](../people_decision.nls)

Tests `member? [gathering-type] of my-work ["workplace" "university"]`. The Ebola
build creates neither type, so this is always false. Retire it, or retarget it -
though mining and agriculture are not work-from-home occupations, so "always
false" may turn out to be the right answer stated the wrong way.

<a id="update-memory"></a>

### `update-memory`

[people_decision.nls](../people_decision.nls)

Fully written, and the only call to it - at the end of
`update-belief-based-on-current-activity` - is commented out. It maintains
`what-my-network-did-week-day`, `what-my-network-did-weekend`,
`did-my-network-socially-distance?` and `did-my-network-quarantine?`, none of
which anything else currently writes, so social imitation is inert.

**Needed:** is network imitation part of the Ebola model? If yes this wants
turning back on and its cost checking - it walks the whole network per person per
tick. If no, it and the four variables should go.

<a id="drc-hofstede-partial"></a>

### `drc-hofstede-partial`

[country_specific_features/cultural_model.nls](../country_specific_features/cultural_model.nls)

Hardcodes Ugandan scores (with East African alternatives noted in a comment) and
then `stop`s, which makes the `"Custom"` branch below it unreachable and the
`load-country-specific-settings` chooser inoperative. The banner comment asks for
DRC values "if available, or discuss".

**Needed:** DRC Hofstede scores if they exist, and a decision on whether the
country chooser stays on the interface. Note that while
`set-values-based-on-culture` is commented out of setup, these scores feed nothing
at all - see [calibrate-importance-weights-of-needs](#calibrate-importance-weights-of-needs).

<a id="setup-social-network-ebola"></a>

### `setup-social-network-ebola`

[social_networks.nls](../social_networks.nls)

Empty body, no callers. The name says it is meant to replace
`setup-social-networks` for the Ebola model, and the current network is three
uniformly random friends per person regardless of household, workplace, church or
village.

**Needed:** what the Ebola network should be. The commented-out `possible-friends`
body - befriend people you meet at church or school - is the obvious starting
point, and matters for funeral attendance.

<a id="the-two-quarantining-reactions"></a>

### The two quarantining reactions

[decision/quarantining.nls](../decision/quarantining.nls)

`quarantining-reaction-when-being-infected` and
`quarantining-reaction-when-someone-from-household-is-infected` both have
entirely commented-out bodies, but both are still called from
`update-people-mind`. Learning you are infected therefore changes nothing, and
the only route into `is-officially-asked-to-quarantine?` that can currently fire
is `is-believing-to-be-infected?` - the household route is closed too, because
`home-in-isolation?` is a stub returning false.

Note a latent unit bug in the commented-out code: both would call
`include-time-for-quarantining` with a number of **ticks**
(`7 * #ticks-per-day`), while the variable is
`#days-I-should-remain-in-self-quarantining` and `update-quarantining-decisions`
decrements it once per call.

**Needed:** whether self-quarantine exists in the Ebola scenario at all, and in
what units.

<a id="the-behaviourspace-checks"></a>

### The BehaviorSpace checks

[validation/sanity.nls](../validation/sanity.nls)

`behaviourspace-satisfaction-casualties` and
`behaviourspace-soft-working-from-home-check` read experiment names and variable
names from the COVID model (`no-measures-behaviourspace-experiment-name`,
`soft-working-from-home-behaviourspace-experiment-name`,
`ratio-working-at-work-behaviourspace-variable-name`).

`behaviourspace-satisfaction-casualties` also does not compute what its comment
describes: the comment says the casualty ratio with schools and universities
closed should be about twice the ratio with everything closed, but the body reads
a single experiment and maps its casualty ratio from [0, 0.1] onto [0, 1].

**Needed:** which experiments the Ebola model will actually run, and what the
sanity criteria are for it. Until then `sanity-checks` cannot be trusted.

<a id="the-check-reporters"></a>

### The `check-*` reporters

[validation/sanity.nls](../validation/sanity.nls)

`check-infection-works`, `check-quarantine-stops-the-propagation`,
`check-r0-works` and
`check-linear-growth-of-contacts-when-size-increase-no-infection` each run the
model themselves and assert on the outcome. All four depend on COVID-era
machinery: `all-self-isolate-for-35-days-when-first-hitting-2%-infected?`,
`with-infected?`, `propagation-risk`, `preset-scenario`,
`rescale-households-and-gathering-points-based-on-the-number-of-households`,
`set-immune` and `has-been-through-global-quarantine?`.

`set-immune` still exists in two places
([disease/disease_model.nls](../disease/disease_model.nls) and
[contagion/disease_model_covid.nls](../contagion/disease_model_covid.nls)), which
is its own question.

**Needed:** confirm whether this file still compiles against the Ebola build, and
whether these tests are being kept. They are the only automated behavioural checks
in the model, so they are worth rescuing rather than deleting.

---

## Checklist

| # | Item | File | Kind |
| --- | --- | --- | --- |
| 1 | [`age` is never assigned](#age-is-never-assigned) | model-wide | cross-cutting |
| 2 | [Motive strings have forked](#motive-strings-have-forked) | model-wide | cross-cutting |
| 3 | [`my-available-activity-descriptors`](#my-available-activity-descriptors) | select_activity.nls | red |
| 4 | [`perform-people-activities`](#perform-people-activities) | activity_model.nls | red |
| 5 | [`decay-satisfaction-level-ebo`](#decay-satisfaction-level-ebo) | need_management.nls | red |
| 6 | [The `SUE-*` family](#the-sue-family) | need_management.nls | red |
| 7 | [`SUE-autonomy`](#sue-autonomy) | need_management.nls | red |
| 8 | [`SUE-financial-survival`](#sue-financial-survival) | need_management.nls | red |
| 9 | [`#workers-working-at-mine` and friends](#workers-working-at-mine-agriculture-house) | activity_model.nls | red |
| 10 | [`proximity-factor`](#proximity-factor) | gathering_points.nls | red |
| 11 | [`is-alone-at?`](#is-alone-at-) | gathering_points.nls | red |
| 12 | [`update-alive-status`](#update-alive-status) | people_management.nls | red |
| 13 | [`calibrate-importance-weights-of-needs`](#calibrate-importance-weights-of-needs) | cultural_model.nls | red |
| 14 | [`social-distancing-of`](#social-distancing-of) | activity_model.nls | red |
| 15 | [`is-performing-essential-shopping?`](#is-performing-essential-shopping--and-is-essential-shop-gathering-point-) | activity_model.nls | red |
| 16 | [`current-capacity`](#current-capacity) | gathering_points.nls | red |
| 17 | [`is-away?` and `is-at-work?`](#is-away--and-is-at-work-) | gathering_points.nls | amber |
| 18 | [`is-technically-feasible-to-work-from-home?`](#is-technically-feasible-to-work-from-home-) | people_decision.nls | amber |
| 19 | [`update-memory`](#update-memory) | people_decision.nls | amber |
| 20 | [`drc-hofstede-partial`](#drc-hofstede-partial) | cultural_model.nls | amber |
| 21 | [`setup-social-network-ebola`](#setup-social-network-ebola) | social_networks.nls | amber |
| 22 | [The two quarantining reactions](#the-two-quarantining-reactions) | quarantining.nls | amber |
| 23 | [The BehaviorSpace checks](#the-behaviourspace-checks) | sanity.nls | amber |
| 24 | [The `check-*` reporters](#the-check-reporters) | sanity.nls | amber |

## Smaller things, noted in the code rather than here

These are recorded as `@note` or `@todo` on the definitions themselves and need
no discussion:

- `is-university?`, `is-workplace?`, `is-a-non-essential-gathering-point?` and
  `is-relaxing?` test types and motives the Ebola build never creates.
- `#healthy-personel` and `#sick-personel` are missing an `n`.
- `ratio-personnel-here` and `ratio-sick-personnel` divide by zero at a place
  with no staff.
- `workplace-homes` and `leisure` are declared as globals and never assigned.
- `global-prevalence-of`, `perform-people-activities` and
  `my-preferred-available-activity-descriptor` call `reset-timer` or `print` on
  every invocation.
- `update-people-mind` recomputes epistemic status for the whole population once
  per queued event.
- `days-since-seen-relatives`, `-colleagues` and `-friends` are reset but never
  incremented.
- `SUE-leisure` computes a need that does not exist among the eleven satisfaction
  levels.
- `is-working-motive?` and `is-contractual-obligation?` are duplicates, and both
  inline the string instead of calling `work-motive`.
