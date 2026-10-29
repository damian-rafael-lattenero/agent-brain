package dev.lattenero.brain.db

import kap.ExitCase
import kap.Kap
import kap.bracketCase
import kap.evalGraph
import java.sql.Connection

/**
 * The ONE transaction bracket (plan B1), built on kap's [kap.bracketCase]:
 * commit on [ExitCase.Completed], rollback on failure **or cancellation**,
 * autocommit restored in every case. The release runs in NonCancellable —
 * a cancelled caller still returns a clean connection to the pool.
 *
 * Law (pinned by `InTransactionTest`): a failing statement rolls back, a
 * succeeding one commits, and `autoCommit` is `true` again on success,
 * failure and cancel alike.
 */
suspend fun <T> Connection.inTransaction(block: (Connection) -> T): T {
    autoCommit = false
    return bracketCase(
        acquire = { this },
        use = { conn -> Kap { block(conn) } },
        release = { conn, exit ->
            try {
                when (exit) {
                    is ExitCase.Completed<*> -> conn.commit()
                    else -> conn.rollback()
                }
            } finally {
                conn.autoCommit = true
            }
        },
    ).evalGraph()
}

/** [Connection.inTransaction] on a pooled connection. */
suspend fun <T> Database.inTransaction(block: (Connection) -> T): T =
    withConnection { it.inTransaction(block) }
