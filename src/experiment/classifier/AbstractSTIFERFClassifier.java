package experiment.classifier;

import java.io.File;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import data_structures.CompressedEventTable;
import data_structures.Sequence;
import stife.distance.DistanceFeatureExtractor;
import stife.distance.DistanceFeatureMatrix;
import stife.distance.exceptions.InvalidEventTableDimensionException;
import stife.distance.exceptions.TimeScaleException;
import stife.shapelet_size2.Shapelet_Size2;
import stife.shapelet_size2.ShapeletExtractor;
import stife.shapelet_size2.ShapeletFeatureMatrix;
import stife.static_metrics.StaticFeatureMatrix;
import stife.static_metrics.StaticMetricExtractor;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.NumericToNominal;

public abstract class AbstractSTIFERFClassifier implements STIClassifier<Integer> {
	

	private DistanceFeatureMatrix distanceFeatureMatrix;
	private ShapeletFeatureMatrix shapeletFeatureMatrix;
	private int epsilon;
	private int sequenceDuration;
	private int numDimensions;
	private RandomForest rf;
	private StaticMetricExtractor staticMetricFeatureExtractor;
	private FastVector allAttributes;
	private Attribute classAttribute;
	private Instances trainInstances;
	private HashSet<Integer> classIdsset;

	public AbstractSTIFERFClassifier(List<Sequence> train, List<Integer> classIds, int numDimensions, int sequenceDuration,int epsilon,int shapeletFeatureCount,ExecutorService pool) throws Exception {
		this.epsilon = epsilon;
		this.sequenceDuration = sequenceDuration;
		this.numDimensions = numDimensions;
		classIdsset = new HashSet<>(classIds);
		for(Sequence seq : train){
			seq.sortIntervals();
		}
		//TODO: static metrics
		staticMetricFeatureExtractor = new StaticMetricExtractor();
		StaticFeatureMatrix staticFeatureMatrix = staticMetricFeatureExtractor.extractAll(train);
		//distance:
		DistanceFeatureExtractor distanceFeatureExtractor = new DistanceFeatureExtractor(train, classIds, numDimensions,sequenceDuration);
		distanceFeatureMatrix = distanceFeatureExtractor.calculateDistanceFeatureMatrix();
		//shapelets:
		shapeletFeatureMatrix = new ShapeletFeatureMatrix(train.size(), numDimensions, Sequence.NUM_RELATIONSHIPS,classIds);
		//create all the jobs:
		int numSequencesPerJob = 10;
		int prev = 0;
		List<ShapeletExtractor> jobs = new LinkedList<>();
		for(int i=0;i<train.size();i+=numSequencesPerJob){
			jobs.add(new ShapeletExtractor(train, prev, Math.min(i+numSequencesPerJob, train.size()), shapeletFeatureMatrix, epsilon));
			prev = i+numSequencesPerJob;
		}
		//submit all jobs
		Collection<Future<?>> futures = new LinkedList<Future<?>>();
		for(ShapeletExtractor job : jobs){
			futures.add(pool.submit(job));
		}
		for (Future<?> future:futures) {
			future.get();
		}
		shapeletFeatureMatrix.featureSelection(shapeletFeatureCount);
		trainInstances = buildInstances(train, classIds, staticFeatureMatrix,distanceFeatureMatrix.getMatrix(),shapeletFeatureMatrix.getMatrix(), "testdata" + File.separator + "stifeTrainData.csv");
		rf = new RandomForest();
		Integer numFeaturesPerTree = new Integer((int) Math.sqrt(trainInstances.numAttributes()-1));
		rf.setOptions(new String[]{"-I","500","-K",numFeaturesPerTree.toString()});
		rf.buildClassifier(trainInstances);
		allAttributes = new FastVector();
		for(int col=0;col< trainInstances.numAttributes();col++){
			allAttributes.addElement(trainInstances.attribute(col));
		}
		classAttribute = trainInstances.classAttribute();
	}

	private Instances buildInstances(List<Sequence> sequences, List<Integer> classIds,StaticFeatureMatrix staticFeatureMatrix,double[][] distanceFeatureMatrix, short[][] shapeletFeatureMatrix, String tempFilePath) throws Exception {
		PrintStream out = new PrintStream(new File(tempFilePath));
		int numTotalColsWithoutClass = staticFeatureMatrix.numCols()+distanceFeatureMatrix[0].length+shapeletFeatureMatrix[0].length;
		for(int col = 0;col<=numTotalColsWithoutClass;col++){
			out.print("Col_"+col);
			if(col!=numTotalColsWithoutClass){
				out.print(",");
			} else{
				out.println();
			}
		}
		for(int row = 0;row<sequences.size();row++){
			out.print(classIds.get(row)+",");
			for(int col = 0;col<staticFeatureMatrix.numCols();col++){
				double val = staticFeatureMatrix.get(row, col);
				out.print(val+",");
			}
			for(int col = 0;col<shapeletFeatureMatrix[0].length;col++){
				double val = shapeletFeatureMatrix[row][col];
				out.print(val+",");
			}
			for(int col = 0;col<distanceFeatureMatrix[0].length;col++){
				double val = distanceFeatureMatrix[row][col];
				out.print(val);
				if(col!= distanceFeatureMatrix[0].length-1){
					out.print(",");
				}
			}
			if(row!=sequences.size()-1){
				out.println();
			}
		}
		out.close();
		CSVLoader loader = new CSVLoader();
		File tempFile = new File(tempFilePath);
		loader.setSource(tempFile);
		assert(tempFile.exists());
		Instances instances = loader.getDataSet();
		instances.setClassIndex(0);
		//new stuff I am trying:
		HashSet<Integer> intersection = new HashSet<>(classIdsset);
		intersection.removeAll(classIds);
		for(Integer i : intersection){
			instances.classAttribute().addStringValue(i.toString());
		}
        String[] options2 = new String[2];
		options2[0]="-R";
        options2[1]="1";
		NumericToNominal convert= new NumericToNominal();
		convert.setOptions(options2);			
		convert.setInputFormat(instances);
	    instances = Filter.useFilter(instances, convert);
	    return instances;
	}

	@Override
	public Integer classify(Sequence sequence) throws Exception {
		Sequence mySeq = new Sequence(sequence);
		mySeq.sortIntervals();
		double[] staticFeatures = onlineStaticFeatureExtraction(mySeq);
		short[] shapeletFeatures = onlineShapeletFeatureExtraction(mySeq);
		double[] distanceFeatures = onlineDistanceFeatureExtraction(mySeq);
		Instances instances = new Instances("test instances",allAttributes,1);
		instances.setClassIndex(0);
		Instance instance = createInstance(staticFeatures,shapeletFeatures,distanceFeatures);
		instances.add(instance);
		instance.setDataset(instances);
		int predictedClass;
		try {
			int predictedClassIndex = (int) rf.classifyInstance(instance);
			int a = Integer.parseInt(instance.classAttribute().value(predictedClassIndex));
			predictedClass = Integer.parseInt(classAttribute.value(predictedClassIndex));
			assert(predictedClass==a);
		} catch (Exception e) {
			throw new ClassificationException(e);
		}
		return predictedClass;
	}

	private Instance createInstance(double[] staticFeatures, short[] shapeletFeatures, double[] distanceFeatures) {
		Instance curInstance = new Instance(allAttributes.size());
		//curInstance.setClassMissing();
		int instanceCol = 1;
		for(int i = 0;i<staticFeatures.length;i++){
			double val = staticFeatures[i];
			curInstance.setValue((Attribute)allAttributes.elementAt(instanceCol), val);
			instanceCol++;
		}
		for(int i = 0;i<shapeletFeatures.length;i++){
			double val = shapeletFeatures[i];
			curInstance.setValue((Attribute)allAttributes.elementAt(instanceCol), val);
			instanceCol++;
		}
		for(int i = 0;i<distanceFeatures.length;i++){
			double val = distanceFeatures[i];
			curInstance.setValue((Attribute)allAttributes.elementAt(instanceCol), val);
			instanceCol++;
		}
		curInstance.setMissing(classAttribute);
		return curInstance;
	}

	public double[] onlineStaticFeatureExtraction(Sequence mySeq) {
		return staticMetricFeatureExtractor.extract(mySeq);
	}

	public double[] onlineDistanceFeatureExtraction(Sequence sequence) throws TimeScaleException, InvalidEventTableDimensionException {
		double[] distanceFeatures = new double[distanceFeatureMatrix.numCols()];
		Sequence resizedSequence = new Sequence(sequence);
		resizedSequence.rescaleTimeAxis(1, sequenceDuration);
		CompressedEventTable table = new CompressedEventTable(resizedSequence, numDimensions);
		for(int i=0;i<distanceFeatureMatrix.numCols();i++){
			CompressedEventTable medoid = distanceFeatureMatrix.getCompressedEventTable(i);
			distanceFeatures[i] = medoid.euclidianDistance(table);
		}
		return distanceFeatures;
	}

	public short[] onlineShapeletFeatureExtraction(Sequence sequence) {
		short[] shapeletFeatures = new short[shapeletFeatureMatrix.numCols()];
		for(int i=0;i<shapeletFeatureMatrix.numCols();i++){
			Shapelet_Size2 curShapelet = shapeletFeatureMatrix.getShapeletOfColumn(i);
			shapeletFeatures[i] = sequence.countShapeletOccurance(curShapelet,epsilon);
		}
		return shapeletFeatures;
	}

	public static String getName() {
		return "STIFE framework + Weka Random Forest";
	}

	//only for test purposes!!!!
	public ShapeletFeatureMatrix getShapeletFeatureMatrix() {
		return shapeletFeatureMatrix;
	}

}
