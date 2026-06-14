package integrationTests.neg

import java.io.File
import java.nio.file.Files
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class NegativeCompilationSuite extends munit.FunSuite:

  private val ErrorSnippet = raw"//\s*error:\s*(.*)".r

  private def requiredProp(name: String): String =
    sys.props.getOrElse(name, fail(s"missing system property: $name"))

  private lazy val fixturesDir = File(requiredProp("neg.fixturesDir"))
  private lazy val testClasspath = requiredProp("neg.testClasspath")
  private lazy val libraryClasspath = requiredProp("neg.libraryClasspath")
  private lazy val pluginJar = requiredProp("neg.pluginJar")

  private def javaExe: String =
    val bin = File(sys.props("java.home"), "bin")
    File(bin, if sys.props("os.name").startsWith("Windows") then "java.exe" else "java").getAbsolutePath

  private def expectedSnippets(source: String): List[String] =
    source.linesIterator.collect { case ErrorSnippet.unanchored(snippet) => snippet.trim }.toList

  private def compileFixture(fixture: File): (Int, String) =
    val outDir = Files.createTempDirectory("dotty-continuation-neg").toFile
    val command = Seq(
      javaExe,
      "-cp",
      testClasspath,
      "dotty.tools.dotc.Main",
      "-color:never",
      "-classpath",
      libraryClasspath,
      s"-Xplugin:$pluginJar",
      "-d",
      outDir.getAbsolutePath,
      fixture.getAbsolutePath
    )
    val output = StringBuilder()
    val exitCode = Process(command).!(ProcessLogger(line => output.append(line).append('\n')))
    (exitCode, output.toString)

  private val fixtures =
    Option(fixturesDir.listFiles()).getOrElse(Array.empty[File]).filter(_.getName.endsWith(".scala")).sortBy(_.getName).toList

  test("negative fixtures directory is not empty") {
    assert(fixtures.nonEmpty, s"expected at least one fixture in $fixturesDir")
  }

  fixtures.foreach { fixture =>
    test(s"${fixture.getName} fails with expected diagnostics") {
      val source = scala.io.Source.fromFile(fixture, "UTF-8")
      val snippets =
        try expectedSnippets(source.mkString)
        finally source.close()

      assert(snippets.nonEmpty, s"${fixture.getName} should contain at least one // error: snippet")

      val (exitCode, output) = compileFixture(fixture)
      assert(exitCode != 0, s"${fixture.getName} should fail compilation but succeeded\n$output")
      snippets.foreach { snippet =>
        assert(
          output.contains(snippet),
          s"${fixture.getName} did not contain expected diagnostic snippet:\n$snippet\n\nCompiler output:\n$output"
        )
      }
    }
  }
