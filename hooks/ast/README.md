# ast/ — the AST layer of the giant linter (T-AST, 22/11)

`ast-nu.mjs` + `tree-sitter-nu.wasm`: the OFFICIAL nushell grammar
(nushell/tree-sitter-nu) built to wasm and loaded via web-tree-sitter —
zero native deps. The anti-pattern-lint hook spawns this helper to detect
the else-if chain at SYNTAX level: the grammar NESTS the else-if as a
`ctrl_if` child of the outer `ctrl_if`, next to the `else` token — that
shape IS the chain (one else-if is already a violation; the regex rule's
minHits-2 fallback stays for environments without node).

## Install (the live home is ~/.zcode/hooks/ast/)

```nu
cd ~/.zcode/hooks/ast
npm install web-tree-sitter@0.27.2   # restores node_modules (not in git)
```

## The build flow (how the wasm was made, 22/11)

```nu
mkdir /tmp/ast-probe; cd /tmp/ast-probe; npm init -y
npm install tree-sitter-cli@latest          # prebuilt CLI, no emscripten
git clone --depth 1 https://github.com/nushell/tree-sitter-nu
cd tree-sitter-nu
npx tree-sitter build --wasm                # auto-fetches binaryen; 0.27 self-contained
```

## Measured laws (22/11)

- **The else-if nests**: `else if` = a ctrl_if CHILD of the outer ctrl_if
  (no ERROR node — an ERROR "else if" only appears with an EMPTY
  condition, an artifact shape).
- **Table payloads are eager**: the first detector version put the
  apply-call in a row field — it ran on null. Payloads are thunks.
- **Prose parens inside the hook's own deny message** crashed the deny
  into a fail-open exit 1 (the anti-pattern biting the anti-pattern
  hook).
- **node rides the mise shim at an absolute path** — the hook's env is
  the app's, whose PATH may not carry shims.
- Helper cost: ~1.15 s per call (node boot + wasm load) against the
  hook's 5 s timeout.
