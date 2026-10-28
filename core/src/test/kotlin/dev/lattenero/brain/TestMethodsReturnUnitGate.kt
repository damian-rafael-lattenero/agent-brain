package dev.lattenero.brain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * JUnit silently ignores a @Test method whose return type is not void, and
 * Kotlin's `fun x() = runBlocking { assertThat(..) }` returns the assert.
 * That is how four Migrator tests once never ran while the build stayed
 * green. This gate scans every compiled test class and fails naming the
 * offenders, so a lost test is a red build, not a quiet one.
 */
class TestMethodsReturnUnitGate {
    @Test
    fun `every test method returns void so JUnit actually runs it`() {
        val root = File(TestMethodsReturnUnitGate::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(root).isDirectory
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { name -> runCatching { Class.forName(name) }.getOrNull() }
            .flatMap { klass -> klass.declaredMethods.asSequence().filter { it.isTestMethod() && it.returnType != Void.TYPE } }
            .map { "${it.declaringClass.simpleName}.${it.name} returns ${it.returnType.simpleName}" }
            .toList()
        assertThat(offenders).withFailMessage("these @Test methods would be silently skipped by JUnit:\n  %s", offenders.joinToString("\n  ")).isEmpty()
    }

    private fun Method.isTestMethod(): Boolean =
        annotations.any { it.annotationClass.qualifiedName in TEST_ANNOTATIONS }

    companion object {
        val TEST_ANNOTATIONS = setOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.TestFactory",
        )
    }
}
