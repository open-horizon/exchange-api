//addSbtPlugin("org.scalatra.sbt"    % "sbt-scalatra"           % "latest.release")

// Reformats the scala source code when compiling it - this was giving parser errors w/o giving line numbers
//addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.2")

// A fast restart of our rest api svr in sbt. Does NOT require use of spray
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")

// Builds docker image of our exchange svr
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.0")

// To see the current versions being used, uncomment this line, then run:  sbt dependencyTree
//addSbtPlugin("net.virtual-void" % "sbt-dependencpwdy-graph" % "0.8.0")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "5.2.4")
