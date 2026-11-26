#!/Users/lattenero/.local/bin/nu-nightly
# backup-pg.nu — THE INSURANCE for the two Postgres databases this house cannot
# rebuild by hand: `agent_brain` (memories, evals, RAG, turn history) and
# `whatsdigest` (the groups' messages). Until now BOTH lived only inside the
# colima volume — one corrupted volume and a year of memory was gone.
#
# Shape: pg_dump in CUSTOM format (-Fc, compressed, pg_restore-able) run INSIDE
# their containers (`docker exec`, so no host password/port plumbing), copied
# out with `docker cp`, and pruned by age. A dump that comes out EMPTY aborts
# the run and says so — the flip-chrome.nu lesson: never rotate over a backup
# you did not verify.
#
# `--drill` proves the backup is restorable: it restores the newest agent_brain
# dump into a scratch database, compares the row counts of the tables that
# matter, and drops the scratch. A backup nobody restored is a hope, not a
# backup.
#
# Exit contract: 0 = every database dumped (and drilled, with --drill);
# 1 = a real failure, spoke LOUD to Telegram (a calendar job does NOT retry a
# non-zero exit, so waiting for pg here would only lose the night's backup).
#
# Installed as a LaunchAgent (com.zcode.pg-backup, 03:40 daily — before the
# 04:00 reindex); run it by hand any time: `nu ~/Projects/agent-brain/scripts/backup-pg.nu`.

use /Users/lattenero/Scripts/nushell-fp/mods/telegram.nu send-telegram

const DOCKER: string = "/opt/homebrew/bin/docker"
const RETENTION_DAYS: int = 14
const DRILL_SCRATCH: string = "agent_brain_drill"

# The type aliases (the owner's 22/11 law): the shapes read once, the
# signatures spell the name.
type SourceDb = record<container: string, user: string, db: string>

# The two databases and the credentials their own images declare.
const SOURCES: list<SourceDb> = [
    { container: "agent-brain-pg", user: "brain", db: "agent_brain" }
    { container: "pg-base", user: "whatsdigest", db: "whatsdigest" }
]

# The tables the drill compares — one per risk surface (memory, corpus, evals).
const DRILL_TABLES: list<string> = ["memories", "documents", "eval_runs"]

def die [msg: string]: nothing -> nothing {
    print -e $"✗ ($msg)"
    send-telegram --html $"❌ <b>BACKUP PG FAILED</b> — ($msg)"
    exit 1
}

def docker-bin []: nothing -> string {
    # launchd starts with a minimal PATH; every external must resolve anyway.
    $env.PATH = ($env.PATH | append [/opt/homebrew/bin /usr/local/bin])
    if not ($DOCKER | path exists) { die "docker is not at /opt/homebrew/bin" }
    $DOCKER
}

def container-up [name: string]: nothing -> bool {
    let r: record = (^$DOCKER ps --format "{{.Names}}" | complete)
    if $r.exit_code != 0 { return false }
    ($r.stdout | lines | any {|n| ($n | str trim) == $name })
}

def stamp []: nothing -> string {
    date now | format date "%Y%m%d-%H%M%S"
}

# One database → one -Fc dump on the host. Refuses an empty dump (exit 1).
def dump-one [src: SourceDb, out_dir: path, ts: string]: nothing -> path {
    let db: string = ($src.db | into string)
    let container: string = ($src.container | into string)
    let user: string = ($src.user | into string)
    let in_box: string = $"/tmp/($db).dump"
    let dest: path = ($out_dir | path join $"($db)-($ts).dump")
    let d: record = (^$DOCKER exec $container pg_dump -U $user -d $db -Fc -f $in_box | complete)
    if $d.exit_code != 0 {
        die $"pg_dump ($db) exit ($d.exit_code): ($d.stderr | str trim | str substring 0..200)"
    }
    let cp: record = (^$DOCKER cp $"($container):($in_box)" $dest | complete)
    if $cp.exit_code != 0 {
        die $"docker cp ($db) exit ($cp.exit_code): ($cp.stderr | str trim | str substring 0..200)"
    }
    let rm: record = (^$DOCKER exec $container rm -f $in_box | complete)
    if $rm.exit_code != 0 { print -e $"⚠ could not remove ($in_box) inside the container" }
    let size: int = (ls $dest | get 0.size | into int)
    if $size <= 0 {
        rm -f $dest
        die $"EMPTY dump for ($db) — nothing saved, nothing rotated"
    }
    print $"✓ ($db) → ($dest) — ($size) bytes"
    $dest
}

# Deletes dumps older than RETENTION_DAYS; returns the removed paths.
def prune [out_dir: path]: nothing -> list<string> {
    let cutoff: datetime = ((date now) - ($RETENTION_DAYS * 1day))
    let files: list<record> = (glob $"($out_dir)/*.dump" | each {|p| ls $p | get 0 | select name modified })
    let old: list<string> = ($files | where {|f| $f.modified < $cutoff } | get name)
    for p in $old {
        rm $p
        print $"· rotated (>($RETENTION_DAYS) days): ($p)"
    }
    $old
}

def psql-count [container: string, user: string, db: string, table: string]: nothing -> string {
    # concatenation, not interpolation: `count(*)` inside $"...(...)..." would
    # parse the parens as a command call (measured twice tonight).
    let sql: string = "select count(*) from " + $table
    let r: record = (^$DOCKER exec $container psql -U $user -d $db -At -c $sql | complete)
    if $r.exit_code != 0 { die $"psql count ($db.$table) exit ($r.exit_code): ($r.stderr | str trim | str substring 0..200)" }
    ($r.stdout | str trim)
}

def psql-admin [sql: string]: nothing -> nothing {
    let r: record = (^$DOCKER exec agent-brain-pg psql -U brain -d postgres -At -c $sql | complete)
    if $r.exit_code != 0 { die $"psql exit ($r.exit_code): ($r.stderr | str trim | str substring 0..200)" }
}

def sample-counts []: nothing -> record {
    mut counts: record = {}
    for table in $DRILL_TABLES {
        let n: string = (psql-count agent-brain-pg brain agent_brain $table)
        $counts = ($counts | insert $table ($n | into int))
    }
    $counts
}

# THE RESTORE DRILL: newest agent_brain dump → scratch DB → it must contain AT
# LEAST everything that existed before the dump (a live DB keeps writing while
# pg_dump runs: equality would race the worker, and "at least" is the honest
# law — a backup may not miss a single pre-existing row). The restore runs
# with parallel index builds OFF: the container's /dev/shm is 64 MB and an
# HNSW build asks for more, which fails as "could not resize shared memory
# segment" — a DRILL-env limit, not a dump problem (a real DR restore needs the
# same flag, so the drill teaches it).
def drill [out_dir: path, before: record]: nothing -> nothing {
    let dumps: list<string> = (glob $"($out_dir)/agent_brain-*.dump" | sort)
    if ($dumps | is-empty) { die "drill: no agent_brain dump exists yet" }
    let newest: string = ($dumps | last)
    print $"— drill on ($newest | path basename)"
    psql-admin $"DROP DATABASE IF EXISTS ($DRILL_SCRATCH)"
    psql-admin $"CREATE DATABASE ($DRILL_SCRATCH)"
    let box_dump: string = $"/tmp/($DRILL_SCRATCH).dump"
    let cp: record = (^$DOCKER cp $newest $"agent-brain-pg:($box_dump)" | complete)
    if $cp.exit_code != 0 { die $"drill: docker cp exit ($cp.exit_code)" }
    let restore: record = (^$DOCKER exec -e "PGOPTIONS=-c max_parallel_maintenance_workers=0" agent-brain-pg pg_restore -U brain -d $DRILL_SCRATCH --no-owner $box_dump | complete)
    if $restore.exit_code != 0 {
        psql-admin $"DROP DATABASE IF EXISTS ($DRILL_SCRATCH)"
        die $"drill: pg_restore exit ($restore.exit_code): ($restore.stderr | str trim | str substring 0..300)"
    }
    for table in $DRILL_TABLES {
        let restored: int = ((psql-count agent-brain-pg brain $DRILL_SCRATCH $table) | into int)
        let preDump: int = ($before | get $table)
        if $restored < $preDump {
            psql-admin $"DROP DATABASE IF EXISTS ($DRILL_SCRATCH)"
            die $"drill: ($table) restored=($restored) but pre-dump had ($preDump) — the dump LOST rows"
        }
        let live: string = (psql-count agent-brain-pg brain agent_brain $table)
        print $"✓ drill: ($table) pre-dump=($preDump) restored=($restored) live-now=($live)"
    }
    psql-admin $"DROP DATABASE IF EXISTS ($DRILL_SCRATCH)"
    print "✅ drill PASS — the backup restores and lost nothing"
}

def main [--drill, --no-prune]: nothing -> nothing {
    docker-bin | ignore
    let out_dir: path = ($nu.home-dir | path join "Backups/pg")
    mkdir $out_dir
    for src in $SOURCES {
        if not (container-up ($src.container | into string)) {
            die $"($src.container) is not running — ($src.db) goes UNBACKED-UP tonight"
        }
    }
    let ts: string = (stamp)
    let before: record = (sample-counts)
    for src in $SOURCES { dump-one $src $out_dir $ts | ignore }
    if not $no_prune {
        let removed: list<string> = (prune $out_dir)
        print $"rotation: ($removed | length) old dumps out"
    }
    if $drill { drill $out_dir $before }
    print $"✅ backups OK — ($out_dir)"
}
