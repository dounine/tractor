val akkaVersion = "2.6.12"
val akkaHttpVersion = "10.2.3"
val json4sVersion = "3.7.0-M6"
val alpakka = "2.0.2"

lazy val app = (project in file("."))
  .enablePlugins(DockerPlugin, JavaServerAppPackaging)
  .settings(
    name := "tractor",
    organization := "com.dounine",
    maintainer := "amwoqmgo@gmail.com",
    scalaVersion := "2.13.4",
    dockerBaseImage := "openjdk:11.0.8-slim",
    //    mainClass in (Compile, run) := Some("com.dounine.tractor.Bootstrap"),
    mappings in Universal ++= {
      import NativePackagerHelper._
      val confFile = buildEnv.value match {
        case BuildEnv.Stage => "stage"
        case BuildEnv.Developement => "dev"
        case BuildEnv.Test => "test"
        case BuildEnv.Production => "prod"
      }
      contentOf("src/main/resources/" + confFile)
    },
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.github.pureconfig" %% "pureconfig" % "0.12.3",
      "eu.timepit" %% "refined" % "0.9.20",
      "com.lightbend.akka" %% "akka-stream-alpakka-file" % alpakka,
      "com.lightbend.akka" %% "akka-stream-alpakka-slick" % alpakka,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-caching" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.lightbend.akka" %% "akka-persistence-jdbc" % "4.0.0",
      "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "org.json4s" %% "json4s-jackson" % json4sVersion,
      "org.json4s" %% "json4s-native" % json4sVersion,
      "org.json4s" %% "json4s-ext" % json4sVersion,
      "com.lightbend.akka.management" %% "akka-lease-kubernetes" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.9",
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.9",
      "com.github.pathikrit" %% "better-files" % "3.9.1",
      "com.github.pathikrit" %% "better-files-akka" % "3.9.1",
      "com.chuusai" %% "shapeless" % "2.3.3",
      "io.underscore" %% "slickless" % "0.3.6",
      "io.altoo" %% "akka-kryo-serialization" % "2.0.1",
      "de.heikoseeberger" %% "akka-http-circe" % "1.35.3",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.35.3",
      "com.esotericsoftware" % "kryo" % "5.0.3",
      "mysql" % "mysql-connector-java" % "8.0.22",
      "commons-codec" % "commons-codec" % "1.15",
      "commons-cli" % "commons-cli" % "1.4",
      "redis.clients" % "jedis" % "3.3.0",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
      "com.typesafe.slick" %% "slick-codegen" % "3.3.3",
      "io.spray" %% "spray-json" % "1.3.6",
      "com.pauldijou" %% "jwt-core" % "4.3.0",
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.scalatest" %% "scalatest" % "3.1.2" % Test
    )
  )
