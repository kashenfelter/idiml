package com.idibon.ml.train.furnace

import scala.util.{Try, Success, Failure}
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.reflect.runtime.universe.{typeOf, Type, TypeTag}

import com.idibon.ml.train.TrainOptions
import com.idibon.ml.common.Engine
import com.idibon.ml.predict._

import org.json4s.JObject

trait Furnace2[+T <: PredictResult] {

  /** Name for the generated model */
  val name: String

  /** Trains the model synchronously
    *
    * @param options training data and options
    */
  protected def doTrain(options: TrainOptions): PredictModel[T]

  /** Trains the model asynchronously
    *
    * @param options training configuration options
    * @return an asynchronously-trained model
    */
  def train(options: TrainOptions): Future[PredictModel[T]] = {
    implicit val executor = ExecutionContext.global
    val model = Promise[PredictModel[T]]()
    Future {
      model.complete(Try(doTrain(options)))
    }
    model.future
  }
}

object Furnace2 {

  type Builder = Function3[Engine, String, JObject, Furnace2[_]]

  // master registry of all furnaces
  private[this] val _registry = mutable.Map[Type, mutable.Map[String, Builder]]()

  /** Register a new furnace type
    *
    * @tparam T class of prediction made by the model
    * @param tpe short name used to identify the furnace class
    * @param b builder function
    */
  private[train] def register[T <: PredictResult: TypeTag](tpe: String, b: Builder) {
    val r = _registry.getOrElseUpdate(typeOf[T], mutable.Map[String, Builder]())
    r += (tpe -> b)
  }

  /** Re-initializes the registry (for testing) */
  private[train] def resetRegistry() {
    _registry.clear()
    register[Span]("ChainNERFurnace", ChainNERFurnace)
  }

  resetRegistry()

  /** Constructs a new Furnace
    *
    * @param engine current engine context
    * @param tpe type of furnace to construct
    * @param name name for the new furnace
    * @param config furnace JSON configuration
    */
  def apply[T <: PredictResult: TypeTag](engine: Engine, tpe: String,
      name: String, config: JObject): Furnace2[T] = {

    _registry(typeOf[T])(tpe)(engine, name, config).asInstanceOf[Furnace2[T]]
  }
}

/** Trait for Furnaces which delegates some of the training to other furnaces
  */
trait HasFurnaces[T <: PredictResult] {

  /** Furnaces contained within this furnace, and delegates for training */
  val furnaces: Seq[Furnace2[T]]

  /** Trains all of the furnaces within this container in sequence
    *
    * @param options training configuration options
    * @return the models generated by each furnace
    */
  protected def trainFurnaces(options: TrainOptions): Seq[PredictModel[T]] = {
    /* loop through all of the contained furnaces, training each in order.
     * abort if any model training fails, or exceeds the max train time */
    implicit val context = ExecutionContext.global

    furnaces.map(furnace =>
      Await.ready(furnace.train(options), options.maxTrainTime).value match {
        case Some(Success(model)) => model
        case Some(Failure(reason)) => throw reason
        case _ => throw new Error("Invalid")
      })
  }
}