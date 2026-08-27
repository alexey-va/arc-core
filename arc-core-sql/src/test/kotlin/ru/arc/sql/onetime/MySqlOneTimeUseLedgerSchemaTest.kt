package ru.arc.sql.onetime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain

class MySqlOneTimeUseLedgerSchemaTest : FreeSpec({
    "purpose and generated SQL identifiers are bounded before interpolation" {
        shouldThrow<IllegalArgumentException> { MySqlOneTimeUsePartition("Unsafe Purpose") }
        shouldThrow<IllegalArgumentException> { MySqlOneTimeUsePartition("safe", "a".repeat(49)) }
        shouldThrow<IllegalArgumentException> { MySqlOneTimeUsePartition("safe", "table-name") }

        MySqlOneTimeUseLedger.createTableSql("a".repeat(48)) shouldContain
            "CONSTRAINT `${"a".repeat(48)}_status_chk`"
    }

    "shared schema uses binary ASCII comparison for routing and scope" {
        val sql = MySqlOneTimeUseLedger.createTableSql()
        sql shouldContain "`purpose` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin"
        sql shouldContain "`claim_scope` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin"
    }
})
