package org.dbpedia.extraction.util

import java.io.File
import java.net.URL
import java.util.Properties
import java.util.logging.{Level, Logger}

import org.dbpedia.extraction.destinations.formatters.Formatter
import org.dbpedia.extraction.destinations.formatters.UriPolicy._
import org.dbpedia.extraction.mappings.Extractor
import org.dbpedia.extraction.util.Config.{AbstractParameters, MediaWikiConnection, NifParameters, SlackCredentials}
import org.dbpedia.extraction.util.ConfigUtils._
import org.dbpedia.extraction.wikiparser.Namespace

import scala.collection.Map
import scala.io.Codec
import scala.util.{Failure, Success, Try}


class Config(val configPath: String) extends
  Properties(Config.universalProperties)
{

  if(configPath != null)
    this.putAll(ConfigUtils.loadConfig(configPath))

  val logger = Logger.getLogger(getClass.getName)
  /**
    * load two config files:
    * 1. the universal config containing properties universal for a release
    * 2. the extraction job specific config provided by the user
    */

  def getArbitraryStringProperty(key: String): Option[String] = {
    Option(getString(this, key))
  }

  def throwMissingPropertyException(property: String, required: Boolean): Unit ={
    if(required)
      throw new IllegalArgumentException("The following required property is missing from the provided .properties file (or has an invalid format): '" + property + "'")
    else
      logger.log(Level.WARNING, "The following property is missing from the provided .properties file (or has an invalid format): '" + property + "'. It will not factor in.")
  }

  /**
    * get all universal properties, check if there is an override in the provided config file
    */


  // TODO Watch out, this could be a regex
  lazy val source: String = this.getProperty("source", "pages-articles.xml.bz2").trim

  lazy val wikiName: String = this.getProperty("wiki-name", "wiki").trim

  lazy val copyrightCheck: Boolean = Try(this.getProperty("copyrightCheck", "false").toBoolean).getOrElse(false)

  /**
   * Dump directory
   * Note: This is lazy to defer initialization until actually called (eg. this class is not used
   * directly in the distributed extraction framework - DistConfig.ExtractionConfig extends Config
   * and overrides this val to null because it is not needed)
   */
  lazy val dumpDir: File = getValue(this, "base-dir", required = true){ x => new File(x)}

  lazy val parallelProcesses: Int = this.getProperty("parallel-processes", "4").trim.toInt

  lazy val dbPediaVersion: String = parseVersionString(getString(this, "dbpedia-version").trim) match{
    case Success(s) => s
    case Failure(e) => throw new IllegalArgumentException("dbpedia-version option in universal.properties was not defined or in a wrong format", e)
  }

  lazy val wikidataMappingsFile: File = {
    val name = this.getProperty("wikidata-property-mappings-file", "wikidata-property-mappings.json").trim
    new File(dumpDir, name)
  }

  /**
    * The directory where all log files will be stored
    */
  lazy val logDir: Option[File] = Option(getString(this, "log-dir")) match {
    case Some(x) => Some(new File(x))
    case None => None
  }

  /**
    * If set, extraction summaries are forwarded via the API of Slack, displaying messages on a dedicated channel.
    * The URL of the slack webhook to be used
    * the username under which all messages are posted (has to be registered for this webhook?)
    * Threshold of extracted pages over which a summary of the current extraction is posted
    * Threshold of exceptions over which an exception report is posted
    */
  lazy val slackCredentials = Try{
      SlackCredentials(
        webhook = new URL(getString(this, "slack-webhook").trim),
        username = this.getProperty("slack-username").trim,
        summaryThreshold = this.getProperty("slack-summary-threshold").trim.toInt,
        exceptionThreshold = this.getProperty("slack-exception-threshold").trim.toInt
      )}

  /**
    * Local ontology file, downloaded for speed and reproducibility
    * Note: This is lazy to defer initialization until actually called (eg. this class is not used
    * directly in the distributed extraction framework - DistConfig.ExtractionConfig extends Config
    * and overrides this val to null because it is not needed)
    */
  lazy val ontologyFile: File = getValue(this, "ontology", required = false)(new File(_))

  /**
    * Local mappings files, downloaded for speed and reproducibility
    * Note: This is lazy to defer initialization until actually called (eg. this class is not used
    * directly in the distributed extraction framework - DistConfig.ExtractionConfig extends Config
    * and overrides this val to null because it is not needed)
    */
  lazy val mappingsDir: File = getValue(this, "mappings", required = false)(new File(_))

  lazy val policies: Map[String, Array[Policy]] = parsePolicies(this, "uri-policy")

  lazy val formats: Map[String, Formatter] = parseFormats(this, "format", policies).toMap

  lazy val disambiguations: String = this.getProperty("disambiguations", "page_props.sql.gz")

  /**
    * all non universal properties...
    */
  /**
    * An array of input dataset names (e.g. 'instance-types' or 'mappingbased-literals') (separated by a ',')
    */
  lazy val inputDatasets: Seq[String] = getStrings(this, "input", ",").distinct   //unique collection of names!!!
  /**
    * A dataset name for the output file generated (e.g. 'instance-types' or 'mappingbased-literals')
    */
  lazy val outputDataset: Option[String] = Option(getString(this, "output"))
  /**
    * the suffix of the files representing the input dataset (usually a combination of RDF serialization extension and compression used - e.g. .ttl.bz2 when using the TURTLE triples compressed with bzip2)
    */
  lazy val inputSuffix: Option[String] = Option(getString(this, "suffix"))
  /**
    * same as for inputSuffix (for the output dataset)
    */
  lazy val outputSuffix: Option[String] = Option(getString(this, "output-suffix"))
  /**
    * instead of a defined output dataset name, one can specify a name extension turncated at the end of the input dataset name (e.g. '-transitive' -> instance-types-transitive)
    */
  lazy val datasetnameExtension: Option[String] = Option(getString(this, "name-extension"))

  /**
    * An array of languages specified by the exact enumeration of language wiki codes (e.g. en,de,fr...)
    * or article count ranges ('10000-20000' or '10000-' -> all wiki languages having that much articles...)
    * or '@mappings', '@chapters' when only mapping/chapter languages are of concern
    * or '@downloaded' if all downloaded languages are to be processed (containing the download.complete file)
    * or '@abstracts' to only process languages which provide human readable abstracts (thus not 'wikidata' and the like...)
    */
  lazy val languages: Array[Language] = parseLanguages(dumpDir, getStrings(this, "languages", ","), wikiName)

  /**
    * before processing a given language, check if the download.complete file is present
    */
  lazy val requireComplete: Boolean = this.getProperty("require-download-complete", "false").trim.toBoolean

  /**
    * TODO experimental, ignore for now
    */
  lazy val retryFailedPages: Boolean = this.getProperty("retry-failed-pages", "false").trim.toBoolean

  /**
    * the extractor classes to be used when extracting the XML dumps
    */
  lazy val extractorClasses: Map[Language, Seq[Class[_ <: Extractor[_]]]] = loadExtractorClasses()

  /**
    * namespaces loaded defined by the languages in use (see languages)
    */
  lazy val namespaces: Set[Namespace] = loadNamespaces()

  private def loadNamespaces(): Set[Namespace] = {
    val names = getStrings(this, "namespaces", ",")
    if (names.isEmpty) Set(Namespace.Main, Namespace.File, Namespace.Category, Namespace.Template, Namespace.WikidataProperty)
    // Special case for namespace "Main" - its Wikipedia name is the empty string ""
    else names.map(name => if (name.toLowerCase(Language.English.locale) == "main") Namespace.Main else Namespace(Language.English, name)).toSet
  }

  /**
   * Loads the extractors classes from the configuration.
   * Loads only the languages defined in the languages property
   *
   * @return A Map which contains the extractor classes for each language
   */
  private def loadExtractorClasses() : Map[Language, Seq[Class[_ <: Extractor[_]]]] =
  {
    ExtractorUtils.loadExtractorsMapFromConfig(languages, this)
  }

  private def error(message: String, cause: Throwable = null): IllegalArgumentException = {
    new IllegalArgumentException(message, cause)
  }

  lazy val mediawikiConnection = MediaWikiConnection(
    apiUrl=this.getProperty("mwc-apiUrl", "").trim,
    maxRetries = this.getProperty("mwc-maxRetries", "4").trim.toInt,
    connectMs = this.getProperty("mwc-connectMs", "2000").trim.toInt,
    readMs = this.getProperty("mwc-readMs", "5000").trim.toInt,
    sleepFactor = this.getProperty("mwc-sleepFactor", "1000").trim.toInt
  )

  lazy val abstractParameters = AbstractParameters(
    abstractQuery=this.getProperty("abstract-query", "").trim,
    shortAbstractsProperty = this.getProperty("short-abstracts-property", "rdfs:comment").trim,
    longAbstractsProperty = this.getProperty("long-abstracts-property", "abstract").trim,
    shortAbstractMinLength = this.getProperty("short-abstract-min-length", "200").trim.toInt,
    abstractTags = this.getProperty("abstract-tags", "query,pages,page,extract").trim
  )


  lazy val nifParameters = NifParameters(
    nifQuery=this.getProperty("nif-query", "").trim,
    nifTags = this.getProperty("nif-tags", "parse,text").trim,
    isTestRun = this.getProperty("nif-isTestRun", "false").trim.toBoolean,
    writeAnchor = this.getProperty("nif-write-anchor", "false").trim.toBoolean,
    writeLinkAnchor = this.getProperty("nif-write-link-anchor", "true").trim.toBoolean,
    abstractsOnly = this.getProperty("nif-extract-abstract-only", "true").trim.toBoolean,
    cssSelectorMap = this.getClass.getClassLoader.getResource("nifextractionconfig.json")   //static config file in core/src/main/resources
  )
}

object Config{
  case class NifParameters(
    nifQuery: String,
    nifTags: String,
    isTestRun: Boolean,
    writeAnchor: Boolean,
    writeLinkAnchor: Boolean,
    abstractsOnly: Boolean,
    cssSelectorMap: URL
  )

  case class MediaWikiConnection(
    apiUrl: String,
    maxRetries: Int,
    connectMs: Int,
    readMs: Int,
    sleepFactor: Int
  )

  case class AbstractParameters(
    abstractQuery: String,
    shortAbstractsProperty: String,
    longAbstractsProperty: String,
    shortAbstractMinLength: Int,
    abstractTags: String
  )

  case class SlackCredentials(
     webhook: URL,
     username: String,
     summaryThreshold: Int,
     exceptionThreshold: Int
   )

  private val universalProperties: Properties = loadConfig(this.getClass.getClassLoader.getResource("universal.properties")).asInstanceOf[Properties]
  val universalConfig: Config = new Config(null)

  private val wikisinfoFile = new File(getString(universalProperties , "base-dir", true), WikiInfo.FileName)
  private lazy val wikiinfo = if(wikisinfoFile.exists()) WikiInfo.fromFile(wikisinfoFile, Codec.UTF8) else Seq()
  def wikiInfos = wikiinfo
}
