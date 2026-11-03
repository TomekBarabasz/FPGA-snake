// hw/build.sbt - gra snake na FPGA.
//   snake/        logika niezalezna od plytki (gra, VGA/HDMI, sterowanie)
//   cpu/j1/       CPU J1 (submodule, fork J1Sc)
//   demo/<plytka> top-levele per plytka: tangnano9k (GW1NR-9), icesugar (iCE40UP5K),
//                 mimas_j1 (Mimas V2: J1 + SwapForth + 7-seg)
// Biblioteki: submodule lib/ (github.com/TomekBarabasz/fpga-hw-lib), wciagane
// ze zrodel jako osobny build sbt (ProjectRef) - edycja w lib/ jest widoczna
// od razu, bez publishLocal.
// Wspolne ustawienia (layout, fork, przelaczniki symulacji): lib/sbt/HwBuild.scala,
// dolaczone do meta-buildu przez project/build.sbt.

import HwBuild._

inThisBuild(buildSettings)

// ---- biblioteki (lib/build.sbt) ------------------------------------------------

lazy val hwlib = RootProject(file("lib"))
def libRef(id: String) = ProjectRef(file("lib"), id)

lazy val core     = libRef("core")
lazy val vertebra = libRef("vertebra")
lazy val mimas_v2 = libRef("mimas_v2")

// ---- CPU: submodule cpu/j1 (github.com/TomekBarabasz/J1Sc, galaz newhope) ----
// Fork J1Sc przeniesiony na Spinal 1.12 / Scala 2.13 (cpu/j1/NEWHOPE.md).
// Testy CPU: sbt "{cpu/j1}j1/test".
lazy val j1       = ProjectRef(file("cpu/j1"), "j1")
lazy val j1boards = ProjectRef(file("cpu/j1"), "boards")   // SoC-e plytek, m.in. J1MimasV2Soc

// ---- logika gry (niezalezna od plytki) -----------------------------------------

lazy val snake = Project("snake", file("snake"))
  .settings(hwLayout, forkSettings, libraryDependencies ++= spinal, publish / skip := true)
  .dependsOn(core, j1, vertebra % "test->compile")

// ---- plytki ------------------------------------------------------------------

// run forkowany z katalogu plytki (forkSettings): Conventions.spinal.targetDirectory
// = "hw/gen" laduje w demo/<plytka>/hw/gen. Synteza: Makefile w demo/<plytka>.
def board(id: String) = Project(s"demo-$id", file(s"demo/$id"))
  .settings(hwLayout, forkSettings, libraryDependencies ++= spinal, publish / skip := true)
  .dependsOn(core, snake)

lazy val tangnano9k = board("tangnano9k")
lazy val icesugar   = board("icesugar")

// J1 + SwapForth na Mimas V2 z wyswietlaczem 7-seg (SevenSegMux z lib/mimas_v2).
// Poligon rozszerzen J1: SoC z cpu/j1 + wlasne peryferia na magistrali IO.
// Obraz Fortha (forth16) z cpu/j1/toolchain/forth: J1SCBASE dla run i test.
val j1Base = file("cpu/j1").getAbsoluteFile
lazy val mimasJ1 = Project("demo-mimas-j1", file("demo/mimas_j1"))
  .settings(hwLayout, forkSettings, libraryDependencies ++= spinal, publish / skip := true,
            libraryDependencies += scalatest % Test,
            Test          / envVars += "J1SCBASE" -> j1Base.getPath,
            Compile / run / envVars += "J1SCBASE" -> j1Base.getPath)
  .dependsOn(core, j1boards, mimas_v2)

// test z hw/ odpala tez testy bibliotek i CPU (aggregate hwlib, j1).
lazy val root = (project in file("."))
  .aggregate(hwlib, j1, snake, tangnano9k, icesugar, mimasJ1)
  .settings(publish / skip := true)
