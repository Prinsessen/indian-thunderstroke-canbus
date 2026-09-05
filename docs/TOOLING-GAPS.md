# Three things missing from how we work

Not bugs. Tools that are not there, each of which cost real time on 2026-09-04 —
a day of 32 firmware builds, 54 commits and six signals found.

Written down because they were named out loud and then left in the conversation,
which is where good observations go to die. The owner asked whether they had been
recorded. They had not.

Ordered by what they would give back soonest.

---

## 1. Nothing compiles the app before it leaves this machine

> **Partly closed 2026-09-05.** `source-code/indian-canbus-app/tools/kt-audit.py`
> now runs here and catches the two mistakes that have actually happened: a
> property declared twice in a class body, and a reference to a field that was
> deleted from the data classes. It is not a compiler and does not pretend to
> be — it only makes checks it can make reliably.
>
> Getting there took three versions. The first reported 45 findings of which 44
> were false, because it looked for a class called `BikeState` when the class is
> `BikeJsonState`. The second reported 52, all false, because it counted local
> variables inside functions as class properties — `val colour` in two different
> draw functions is two variables, and indentation is not scope. Only the third,
> which tracks brace depth and looks at depth 1 alone, is quiet when the code is
> clean. A checker that cries wolf is worse than no checker: it teaches you to
> skip the output, which is the one thing a safety net must never do.
>
> It was then verified against deliberately broken code before being trusted,
> because a test that reports nothing must first be shown capable of reporting
> something.

Kotlin is written here; the Android SDK is not installed here. So changes are
read through carefully and then handed over unverified, and the compiler on the
other side is the first thing that actually checks them.

**What it cost, twice in one day.** The same mistake both times: a new property
inserted directly above another property's `set` line, which left the setter
attached to the wrong one. Kotlin allows exactly one setter per property, and
its complaint -- unresolved `field` and `v` -- points nowhere near the cause.
Each round trip was scp, build, error report, fix, scp again.

The second one was caught here only because an audit script had been written
after the first. That is the wrong order: the script found what a compiler would
have found for free.

**The fix.** Install the Android command-line tools on the server and run
`./gradlew assembleDebug` before handing anything over. Nothing then reaches the
Windows machine that does not compile.

---

## 2. The firmware has no tests at all

The decoding is arithmetic: grips are `byte / 25`, tilt is `byte - 127`,
throttle is `byte * 0.4`, the stand state is a set of thresholds. All of it is
pure, all of it could run on a PC, and none of it is checked by anything except
flashing the bike and riding it.

**What it cost.** The `SETI` macro casts to `int8_t`, which is correct for the
tri-state switch flags it was written for and silently wrong for a 0-255 tilt
reading: every value above 127 became negative, so the field vanished from the
state exactly when the bike leaned right of vertical. A test that passed 200 in
and looked at what came out would have caught it in a second.

Instead it took three firmware versions of adjusting thresholds -- treating the
symptom each time -- and the owner standing in a garage rocking a 400 kg
motorcycle to reproduce it.

Four or five of the day's 32 builds existed only because of this.

**The fix.** Pull the decode arithmetic into functions that take bytes and
return values, with no hardware in them, and test those. The app already does
exactly this: `HeatCurve` and `Dtc` were split apart for the same reason after a
heat-curve bug proved untestable, and they have 34 tests between them. The
firmware has none.

---

## 3. Landscape gets forgotten

There are two layout files per page. Adding a view to one does nothing to the
other, and nothing warns about it.

**What it cost, three times in one day.** The ignition lamp went into portrait
only. So did the ride distance. The throttle would have been the third, and was
caught only because the owner asked "what about landscape?" -- which is not a
mechanism, it is luck.

**The fix.** Twenty lines that list the view IDs present in `layout/` and absent
from `layout-land/`, and vice versa, run before handing anything over. It cannot
know whether a difference is deliberate, but it can make every difference
deliberate.

---

## What these have in common

Each is a place where a machine could check something and a person is checking
it instead — or not checking it at all. The project is unusually careful about
*what it claims*: four signals were withdrawn across three days for being unverified
guesses, and withdrawn signals keep their code and a comment saying why.

That care is applied to the motorcycle's data and not yet to our own work.


---

## 4. Deleting code with a regex, and not reading the diff

Added 2026-09-05, the same evening it cost a build.

`targetAtTyreTemp` was removed from `TyreMemory.Wheel` because nothing displayed
it any more. The deletion was done by matching from the start of its doc comment
to a later anchor — and the span swallowed the `level` property that sat between
them. `level` is what classifies a wheel as OK, WATCH or ACT, and it is read in
four places including the alert banner, so the whole app stopped compiling.

**`kt-audit.py` cannot catch this, and should not pretend to.** It would need to
resolve types to know that `data.level` no longer exists, and a crude version --
flagging any `.name` that nothing in the project declares — would fire on every
Android property in the SDK. That is the wolf-crying failure this file already
records twice.

**What would have caught it costs one command:** `git diff` on the file before
committing. The deletion was verified by re-running the audit, which passed,
rather than by looking at what had actually been removed. The audit answers "is
the shape still legal", not "did you delete what you meant to".

**So: after any scripted deletion, read the diff.** Not the file, not the test
result — the diff. It is the only thing that shows what left.

A note on the other side of it: the Windows build reported the error with the
line, the missing property, the enum that existed without it, and an explicit
refusal to guess the threshold constants, on the grounds that they were a product
decision. That refusal was right. Guessing 1.5 and 3.0 would have compiled and
been quietly wrong, which is worse than a build failure.
