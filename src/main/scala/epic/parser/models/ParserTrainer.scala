package epic.parser.models

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import epic.framework._
import epic.parser._
import breeze.linalg._
import breeze.optimize._
import epic.trees.{ProcessedTreebank, AnnotatedLabel, TreeInstance}
import breeze.config.{CommandLineParser, Help}
import com.typesafe.scalalogging.slf4j.LazyLogging
import epic.parser.projections.{GrammarRefinements, OracleParser, ParserChartConstraintsFactory}
import epic.util.CacheBroker
import epic.parser.ParserParams.XbarGrammar
import breeze.util._
import epic.trees.annotations._
import java.io.File
import epic.constraints.{ChartConstraints, CachedChartConstraintsFactory}
import breeze.util.Implicits._
import breeze.optimize.FirstOrderMinimizer.OptParams
import epic.parser.ParseEval.Statistics
import epic.features.LongestFrequentSuffixFeaturizer.LongestFrequentSuffix
import epic.features.LongestFrequentSuffixFeaturizer
import epic.dense.AdadeltaGradientDescentDVD


/**
 * The main entry point for training discriminative parsers.
 * Has a main method inherited from ParserPipeline.
 * Use --help to see options, or just look at the Params class.
 *
 *
 */
object ParserTrainer extends epic.parser.ParserPipeline with LazyLogging {
  
  case class ExtraPTParams(useSGD: Boolean = false,
                           momentum: Double = 0.95,
                           useAdagradCombo: Boolean = false,
                           computeTrainLL: Boolean = true)

  case class Params(@Help(text="What parser to build. LatentModelFactory,StructModelFactory,LexModelFactory,SpanModelFactory")
                    modelFactory: ParserExtractableModelFactory[AnnotatedLabel, String],
                    @Help(text="Name for the parser for saving and logging. will be inferrred if not provided.")
                    name: String = null,
                    implicit val cache: CacheBroker,
                    @Help(text="path for a baseline parser for computing constraints. will be built automatically if not provided.")
                    parser: File = null,
                    opt: OptParams,
                    @Help(text="How often to run on the dev set.")
                    iterationsPerEval: Int = 100,
                    @Help(text="How many iterations to run.")
                    maxIterations: Int = 1002,
                    @Help(text="How often to look at a small set of the dev set.")
                    iterPerValidate: Int = 30,
                    @Help(text="How many threads to use, default is to use whatever Scala thinks is best.")
                    threads: Int = -1,
                    @Help(text="Should we randomize weights? Some models will force randomization.")
                    randomize: Boolean = false,
                    @Help(text="Scale of random weight initialization")
                    initWeightsScale: Double = 1E-2,
                    @Help(text="Scale of random weight initialization")
                    initializerSpec: String = "",
                    @Help(text="True if we should determinimize training (remove randomness associated with random minibatches)")
                    determinizeTraining: Boolean = false,
                    @Help(text="True if we should train two models and ram them together")
                    ensemble: Boolean = false,
                    @Help(text="Use Adadelta for optimiziation instead of Adagrad")
                    useAdadelta: Boolean = false,
                    @Help(text="Should we enforce reachability? Can be useful if we're pruning the gold tree.")
                    enforceReachability: Boolean = true,
                    @Help(text="Whether or not we use constraints. Not using constraints is very slow.")
                    useConstraints: Boolean = true,
                    @Help(text="Should we check the gradient to make sure it's coded correctly?")
                    checkGradient: Boolean = false,
                    @Help(text="check specific indices, in addition to doing a full search.")
                    checkGradientsAt: String = null,
                    @Help(text="check specific indices, in addition to doing a full search.")
                    maxParseLength: Int = 70,
                    annotator: TreeAnnotator[AnnotatedLabel, String, AnnotatedLabel] = GenerativeParser.defaultAnnotator(),
                    extraPTParams: ExtraPTParams = ExtraPTParams())
  protected val paramManifest = manifest[Params]

  def trainParser( trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]],
                  validate: (Parser[AnnotatedLabel, String]) => Statistics, params: Params) = {
    import params._
    import extraPTParams._
    
//    if(threads >= 1)
//      collection.parallel.ForkJoinTasks.defaultForkJoinPool.setParallelism(params.threads)

    val initialParser = params.parser match {
      case null =>
        val (grammar, lexicon) = XbarGrammar().xbarGrammar(trainTrees)
        GenerativeParser.annotatedParser(grammar, lexicon, annotator, trainTrees)
//        GenerativeParser.annotatedParser(grammar, lexicon, Xbarize(), trainTrees)
      case f =>
        readObject[Parser[AnnotatedLabel, String]](f)
    }

    val constraints = {

      val maxMarginalized = initialParser.copy(marginalFactory=initialParser.marginalFactory match {
        case StandardChartFactory(ref, mm) => StandardChartFactory(ref, maxMarginal = true)
        case x => x
      })

      val uncached = new ParserChartConstraintsFactory[AnnotatedLabel, String](maxMarginalized, {(_:AnnotatedLabel).isIntermediate})
      new CachedChartConstraintsFactory[AnnotatedLabel, String](uncached)
    }

    var theTrees = trainTrees.toIndexedSeq.filterNot(sentTooLong(_, params.maxParseLength))

    if(useConstraints && enforceReachability)  {
      val treebankGrammar = GenerativeParser.annotated(initialParser.topology, initialParser.lexicon, TreeAnnotator.identity, trainTrees)
      val markovizedGrammar = GenerativeParser.annotated(initialParser.topology, initialParser.lexicon, annotator, trainTrees)
      val proj = new OracleParser(treebankGrammar, markovizedGrammar)
      theTrees = theTrees.par.map(ti => ti.copy(tree=proj.forTree(ti.tree, ti.words, constraints.constraints(ti.words)))).seq.toIndexedSeq
    }

    val baseMeasure = if(useConstraints) {
      constraints
    } else {
      ChartConstraints.Factory.noSparsity[AnnotatedLabel, String]
    }

    println("Building model")
    val model = modelFactory.make(theTrees, initialParser.topology, initialParser.lexicon, constraints)
    val obj = new ModelObjective(model, theTrees, params.threads)
    val cachedObj = new CachedBatchDiffFunction(obj)
    val init = if (model.isInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]) {
      println("Initializing weights custom for model " + model.getClass)
      model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]].initialWeightVector(initWeightsScale, initializerSpec)
    } else {
      obj.initialWeightVector(randomize, initWeightsScale)
    }
    if(checkGradient) {
      val cachedObj2 = new CachedBatchDiffFunction(new ModelObjective(model, theTrees.take(opt.batchSize), params.threads))
      val defaultIndices = (0 until 10).map(i => if(i < 0) model.featureIndex.size + i else i)
      val indices = if (model.isInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]) {
        val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
        if (castModel.transforms.size > 0) {
          castModel.transforms(0).getInterestingWeightIndicesForGradientCheck(0)
        } else {
          defaultIndices
        }
      } else {
        defaultIndices
      }
//      val indices = (0 until 10).map(i => 283316 + i)
      println("testIndices: " + indices)
      GradientTester.testIndices(cachedObj2, init, indices, toString={(i: Int) => model.featureIndex.get(i).toString}, skipZeros = true)
      println("test")
      GradientTester.test(cachedObj2, init, toString={(i: Int) => model.featureIndex.get(i).toString}, skipZeros = false)
    }

    type OptState = FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State
    def evalAndCache(pair: (OptState, Int)) {
      val (state, iter) = pair
      val weights = state.x
      if (iter % iterPerValidate == 0) {
        logger.info("Validating...")
        val parser = model.extractParser(weights)
        val stats = validate(parser)
        logger.info("Overall statistics for validation: " + stats)
      }
    }


    val name = Option(params.name).orElse(Option(model.getClass.getSimpleName).filter(_.nonEmpty)).getOrElse("DiscrimParser")
    val itr: Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State] = if (determinizeTraining) {
      val scanningBatchesObj = cachedObj.withScanningBatches(params.opt.batchSize)
      if (useAdadelta) {
        println("OPTIMIZATION: Adadelta")
        new AdadeltaGradientDescentDVD(params.opt.maxIterations, momentum).iterations(scanningBatchesObj, init).
            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
//      } else if (useSGD) {
//        println("OPTIMIZATION: SGDMOM")
//        new StochasticGradientDescentMomentumDVD(params.opt.maxIterations, params.opt.alpha, momentum).iterations(scanningBatchesObj, init).
//            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
//      } else if (useAdagradCombo) {
//        println("OPTIMIZATION: AdagradCombo")
//        val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
//        require(castModel.maybeSparseSurfaceFeaturizer.isDefined, "Can't do combo optimization without combination")
//        val idxToSwitchAt = castModel.index.componentOffset(castModel.index.indices.size - 1)
//        new CombinationAdaptiveGradientDescentDVD(params.opt.regularization, 0.01, params.opt.regularization, 0.1, idxToSwitchAt, params.opt.maxIterations, params.opt.tolerance).iterations(scanningBatchesObj, init).
//            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
      } else {
        println("OPTIMIZATION: Adagrad")
        params.opt.iterations(scanningBatchesObj, init).asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
      }
    } else {
      if (useAdadelta) {
        println("OPTIMIZATION: Adadelta")
        new AdadeltaGradientDescentDVD(params.opt.maxIterations, momentum).iterations(cachedObj.withRandomBatches(params.opt.batchSize), init).
            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
//      } else if (useSGD) {
//        println("OPTIMIZATION: SGDMOM")
//        new StochasticGradientDescentMomentumDVD(params.opt.maxIterations, params.opt.alpha, momentum).iterations(cachedObj.withRandomBatches(params.opt.batchSize), init).
//            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
//      } else if (useAdagradCombo) {
//        println("OPTIMIZATION: AdagradCombo")
//        val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
//        require(castModel.maybeSparseSurfaceFeaturizer.isDefined, "Can't do combo optimization without combination")
//        val idxToSwitchAt = castModel.index.componentOffset(castModel.index.indices.size - 1)
//        new CombinationAdaptiveGradientDescentDVD(params.opt.alpha, 0.01, params.opt.alpha, 0.1, idxToSwitchAt, params.opt.maxIterations, params.opt.tolerance).iterations(cachedObj.withRandomBatches(params.opt.batchSize), init).
//            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
      } else {
        println("OPTIMIZATION: Adagrad")
        params.opt.iterations(cachedObj, init)
      }
    }
    if (ensemble) {
      val weights1 = itr.take(maxIterations).last.x
      // Hard-wired to use Adadelta
      val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
      val initParams2 = castModel.initialWeightVector(initWeightsScale, initializerSpec, trulyRandom = true)
      val itr2 = new AdadeltaGradientDescentDVD(params.opt.maxIterations).iterations(cachedObj.withRandomBatches(params.opt.batchSize), initParams2).
            asInstanceOf[Iterator[FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State]]
      println("Optimizing second parser")
      val weights2 = itr2.take(maxIterations).last.x
      println("Optimized both parsers")
      val clonedModel = castModel.cloneModelForEnsembling
      val mergedWeights = castModel.mergeWeightsForEnsembling(weights1, weights2)
      Seq(("ComboParser-Final", clonedModel.extractParser(mergedWeights))).iterator
    } else {
      // Normal execution
      for ((state, iter) <- itr.take(maxIterations).zipWithIndex.tee(evalAndCache _)
           if iter != 0 && iter % iterationsPerEval == 0 || evaluateNow) yield try {
        val parser = if (model.isInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]) {
          val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
          if (castModel.batchNormalization) {
//            castModel.extractParser(state.x, theTrees)
            model.extractParser(state.x)
          } else {
            model.extractParser(state.x)
          }
        } else {
          model.extractParser(state.x)
        }
        if (iter + iterationsPerEval >= maxIterations && computeTrainLL) {
          computeLL(trainTrees, model, state.x)
        }
        (s"$name-$iter", parser)
      } catch {
        case e: Exception => e.printStackTrace(); throw e
      }
    }
  }
  
  def sentTooLong(p: TreeInstance[AnnotatedLabel, String], maxLength: Int): Boolean = {
    p.words.count(x => x == "'s" || x(0).isLetterOrDigit) > maxLength
  }
  
  def evaluateNow = {
    val sentinel = new File("EVALUATE_NOW")
    if(sentinel.exists()) {
      sentinel.delete()
      logger.info("Evaluating now!!!!")
      true
    } else {
      false
    }
    
  }
  
  def computeLL(trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]], model: Model[TreeInstance[AnnotatedLabel, String]], weights: DenseVector[Double]) {
    println("Computing final log likelihood on the whole training set...")
    val inf = if (model.isInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]) {
      val castModel = model.asInstanceOf[PositionalTransformModel[AnnotatedLabel, AnnotatedLabel, String]]
      if (castModel.batchNormalization) {
        val inf = castModel.inferenceFromWeights(weights).forTesting
        inf.relativizeToData(trainTrees.slice(0, Math.min(trainTrees.size, 200)))
        inf
      } else {
        model.inferenceFromWeights(weights).forTesting
      }
    } else {
      model.inferenceFromWeights(weights).forTesting
    }
    val ll = trainTrees.par.aggregate(0.0)((currLL, trainTree) => { 
      try {
        val s = inf.scorer(trainTree)
        currLL + inf.goldMarginal(s, trainTree).logPartition - inf.marginal(s, trainTree).logPartition
      } catch {
        case e: Exception => println("Couldn't parse")
        currLL
      }
    }, _ + _)
    println("Log likelihood on " + trainTrees.size + " examples: " + ll)
  }
}


object Suffixes extends LazyLogging {
  def main(args: Array[String]):Unit = {
    val tb = CommandLineParser.readIn[ProcessedTreebank](args)

    val counts = GenerativeParser.extractCounts(tb.trainTrees)._1

    val marginalized: Counter[String, Double] = sum(counts(::, *))

    val lfs = LongestFrequentSuffixFeaturizer(marginalized)

    for(ti <- tb.trainTrees) {
      val suffixes = lfs.lookupSentence(ti.words)
      println("original: " +  ti.words.mkString(" "))
      println("suffixes: " +  suffixes.mkString(" "))
    }


  }


}
