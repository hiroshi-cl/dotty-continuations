ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.3"

lazy val library = (project in file("library"))
  .settings(
    name := "dotty-continuations-library",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val plugin = (project in file("plugin"))
  .dependsOn(library)
  .settings(
    name := "dotty-continuations-plugin",
    libraryDependencies += scalaOrganization.value %% "scala3-compiler" % scalaVersion.value % "provided",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true
  )

lazy val integrationTests = (project in file("integration-tests"))
  .dependsOn(library)
  .settings(
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val reifyReflect = (project in file("sandbox/reify-reflect/core"))
  .dependsOn(library)
  .settings(
    name := "dotty-continuations-reify-reflect",
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val reifyReflectCats = (project in file("sandbox/reify-reflect/cats"))
  .dependsOn(reifyReflect)
  .settings(
    name := "dotty-continuations-reify-reflect-cats",
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.12.0",
      "org.typelevel" %% "cats-effect" % "3.5.4" % Test,
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val reifyReflectScalaz = (project in file("sandbox/reify-reflect/scalaz"))
  .dependsOn(reifyReflect)
  .settings(
    name := "dotty-continuations-reify-reflect-scalaz",
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies ++= Seq("org.scalaz" %% "scalaz-core" % "7.3.8", "org.scalameta" %% "munit" % "1.0.0" % Test),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val reifyReflectZio = (project in file("sandbox/reify-reflect/zio"))
  .dependsOn(reifyReflect)
  .settings(
    name := "dotty-continuations-reify-reflect-zio",
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.9",
      "dev.zio" %% "zio-test" % "2.1.9" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.9" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val shiftReset = (project in file("sandbox/shift-reset"))
  .dependsOn(library)
  .settings(
    name := "dotty-continuations-shift-reset",
    publish / skip := true,
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}", s"-J-Dts=${jar.lastModified()}")
    },
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val negTest = taskKey[Unit]("Run negative compilation tests")

lazy val integrationTestsNeg = (project in file("integration-tests-neg"))
  .dependsOn(library)
  .settings(
    name := "dotty-continuations-integration-tests-neg",
    publish / skip := true,
    libraryDependencies += scalaOrganization.value %% "scala3-compiler" % scalaVersion.value % Test,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true,
    Test / javaOptions ++= {
      val testCp = (Test / fullClasspath).value.files.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val libraryCp =
        (library / Compile / fullClasspath).value.files.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
      val fixturesDir = (Test / resourceDirectory).value / "neg"
      Seq(
        s"-Dneg.testClasspath=$testCp",
        s"-Dneg.libraryClasspath=$libraryCp",
        s"-Dneg.pluginJar=$pluginJar",
        s"-Dneg.fixturesDir=${fixturesDir.getAbsolutePath}"
      )
    }
  )

integrationTestsNeg / negTest :=
  (integrationTestsNeg / Test / test).value

lazy val root = (project in file("."))
  .aggregate(
    library,
    plugin,
    integrationTests,
    reifyReflect,
    reifyReflectCats,
    reifyReflectScalaz,
    reifyReflectZio,
    shiftReset
  )
  .settings(name := "dotty-continuations", publish / skip := true, negTest := (integrationTestsNeg / negTest).value)
