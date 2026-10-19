# Task spec template for OpenClaw (copy to `claude-link/task-<id>-spec.md`, fill every part)

# Task: <one line: what, in which package>
Use ONLY the Kotlin standard library and `kotlin.test` (or name the libraries allowed). Code in `commonMain` must be common Kotlin: no `java.*`, no `String.format`, no locale-dependent calls; say `Long` or `Int` explicitly wherever overflow could matter.
Work ONLY inside C:\Users\<agent-user>\Shared\claude-link. Write exactly these files into the `out` folder there:
  <File1.kt>, <File2.kt>, <FileTest.kt>
Do not create other files. When finished reply with the single word DONE (or the exact error).

## Package and imports (copy exactly, add nothing else)
package <package of the new files>
<every import line, one per line>
Types defined in the SAME package as your files are never imported.

## Existing types (do not redefine; paste their real declarations; write "None, this is standalone" if there are none)
```kotlin
<declarations>
```

## Signatures to write (exact)
```kotlin
<signatures with parameter and return types>
```

## Rules (each with a worked example: input -> output)
- <rule>: <example>

## Tests to write (list EVERY case with its expected value)
- `<test name as a sentence in backticks>`: <input> -> <expected>
Assertions must check real values (never only sizes or `assertTrue(true)`); include boundary cases and a case that must be EXCLUDED/NOT match.
