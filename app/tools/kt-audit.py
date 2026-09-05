#!/usr/bin/env python3
"""Cheap sanity checks for the Kotlin sources, on a machine with no Android SDK.

NOT a compiler. It only makes checks it can make reliably, because the first
version of this script produced 45 findings of which 44 were false, and a tool
that cries wolf is worse than no tool -- it trains you to skip the output.

What it checks, and why each one is here:
  1. Brace balance.
  2. A property declared twice inside the same class body. This has happened
     twice, both times by inserting a property directly above another one's
     accessor and orphaning it.
  3. References to fields that were deleted from the data classes -- the
     failure mode of any field rename, and invisible here until the far side
     compiles it.

See ../../indian-canbus/TOOLING-GAPS.md, gap 1.

Usage:  python3 tools/kt-audit.py
"""
import re, sys, glob, os

SRC = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'java',
                   'dk', 'agesen', 'springfield')

# Fields removed from the data classes, with the date and the reason, so a hit
# explains itself instead of just failing.
REMOVED = {
    'brakeFront': '2026-09-05: one brake signal on this bus, either control works it',
    'horn':       '2026-09-05: proven not on the bus at all',
}

files = sorted(glob.glob(os.path.join(SRC, '*.kt')))
problems = []

for f in files:
    src = open(f, errors='ignore').read()
    lines = src.split('\n')
    name = os.path.basename(f)

    depth = src.count('{') - src.count('}')
    if depth:
        problems.append(f"{name}: brace imbalance {depth:+d}")

    # Duplicates among CLASS PROPERTIES only -- brace depth exactly 1, i.e. the
    # class body and not the inside of any function.
    #
    # Two earlier versions of this check counted local variables too and
    # reported 52 findings, all false: `val colour` inside two different draw
    # functions is two variables, not a redeclaration. Depth is the only thing
    # that separates them, and indentation is not depth.
    cls, seen, depth_now = None, {}, 0
    for i, ln in enumerate(lines, 1):
        m = re.match(r'^(?:data |sealed |abstract |open |)class (\w+)', ln)
        if m:
            cls, seen = m.group(1), {}
        opened = ln.count('{')
        closed = ln.count('}')
        # A property line is judged at the depth BEFORE its own braces open.
        if cls and depth_now == 1:
            p = re.match(r'^\s+(?:private |internal |protected |)(?:var|val) (\w+)\s*[:=]', ln)
            if p:
                k = p.group(1)
                if k in seen:
                    problems.append(f"{name}:{i}: property '{k}' declared twice in {cls} (first line {seen[k]})")
                seen[k] = i
        depth_now += opened - closed

    # 4. `const val` outside a companion object / object / top level.
    #
    # Kotlin allows const only where it can be resolved at compile time without
    # an instance, so one written straight into a class body does not compile.
    # Added 2026-09-05 after making exactly that mistake: the constant was put
    # beside the property it was used with, which reads perfectly and is
    # illegal. The audit was silent, which is the failure this file exists to
    # prevent.
    depth, in_holder = 0, []
    for i, ln in enumerate(lines, 1):
        stripped = ln.strip()
        if re.match(r'^(private |internal |)const val ', stripped):
            # Top level (no enclosing brace) is legal and common -- the BIT_
            # constants in BikeState.kt live there. Only flag a const that is
            # nested inside something which is not a companion object or object.
            # The first version of this check missed that and reported eight
            # false positives on perfectly legal code, which is the third time
            # today a checker here has cried wolf before being trusted.
            if in_holder and not any(in_holder):
                problems.append(f"{name}:{i}: `const val` in a class body -- "
                                "Kotlin needs it in a companion object, an object, or at top level")
        opened = ln.count('{')
        closed = ln.count('}')
        if opened:
            holder = bool(re.search(r'\b(companion object|^object |\bobject )', stripped))
            in_holder.extend([holder] * opened)
        for _ in range(min(closed, len(in_holder))):
            in_holder.pop()

    for i, ln in enumerate(lines, 1):
        if ln.lstrip().startswith(('//', '*')):
            continue
        for gone, why in REMOVED.items():
            if re.search(r'[.?]%s\b' % gone, ln):
                problems.append(f"{name}:{i}: uses removed field '{gone}' -- {why}")

print(f"{len(files)} files checked.")
if problems:
    print(f"\n{len(problems)} problem(s):")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("Nothing found.")
