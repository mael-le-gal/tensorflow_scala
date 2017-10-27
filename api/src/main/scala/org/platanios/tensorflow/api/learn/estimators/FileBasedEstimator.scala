/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.learn.estimators

import org.platanios.tensorflow.api.config._
import org.platanios.tensorflow.api.core.Graph
import org.platanios.tensorflow.api.core.client.Fetchable
import org.platanios.tensorflow.api.core.exception.{CheckpointNotFoundException, InvalidArgumentException}
import org.platanios.tensorflow.api.io.CheckpointReader
import org.platanios.tensorflow.api.learn._
import org.platanios.tensorflow.api.learn.hooks._
import org.platanios.tensorflow.api.ops.control_flow.ControlFlow
import org.platanios.tensorflow.api.ops.io.Dataset
import org.platanios.tensorflow.api.ops.metrics.Metric
import org.platanios.tensorflow.api.ops.variables.Saver
import org.platanios.tensorflow.api.ops.{Op, Output}
import org.platanios.tensorflow.api.tensors.Tensor

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Path

import scala.collection.immutable.TreeMap
import scala.collection.mutable

/** File-based estimator which is used to train, use, and evaluate TensorFlow models, and uses checkpoint files for
  * storing and retrieving its state. This means that checkpoint files are written after every call to `train()` and are
  * loaded on every call to `infer()` or `evaluate()`.
  *
  * @param  modelFunction     Model-generating function that can optionally have a [[Configuration]] argument which will
  *                           be used to pass the estimator's configuration to the model and allows customizing the
  *                           model based on the execution environment.
  * @param  configurationBase Configuration base for this estimator. This allows for setting up distributed training
  *                           environments, for example. Note that this is a *base* for a configuration because the
  *                           estimator might modify it and set some missing fields to appropriate default values, in
  *                           order to obtain its final configuration that can be obtain through its `configuration`
  *                           field.
  * @param  hooks             Default hooks to use while training, inferring, and evaluating (e.g., logging for the loss
  *                           function value, etc.).
  * @param  chiefOnlyHooks    Default hooks to use while training for the chief node only. This argument is only useful
  *                           for a distributed training setting.
  * @param  tensorBoardConfig Default TensorBoard configuration to use while training. If provided, a TensorBoard server
  *                           is launched while training, using the provided configuration. In that case, it is required
  *                           that TensorBoard is installed for the default Python environment in the system. If
  *                           training in a distributed setting, the TensorBoard server is launched on the chief node.
  * @param  evaluationMetrics Default evaluation metrics to use.
  *
  * @author Emmanouil Antonios Platanios
  */
class FileBasedEstimator[IT, IO, ID, IS, I, TT, TO, TD, TS, EI] private[estimators] (
    override protected val modelFunction: Estimator.ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, EI],
    override protected val configurationBase: Configuration = null,
    val stopCriteria: StopCriteria = StopCriteria(),
    val hooks: Seq[Hook] = Seq.empty,
    val chiefOnlyHooks: Seq[Hook] = Seq.empty,
    val tensorBoardConfig: TensorBoardConfig = null,
    val evaluationMetrics: Seq[Metric[EI, Output]] = Seq.empty
) extends Estimator[IT, IO, ID, IS, I, TT, TO, TD, TS, EI](modelFunction, configurationBase) {
  /** Trains the model managed by this estimator.
    *
    * @param  data         Training dataset. Each element is a tuple over input and training inputs (i.e.,
    *                      supervision labels).
    * @param  stopCriteria Stop criteria to use for stopping the training iteration. For the default criteria please
    *                      refer to the documentation of [[StopCriteria]].
    */
  override def train(data: Dataset[TT, TO, TD, TS], stopCriteria: StopCriteria = StopCriteria()): Unit = {
    trainWithHooks(data, stopCriteria)
  }

  /** Trains the model managed by this estimator.
    *
    * '''NOTE:''' If you provide any summary saver or checkpoint saver hooks in `hooks` or `chiefOnlyHooks`, then the
    * checkpoint configuration in this estimator's `configuration` will be ignored for the chief and those hooks will be
    * used instead.
    *
    * If any of `hooks`, `chiefOnlyHooks`, or `tensorBoardConfig` are provided, they override the values provided in the
    * constructor of this estimator.
    *
    * @param  data              Training dataset. Each element is a tuple over input and training inputs (i.e.,
    *                           supervision labels).
    * @param  stopCriteria      Stop criteria to use for stopping the training iteration. For the default criteria
    *                           please refer to the documentation of [[StopCriteria]].
    * @param  hooks             Hooks to use while training (e.g., logging for the loss function value, etc.).
    * @param  chiefOnlyHooks    Hooks to use while training for the chief node only. This argument is only useful for
    *                           a distributed training setting.
    * @param  tensorBoardConfig If provided, a TensorBoard server is launched using the provided configuration. In
    *                           that case, it is required that TensorBoard is installed for the default Python
    *                           environment in the system. If training in a distributed setting, the TensorBoard
    *                           server is launched on the chief node.
    */
  def trainWithHooks(
      data: Dataset[TT, TO, TD, TS],
      stopCriteria: StopCriteria = StopCriteria(),
      hooks: Seq[Hook] = this.hooks,
      chiefOnlyHooks: Seq[Hook] = this.chiefOnlyHooks,
      tensorBoardConfig: TensorBoardConfig = this.tensorBoardConfig): Unit = {
    val needsToTrain = {
      if (!stopCriteria.restartCounting) {
        workingDir.flatMap(dir => Saver.latestCheckpoint(dir).flatMap(latestPath => {
          CheckpointReader(latestPath).getTensor(Graph.Keys.GLOBAL_STEP.name)
        })).map(_.scalar.asInstanceOf[Long]).flatMap(s => stopCriteria.maxSteps.map(_ <= s)).getOrElse(true)
      } else {
        true
      }
    }
    if (!needsToTrain) {
      FileBasedEstimator.logger.info(
        "Skipping training because no restarting is allowed in the termination criteria and the maximum number of " +
            "steps have already been executed in the past (i.e., saved checkpoint).")
    } else {
      val allHooks = mutable.ListBuffer(hooks: _*)
      val allChiefOnlyHooks = mutable.ListBuffer(chiefOnlyHooks: _*)
      allHooks += StopHook(stopCriteria)
      val model = modelFunction(configuration)
      val graph = Graph()
      Op.createWith(graph = graph, deviceFunction = deviceFunction.getOrElse(_.device)) {
        graph.setRandomSeed(randomSeed)
        // TODO: [LEARN] !!! Do we ever update the global epoch?
        Counter.getOrCreate(Graph.Keys.GLOBAL_EPOCH, local = false)
        val globalStep = Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, local = false)
        val trainingOps = Op.createWithNameScope("Model")(model.buildTrainingOps())
        val inputInitializer = trainingOps.inputIterator.createInitializer(data)
        graph.addToCollection(trainingOps.loss, Graph.Keys.LOSSES)
        allHooks += TensorNaNHook(Set(trainingOps.loss.name))
        allHooks += TensorLoggingHook(TreeMap(
          "Step" -> globalStep.value.name,
          "Loss" -> trainingOps.loss.name
        ), StepHookTrigger(100))
        if (tensorBoardConfig != null)
          allChiefOnlyHooks += TensorBoardHook(tensorBoardConfig)
        val saver = getOrCreateSaver()
        val session = Estimator.monitoredTrainingSession(
          configuration = configuration,
          hooks = allHooks,
          chiefOnlyHooks = allChiefOnlyHooks,
          sessionScaffold = SessionScaffold(
            initOp = Some(graph.globalVariablesInitializer()),
            localInitOp = Some(ControlFlow.group(Set(inputInitializer, graph.localVariablesInitializer()))),
            saver = Some(saver)))
        try {
          while (!session.shouldStop)
            session.run(targets = trainingOps.trainOp)
        } catch {
          case e if RECOVERABLE_EXCEPTIONS.contains(e.getClass) => session.close()
          case e: Throwable =>
            session.closeWithoutHookEnd()
            throw e
        } finally {
          if (!session.closed)
            session.close()
        }
      }
    }
  }

  /** Infers output (i.e., computes predictions) for `input` using the model managed by this estimator.
    *
    * `input` can be of one of the following types:
    *
    *   - A [[Dataset]], in which case this method returns an iterator over `(input, output)` tuples corresponding to
    *     each element in the dataset. Note that the predictions are computed lazily in this case, whenever an element
    *     is requested from the returned iterator.
    *   - A single input of type `IT`, in which case this method returns a prediction of type `I`.
    *
    * Note that, `ModelInferenceOutput` refers to the tensor type that corresponds to the symbolic type `I`. For
    * example, if `I` is `(Output, Output)`, then `ModelInferenceOutput` will be `(Tensor, Tensor)`.
    *
    * @param  input Input for the predictions.
    * @return Either an iterator over `(IT, ModelInferenceOutput)` tuples, or a single element of type `I`, depending on
    *         the type of `input`.
    */
  override def infer[InferInput, InferOutput, ModelInferenceOutput](
      input: InferInput
  )(implicit
      evFetchableIO: Fetchable.Aux[IO, IT],
      evFetchableI: Fetchable.Aux[I, ModelInferenceOutput],
      evFetchableIIO: Fetchable.Aux[(IO, I), (IT, ModelInferenceOutput)],
      ev: Estimator.SupportedInferInput[InferInput, InferOutput, IT, IO, ID, IS, ModelInferenceOutput]
  ): InferOutput = {
    inferWithHooks(input)(evFetchableIO, evFetchableI, evFetchableIIO, ev)
  }

  /** Infers output (i.e., computes predictions) for `input` using the model managed by this estimator.
    *
    * This method requires that a checkpoint can be found in either `checkpointPath`, if provided, or in this
    * estimator's working directory. It first loads the trained parameter values from the checkpoint specified by
    * `checkpointPath` or from the latest checkpoint found in the working directory, and it then computes predictions
    * for `input`.
    *
    * `input` can be of one of the following types:
    *
    *   - A [[Dataset]], in which case this method returns an iterator over `(input, output)` tuples corresponding to
    *     each element in the dataset. Note that the predictions are computed lazily in this case, whenever an element
    *     is requested from the returned iterator.
    *   - A single input of type `IT`, in which case this method returns a prediction of type `I`.
    *
    * Note that, `ModelInferenceOutput` refers to the tensor type that corresponds to the symbolic type `I`. For
    * example, if `I` is `(Output, Output)`, then `ModelInferenceOutput` will be `(Tensor, Tensor)`.
    *
    * If `hooks` is provided, it overrides the value provided in the constructor of this estimator.
    *
    * @param  input          Input for the predictions.
    * @param  hooks          Hooks to use while making predictions.
    * @param  checkpointPath Path to a checkpoint file to use. If `null`, then the latest checkpoint found in this
    *                        estimator's working directory will be used.
    * @return Either an iterator over `(IT, ModelInferenceOutput)` tuples, or a single element of type `I`, depending on
    *         the type of `input`.
    * @throws CheckpointNotFoundException If no checkpoint could be found. This can happen if `checkpointPath` is `null`
    *                                     and no checkpoint could be found in this estimator's working directory.
    */
  @throws[CheckpointNotFoundException]
  def inferWithHooks[InferInput, InferOutput, ModelInferenceOutput](
      input: InferInput,
      hooks: Seq[Hook] = this.hooks,
      checkpointPath: Path = null
  )(implicit
      evFetchableIO: Fetchable.Aux[IO, IT],
      evFetchableI: Fetchable.Aux[I, ModelInferenceOutput],
      evFetchableIIO: Fetchable.Aux[(IO, I), (IT, ModelInferenceOutput)],
      ev: Estimator.SupportedInferInput[InferInput, InferOutput, IT, IO, ID, IS, ModelInferenceOutput]
  ): InferOutput = {
    // Check that the model has been trained.
    val _checkpointPath = Option(checkpointPath).orElse(workingDir.flatMap(Saver.latestCheckpoint(_)))
    if (_checkpointPath.isEmpty)
      throw CheckpointNotFoundException(
        "No checkpoint was found. Please provide a valid 'workingDir' the estimator configuration, or a path to a " +
            "valid checkpoint file through the 'checkpointPath' argument.")
    val model = modelFunction(configuration)
    val graph = Graph()
    Op.createWith(graph) {
      graph.setRandomSeed(randomSeed)
      Counter.getOrCreate(Graph.Keys.GLOBAL_EPOCH, local = false)
      Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, local = false)
      val inferenceOps = Op.createWithNameScope("Model")(model.buildInferenceOps())
      val inputInitializer = inferenceOps.inputIterator.createInitializer(ev.toDataset(input))
      val saver = getOrCreateSaver()
      val session = MonitoredSession(
        ChiefSessionCreator(
          sessionScaffold = SessionScaffold(
            initOp = Some(graph.globalVariablesInitializer()),
            localInitOp = Some(ControlFlow.group(Set(inputInitializer, graph.localVariablesInitializer()))),
            saver = Some(saver)),
          sessionConfig = configuration.sessionConfig,
          checkpointPath = workingDir),
        hooks, shouldRecover = true)
      val output = ev.convertFetched(new Iterator[(IT, ModelInferenceOutput)] {
        override def hasNext: Boolean = session.shouldStop
        override def next(): (IT, ModelInferenceOutput) = {
          try {
            session.run(fetches = (inferenceOps.input, inferenceOps.output))
          } catch {
            case e: Throwable =>
              session.closeWithoutHookEnd()
              throw e
          }
        }
      })
      if (!session.closed)
        session.close()
      output
    }
  }

  /** Evaluates the model managed by this estimator given the provided evaluation data, `data`.
    *
    * The evaluation process is iterative. In each step, a data batch is obtained from `data` and internal metric value
    * accumulators are updated. The number of steps to perform is controlled through the `maxSteps` argument. If set to
    * `-1`, then all batches from `data` will be processed.
    *
    * If `metrics` is provided, it overrides the value provided in the constructor of this estimator.
    *
    * @param  data           Evaluation dataset. Each element is a tuple over input and training inputs (i.e.,
    *                        supervision labels).
    * @param  metrics        Evaluation metrics to use.
    * @param  maxSteps       Maximum number of evaluation steps to perform. If `-1`, the evaluation process will run
    *                        until `data` is exhausted.
    * @param  saveSummaries  Boolean indicator specifying whether to save the evaluation results as summaries in the
    *                        working directory of this estimator.
    * @param  name           Name for this evaluation. If provided, it will be used to generate an appropriate directory
    *                        name for the resulting summaries. If `saveSummaries` is `false`, this argument has no
    *                        effect. This is useful if the user needs to run multiple evaluations on different data
    *                        sets, such as on training data vs test data. Metrics for different evaluations are saved in
    *                        separate folders, and appear separately in TensorBoard.
    * @return Evaluation metric values at the end of the evaluation process. The return sequence matches the ordering of
    *         `metrics`.
    * @throws InvalidArgumentException If `saveSummaries` is `true`, but the estimator has no working directory
    *                                  specified.
    */
  @throws[InvalidArgumentException]
  override def evaluate(
      data: Dataset[TT, TO, TD, TS],
      metrics: Seq[Metric[EI, Output]] = this.evaluationMetrics,
      maxSteps: Long = -1L,
      saveSummaries: Boolean = true,
      name: String = null): Seq[Tensor] = {
    evaluateWithHooks(data, metrics, maxSteps, saveSummaries = saveSummaries, name = name)
  }

  /** Evaluates the model managed by this estimator given the provided evaluation data, `data`.
    *
    * This method requires that a checkpoint can be found in either `checkpointPath`, if provided, or in this
    * estimator's working directory. It first loads the trained parameter values from the checkpoint specified by
    * `checkpointPath` or from the latest checkpoint found in the working directory, and it then computes predictions
    * for `input`.
    *
    * The evaluation process is iterative. In each step, a data batch is obtained from `data` and internal metric value
    * accumulators are updated. The number of steps to perform is controlled through the `maxSteps` argument. If set to
    * `-1`, then all batches from `data` will be processed.
    *
    * If `hooks` or `metrics` are provided, they override the values provided in the constructor of this estimator.
    *
    * @param  data           Evaluation dataset. Each element is a tuple over input and training inputs (i.e.,
    *                        supervision labels).
    * @param  metrics        Evaluation metrics to use.
    * @param  maxSteps       Maximum number of evaluation steps to perform. If `-1`, the evaluation process will run
    *                        until `data` is exhausted.
    * @param  hooks          Hooks to use while evaluating.
    * @param  checkpointPath Path to a checkpoint file to use. If `null`, then the latest checkpoint found in this
    *                        estimator's working directory will be used.
    * @param  saveSummaries  Boolean indicator specifying whether to save the evaluation results as summaries in the
    *                        working directory of this estimator.
    * @param  name           Name for this evaluation. If provided, it will be used to generate an appropriate directory
    *                        name for the resulting summaries. If `saveSummaries` is `false`, this argument has no
    *                        effect. This is useful if the user needs to run multiple evaluations on different data
    *                        sets, such as on training data vs test data. Metrics for different evaluations are saved in
    *                        separate folders, and appear separately in TensorBoard.
    * @return                Evaluation metric values at the end of the evaluation process. The return sequence matches
    *                        the ordering of `metrics`.
    * @throws CheckpointNotFoundException If no checkpoint could be found. This can happen if `checkpointPath` is `null`
    *                                     and no checkpoint could be found in this estimator's working directory.
    * @throws InvalidArgumentException    If `saveSummaries` is `true`, but the estimator has no working directory
    *                                     specified.
    */
  @throws[CheckpointNotFoundException]
  @throws[InvalidArgumentException]
  def evaluateWithHooks(
      data: Dataset[TT, TO, TD, TS],
      metrics: Seq[Metric[EI, Output]] = this.evaluationMetrics,
      maxSteps: Long = -1L,
      hooks: Seq[Hook] = this.hooks,
      checkpointPath: Path = null,
      saveSummaries: Boolean = true,
      name: String = null): Seq[Tensor] = {
    // Check that the model has been trained.
    val _checkpointPath = Option(checkpointPath).orElse(workingDir.flatMap(Saver.latestCheckpoint(_)))
    if (_checkpointPath.isEmpty)
      throw CheckpointNotFoundException(
        "No checkpoint was found. Please provide a valid 'workingDir' the estimator configuration, or a path to a " +
            "valid checkpoint file through the 'checkpointPath' argument.")
    val model = modelFunction(configuration)
    val graph = Graph()
    Op.createWith(graph) {
      graph.setRandomSeed(randomSeed)
      val evaluationOps = Op.createWithNameScope("Model")(model.buildEvaluationOps(metrics))
      val inputInitializer = evaluationOps.inputIterator.createInitializer(data)
      Counter.getOrCreate(Graph.Keys.GLOBAL_EPOCH, local = false)
      val globalStep = Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, local = false)
      val evalStep = Counter.getOrCreate(Graph.Keys.EVAL_STEP, local = true)
      val evalStepUpdate = evalStep.assignAdd(1)
      val evalUpdateOps = ControlFlow.group(evaluationOps.metricUpdates.map(_.op).toSet + evalStepUpdate.op)
      val allHooks = mutable.ListBuffer(hooks: _*)
      allHooks += StopEvaluationHook(maxSteps)
      val saver = getOrCreateSaver()
      val session = MonitoredSession(
        ChiefSessionCreator(
          master = configuration.evaluationMaster,
          sessionScaffold = SessionScaffold(
            initOp = Some(graph.globalVariablesInitializer()),
            localInitOp = Some(ControlFlow.group(Set(inputInitializer, graph.localVariablesInitializer()))),
            saver = Some(saver)),
          sessionConfig = configuration.sessionConfig,
          checkpointPath = workingDir),
        hooks, shouldRecover = true)
      FileBasedEstimator.logger.info("Starting evaluation.")
      val (step, metricValues) = {
        try {
          val step = session.run(fetches = globalStep.value).scalar.asInstanceOf[Long]
          while (!session.shouldStop)
            session.run(targets = evalUpdateOps)
          (step, session.run(fetches = evaluationOps.metricValues))
        } catch {
          case e if RECOVERABLE_EXCEPTIONS.contains(e.getClass) =>
            session.close()
            (-1L, Seq.empty[Tensor])
          case e: Throwable =>
            session.closeWithoutHookEnd()
            throw e
        }
      }
      if (!session.closed)
        session.close()
      FileBasedEstimator.logger.info("Finished evaluation.")
      FileBasedEstimator.logger.info("Saving evaluation results.")
      if (saveSummaries)
        saveEvaluationSummaries(step, metrics, metricValues, name)
      metricValues
    }
  }
}

object FileBasedEstimator {
  private[estimators] val logger = Logger(LoggerFactory.getLogger("Learn / Estimator"))

  def apply[IT, IO, ID, IS, I, TT, TO, TD, TS, EI](
      modelFunction: Estimator.ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, EI],
      configurationBase: Configuration = null,
      stopCriteria: StopCriteria = StopCriteria(),
      hooks: Seq[Hook] = Seq.empty,
      chiefOnlyHooks: Seq[Hook] = Seq.empty,
      tensorBoardConfig: TensorBoardConfig = null,
      evaluationMetrics: Seq[Metric[EI, Output]] = Seq.empty
  ): FileBasedEstimator[IT, IO, ID, IS, I, TT, TO, TD, TS, EI] = {
    new FileBasedEstimator(modelFunction, configurationBase)
  }
}