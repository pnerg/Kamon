package kamon
package metric
package instrument

import java.time.Duration

import com.typesafe.config.Config
import kamon.metric.instrument.InstrumentFactory.CustomInstrumentSettings


private[kamon] class InstrumentFactory private (
    defaultHistogramDynamicRange: DynamicRange,
    defaultMMCounterDynamicRange: DynamicRange,
    defaultMMCounterSampleRate: Duration,
    customSettings: Map[(String, String), CustomInstrumentSettings]) {

  def buildHistogram(entity: Entity, name: String, dynamicRange: DynamicRange = defaultHistogramDynamicRange): Histogram =
    Histogram(entity, name, instrumentDynamicRange(entity, name, dynamicRange))

  def buildMinMaxCounter(entity: Entity, name: String, dynamicRange: DynamicRange = defaultMMCounterDynamicRange,
      sampleInterval: Duration = defaultMMCounterSampleRate): MinMaxCounter =
    MinMaxCounter(
      entity,
      name,
      instrumentDynamicRange(entity, name, dynamicRange),
      instrumentSampleInterval(entity, name, sampleInterval)
    )

  def buildGauge(entity: Entity, name: String): Gauge =
    Gauge(entity, name)

  def buildCounter(entity: Entity, name: String): Counter =
    Counter(entity, name)


  private def instrumentDynamicRange(entity: Entity, instrumentName: String, dynamicRange: DynamicRange): DynamicRange =
    customSettings.get((entity.category, instrumentName)).fold(dynamicRange) { cs =>
      overrideDynamicRange(dynamicRange, cs)
    }

  private def instrumentSampleInterval(entity: Entity, instrumentName: String, sampleInterval: Duration): Duration =
    customSettings.get((entity.category, instrumentName)).fold(sampleInterval) { cs =>
      cs.sampleInterval.getOrElse(sampleInterval)
    }

  private def overrideDynamicRange(defaultDynamicRange: DynamicRange, customSettings: CustomInstrumentSettings): DynamicRange =
    DynamicRange(
      customSettings.lowestDiscernibleValue.getOrElse(defaultDynamicRange.lowestDiscernibleValue),
      customSettings.highestTrackableValue.getOrElse(defaultDynamicRange.highestTrackableValue),
      customSettings.significantValueDigits.getOrElse(defaultDynamicRange.significantValueDigits)
    )
}

object InstrumentFactory {

  def apply(config: Config): InstrumentFactory = {
    val histogramDynamicRange = readDynamicRange(config.getConfig("default-settings.histogram"))
    val mmCounterDynamicRange = readDynamicRange(config.getConfig("default-settings.min-max-counter"))
    val mmCounterSampleInterval = config.getDuration("default-settings.min-max-counter.sample-interval")

    val customSettings = config.getConfig("custom-settings")
      .configurations
      .filter(nonEmptyCategories)
      .flatMap(buildCustomInstrumentSettings)

    new InstrumentFactory(histogramDynamicRange, mmCounterDynamicRange, mmCounterSampleInterval, customSettings)
  }

  private def nonEmptyCategories(entry: (String, Config)): Boolean = entry match {
    case (_, config) => config.firstLevelKeys.nonEmpty
  }

  private def buildCustomInstrumentSettings(entry: (String, Config)): Map[(String, String), CustomInstrumentSettings] = {
    val (category, categoryConfig) = entry
    categoryConfig.configurations.map {
      case (instrumentName, instrumentConfig) => (category, instrumentName) -> readCustomSettings(instrumentConfig)
    }
  }

  private def readDynamicRange(config: Config): DynamicRange =
    DynamicRange(
      lowestDiscernibleValue = config.getLong("lowest-discernible-value"),
      highestTrackableValue = config.getLong("highest-trackable-value"),
      significantValueDigits = config.getInt("significant-value-digits")
    )


  private case class CustomInstrumentSettings(
    lowestDiscernibleValue: Option[Long],
    highestTrackableValue: Option[Long],
    significantValueDigits: Option[Int],
    sampleInterval: Option[Duration]
  )

  private def readCustomSettings(config: Config): CustomInstrumentSettings =
    CustomInstrumentSettings(
      if (config.hasPath("lowest-discernible-value")) Some(config.getLong("lowest-discernible-value")) else None,
      if (config.hasPath("highest-trackable-value")) Some(config.getLong("highest-trackable-value")) else None,
      if (config.hasPath("significant-value-digits")) Some(config.getInt("significant-value-digits")) else None,
      if (config.hasPath("sample-interval")) Some(config.getDuration("sample-interval")) else None
    )
}