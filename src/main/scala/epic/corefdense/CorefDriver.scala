package epic.corefdense

import edu.berkeley.nlp.entity.coref.DocumentInferencerBasic
import edu.berkeley.nlp.entity.coref.MentionPropertyComputer
import edu.berkeley.nlp.entity.sem.QueryCountsBundle
import edu.berkeley.nlp.entity.coref.NumberGenderComputer
import edu.berkeley.nlp.entity.sem.BasicWordNetSemClasser
import edu.berkeley.nlp.entity.coref.PairwiseScorer
import edu.berkeley.nlp.entity.coref.CorefEvaluator
import edu.berkeley.nlp.entity.coref.DocumentGraph
import edu.berkeley.nlp.entity.coref.CorefDoc
import edu.berkeley.nlp.entity.lang.Language
import edu.berkeley.nlp.entity.sem.SemClasser
import edu.berkeley.nlp.entity.ConllDocReader
import edu.berkeley.nlp.entity.coref.CorefPruner
import edu.berkeley.nlp.entity.coref.CorefDocAssembler
import edu.berkeley.nlp.entity.coref.LexicalCountsBundle
import edu.berkeley.nlp.entity.WordNetInterfacer
import edu.berkeley.nlp.entity.coref.CorefFeaturizerTrainer
import edu.berkeley.nlp.entity.coref.OrderedClustering
import edu.berkeley.nlp.futile.util.Logger
import edu.berkeley.nlp.futile.fig.basic.Indexer
import edu.berkeley.nlp.entity.coref.PairwiseIndexingFeaturizerJoint
import epic.dense.TanhTransform
import epic.dense.IdentityTransform
import epic.dense.Word2VecIndexed
import epic.parser.models.PositionalNeuralModelFactory
import edu.berkeley.nlp.futile.LightRunner
import epic.dense.AffineTransform
import breeze.linalg.DenseVector
import epic.dense.Word2VecSurfaceFeaturizerIndexed
import edu.berkeley.nlp.entity.coref.PairwiseLossFunctions
import edu.berkeley.nlp.entity.coref.ConjScheme
import edu.berkeley.nlp.entity.coref.ConjFeatures
import edu.berkeley.nlp.entity.coref.FeatureSetSpecification
import epic.features.SegmentedIndex
import epic.framework.Feature
import scala.collection.mutable.HashSet
import epic.dense.Word2Vec
import epic.dense.CachingLookupTransform
import epic.dense.CachingLookupAndAffineTransformDense
import epic.dense.AffineOutputTransform
import epic.dense.EmbeddingsTransform
import epic.dense.Transform
import epic.dense.CachingLookupAffineWithSparse

object CorefDriver {
  
  // ARGUMENTS
  
  val numberGenderDataPath = "data/gender.data"
  val docSuffix = "auto_conll"
  val wordNetPath = ""
  val pruningStrategy = "distance:10000:5000" // TODO: FIGURE OUT PRUNING
  
  // Doesn't matter since we use batch size of 1 anyway...
  val parallel = false
  
  val useAdadelta = false
  val rho = 0.95
  
  val numItrs = 5
  val eta = 1.0F
  val reg = 0.001F
  val initWeightsScale = 0.01
  val batchSize = 1
  
  val lowercasedVectors: Boolean = false
    
  val featsToUse = ""
  
  val lossFcn = "customLoss-0.1-3-1";
  val conllEvalScriptPath = "scorer/v7/scorer.pl"
  val word2vecPath = ""
  
  val useDiscourseNewTransform: Boolean = false;
  val backpropIntoEmbeddings: Boolean = false;
  val hiddenSize = 100
  val numHiddenLayers = 1
  val nonLinType = "relu"
  val dropoutRate = 0.0
  val surfaceFeats = ""
  val useOutputLayerFeatures = false
  val outputLayerFeatures = ""
  
  val sparseFeatStr = "FINAL"
  
  val trainPath = "";
  val trainSize = -1;
  val testPath = "";
  val testSize = -1;
  
  val checkEmpiricalGradient = false
  
  val oldStyleCorefNN = false;
  
  val useSparseNetFeats = false
  
  ////////////////////

  def loadCorefDocs(path: String, size: Int, suffix: String, maybeNumberGenderComputer: Option[NumberGenderComputer]): Seq[CorefDoc] = {
    val docs = ConllDocReader.loadRawConllDocsWithSuffix(path, size, suffix);
    val assembler = CorefDocAssembler(Language.ENGLISH, false);
    val mentionPropertyComputer = new MentionPropertyComputer(maybeNumberGenderComputer);
    val corefDocs = docs.map(doc => assembler.createCorefDoc(doc, mentionPropertyComputer));
    CorefDocAssembler.checkGoldMentionRecall(corefDocs);
    corefDocs;
  }
  
  def preprocessDocsCacheResources(allDocGraphs: Seq[DocumentGraph]) {
    if (wordNetPath != "") {
      val wni = new WordNetInterfacer(wordNetPath);
      allDocGraphs.foreach(_.cacheWordNetInterfacer(wni));
    }
  }
  
  def prepareTestDocuments(devPath: String, devSize: Int, corefPruner: CorefPruner): Seq[DocumentGraph] = {
    val numberGenderComputer = NumberGenderComputer.readBergsmaLinData(numberGenderDataPath);
    val devDocs = loadCorefDocs(devPath, devSize, docSuffix, Some(numberGenderComputer));
    val devDocGraphs = devDocs.map(new DocumentGraph(_, false));
    preprocessDocsCacheResources(devDocGraphs);
    corefPruner.pruneAll(devDocGraphs);
    devDocGraphs;
  }
  
  def main(args: Array[String]) {
    LightRunner.initializeOutput(CorefDriver.getClass())
    LightRunner.populateScala(CorefDriver.getClass(), args)
    val numberGenderComputer = NumberGenderComputer.readBergsmaLinData(numberGenderDataPath);
    val queryCounts: Option[QueryCountsBundle] = None;
    val trainDocs = loadCorefDocs(trainPath, trainSize, docSuffix, Some(numberGenderComputer));
    // Randomize
    val trainDocsReordered = new scala.util.Random(0).shuffle(trainDocs);
    val lexicalCounts = LexicalCountsBundle.countLexicalItems(trainDocs, 20);
    val semClasser: Option[SemClasser] = Some(new BasicWordNetSemClasser);
    val trainDocGraphs = trainDocsReordered.map(new DocumentGraph(_, true));
    preprocessDocsCacheResources(trainDocGraphs);
    val corefPruner = CorefPruner.buildPruner(pruningStrategy)
    corefPruner.pruneAll(trainDocGraphs);
    
    val trainVoc = trainDocGraphs.map(docGraph => docGraph.getMentions.map(ment => CorefNeuralModel.extractRelevantMentionWords(ment, surfaceFeats).toSet).reduce(_ ++ _)).reduce(_ ++ _)
    
    val featureIndexer = new Indexer[String]();
    val word2vecRaw = Word2Vec.smartLoadVectorsForVocabulary(word2vecPath.split(":"), trainVoc.map(str => Word2Vec.convertWord(str, lowercasedVectors)), maxVectorLen = Int.MaxValue, inputVectorBias = true)
    val word2vecRawDoubleVect = word2vecRaw.map(keyValue => (keyValue._1 -> keyValue._2.map(_.toDouble)))
    val word2vecIndexed = Word2VecIndexed(word2vecRawDoubleVect, (str: String) => Word2Vec.convertWord(str, lowercasedVectors))
    
//    val corefNeuralModel 
    
    featureIndexer.getIndex(PairwiseIndexingFeaturizerJoint.UnkFeatName);
//    val featurizer = new SimplePairwiseIndexingFeaturizerJoint(featureIndexer, featsToUse.split(",").toSet)
    val featureSetSpec = FeatureSetSpecification(sparseFeatStr, ConjScheme.COARSE_BOTH, ConjFeatures.TYPE_OR_CANONICAL_PRON, "", "");
    val featurizer = new PairwiseIndexingFeaturizerJoint(featureIndexer, featureSetSpec, lexicalCounts, None, None)
    val lossFcnObj = PairwiseLossFunctions(lossFcn)
    
//    val (transform, corefNeuralModel): (AffineTransform[DenseVector[Double],DenseVector[Double]],CorefPredictor]) = if (oldStyleCorefNN) {
    require(!oldStyleCorefNN)
//      val vecSize = word2vecIndexed.wordRepSize * CorefNeuralModel2.extractRelevantMentionWords(trainDocGraphs.head.getMention(0), trainDocGraphs.head.getMention(0)).size
//      Logger.logss("Net size: " + vecSize + " x " + hiddenSize + " x " + vecSize)
//      val transform = if (nonLinear) {
//        new AffineTransform(1, hiddenSize, new TanhTransform(new AffineTransform(hiddenSize, vecSize, new IdentityTransform[DenseVector[Double]]())))
//      } else {
//        new AffineTransform(1, vecSize, new IdentityTransform[DenseVector[Double]]())
//      }
//      Logger.logss("Transform index size: " + transform.index.size)
//      (transform, new CorefNeuralModel2(featurizer, transform, word2vecIndexed, lossFcnObj))
    
    val outputIndexer = new Indexer[String]
    val outputFeaturizer = new OutputFeaturizer(outputIndexer, outputLayerFeatures)
    OutputFeaturizer.featurizeAll(outputFeaturizer, trainDocGraphs)
    Logger.logss("Output features: " + outputIndexer.size)
    
    val dnOutputIndexer = new Indexer[String]
    val dnOutputFeaturizer = new OutputFeaturizer(dnOutputIndexer, outputLayerFeatures)
    OutputFeaturizer.featurizeAllDiscourseNew(dnOutputFeaturizer, trainDocGraphs)
    Logger.logss("Discourse new output features: " + dnOutputIndexer.size)
    
    val maybeSparseNetFeaturizer: Option[SimplePairwiseIndexingFeaturizerJoint] = if (useSparseNetFeats) {
      val feat = new SimplePairwiseIndexingFeaturizerJoint(SimplePairwiseIndexingFeaturizerJoint.makeTypeIndexer(trainDocGraphs))
      Logger.logss("Predefined feature domain sizes: " + feat.getPredefinedFeatureDomains.toSeq)
      Some(feat)
    } else {
      None
    }
    
    val numKeyWords = CorefNeuralModel.extractRelevantMentionWords(trainDocGraphs.head.getMention(0), surfaceFeats).size
    val vecSize = word2vecIndexed.wordRepSize * numKeyWords * 2
    val discourseNewVecSize = word2vecIndexed.wordRepSize * numKeyWords
//      Logger.logss("Net size: " + vecSize + " x " + hiddenSize + " x " + vecSize)
//      val transform = if (nonLinear) {
//        new AffineTransform(1, hiddenSize, new TanhTransform(new AffineTransform(hiddenSize, vecSize, new IdentityTransform[DenseVector[Double]]())))
//      } else {
//        new AffineTransform(1, vecSize, new IdentityTransform[DenseVector[Double]]())
//      }
//      Logger.logss("Transform index size: " + transform.index.size)
    val netOutputSize = if (useOutputLayerFeatures) outputIndexer.size else 1
    val transform = if (useSparseNetFeats) {
      buildNetWithSparse(word2vecIndexed, numKeyWords * 2, maybeSparseNetFeaturizer.get.getPredefinedFeatureDomains, hiddenSize, numHiddenLayers, netOutputSize, nonLinType, dropoutRate)
    } else {
      buildNet(word2vecIndexed, vecSize, hiddenSize, numHiddenLayers, netOutputSize, nonLinType, dropoutRate, backpropIntoEmbeddings, batchNormalization = false)
    }
    val maybeOutputFeaturizer = if (useOutputLayerFeatures) Some(outputFeaturizer) else None
    val dnTransform = if (useDiscourseNewTransform) {
      if (useOutputLayerFeatures) {
        Some(buildNet(word2vecIndexed, discourseNewVecSize, hiddenSize, numHiddenLayers, dnOutputIndexer.size, nonLinType, dropoutRate, backpropIntoEmbeddings, batchNormalization = false))
      } else {
        Some(buildNet(word2vecIndexed, discourseNewVecSize, hiddenSize, numHiddenLayers, 1, nonLinType, dropoutRate, backpropIntoEmbeddings, batchNormalization = false))
      }
    } else {
      None
    }
    val maybeDnOutputFeaturizer = if (useOutputLayerFeatures && useDiscourseNewTransform) Some(dnOutputFeaturizer) else None
    val corefNeuralModel = new CorefNeuralModel3(featurizer, transform, dnTransform, word2vecIndexed, lossFcnObj, surfaceFeats, maybeOutputFeaturizer, maybeDnOutputFeaturizer, maybeSparseNetFeaturizer)
    new CorefFeaturizerTrainer().featurizeBasic(trainDocGraphs, featurizer)
    
    
    val initialWeights = corefNeuralModel.getInitialWeights(initWeightsScale);
    Logger.logss(initialWeights.size + " weights in the model")
//    val numItrs = 100
    
    if (checkEmpiricalGradient) {
      val indices = HashSet[Int]();
      indices ++= 0 until 5
//      indices ++= ((vecSize + 1) * hiddenSize) until ((vecSize + 1) * hiddenSize + 5)
      indices ++= transform.index.size until transform.index.size + 5
      GeneralTrainer.checkGradientFromPoint(trainDocGraphs, corefNeuralModel, initialWeights, Array.tabulate(initialWeights.size)(i => 0.0), indices.toSet, verbose = true)
    }
    val weights = if (useAdadelta) {
      new GeneralTrainer(parallel).trainAdadelta(trainDocGraphs, corefNeuralModel, rho, batchSize, numItrs, initialWeights, verbose = true);
    } else {
      new GeneralTrainer(parallel).trainAdagrad(trainDocGraphs, corefNeuralModel, eta, reg, batchSize, numItrs, initialWeights, verbose = true);
    }
    
    
//    val basicFeaturizer = new PairwiseIndexingFeaturizerJoint(featureIndexer, featureSetSpec, lexicalCounts, queryCounts, semClasser);
//    val featurizerTrainer = 
//    featurizerTrainer.featurizeBasic(trainDocGraphs, basicFeaturizer);
//    PairwiseIndexingFeaturizer.printFeatureTemplateCounts(featureIndexer)
//
//    val basicInferencer = new DocumentInferencerBasic()
//    val lossFcnObjFirstPass = PairwiseLossFunctions(Driver.lossFcn);
//    val firstPassWeights = featurizerTrainer.train(trainDocGraphs,
//                                                   basicFeaturizer,
//                                                   Driver.eta.toFloat,
//                                                   Driver.reg.toFloat,
//                                                   Driver.batchSize,
//                                                   lossFcnObjFirstPass,
//                                                   Driver.numItrs,
//                                                   basicInferencer);
//    new PairwiseScorer(basicFeaturizer, firstPassWeights).pack;
    
    
    val devDocGraphs = prepareTestDocuments(testPath, testSize, corefPruner);
//    new CorefFeaturizerTrainer().featurizeBasic(devDocGraphs, scorer.featurizer);  // dev docs already know they are dev docs so they don't add features
//    val basicInferencer = new DocumentInferencerBasic();
//    val (allPredBackptrs, allPredClusterings) = basicInferencer.viterbiDecodeAllFormClusterings(devDocGraphs, scorer);
    Logger.startTrack("Decoding dev");
    val (allPredBackptrs, allPredClusterings) = corefNeuralModel.predictAllFormClusterings(devDocGraphs, weights)
    Logger.logss(CorefEvaluator.evaluateAndRender(devDocGraphs, allPredBackptrs, allPredClusterings, conllEvalScriptPath, "DEV: ", ""));
    Logger.endTrack();
    LightRunner.finalizeOutput()
  }
  
  
  def buildNetInnerTransforms(word2vecIndexed: Word2VecIndexed[String],
                              inputSize: Int,
                              numHidden: Int,
                              numHiddenLayers: Int,
                              nonLinType: String,
                              dropoutRate: Double,
                              backpropIntoEmbeddings: Boolean,
                              batchNormalization: Boolean): Transform[Array[Int],DenseVector[Double]] = {
    if (numHiddenLayers == 0) {
      new CachingLookupTransform(word2vecIndexed)
    } else {
      val baseTransformLayer = if (backpropIntoEmbeddings) {
        new EmbeddingsTransform(numHidden, inputSize, word2vecIndexed)
      } else {
        new CachingLookupAndAffineTransformDense(numHidden, inputSize, word2vecIndexed)
      }
      var currLayer = PositionalNeuralModelFactory.addNonlinearity(nonLinType, numHidden, dropoutRate, batchNormalization, baseTransformLayer)
      for (i <- 1 until numHiddenLayers) {
        currLayer = PositionalNeuralModelFactory.addNonlinearity(nonLinType, numHidden, dropoutRate, batchNormalization, new AffineTransform(numHidden, numHidden, currLayer))
      }
      currLayer
    }
  }
  
  def buildNet(word2vecIndexed: Word2VecIndexed[String],
               inputSize: Int,
               numHidden: Int,
               numHiddenLayers: Int,
               outputSize: Int,
               nonLinType: String,
               dropoutRate: Double,
               backpropIntoEmbeddings: Boolean,
               batchNormalization: Boolean): AffineOutputTransform[Array[Int]] = {
    val innerTransform = buildNetInnerTransforms(word2vecIndexed, inputSize, numHidden, numHiddenLayers, nonLinType, dropoutRate, backpropIntoEmbeddings, batchNormalization)
    new AffineOutputTransform(outputSize, if (numHiddenLayers >= 1) numHidden else inputSize, innerTransform)
  }
  
  
  
  def buildNetInnerTransformsWithSparse(word2vecIndexed: Word2VecIndexed[String],
                              inputWords: Int,
                              inputSparseFeatDomains: Array[Int],
                              numHidden: Int,
                              numHiddenLayers: Int,
                              nonLinType: String,
                              dropoutRate: Double): Transform[Array[Int],DenseVector[Double]] = {
    if (numHiddenLayers == 0) {
      new CachingLookupTransform(word2vecIndexed)
    } else {
      val baseTransformLayer = new CachingLookupAffineWithSparse(numHidden, inputWords, word2vecIndexed, inputSparseFeatDomains)
      var currLayer = PositionalNeuralModelFactory.addNonlinearity(nonLinType, numHidden, dropoutRate, false, baseTransformLayer)
      for (i <- 1 until numHiddenLayers) {
        currLayer = PositionalNeuralModelFactory.addNonlinearity(nonLinType, numHidden, dropoutRate, false, new AffineTransform(numHidden, numHidden, currLayer))
      }
      currLayer
    }
  }
  
  def buildNetWithSparse(word2vecIndexed: Word2VecIndexed[String],
                         inputWords: Int,
                         inputSparseFeatDomains: Array[Int],
                         numHidden: Int,
                         numHiddenLayers: Int,
                         outputSize: Int,
                         nonLinType: String,
                         dropoutRate: Double): AffineOutputTransform[Array[Int]] = {
    val innerTransform = buildNetInnerTransformsWithSparse(word2vecIndexed, inputWords, inputSparseFeatDomains, numHidden, numHiddenLayers, nonLinType, dropoutRate)
    new AffineOutputTransform(outputSize, if (numHiddenLayers >= 1) numHidden else inputWords * word2vecIndexed.wordRepSize, innerTransform)
  }
  
//  def getInterestingIndexSet(transform: AffineTransform[DenseVector[Double],DenseVector[Double]]) {
//    if (transform.index.isInstanceOf[SegmentedIndex[Feature,_]]) {
//      transform.index.asInstanceOf[SegmentedIndex[Feature]].
//    }
//    transform.get
//  }
}

