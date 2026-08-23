#!/usr/bin/env node
/**
 * Build the installable tarballs the instance serves at /downloads — the ONLY way a user who
 * has no access to this repository gets @tcms/cli or a local @tcms/mcp.
 *
 *     node scripts/pack-release.mjs --version g66280d6 --out ../release
 *
 * THE PROBLEM THIS SOLVES, because a plain `npm pack` looks like it already does the job and
 * produces a tarball that cannot be installed anywhere. Both shipped packages depend on
 * `@tcms/client`, which is a WORKSPACE member and is published to no registry. `npm pack -w
 * @tcms/cli` emits a manifest saying `"@tcms/client": "0.1.0"`, npm resolves that against
 * registry.npmjs.org, and the install dies with E404 on a scope nobody owns. It packs green.
 *
 * So the client is BUNDLED: extracted into the staged package's own `node_modules` and named in
 * `bundleDependencies`, which makes npm carry it inside the tarball and never look it up. The
 * two public dependencies (`openapi-fetch`, `@modelcontextprotocol/sdk`) are deliberately NOT
 * bundled — they exist on the registry, and vendoring them would freeze their patch stream.
 *
 * WHY A STAGING DIRECTORY rather than adding `bundleDependencies` to the real package.json:
 * `packages/<member>/node_modules/@tcms/client` is a workspace SYMLINK. Bundling a symlinked dep is
 * npm-version-dependent behaviour, and the failure mode is a tarball that packs green and is
 * missing the dependency — the same silent shape as the E404 above. Staging copies real bytes,
 * so what is in the tarball is decided here and not by npm's link handling.
 *
 * --verify (default ON) is what makes any of the above a claim rather than a hope: it installs
 * each tarball into a scratch prefix and RUNS the binary. Needs network for the two registry
 * dependencies. `--no-verify` exists for an offline box; CI must never pass it.
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** The packages that reach a user. `client` is packed too, but only to be bundled into these. */
const SHIPPED = [
  {
    dir: 'cli',
    file: 'tcms-cli',
    bin: 'tcms',
    entry: 'dist/main.js',
    smoke: ['help'],
    blurb: 'The command line client. Reads and writes the test repository as the user whose API token it carries.',
  },
  {
    dir: 'mcp',
    file: 'tcms-mcp',
    bin: 'tcms-mcp',
    entry: 'dist/main.js',
    smoke: ['--help'],
    blurb: 'The MCP server, for running locally over stdio beside your agent.',
  },
];

const { values } = parseArgs({
  options: {
    version: { type: 'string' },
    out: { type: 'string', default: 'release' },
    verify: { type: 'boolean', default: true },
  },
});

/**
 * Two shapes, matching deploy.yml's `preflight` exactly: a bare semver for a dispatched release,
 * or `g<sha>` for a lab build. Re-checked here rather than trusted, because this value becomes a
 * FILENAME under a public web root — a `../` in it would write outside the output directory.
 */
const release = values.version ?? '';
if (!/^([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?|g[0-9a-f]{7,40})$/.test(release)) {
  fail(`--version '${release}' is neither a bare semver (0.1.2) nor a commit tag (g<7-40 hex>)`);
}

const out = resolve(process.cwd(), values.out);

function fail(message) {
  console.error(`pack-release: ${message}`);
  process.exit(1);
}

function run(command, args, cwd, { quiet = false } = {}) {
  // stderr is inherited so npm's own diagnostics reach the CI log — except for the smoke runs,
  // whose whole output is a help screen nobody needs printed four times.
  return execFileSync(command, args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', quiet ? 'pipe' : 'inherit'],
  });
}

/** `npm pack --json` returns an array of one; the human form writes notices to stderr. */
function pack(cwd, destination) {
  const [result] = JSON.parse(run('npm', ['pack', '--json', '--ignore-scripts', '--pack-destination', destination], cwd));
  return join(destination, result.filename);
}

/**
 * npm requires `version` to be semver, and a lab tag is not one. Build metadata (`+g66280d6`) is
 * valid semver 2.0, npm accepts it in a manifest, and semver comparison IGNORES it — which is
 * what lets the bundled client keep satisfying the dependency range below.
 */
function stamp(base) {
  return /^[0-9]+\.[0-9]+\.[0-9]+/.test(release) ? release : `${base}+${release}`;
}

const manifestOf = (dir) => JSON.parse(readFileSync(join(ROOT, dir, 'package.json'), 'utf8'));

// A build is not optional and its absence must not be a subtle failure: without dist/ every
// tarball below packs successfully and contains a package.json and nothing else.
for (const { dir } of [...SHIPPED, { dir: 'client' }]) {
  try {
    readdirSync(join(ROOT, dir, 'dist'));
  } catch {
    fail(`${dir}/dist is missing — run 'npm run build' at the workspace root first`);
  }
}

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const stage = mkdtempSync(join(tmpdir(), 'tcms-pack-'));
process.on('exit', () => rmSync(stage, { recursive: true, force: true }));

const clientVersion = stamp(manifestOf('client').version);
const clientStage = join(stage, 'client');
cpSync(join(ROOT, 'client'), clientStage, { recursive: true, filter: (p) => !p.includes('node_modules') });
writeManifest(clientStage, (m) => ({ ...m, version: clientVersion }));
const clientTarball = pack(clientStage, stage);

function writeManifest(dir, edit) {
  const manifest = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  writeFileSync(join(dir, 'package.json'), `${JSON.stringify(edit(manifest), null, 2)}\n`);
}

const shipped = [];
for (const pkg of SHIPPED) {
  const source = join(ROOT, pkg.dir);
  const target = join(stage, pkg.dir);
  // `files: ["dist"]` is what npm would honour anyway; copying only what ships keeps the
  // staged tree from carrying src/ and the test corpus into a public download by accident.
  mkdirSync(target, { recursive: true });
  cpSync(join(source, 'dist'), join(target, 'dist'), { recursive: true });
  cpSync(join(source, 'package.json'), join(target, 'package.json'));

  // The client's own dependencies are merged in, because the bundled copy's imports have to
  // resolve and npm will not resolve them on its behalf (see below).
  const declared = { ...manifestOf('client').dependencies, ...manifestOf(pkg.dir).dependencies };
  delete declared['@tcms/client'];
  writeManifest(target, (m) => ({ ...m, version: stamp(m.version), dependencies: declared }));

  // Materialize the real registry closure into the staged tree, then bundle ALL of it.
  run('npm', ['install', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund', '--no-package-lock'], target);

  const vendored = join(target, 'node_modules', '@tcms', 'client');
  mkdirSync(vendored, { recursive: true });
  run('tar', ['-xzf', clientTarball, '--strip-components=1', '-C', vendored]);

  /*
    EVERYTHING IN node_modules IS BUNDLED, not just @tcms/client, and the reason is measured
    rather than tidy-minded.

    Bundling the client alone produces a tarball that installs green and then dies at the
    first import. npm installs a bundled dependency AS IT IS and does not resolve what IT
    depends on: `npm install <tarball>` into a project happens to work, because the client's
    `openapi-fetch` lands in the PROJECT's top-level node_modules and Node's upward walk finds
    it there — but `npm install -g <tarball>`, which is the command every document here hands
    out, creates `@tcms/cli/node_modules/openapi-fetch` as an EMPTY DIRECTORY and the binary
    fails with ERR_MODULE_NOT_FOUND on its first run. The two install shapes disagreed, and the
    one that was verified was not the one users are told to run.

    Vendoring the whole closure removes the disagreement: nothing is resolved at install time,
    so both shapes install the same bytes. It also makes the tarball installable with NO
    registry access at all, which is the right property for a self-hosted product whose users
    sit behind corporate proxies — and it means what CI verified is exactly what runs.

    The cost is real and is the reason this is stated at length: `openapi-fetch` and the MCP SDK
    no longer float their patch versions. They move when this repository's lockfile moves.
  */
  const bundled = [];
  for (const entry of readdirSync(join(target, 'node_modules'))) {
    if (entry.startsWith('.')) continue;
    if (entry.startsWith('@')) {
      for (const scoped of readdirSync(join(target, 'node_modules', entry))) bundled.push(`${entry}/${scoped}`);
    } else {
      bundled.push(entry);
    }
  }
  bundled.sort();

  writeManifest(target, (m) => ({
    ...m,
    // npm requires a bundled package to be declared as a dependency, transitive ones included.
    // Pinned to the exact version staged here rather than to the range it came from: the range
    // is what a resolver would use, and nothing resolves these any more.
    dependencies: Object.fromEntries(
      bundled.map((name) => [
        name,
        name === '@tcms/client'
          ? clientVersion
          : JSON.parse(readFileSync(join(target, 'node_modules', name, 'package.json'), 'utf8')).version,
      ]),
    ),
    bundleDependencies: bundled,
  }));

  writeFileSync(join(target, 'README.md'), readme(pkg));

  const packed = pack(target, out);
  const named = join(out, `${pkg.file}-${release}.tgz`);
  // For a bare-semver release npm's own filename IS the stable name — `@tcms/cli` at 0.9.0 packs
  // as `tcms-cli-0.9.0.tgz` — and copying a file onto itself is ERR_FS_CP_EINVAL, not a no-op.
  // Only a lab build (`g<sha>`, staged as `0.1.0+g<sha>`) actually needs the rename, which is why
  // this held for every lab build and broke on the first dispatched version.
  if (packed !== named) {
    cpSync(packed, named);
    rmSync(packed);
  }
  // A SECOND COPY, not a symlink. The stable URL is the one every document hands out, and a
  // symlink survives neither `rsync -a` into a web root nor Caddy's file_server by default.
  cpSync(named, join(out, `${pkg.file}-latest.tgz`));
  shipped.push({ ...pkg, named });
}

function readme(pkg) {
  return `# @tcms/${pkg.dir}

${pkg.blurb}

Version \`${release}\`, served by the TCMS instance you downloaded it from. It speaks that
instance's API — install the copy from the instance you use, not from another one.

## Install

    npm install -g https://<your-tcms-host>/downloads/${pkg.file}-latest.tgz

## Credentials

Both come from the environment; there is no config file and no keychain.

    export TCMS_URL=https://<your-tcms-host>
    export TCMS_TOKEN=<an API token issued to you in the web UI>

Then \`${pkg.bin}${pkg.dir === 'cli' ? ' help' : ' --help'}\`.
`;
}

// ---- Verify: install each tarball into a scratch prefix and RUN it ----------------------
//
// Everything above is a claim about what npm will do at install time. This is the part that
// checks it, and it has caught the exact failure the bundling exists to prevent.
if (values.verify) {
  const scratch = mkdtempSync(join(tmpdir(), 'tcms-verify-'));
  process.on('exit', () => rmSync(scratch, { recursive: true, force: true }));
  writeFileSync(join(scratch, 'package.json'), '{"name":"verify","private":true}\n');

  /*
    BOTH INSTALL SHAPES, because they lay the tree out differently and they have already
    disagreed: a `-g` install puts a package's dependencies under the package itself, a local
    one hoists them to the project root, and a tarball can satisfy the second while failing the
    first. `-g` is the shape every document here hands out, so it is the one that must pass;
    the local shape is checked too because it is what a CI job would write.

    `--prefix` keeps the global install inside the scratch directory — nothing here may write
    to the machine's real global prefix.
  */
  const shapes = [
    { label: 'npm install -g', args: (t) => ['install', '-g', '--prefix', join(scratch, 'global'), t] },
    { label: 'npm install', args: (t) => ['install', '--no-save', t] },
  ];

  for (const pkg of shipped) {
    for (const shape of shapes) {
      try {
        run('npm', [...shape.args(pkg.named), '--no-audit', '--no-fund'], scratch);
      } catch {
        fail(`${pkg.file} could not be installed by \`${shape.label}\``);
      }
      const root =
        shape.label === 'npm install -g'
          ? join(scratch, 'global', 'lib', 'node_modules')
          : join(scratch, 'node_modules');
      try {
        run(process.execPath, [join(root, '@tcms', pkg.dir, pkg.entry), ...pkg.smoke], scratch, { quiet: true });
      } catch {
        fail(
          `${pkg.file} installed by \`${shape.label}\` but '${pkg.bin} ${pkg.smoke.join(' ')}' failed — ` +
            'a runtime dependency is missing from the tarball',
        );
      }
      console.log(`verified: ${pkg.file} — ${shape.label}`);
    }
  }
}

// ---- The page and the checksums --------------------------------------------------------
writeFileSync(join(out, 'index.html'), page());

const sums = readdirSync(out)
  .filter((f) => f.endsWith('.tgz'))
  .sort()
  .map((f) => `${createHash('sha256').update(readFileSync(join(out, f))).digest('hex')}  ${f}`)
  .join('\n');
writeFileSync(join(out, 'SHA256SUMS'), `${sums}\n`);

console.log(`packed ${release} into ${out}`);

/**
 * The download page. It is served from the instance, so the host it should tell people to use is
 * the one they are reading it on — `location.origin`, filled in at render time. A hardcoded host
 * would be wrong on every instance but one, and this file ships to all of them.
 */
function page() {
  const rows = SHIPPED.map(
    (p) => `
      <section>
        <h2>@tcms/${p.dir}</h2>
        <p>${p.blurb}</p>
        <pre><code>npm install -g <span class="origin">https://your-tcms-host</span>/downloads/${p.file}-latest.tgz</code></pre>
        <p class="pin">Pin this build instead: <code><span class="origin">https://your-tcms-host</span>/downloads/${p.file}-${release}.tgz</code></p>
      </section>`,
  ).join('');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TCMS command line and MCP downloads</title>
<style>
  :root { color-scheme: light dark; --fg: #16181d; --bg: #fff; --muted: #5b6270; --line: #e3e6ec; --code: #f5f6f8; }
  @media (prefers-color-scheme: dark) { :root { --fg: #e6e8ee; --bg: #14161a; --muted: #99a1b0; --line: #2a2e37; --code: #1d2027; } }
  body { margin: 0 auto; padding: 3rem 1.25rem 6rem; max-width: 46rem; background: var(--bg); color: var(--fg);
         font: 16px/1.6 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
  h2 { font-size: 1.1rem; margin: 2.5rem 0 .25rem; }
  p { margin: .5rem 0; }
  .lede, .pin, footer { color: var(--muted); }
  .pin, footer { font-size: .875rem; }
  pre { background: var(--code); border: 1px solid var(--line); border-radius: 6px; padding: .75rem 1rem; overflow-x: auto; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .875rem; }
  footer { margin-top: 3rem; border-top: 1px solid var(--line); padding-top: 1rem; }
</style>
</head>
<body>
<h1>TCMS tools</h1>
<p class="lede">Build <code>${release}</code>. Node 22.12 or newer.</p>
${rows}
<h2>Credentials</h2>
<p>Both tools read the environment. Issue an API token from your profile in the web UI.</p>
<pre><code>export TCMS_URL=<span class="origin">https://your-tcms-host</span>
export TCMS_TOKEN=&lt;your API token&gt;</code></pre>
<h2>MCP without installing anything</h2>
<p>This instance also serves the MCP server over HTTP. Point an MCP host at the URL below and send
your API token as the bearer; there is nothing to install and nothing to keep up to date.</p>
<pre><code><span class="origin">https://your-tcms-host</span>/mcp</code></pre>
<footer><a href="/">Back to TCMS</a> · <a href="/downloads/SHA256SUMS">SHA256SUMS</a></footer>
<script>
  for (const el of document.querySelectorAll('.origin')) el.textContent = location.origin;
</script>
</body>
</html>
`;
}
