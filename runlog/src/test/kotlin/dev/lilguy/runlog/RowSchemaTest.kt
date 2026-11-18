package dev.lilguy.runlog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RowSchemaTest {
    @Test
    fun `the server's row schema is what the row classes generate`() {
        val shipped = File("../server/schema/rows.schema.json").readText()
        assertEquals(RowSchema.text(), shipped, "server/schema/rows.schema.json is stale: run `make server-schema`")
    }
}
