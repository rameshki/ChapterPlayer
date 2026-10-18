# Fix-round prompt template (tests first; built by Claude, sent with scripts/oc-run.ps1)

This shape took a 500-line first draft from 9 failing tests to 0 in four calls of about 40 seconds each. Fill every part; keep the whole prompt under ~12,000 characters.

```
You are fixing one Kotlin file (round N). Package <package>. <Types defined in this same package> are never imported. <Only imports that may be needed.>

RULES.
<the rules for this file only, each with its exact value or example; copy from the spec>

CURRENT FILE <Name.kt> (<compiles / does NOT compile yet; K of M tests pass>):
```kotlin
<full current file>
```

COMPILE ERRORS (only if it does not compile): <exact compiler lines>
FIX: <the exact code shape to use, for example the new signature and the call sites>

<K> tests still FAIL. Failure message and hint for each:
1. "<test name>": expected <X> but was <Y>. Hint: <one sentence naming the cause and the fix>
The tests (item(...), book(...) are helpers that build the inputs):
```kotlin
<only the failing tests, copied exactly>
```

OUTPUT FORMAT: reply with ONLY the complete corrected <Name.kt> in a single ```kotlin code block. No explanation before or after.
```

Header rule: always dictate the file header (the `package` line and the exact import lines, or "no imports") instead of only prohibiting a wrong import; prohibitions were ignored three times in a row.

Rules of thumb: one file per call; hints name the cause, not just the symptom; when the model introduced a compile error give the exact replacement shape in the next round; remove from the RULES anything not needed for the failing tests so the model is not distracted.
