package org.dbpedia.extraction.dump.extract

import org.dbpedia.extraction.mappings.Extractor
import scala.collection.mutable.HashMap
import java.util.Properties
import java.io.File
import org.dbpedia.extraction.wikiparser.Namespace
import scala.collection.JavaConversions.asScalaSet
import scala.collection.immutable.{SortedSet,SortedMap}
import org.dbpedia.extraction.util.{Language,WikiInfo}
import org.dbpedia.extraction.util.Language.wikiCodeOrdering
import org.dbpedia.extraction.util.ConfigUtils.{LanguageRegex,RangeRegex,toRange}
import scala.io.Codec

private class Config(config: Properties)
extends ConfigParser(config)
{
  // TODO: rewrite this, similar to download stuff:
  // - Don't use java.util.Properties, allow multiple values for one key
  // - Resolve config file names and load them as well
  // - Use pattern matching to parse arguments
  // - allow multiple config files, given on command line

  /** Dump directory */
  val dumpDir = getFile("base-dir")
  if (dumpDir == null) throw error("property 'base-dir' not defined.")
  if (! dumpDir.exists) throw error("dir "+dumpDir+" does not exist")
  
  val requireComplete = config.getProperty("require-download-complete", "false").toBoolean
  
  val source = config.getProperty("source", "pages-articles.xml")

  val wikiName = config.getProperty("wikiName", "wiki")

  val parser = config.getProperty("parser", "simple")

  /** Local ontology file, downloaded for speed and reproducibility */
  val ontologyFile = getFile("ontology")

  /** Local mappings files, downloaded for speed and reproducibility */
  val mappingsDir = getFile("mappings")
  
  val formats = new PolicyParser(config).parseFormats()

  val extractorClasses = loadExtractorClasses()
  
  val namespaces = loadNamespaces()
  
  private def getFile(key: String): File = {
    val value = config.getProperty(key)
    if (value == null) null else new File(value)
  }
  
  private def loadNamespaces(): Set[Namespace] = {
    val names = splitValue("namespaces", ',')
    if (names.isEmpty) Set(Namespace.Main, Namespace.File, Namespace.Category, Namespace.Template)
    else names.map(name => Namespace(Language.English, name)).toSet
  }
  
  /**
   * Loads the extractors classes from the configuration.
   *
   * @return A Map which contains the extractor classes for each language
   */
  private def loadExtractorClasses() : Map[Language, List[Class[_ <: Extractor]]] =
  {
    val languages = loadLanguages()

    //Load extractor classes
    if(config.getProperty("extractors") == null) throw error("Property 'extractors' not defined.")
    
    val stdExtractors = splitValue("extractors", ',').map(loadExtractorClass)

    //Create extractor map
    val classes = new HashMap[Language, List[Class[_ <: Extractor]]]()
    
    /*
    TODO: maybe we should check in the first loop if property "extractors."+language.wikiCode
    exists and if it does, add its specific extractors. Better: refactor the whole config mess.
    Currently, the "languages" property just defines for which languages the default extractors
    should be loaded. It does not define which languages should be processed in general,
    all the "extractors.xx" properties are independent from the "languages" property.
    It should be possible to say: run extractors A,B,C for languages xx,yy,zz. That
    would make the configuration much simpler, less repetitive and more flexible.
    */
    for(language <- languages) {
      classes(language) = stdExtractors
    }

    for (key <- config.stringPropertyNames) {
      if (key.startsWith("extractors.")) {
        val language = Language(key.substring("extractors.".length()))
        classes(language) = stdExtractors ++ splitValue(key, ',').map(loadExtractorClass)
      }
    }

    SortedMap(classes.toSeq: _*)
  }
  
  private def loadLanguages(): Set[Language] = {
    
    /** Languages */
    // TODO: add special parameters, similar to download:
    // extract=10000-:InfoboxExtractor,PageIdExtractor means all languages with at least 10000 articles
    // extract=mapped:MappingExtractor means all languages with a mapping namespace
    
    var keys = splitValue("languages", ',')
        
    var languages = Set[Language]()
    
    var ranges = Set[(Int,Int)]()
  
    // FIXME: copy & paste in DownloadConfig and ConfigUtils
    
    for (key <- keys) key match {
      case "@mappings" => languages ++= Namespace.mappings.keySet
      case RangeRegex(from, to) => ranges += toRange(from, to)
      case LanguageRegex(language) => languages += Language(language)
      case other => throw new Exception("Invalid language / range '"+other+"'")
    }
    
    // resolve page count ranges to languages
    if (ranges.nonEmpty)
    {
      val listFile = new File(dumpDir, WikiInfo.FileName)
      
      // Note: the file is in ASCII, any non-ASCII chars are XML-encoded like '&#231;'. 
      // There is no Codec.ASCII, but UTF-8 also works for ASCII. Luckily we don't use 
      // these non-ASCII chars anyway, so we don't have to unescape them.
      println("parsing "+listFile)
      val wikis = WikiInfo.fromFile(listFile, Codec.UTF8)
      
      // for all wikis in one of the desired ranges...
      for ((from, to) <- ranges; wiki <- wikis; if (from <= wiki.pages && wiki.pages <= to))
      {
        // ...add its language
        languages += wiki.language
      }
    }
    
    SortedSet[Language](languages.toSeq: _*)
  }

  private def loadExtractorClass(name: String): Class[_ <: Extractor] = {
    val className = if (! name.contains(".")) classOf[Extractor].getPackage.getName+'.'+name else name
    // TODO: class loader of Extractor.class is probably wrong for some users.
    classOf[Extractor].getClassLoader.loadClass(className).asSubclass(classOf[Extractor])
  }
  
}
