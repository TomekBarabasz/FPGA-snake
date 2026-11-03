// hw/build.sbt - gra snake na FPGA.
//   snake/        logika niezalezna od plytki (gra, VGA/HDMI, sterowanie)
//   demo/<plytka> top-levele per plytka: tangnano9k (GW1NR-9), icesugar (iCE40UP5K)
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

// ---- logika gry (niezalezna od plytki) -----------------------------------------

lazy val snake = Project("snake", file("snake"))
  .settings(hwLayout, forkSettings, libraryDependencies ++= spinal, publish / skip := true)
  .dependsOn(core, vertebra % "test->compile")

// ---- plytki ------------------------------------------------------------------

// run forkowany z katalogu plytki (forkSettings): Conventions.spinal.targetDirectory
// = "hw/gen" laduje w demo/<plytka>/hw/gen. Synteza: Makefile w demo/<plytka>.
def board(id: String) = Project(s"demo-$id", file(s"demo/$id"))
  .settings(hwLayout, forkSettings, libraryDependencies ++= spinal, publish / skip := true)
  .dependsOn(core, snake)

lazy val tangnano9k = board("tangnano9k")
lazy val icesugar   = board("icesugar")

// test z hw/ odpala tez testy bibliotek (aggregate hwlib).
lazy val root = (project in file("."))
  .aggregate(hwlib, snake, tangnano9k, icesugar)
  .settings(publish / skip := true)
