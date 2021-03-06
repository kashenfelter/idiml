package com.idibon.ml.alloy

import scala.collection.JavaConverters._
import java.util.{Map => JavaMap, List => JavaList}

import com.idibon.ml.common.Engine
import com.idibon.ml.predict.{Label, PredictModel, Classification, Span}
import com.idibon.ml.predict.rules.{SpanRules, DocumentRules}
import com.idibon.ml.predict.ensemble.GangModel

/** Alloy loader helper for Ididat tasks
  *
  * Provides Java-friendly static call sites for loading Alloys, and
  * support for injecting modified rule dictionaries into existing
  * Alloys.
  */
object IdidatAlloy {

  private[this] def buildRulesGang(
    r: JavaMap[Label, JavaMap[String, java.lang.Number]]):
      GangModel = {
    val rulesModels = r.asScala.map({ case (label, dictionary) => {
      // convert JavaMap[String, Number] => List[String, Float]
      val floatRules = dictionary.asScala.map({ case (phrase, weight) => {
        phrase -> weight.floatValue
      }}).toList
      val labelId = label.uuid.toString
      (labelId, new DocumentRules(labelId, floatRules))
    }}).toMap
    new GangModel(rulesModels)
  }

  /** Loads an Alloy using a possibly-updated label and rule set
    *
    * @param engine Engine context for loading
    * @param name   name for the Alloy
    * @param alloyReader reader to access the archived Alloy, may be null
    * @param labels current set of labels
    * @param rules  current set of rules
    * @param validate true to validate the Alloy (if present) after loading
    * @return an alloy with the updated labels and rules
    */
  def loadClassificationTask(engine: Engine, name: String,
    alloyReader: Alloy.Reader, labels: JavaList[Label],
    rules: JavaMap[Label, JavaMap[String, java.lang.Number]],
    validate: Boolean):
      Alloy[Classification] = {

    /* create new rules models for any label where rules are defined.
     * dump them all in a Gang so that they are processed in parallel */
    val rulesGang = buildRulesGang(rules)

    if (alloyReader == null) {
      // return a base alloy with just the rules
      new BaseAlloy[Classification](name, labels.asScala,
        Map(s"${name} - rules" -> rulesGang))
    } else {
      /* inject rules and labels into an existing alloy. discard any
       * existing DocumentRules models defined at the top alloy or
       * any GangModel therein, and construct a new GangModel with
       * rulesGang and the surviving models */
      val alloy = BaseAlloy.load[Classification](engine, alloyReader)
      // validate the alloy if requested
      if (validate) HasValidationData.validate(alloyReader, alloy)
      val keptModels = removeExistingRulesModels(alloy.models)
      if (keptModels.isEmpty) {
        // if no models were kept, don't waste any time with empty gangs
        new BaseAlloy[Classification](name, labels.asScala,
          Map(s"${name} - injected" -> rulesGang))
      } else {
        val combined = new GangModel(Map(
          s"${name} - kept" -> new GangModel(keptModels),
          s"${name} - injected" -> rulesGang))
        new BaseAlloy[Classification](name, labels.asScala,
          Map(s"${name} - combined" -> combined))
      }
    }
  }

  /** Loads an Alloy using a possibly-updated label and rule set
    *
    * @param engine Engine context for loading
    * @param name   name for the Alloy
    * @param alloyReader reader to access the archived Alloy, may be null
    * @param labels current set of labels
    * @param rules  current set of rules
    * @param validate true to validate the Alloy (if present) after loading
    * @return an alloy with the updated labels and rules
    */
  def loadSpanTask(engine: Engine, name: String,
    alloyReader: Alloy.Reader, labels: JavaList[Label],
    rules: JavaMap[Label, JavaMap[String, java.lang.Number]],
    validate: Boolean):
      Alloy[Span] = {
    // TODO: span rules support, validation
    val alloy = BaseAlloy.load[Span](engine, alloyReader)
    new BaseAlloy[Span](name, labels.asScala, alloy.models)
  }

  /** Removes any RulesModel objects from any set of models (e.g., GangModel)
    *
    * TODO: this will need some changes when span models exist
    */
  private[alloy] def removeExistingRulesModels(
    models: Map[String, PredictModel[Classification]]):
      Map[String, PredictModel[Classification]] = {
    models.map({ case (name, model) => {
      model match {
        case gang: GangModel => removeExistingRulesModels(gang.models) match {
          case empty if empty.isEmpty => (name, None)
          case remain => (name, Some(new GangModel(remain, gang.featurePipeline)))
        }
        case rules: DocumentRules => (name, None)
        case spanRules: SpanRules => (name, None)
        case _ => (name, Some(model))
    }}}).filter(_._2.isDefined).map(k => (k._1, k._2.get))
  }
}

/** Empty class to get Java static method call sites for JRuby */
class IdidatAlloy {
}
