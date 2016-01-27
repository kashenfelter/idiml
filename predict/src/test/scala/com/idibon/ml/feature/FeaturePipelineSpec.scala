package com.idibon.ml.feature

import com.idibon.ml.alloy.Alloy
import com.idibon.ml.test.VerifyLogging
import com.idibon.ml.feature.tokenizer.Token
import com.idibon.ml.common.{Engine, EmbeddedEngine}

import scala.util.Random
import scala.collection.mutable.{HashMap => MutableMap}
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import org.json4s.{JArray, JString, JObject, JDouble, JField}
import org.json4s.native.JsonMethods.{parse, render, compact}
import org.scalatest.{BeforeAndAfterAll, FunSpec, Matchers, BeforeAndAfter}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.anyString

class FeaturePipelineSpec extends FunSpec with Matchers with MockitoSugar
    with BeforeAndAfter with BeforeAndAfterAll with VerifyLogging {

  override val loggerName = classOf[FeaturePipeline].getName

  override def beforeAll = {
    super.beforeAll
    initializeLogging
  }

  override def afterAll = {
    shutdownLogging
    super.afterAll
  }

  after {
    // reset the logged messages after every test
    resetLog
  }

  def createMockReader = {
    val reader = mock[Alloy.Reader]
    when(reader.within(anyString())).thenReturn(reader)
    when(reader.resource(anyString())).thenReturn(null)
    reader
  }

  def createMockWriter = {
    val writer = mock[Alloy.Writer]
    when(writer.within(anyString())).thenReturn(writer)
    when(writer.resource(anyString())).thenReturn(null)
    writer
  }

  describe("Vector concatenation & priming") {
    val transforms = Map(
      "contentExtractor" -> new DocumentExtractor,
      "concatenator" -> new VectorConcatenator,
      "concatenatorBad" -> new VectorConcatenatorBad,
      "metadataVector" -> new MetadataNumberExtractor,
      "metadataVector2" -> new MetadataNumberExtractor2,
      "featureVector" -> new FeatureVectors
    )

    val doc = parse("""{"content":"A document!","metadata":{"number":3.14159265, "number2":2.14}}""").asInstanceOf[JObject]

    it("throws error on bad dimensions") {
      val pipeline = List(
        new PipelineEntry("$output", List("concatenatorBad")),
        new PipelineEntry("concatenatorBad", List("metadataVector", "featureVector")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document")),
        new PipelineEntry("featureVector", List("contentExtractor")))

      val featurePipeline = FeaturePipeline.bind(transforms, pipeline).prime(List(doc))
      intercept[AssertionError]{
        featurePipeline(doc)
      }
    }

    it("works as expected with multiple vectors") {
      val pipeline = List(
        new PipelineEntry("$output", List("concatenator")),
        new PipelineEntry("concatenator", List("metadataVector", "featureVector", "metadataVector2")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document")),
        new PipelineEntry("metadataVector2", List("$document")),
        new PipelineEntry("featureVector", List("contentExtractor")))
      val featurePipeline = FeaturePipeline.bind(transforms, pipeline).prime(List(doc))
      featurePipeline(doc) shouldBe Vectors.sparse(3, Array(0, 1, 2), Array(3.14159265, 11.0, 2.14))
    }

    it("primes as intended") {
      val pipeline = List(
        new PipelineEntry("$output", List("concatenator")),
        new PipelineEntry("concatenator", List("metadataVector", "metadataVector2")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document")),
        new PipelineEntry("metadataVector2", List("$document")),
        new PipelineEntry("featureVector", List("contentExtractor")))
      val unprimed = FeaturePipeline.bind(transforms, pipeline)
      val primed = unprimed.prime(List(doc))
      unprimed.isFrozen shouldBe false
      primed.isFrozen shouldBe true
      unprimed.getTotalDimensions() shouldBe -1
      primed.getTotalDimensions() shouldBe 2
    }
  }

  describe("get human readable feature tests") {
    val transforms = Map(
      "contentExtractor" -> new DocumentExtractor,
      "metadataVector" -> new MetadataNumberExtractor,
      "metadataVector2" -> new MetadataNumberExtractor2
    )
    val pipeline = List(
      new PipelineEntry("$output", List("metadataVector", "metadataVector2")),
      new PipelineEntry("metadataVector", List("$document")),
      new PipelineEntry("metadataVector2", List("$document")))

    it("fails on unprimed pipelines") {
      intercept[IllegalStateException]{
        FeaturePipeline.bind(transforms, pipeline).getHumanReadableFeature(List())
      }
    }

    it("works on empty input") {
      val unprimed = FeaturePipeline.bind(transforms, pipeline).prime(List())
      val primed = unprimed.prime(List())
      primed.getHumanReadableFeature(List()) shouldBe Map[Int,String]()
    }

    it("works on single output transform") {
      val unprimed = FeaturePipeline.bind(transforms, List(
        new PipelineEntry("$output", List("metadataVector")),
        new PipelineEntry("metadataVector", List("$document")),
        new PipelineEntry("metadataVector2", List("$document")))).prime(List())
      val primed = unprimed.prime(List())
      primed.getHumanReadableFeature(List(0, 1)) shouldBe Map(0 -> "meta-number")
    }

    it("works on multiple output transforms") {
      val unprimed = FeaturePipeline.bind(transforms, pipeline).prime(List())
      val primed = unprimed.prime(List())
      primed.getHumanReadableFeature(List(0, 1)) shouldBe Map(0 -> "meta-number", 1 -> "meta-number2")
    }
  }

  describe("prune tests") {
    val transforms = Map(
      "contentExtractor" -> new DocumentExtractor,
      "metadataVector" -> new MetadataNumberExtractor,
      "metadataVector2" -> new MetadataNumberExtractor2
    )
    val pipeline = List(
      new PipelineEntry("$output", List("metadataVector", "metadataVector2")),
      new PipelineEntry("contentExtractor", List("$document")),
      new PipelineEntry("metadataVector", List("$document")),
      new PipelineEntry("metadataVector2", List("$document")))

    def predicate1(num:Int): Boolean = {
      !List(0, 1, 2, 3, 4).contains(num)
    }

    it("fails on unprimed pipelines") {
      intercept[IllegalStateException]{
        FeaturePipeline.bind(transforms, pipeline).prune(predicate1)
      }
    }

    it("works on single output transformer") {
      FeaturePipeline.bind(transforms, List(
        new PipelineEntry("$output", List("metadataVector")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document")))).prime(List()).prune(predicate1)
      transforms("metadataVector").asInstanceOf[MetadataNumberExtractor].pruned shouldBe true
      transforms("metadataVector2").asInstanceOf[MetadataNumberExtractor2].pruned shouldBe false
    }

    it("works on multiple output transformers") {
      FeaturePipeline.bind(transforms, pipeline).prime(List()).prune(predicate1)
      transforms("metadataVector").asInstanceOf[MetadataNumberExtractor].pruned shouldBe true
      transforms("metadataVector2").asInstanceOf[MetadataNumberExtractor2].pruned shouldBe true
    }
  }

  describe("save") {
    def runSaveTest(unparsed: String) {
      val dummyReader = createMockReader
      val dummyWriter = createMockWriter
      val json = parse(unparsed).asInstanceOf[JObject]
      val pipeline = (new FeaturePipelineLoader).load(new EmbeddedEngine, dummyReader, Some(json))
      val result = pipeline.save(dummyWriter)
        .map(j => compact(render(j))).getOrElse("")
      result shouldBe unparsed
      verify(dummyWriter).within(anyString())
      verify(dummyWriter).within("A")
    }

    it("should suppress None config parameters for Archivable transforms") {
      runSaveTest("""{"transforms":[{"name":"A","class":"com.idibon.ml.feature.ArchivableTransform"}],"pipeline":[{"name":"A","inputs":[]},{"name":"$output","inputs":["A"]}]}""")
    }

    it("should include config parameters if present") {
      runSaveTest("""{"transforms":[{"name":"A","class":"com.idibon.ml.feature.ArchivableTransform","config":{"values":[1.0,0.25,-0.75]}}],"pipeline":[{"name":"A","inputs":[]},{"name":"$output","inputs":["A"]}]}""")
    }
  }

  describe("load") {
    it("should call load on archivable transforms") {
      val dummyAlloy = createMockReader
      val json = parse("""{
"transforms":[
  {"name":"A","class":"com.idibon.ml.feature.ArchivableTransform"},
  {"name":"B","class":"com.idibon.ml.feature.ArchivableTransform",
   "config":{"values":[0.0,-1.0,0.5]}}],
"pipeline":[
  {"name":"A","inputs":[]},
  {"name":"B","inputs":[]},
  {"name":"$output","inputs":["A","B"]}]}""").asInstanceOf[JObject]

      val pipeline = (new FeaturePipelineLoader).load(new EmbeddedEngine, dummyAlloy, Some(json))
      val document = parse("{}").asInstanceOf[JObject]
      pipeline(document) shouldBe
        Vectors.sparse(6,Array(0,1,2,3,4,5),Array(1.0,0.0,1.0,0.0,-1.0,0.5))
      verify(dummyAlloy, times(2)).within(anyString())
      verify(dummyAlloy).within("A")
      verify(dummyAlloy).within("B")

    }

    it("should log a warning if a reserved name is used") {
      val dummyAlloy = createMockReader
      val json = parse("""{
"transforms":[
  {"name":"contentExtractor","class":"com.idibon.ml.feature.DocumentExtractor"},
  {"name":"$featureVector","class":"com.idibon.ml.feature.FeatureVectors"}],
"pipeline":[
  {"name":"$output","inputs":["$featureVector"]},
  {"name":"$featureVector","inputs":["contentExtractor"]},
  {"name":"contentExtractor","inputs":["$document"]}]
}""")

      (new FeaturePipelineLoader).load(new EmbeddedEngine, dummyAlloy, Some(json.asInstanceOf[JObject]))
      loggedMessages should include regex "\\[<undefined>/\\$featureVector\\] - using reserved name"
    }

    it("should generate a callable graph") {
      val dummyAlloy = createMockReader
      val json = parse("""{
"transforms":[
  {"name":"contentExtractor","class":"com.idibon.ml.feature.DocumentExtractor"},
  {"name":"concatenator","class":"com.idibon.ml.feature.VectorConcatenator"},
  {"name":"metadataVector","class":"com.idibon.ml.feature.MetadataNumberExtractor"},
  {"name":"featureVector","class":"com.idibon.ml.feature.FeatureVectors"}],
"pipeline":[
  {"name":"$output","inputs":["concatenator"]},
  {"name":"concatenator","inputs":["metadataVector","featureVector"]},
  {"name":"contentExtractor","inputs":["$document"]},
  {"name":"metadataVector","inputs":["$document"]},
  {"name":"featureVector","inputs":["contentExtractor"]}]
}""")

      val pipeline = (new FeaturePipelineLoader)
        .load(new EmbeddedEngine, dummyAlloy, Some(json.asInstanceOf[JObject]))
      loggedMessages shouldBe empty

      val doc = parse("""{"content":"A document!","metadata":{"number":3.14159265}}""").asInstanceOf[JObject]
      val fp = pipeline.prime(List(doc))
      fp(doc) shouldBe Vectors.sparse(2, Array(0, 1), Array(3.14159265, 11.0))
    }
  }

  describe("bindGraph") {

    it("should raise an exception if $output is incomplete") {
      val transforms = Map[String, FeatureTransformer]()
      val pipeline = List(new PipelineEntry("$output", List()))

      intercept[IllegalArgumentException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should raise an exception if a transformer doesn't implement apply") {
      val transforms = Map("bogus" -> new NotATransformer)
      val pipeline = List(new PipelineEntry("$output", List("bogus")))

      intercept[IllegalArgumentException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should raise an exception if $output is undefined") {
      val transforms = Map("contentExtractor" -> new DocumentExtractor)
      val pipeline = List(
        new PipelineEntry("contentExtractor", List("$document"))
      )
      intercept[NoSuchElementException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should raise an exception on misnamed pipeline stages") {
      val transforms = Map("contentExtractor" -> new DocumentExtractor)
      val pipeline = List(
        new PipelineEntry("content", List("$document")),
        new PipelineEntry("$output", List("content"))
      )
      intercept[NoSuchElementException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should raise an exception if a pipeline stage is missing") {
      val transforms = Map(
        "contentExtractor" -> new DocumentExtractor,
        "concatenator" -> new VectorConcatenator,
        "metadataVector" -> new MetadataNumberExtractor
      )

      val pipeline = List(
        new PipelineEntry("$output", List("concatenator")),
        new PipelineEntry("concatenator", List("metadataVector", "featureVector")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document"))
      )

      intercept[NoSuchElementException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should raise an exception if the transformer uses currying") {
      val transforms = Map(
        "curriedExtractor" -> new CurriedExtractor,
        "contentExtractor" -> new DocumentExtractor
      )
      val pipeline = List(
        new PipelineEntry("$output", List("curriedExtractor")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("curriedExtractor", List("$document", "contentExtractor"))
      )
      intercept[UnsupportedOperationException] {
        FeaturePipeline.bindGraph(transforms, pipeline)
      }
    }

    it("should treat $document as a typeOf[JObject]") {
      val transforms = Map(
        "contentExtractor" -> new DocumentExtractor,
        "metadataVector" -> new MetadataNumberExtractor,
        "featureVector" -> new FeatureVectors
      )
      val pipeline = List(
        new PipelineEntry("$output", List("featureVector", "metadataVector")),
        new PipelineEntry("contentExtractor", List("$document")),
        new PipelineEntry("metadataVector", List("$document")),
        new PipelineEntry("featureVector", List("contentExtractor"))
      )

      val graph = FeaturePipeline.bindGraph(transforms, pipeline)
      graph.size shouldBe 3

      val intermediates = MutableMap[String, Any]()
      intermediates.put("$document", JObject(List(
        JField("content", JString("A document!")),
        JField("metadata", JObject(List(
          JField("number", JDouble(3.14159265)))))
      )))

      for (stage <- graph; transform <- stage.transforms) {
        intermediates.put(transform.name, transform.transform(intermediates))
      }

      intermediates.get("$output") shouldBe
        Some(List(Vectors.dense(11.0), Vectors.dense(3.14159265)))
    }

    it("should support variadic arguments in pipelines") {
      val transforms = Map(
        "contentExtractor" -> new DocumentExtractor,
        "concatenator" -> new VectorConcatenator,
        "metadataVector" -> new MetadataNumberExtractor,
        "featureVector" -> new FeatureVectors
      )

      val pipelines = List(
        (Vectors.dense(3.14159265, 11.0), List(
          new PipelineEntry("$output", List("concatenator")),
          new PipelineEntry("concatenator", List("metadataVector", "featureVector")),
          new PipelineEntry("contentExtractor", List("$document")),
          new PipelineEntry("metadataVector", List("$document")),
          new PipelineEntry("featureVector", List("contentExtractor")))),
        (Vectors.dense(11.0, 3.14159265), List(
          // exactly the same, but the order of concatenation is reversed
          new PipelineEntry("$output", List("concatenator")),
          new PipelineEntry("concatenator", List("featureVector", "metadataVector")),
          new PipelineEntry("contentExtractor", List("$document")),
          new PipelineEntry("metadataVector", List("$document")),
          new PipelineEntry("featureVector", List("contentExtractor"))))
      )

      pipelines.foreach({ case (expected, pipeline) => {
        val graph = FeaturePipeline.bindGraph(transforms, pipeline)
        graph.size shouldBe 4

        val intermediates = MutableMap[String, Any]()
        intermediates.put("$document", JObject(List(
          JField("content", JString("A document!")),
          JField("metadata", JObject(List(
            JField("number", JDouble(3.14159265)))))
        )))

        for (stage <- graph; transform <- stage.transforms) {
          intermediates.put(transform.name, transform.transform(intermediates))
        }

        intermediates.get("$output") shouldBe Some(List(expected))
      }})
    }
  }

  describe("buildDependencyChain") {
    it("should handle an empty list correctly") {
      val chain = FeaturePipeline.sortDependencies(List[PipelineEntry]())
      chain shouldBe empty
    }

    it("should sort simple pipelines in dependency order") {
      val chain = FeaturePipeline.sortDependencies(
        Random.shuffle(List(
          new PipelineEntry("tokenizer", List("$document")),
          new PipelineEntry("ngrammer", List("tokenizer")),
          new PipelineEntry("$output", List("tokenizer", "ngrammer"))))
      )
      chain shouldBe List(List("tokenizer"), List("ngrammer"), List("$output"))
    }

    it("should support diamond graphs") {
      val chain = FeaturePipeline.sortDependencies(
        Random.shuffle(List(
          new PipelineEntry("tokenizer", List("$document")),
          new PipelineEntry("metadata", List("$document")),
          new PipelineEntry("strip-punctuation", List("tokenizer")),
          new PipelineEntry("n-grams", List("strip-punctuation")),
          new PipelineEntry("word-vectors", List("n-grams")),
          new PipelineEntry("$output", List("word-vectors", "metadata"))))
      )
      chain shouldBe List(
        List("tokenizer", "metadata"),
        List("strip-punctuation"),
        List("n-grams"),
        List("word-vectors"),
        List("$output")
      )
    }

    it("should raise an exception on cyclic graphs") {
      intercept[IllegalArgumentException] {
        FeaturePipeline.sortDependencies(
          Random.shuffle(List(
            new PipelineEntry("tokenizer", List("$document")),
            new PipelineEntry("n-grams", List("tokenizer", "word-vectors")),
            new PipelineEntry("word-vectors", List("n-grams")),
            new PipelineEntry("$output", List("word-vectors"))))
        )
      }

      intercept[IllegalArgumentException] {
        FeaturePipeline.sortDependencies(
          Random.shuffle(List(
            new PipelineEntry("tokenizer", List("$document")),
            new PipelineEntry("n-grams", List("tokenizer", "n-grams")),
            new PipelineEntry("$output", List("n-grams"))))
        )
      }
    }

    it("should raise an exception on non-existent transforms") {
      intercept[NoSuchElementException] {
        FeaturePipeline.sortDependencies(
          Random.shuffle(List(
            new PipelineEntry("n-grams", List("tokenizer")),
            new PipelineEntry("$output", List("n-grams"))))
        )
      }
    }
  }
}

private [this] class VectorConcatenator extends FeatureTransformer with TerminableTransformer {
  var length = 0
  var frozen = false
  def apply(inputs: Vector*): Vector = {
    val v = Vectors.dense(inputs.foldLeft(Array[Double]())(_ ++ _.toArray))
    length = v.size
    v
  }
  override def numDimensions: Int = length

  override def prune(transform: (Int) => Boolean): Unit = ???

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = List()

  override def freeze(): Unit = { frozen = true }
}

/**
  * Bad vector concatenator that has incorrect dimensions.
  */
private [this] class VectorConcatenatorBad extends FeatureTransformer with TerminableTransformer {
  var frozen = false
  def apply(inputs: Vector*): Vector = {
    Vectors.dense(inputs.foldLeft(Array[Double]())(_ ++ _.toArray))
  }
  override def numDimensions: Int = -1

  override def prune(transform: (Int) => Boolean): Unit = ???

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = ???

  override def freeze(): Unit = { frozen = true }
}

private [this] class CurriedExtractor extends FeatureTransformer {
  def apply(document: JObject)(tokens: Seq[Feature[String]]): Vector = {
    Vectors.dense(1.0)
  }
}

private [this] class NotATransformer extends FeatureTransformer {
  def appply(invalid: Int): Int = invalid + 10
}

private [this] class FeatureVectors extends FeatureTransformer with TerminableTransformer {
  var dims = 0
  def apply(content: Seq[Feature[String]]): Vector = {
    val v = Vectors.dense(content.map(_.get.length.toDouble).toArray)
    dims = v.size
    v
  }
  override def numDimensions: Int = dims

  override def prune(transform: (Int) => Boolean): Unit = ???

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = ???

  override def freeze(): Unit = {}
}

private [this] class MetadataNumberExtractor extends FeatureTransformer with TerminableTransformer {
  def apply(document: JObject): Vector = {
    Vectors.dense((document \ "metadata" \ "number").asInstanceOf[JDouble].num)
  }
  var pruned = false

  override def freeze(): Unit = {}

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = {
    List(0 -> "meta-number")
  }

  override def numDimensions: Int = 1

  override def prune(transform: (Int) => Boolean): Unit = { pruned = true }
}

private [this] class MetadataNumberExtractor2 extends FeatureTransformer with TerminableTransformer{
  def apply(document: JObject): Vector = {
    Vectors.dense((document \ "metadata" \ "number2").asInstanceOf[JDouble].num)
  }

  var pruned = false

  override def freeze(): Unit = {}

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = {
    List(0 -> "meta-number2")
  }

  override def numDimensions: Int = 1

  override def prune(transform: (Int) => Boolean): Unit = { pruned = true }
}

private [this] class TokenConsumer extends FeatureTransformer {
  def apply(tokens: Seq[Feature[Token]]) = tokens
}

private [this] class DocumentExtractor extends FeatureTransformer {
  def apply(document: JObject): Seq[StringFeature] = {
    List(StringFeature((document \ "content").asInstanceOf[JString].s))
  }
}

private [this] case class ArchivableTransform(suppliedConfig: Option[JObject])
    extends FeatureTransformer
    with Archivable[ArchivableTransform, ArchivableTransformLoader] with TerminableTransformer {

  def apply: Vector = {
    suppliedConfig.map(config => {
      val values = (config \ "values").asInstanceOf[JArray]
        .arr.map(_.asInstanceOf[JDouble].num)
      Vectors.dense(values.toArray)
    }).getOrElse(Vectors.dense(1.0, 0.0, 1.0))
  }

  def save(w: Alloy.Writer): Option[JObject] = suppliedConfig

  override def numDimensions: Int = 3

  override def prune(transform: (Int) => Boolean): Unit = ???

  override def getHumanReadableFeature(indexes: Set[Int]): List[(Int, String)] = ???

  override def freeze(): Unit = {}
}

private [this] class ArchivableTransformLoader
    extends ArchiveLoader[ArchivableTransform] {

  def load(engine: Engine, r: Alloy.Reader, config: Option[JObject]): ArchivableTransform = {
    new ArchivableTransform(config)
  }
}
