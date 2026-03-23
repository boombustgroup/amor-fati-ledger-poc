lazy val root = (project in file("."))
  .settings(
    name         := "amor-fati-ledger-poc",
    organization := "com.boombustgroup",
    scalaVersion := "3.7.2", // pinned to Stainless 0.9.9.2 bundled version
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
    ),
    libraryDependencies ++= Seq(
      "org.scalatest"     %% "scalatest"       % "3.2.19"   % Test,
      "org.scalacheck"    %% "scalacheck"      % "1.18.1"   % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
    ),
  )
