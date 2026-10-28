package dev.lattenero.brain.db

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

class MigrationsTest {
    @Test
    fun `render substitutes the embedding dimension everywhere`() {
        val sql = "a vector(__EMBED_DIM__) b vector(__EMBED_DIM__) '__EMBED_DIM__'"
        assertThat(Migrations.render(sql, 768)).isEqualTo("a vector(768) b vector(768) '768'")
    }

    @Test
    fun `render refuses leftovers and non-positive dimensions`() {
        assertThatThrownBy { Migrations.render("x __OTHER_THING__ __EMBED_DIM__", 3) }
            .isInstanceOf(MigrationException::class.java)
            .hasMessageContaining("OTHER_THING")
        assertThatThrownBy { Migrations.render("x", 0) }
            .isInstanceOf(MigrationException::class.java)
            .hasMessageContaining("positive")
    }

    @Test
    fun `validate enforces the naming scheme and strictly increasing numbers`() {
        Migrations.validate(listOf(Migration("0001_init.sql", ""), Migration("0002_more_tables.sql", "")))
        assertThatThrownBy { Migrations.validate(listOf(Migration("init.sql", ""))) }
            .isInstanceOf(MigrationException::class.java)
            .hasMessageContaining("NNNN_snake_case")
        assertThatThrownBy { Migrations.validate(listOf(Migration("0002_b.sql", ""), Migration("0001_a.sql", ""))) }
            .isInstanceOf(MigrationException::class.java)
            .hasMessageContaining("strictly increase")
        assertThatThrownBy { Migrations.validate(listOf(Migration("0001_a.sql", ""), Migration("0001_b.sql", ""))) }
            .isInstanceOf(MigrationException::class.java)
    }

    @Test
    fun `the classpath source lists the shipped migrations in order`() {
        val names = ClasspathMigrations.list().map { it.name }
        assertThat(names).startsWith("0001_init.sql")
        assertThat(ClasspathMigrations.list().first().sql).contains("CREATE EXTENSION IF NOT EXISTS vector")
    }

    @Test
    fun `index txt and the migrations directory never drift apart`() {
        val dirUrl = ClasspathMigrations::class.java.classLoader.getResource(ClasspathMigrations.DIR)
        assertThat(dirUrl).withFailMessage("migrations dir missing from the classpath").isNotNull
        val onDisk = File(dirUrl!!.toURI()).listFiles { file -> file.name.endsWith(".sql") }!!.map { it.name }.sorted()
        val indexed = ClasspathMigrations.list().map { it.name }.sorted()
        assertThat(indexed).withFailMessage("index.txt lists %s but the directory has %s", indexed, onDisk).isEqualTo(onDisk)
    }
}
