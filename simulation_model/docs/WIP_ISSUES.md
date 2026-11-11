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


---

## Red: the code contradicts itself

<a id="my-available-activity-descriptors"></a>

### `my-available-activity-descriptors`

[decision/select_activity.nls](../decision/select_activity.nls)

3. **The trailing `foreach` appends a hardcoded `false`** to every descriptor and
   reverses the list with `fput`. That third element is what
   `social-distancing-of` reads. Is the flag meant to stay constant, and does the
   reversal matter?

<a id="perform-people-activities"></a>

### `perform-people-activities`

[activity_model.nls](../activity_model.nls)

The capacity is used to determine whether an activity is (un)successful
BUT this is never actually called, I suspect because 
count all-people > current-capacity
is always false!



### The `SUE-*` family

[need_management.nls](../need_management.nls)

There is no explicit action for caring at the hospital, which is what female relatives can do if someone is sick and at the hospital


# DEBUG BREAK HERE


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
