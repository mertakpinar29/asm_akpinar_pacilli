
import utilities.Time

import java.util.Random
import examples.StochasticChannel.*

@main def mainStochasticChannelSimulation =
  Time.timed:
    println:
      stocChannel.newSimulationTrace(FOLLOWER, new Random)
        .take(10)
        .toList
        .mkString("\n")