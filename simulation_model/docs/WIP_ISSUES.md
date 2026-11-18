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



### The `SUE-*` family

[need_management.nls](../need_management.nls)

There is no explicit action for caring at the hospital, which is what female relatives can do if someone is sick and at the hospital


# DEBUG BREAK HERE




### `social-distancing-of`

[activity_model.nls](../activity_model.nls)

Reads `item 2` of an activity descriptor, but `activity-descriptor` builds a
two-element list. The third element is appended later, and only for descriptors
that pass through `my-available-activity-descriptors`, where it is hardcoded to
`false`. A descriptor built anywhere else raises an index error here. The
question in the original comment - "Why only 2 elements in the returned list?" -
is still open, and the answer decides whether the flag belongs in
`activity-descriptor` itself.



## Smaller things, noted in the code rather than here

- `update-people-mind` recomputes epistemic status for the whole population once
  per queued event.