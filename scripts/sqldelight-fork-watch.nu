#!/usr/bin/env nu
# The sqldelight fork's upstream watch (ADR-007 stewardship): compares the
# upstream release train against the fork's base and says whether a rebase is
# due. Read-only. Exit codes: 0 = current, 10 = drift (a sentinel can page),
# 1 = the upstream query failed.

const PROPS: string = "/Users/lattenero/Projects/sqldelight/dialects/postgresql/gradle.properties"

# The latest upstream release tag, without the leading v.
def upstream-tag []: nothing -> string {
    let release: record = (http get --headers { Accept: "application/vnd.github+json" } "https://api.github.com/repos/sqldelight/sqldelight/releases/latest")
    $release.tag_name | str replace --regex '^v' ''
}

# The fork's base version: VERSION_NAME=2.4.1-brain2 -> 2.4.1.
def fork-base [props: string]: nothing -> string {
    let line: string = (open $props | lines | where {|l| $l | str starts-with 'VERSION_NAME=' } | first)
    $line | split row '=' | get 1 | split row '-' | first
}

def main [
    --props: string = $PROPS # the module properties carrying VERSION_NAME
    --json  # machine output instead of prose
] : nothing -> nothing {
    let latest: string = (try { upstream-tag } catch {|e|
        print -e $"upstream query failed: ($e.msg)"
        exit 1
    })
    let base: string = (fork-base $props)
    let drift: bool = ($latest != $base)
    if $json {
        { upstream: $latest, fork_base: $base, drift: $drift } | to json --raw | print
    } else {
        print $"upstream latest: ($latest) | fork base: ($base)"
        if $drift {
            print $"DRIFT: upstream ($latest) moved past our base ($base) — rebase damian on the release, run the dialect fixtures, bump brainN, re-vendor (ADR-007)"
        } else {
            print "CURRENT: the fork base IS the latest upstream release"
        }
    }
    if $drift { exit 10 }
}
