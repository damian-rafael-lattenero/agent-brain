// ast-nu.mjs — THE AST LAYER of the giant linter (the regex rules' upgrade,
// the 22/11 plan's last item): parses a .nu file with the OFFICIAL
// tree-sitter-nu grammar (web-tree-sitter + the built wasm, zero native
// deps) and reports the structural findings the line regexes can only
// guess. Output: one JSON line { chains: [{ line, text }] } — the
// ctrl_if nodes whose else-if is an ERROR child (the grammar models
// nushell's else-if-as-nested-if by erroring on the `else if` tokens, so
// an ERROR child IS the chain signature, at real syntax level).
// Exit 0 with findings; exit 3 when the AST layer itself is unavailable
// (node/wasm missing — the caller falls back to the regex rules).
import { Parser, Language } from 'web-tree-sitter';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const file = process.argv[2];
if (!file) { console.error('usage: ast-nu.mjs <file.nu>'); process.exit(3); }

let src;
try {
  src = readFileSync(file, 'utf8');
} catch {
  console.error(`unreadable: ${file}`);
  process.exit(3);
}

let parser;
try {
  await Parser.init();
  const nu = await Language.load(join(here, 'tree-sitter-nu.wasm'));
  parser = new Parser();
  parser.setLanguage(nu);
} catch (e) {
  console.error(`ast unavailable: ${e.message}`);
  process.exit(3);
}

const tree = parser.parse(src);
const chains = [];
const walk = (node) => {
  if (node.type === 'ctrl_if') {
    // THE REAL SHAPE (measured 22/11 on a true condition): the grammar
    // NESTS the else-if as a ctrl_if CHILD of the outer ctrl_if, next to
    // the `else` token node. (An empty condition parses as an ERROR node
    // instead — that shape is an artifact, not the detection target.)
    const kids = node.children.map((c) => c.type);
    if (kids.includes('else') && kids.includes('ctrl_if')) {
      chains.push({
        line: node.startPosition.row + 1,
        text: node.text.slice(0, 90),
      });
    }
  }
  for (const child of node.children) walk(child);
};
walk(tree.rootNode);

console.log(JSON.stringify({ chains }));
