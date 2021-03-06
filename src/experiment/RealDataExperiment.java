package experiment;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import data_structures.Sequence;
import experiment.classifier.AbstractCompressedIBSM1NN;
import experiment.classifier.AbstractIBSM1NN;
import experiment.classifier.AbstractSTIFERFClassifier;
import experiment.classifier.MultiLabelCompressedIBSM1NN;
import experiment.classifier.MultiLabelIBSM1NN;
import experiment.classifier.MultiLabelSTIFERFClassifier;
import experiment.classifier.SingleLabelCompressedIBSM1NN;
import experiment.classifier.SingleLabelIBSM1NN;
import experiment.classifier.SingleLabelSTIFERFClassifier;
import io.IOService;

public class RealDataExperiment extends Experiment{

	private File singleLabelDataSetPath;
	private int shapeletFeatureCount;
	private int epsilon;
	private ExecutorService pool;
	private Random random;
	private int k;
	private File multLabelDataSetPath;

	public RealDataExperiment(ExecutorService pool, int epsilon, int shapeletFeatureCount, File singleLabelDatasetPath,File multLabelDataSetPath, Random random, int k) {
		this.pool = pool;
		this.epsilon = epsilon;
		this.shapeletFeatureCount = shapeletFeatureCount;
		this.singleLabelDataSetPath = singleLabelDatasetPath;
		this.multLabelDataSetPath = multLabelDataSetPath;
		this.random = random;
		this.k=k;
	}

	public void runExperiment() throws Exception {
		Map<String,List<ClassifierResult>> results = new LinkedHashMap<>();
		for(File dir : singleLabelDataSetPath.listFiles()){
			if(dir.isDirectory()){
				System.out.println("Processing " + dir.getName());
				List<ClassifierResult> resultList = singleLabelClassifierEvaluation(dir);
				results.put(dir.getName(), resultList);
			}
		}
		for(File dir :multLabelDataSetPath.listFiles()){
			if(dir.isDirectory() && dir.getName().equals("ASL-BU-2")){
				System.out.println("Processing " + dir.getName());
				List<ClassifierResult> resultList = multiLabelClassifierEvaluation(dir);
				results.put(dir.getName(), resultList);
			}
		}
		printExperimentResults(results);
	}

	private List<ClassifierResult> singleLabelClassifierEvaluation(File dir) throws IOException, Exception {
		List<Integer> classIds = IOService.readClassData(dir);
		List<Sequence> database = IOService.readSequenceData(dir);
		for(Sequence seq:database){
			seq.sortIntervals();
		}
		ClassifierResult ibsmResult = new ClassifierResult(AbstractIBSM1NN.getName());
		ClassifierResult compressedIBSMResult = new ClassifierResult(AbstractCompressedIBSM1NN.getName());
		ClassifierResult stifeResult = new ClassifierResult(AbstractSTIFERFClassifier.getName());
		int numDimensions = Sequence.getDimensionSet(database).size();
		int sequenceDuration = Sequence.getMaxDuration(database);
		List<Integer> allIndices = ExperimentUtil.getShuffledIndices(database, random);
		for(int i=0;i<k;i++){
			List<Integer> trainIndices = ExperimentUtil.getTrainingIndices(allIndices, i,k);
			List<Sequence> train = ExperimentUtil.getAll(database,trainIndices);
			List<Integer> trainClassIds = ExperimentUtil.getAll(classIds,trainIndices);
			List<Integer> testIndices = ExperimentUtil.getTestIndices(allIndices,trainIndices);
			List<Sequence> test = ExperimentUtil.getAll(database,testIndices);
			List<Integer> testClassIds = ExperimentUtil.getAll(classIds,testIndices);
			HashSet<Integer> trainWithoutTest = new HashSet<>(testIndices);
			trainWithoutTest.removeAll(trainIndices);
			assert(trainWithoutTest.size()==testIndices.size());
			measureSingleLabelClassificationPerformance(test,testClassIds, new SingleLabelIBSM1NN(train, trainClassIds, numDimensions, sequenceDuration),ibsmResult);
			measureSingleLabelClassificationPerformance(test,testClassIds, new SingleLabelCompressedIBSM1NN(train, trainClassIds, numDimensions, sequenceDuration),compressedIBSMResult);
			measureSingleLabelClassificationPerformance(test,testClassIds, new SingleLabelSTIFERFClassifier(train, trainClassIds, numDimensions, sequenceDuration,epsilon,shapeletFeatureCount,pool),stifeResult);
		}
		//save results:
		List<ClassifierResult> resultList = Arrays.asList(ibsmResult,compressedIBSMResult,stifeResult);
		return resultList;
	}
	
	private List<ClassifierResult> multiLabelClassifierEvaluation(File dir) throws IOException, Exception {
		List<List<Integer>> classIds = IOService.readMultiLabelClassData(dir);
		List<Sequence> database = IOService.readMultiLabelSequenceData(dir);
		for(Sequence seq:database){
			seq.sortIntervals();
		}
		assert(classIds.size()==database.size());
		ClassifierResult ibsmResult = new ClassifierResult(AbstractIBSM1NN.getName());
		ClassifierResult compressedIBSMResult = new ClassifierResult(AbstractCompressedIBSM1NN.getName());
		ClassifierResult stifeResult = new ClassifierResult(AbstractSTIFERFClassifier.getName());
		int numDimensions = Sequence.getDimensionSet(database).size();
		int sequenceDuration = Sequence.getMaxDuration(database);
		List<Integer> allIndices = ExperimentUtil.getShuffledIndices(database, random);
		for(int i=0;i<k;i++){
			List<Integer> trainIndices = ExperimentUtil.getTrainingIndices(allIndices, i,k);
			List<Sequence> train = ExperimentUtil.getAll(database,trainIndices);
			List<List<Integer>> trainClassIds = ExperimentUtil.getAll(classIds,trainIndices);
			List<Integer> testIndices = ExperimentUtil.getTestIndices(allIndices,trainIndices);
			List<Sequence> test = ExperimentUtil.getAll(database,testIndices);
			List<List<Integer>> testClassIds = ExperimentUtil.getAll(classIds,testIndices);
			HashSet<Integer> trainWithoutTest = new HashSet<>(testIndices);
			trainWithoutTest.removeAll(trainIndices);
			assert(trainWithoutTest.size()==testIndices.size());
			System.out.println("beginning 1NN");
			MultiLabelIBSM1NN ibsmClassifier = new MultiLabelIBSM1NN(train, trainClassIds, numDimensions, sequenceDuration);
			System.out.println("1NN Training done");
			measureMultiLabel1NNClassificationPerformance(test,testClassIds, ibsmClassifier,ibsmResult);
			System.out.println("beginning 1NN");
			MultiLabelCompressedIBSM1NN classifier = new MultiLabelCompressedIBSM1NN(train, trainClassIds, numDimensions, sequenceDuration);
			System.out.println("1NN Training done");
			measureMultiLabel1NNClassificationPerformance(test,testClassIds, classifier,compressedIBSMResult);
			System.out.println("beginning stiferf");
			MultiLabelSTIFERFClassifier classifier2 = new MultiLabelSTIFERFClassifier(train, trainClassIds, numDimensions, sequenceDuration,epsilon,shapeletFeatureCount,pool);
			System.out.println("training done");
			measureMultiLabelSTIFERFClassificationPerformance(test,testClassIds, classifier2,stifeResult);
			System.out.println("-----------done with fold " +i);
		}
		//save results:
		List<ClassifierResult> resultList = Arrays.asList(ibsmResult,compressedIBSMResult,stifeResult);
		return resultList;
	}

}
